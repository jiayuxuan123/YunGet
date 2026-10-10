/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import java.io.File
import org.json.JSONObject

/**
 * 「上一可用版本」的记录与读取（P4：插件更新失败可回滚）。
 *
 * ## 为什么需要它
 *
 * 更新插件是**覆盖式**的：新脚本写盘、库记录改成新版本、旧脚本文件被删。
 * 若新版本加载失败（脚本不合法、调用了没授予的能力、超时……），用户就卡在坏版本上 ——
 * 而**旧版本刚刚被我们自己删掉了**，连手动退回的素材都没有。
 * 设计文档第 8.5 章明确要求"插件更新失败应支持回滚到上一可用版本"。
 *
 * ## 为什么用侧车文件而不是加数据库字段
 *
 * 加列需要 Room 迁移，而这个项目**踩过迁移事故**（2.6.17：实体与迁移 SQL 不一致
 * → 升级用户启动即崩，见 `DatabaseMigrationContractTest`）。回滚记录是**短期状态**
 * （一个插件最多一条，用完即清），不值得为它动 schema。
 * 写成 `filesDir/plugins/rollback/<id>.json` 既同样持久，又不影响任何既有安装的库结构。
 *
 * ## 纯文件 IO，不依赖 Android
 *
 * 这样它能在**纯 JVM 单测**里被完整覆盖（本项目的 App 测试没有 Robolectric）。
 * 目录由调用方注入，测试用临时目录即可。
 *
 * ## 只保留一代
 *
 * 每次保存新快照时会先清掉**上一条快照引用的脚本文件**（如果它已经不再被当前记录使用），
 * 所以"备份文件无限堆积"不会发生。见 [save] 的注释。
 */
internal class PluginRollbackStore(private val dir: File) {

    /** 一条"上一可用版本"的快照。字段与 [com.yunget.app.data.db.PluginInstalledEntity] 对应。 */
    data class Snapshot(
        val id: String,
        val name: String,
        val version: String,
        val scriptPath: String,
        val scriptSha256: String,
        val sourceUri: String,
        val sourceKind: String,
        val manifestJson: String,
        val declaredPermissions: String,
        val trustLevel: String,
        val savedAt: Long,
    ) {
        /** 这条快照是否可用（脚本路径非空即可；文件是否存在由调用方判断）。 */
        val isUsable: Boolean get() = scriptPath.isNotBlank()
    }

    private fun fileFor(id: String): File =
        File(dir, PluginIds.sanitizeForFileName(id) + ".json")

    /** 读取某个插件的快照；没有或损坏时返回 null（损坏不该让更新/回滚流程失败）。 */
    fun load(id: String): Snapshot? = runCatching {
        val f = fileFor(id)
        if (!f.isFile) return null
        val o = JSONObject(f.readText(Charsets.UTF_8))
        val path = o.optString("scriptPath").trim()
        if (path.isEmpty()) return null
        Snapshot(
            id = o.optString("id").trim().ifEmpty { id },
            name = o.optString("name"),
            version = o.optString("version"),
            scriptPath = path,
            scriptSha256 = o.optString("scriptSha256"),
            sourceUri = o.optString("sourceUri"),
            sourceKind = o.optString("sourceKind"),
            manifestJson = o.optString("manifestJson"),
            declaredPermissions = o.optString("declaredPermissions"),
            trustLevel = o.optString("trustLevel"),
            savedAt = o.optLong("savedAt"),
        )
    }.getOrNull()

    /**
     * 写入新快照，并返回**被它取代的那条旧快照**（调用方据此清理旧备份文件）。
     *
     * 返回旧快照而不是在这里删文件，是因为删除要经过仓库的 `resolveInDir` 路径校验
     * （确认目标仍在脚本目录内）—— 那个校验属于仓库，不该在这里重复实现一遍。
     */
    fun save(snapshot: Snapshot): Snapshot? {
        val previous = load(snapshot.id)
        return runCatching {
            dir.mkdirs()
            val json = JSONObject().apply {
                put("id", snapshot.id)
                put("name", snapshot.name)
                put("version", snapshot.version)
                put("scriptPath", snapshot.scriptPath)
                put("scriptSha256", snapshot.scriptSha256)
                put("sourceUri", snapshot.sourceUri)
                put("sourceKind", snapshot.sourceKind)
                put("manifestJson", snapshot.manifestJson)
                put("declaredPermissions", snapshot.declaredPermissions)
                put("trustLevel", snapshot.trustLevel)
                put("savedAt", snapshot.savedAt)
            }
            // 原子写：先写临时文件再改名，避免写到一半被杀留下半截 JSON
            val tmp = File(dir, fileFor(snapshot.id).name + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(fileFor(snapshot.id))) {
                fileFor(snapshot.id).writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            previous
        }.getOrNull()
    }

    /** 清掉某个插件的快照（回滚完成、或该插件被卸载/删除时调用）。 */
    fun clear(id: String) {
        runCatching { fileFor(id).delete() }
        runCatching { File(dir, fileFor(id).name + ".tmp").delete() }
    }

    companion object {
        /**
         * 验证脚本文件与快照摘要一致（SHA-256 十六进制，不区分大小写）。
         *
         * 用在真正回滚之前：快照里的路径/库记录只是"指向某个文件"，
         * 如果文件被系统清理后同路径被复用、被手工篡改，不能仅凭"文件存在"就回滚 ——
         * 那会把未知脚本当作上一版加载。摘要不符则当作不可用，交由调用方清掉快照并报错。
         *
         * 放在纯 JVM 可测层：不依赖 Android，不依赖数据库。
         */
        fun matchesDigest(scriptFile: File, expectedSha256: String): Boolean {
            if (!scriptFile.isFile || expectedSha256.length != 64) return false
            val digest = runCatching {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(scriptFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return false
            return digest.equals(expectedSha256, ignoreCase = true)
        }
    }
}
