/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

/**
 * 能力标识的规范与校验（P3；设计文档 §12.1–12.4 的可执行形式）。
 *
 * ## 为什么要有命名空间
 *
 * 能力是**插件之间的契约**：它说"我提供（或需要）这个东西"。
 * 两个插件都声明 `cloud.parser` 时，宿主机无法判断它们说的是不是同一件事——
 * 命名空间回答"这是谁的契约"，版本回答"契约的第几版"。
 *
 * ## 四条规则
 *
 * 1. **不许裸字符串**。`download.engine`、`ai.provider` 一律拒绝。
 * 2. **版本是 id 的一部分**，写成 `id@版本`。`@1.3.0` 与 `@2.0.0` **不自动兼容**。
 * 3. **能力 ≠ 权限**。声明 `yung.cap.storage` 不授予任何访问权，
 *    真正的边界仍由权限系统裁决（这是"声明能力即提权"这类漏洞的防线）。
 * 4. **保留命名空间**。第三方不得占用 `yung` / `dev.turbodl`，
 *    否则任何人发一个 `yung.cap.task.executor` 都会被当成宿主自带的能力。
 *
 * ## 与引擎侧的关系
 *
 * TurboDL 的 `CapabilityId` 是同一套规则在引擎里的实现。两边都实现一遍是**刻意的**：
 * Host 与引擎可以各自独立发版，若抽成共享模块就把两者的发版节奏绑死了。
 * 规则本身是规范，不是实现——重复实现的是规范，风险由两边的测试共同锁住
 * （`CapabilityIdTest` 与 `PluginCapabilityTest`）。
 */
internal object PluginCapability {

    const val CAP_INFIX = ".cap."
    const val VERSION_SEP = '@'

    /** 保留命名空间：第三方不得声明。 */
    val RESERVED_NAMESPACES = setOf("yung", "dev.turbodl")

    data class Parsed(
        val namespace: String,
        val name: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) {
        val canonical: String get() = "$namespace$CAP_INFIX$name$VERSION_SEP$major.$minor.$patch"
        val isReserved: Boolean get() = namespace in RESERVED_NAMESPACES
    }

    sealed interface Result {
        /** 合规范。 */
        data class Ok(val parsed: Parsed) : Result

        /**
         * 旧式裸名（迁移期可读）。
         *
         * 只对**已知**的几个旧名字成立。一个没见过的裸名不是"旧格式"，
         * 是漏了命名空间或写错了——把它当旧格式放过，等于随便起个新裸名就能绕过整套规则。
         */
        data class Legacy(val bare: String) : Result

        data class Invalid(val reason: String) : Result
    }

    /**
     * 一个源声称由它提供的能力是否被接受。
     *
     * 社区的源不该声明保留命名空间的能力 —— 那是官方与引擎的地盘。
     * 这里不直接拒绝整个源（可能只是其中一条写错），而是单独标出问题条目，
     * 由界面决定怎么提示。
     */
    fun check(reservedNamespaceOwner: Boolean, id: String, legacy: Set<String> = LEGACY_NAMES): Result {
        val r = parse(id, legacy)
        if (r is Result.Ok && r.parsed.isReserved && !reservedNamespaceOwner) {
            return Result.Invalid(
                "保留命名空间 `$id` 只能由官方/引擎声明；请改用你自己的命名空间（如 com.example.cap.*）"
            )
        }
        return r
    }

    fun parse(raw: String, legacy: Set<String> = LEGACY_NAMES): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid("能力标识不能为空")

        val at = text.lastIndexOf(VERSION_SEP)
        if (at < 0) {
            return if (text.lowercase() in legacy) Result.Legacy(text)
            else Result.Invalid(
                "缺少命名空间与版本：`$text` 应为 `<命名空间>.cap.<名字>@<主.次.修订>`" +
                    "（例：yung.cap.storage@1.0.0）"
            )
        }

        val idPart = text.substring(0, at)
        val versionPart = text.substring(at + 1)
        if (idPart.isEmpty()) return Result.Invalid("`$text` 缺少 `@` 前的能力名")
        if (idPart != idPart.lowercase()) return Result.Invalid("能力名必须全小写：`$idPart`")

        val nums = parseSemver(versionPart)
            ?: return Result.Invalid("版本 `$versionPart` 不是合法的三段式（应为 major.minor.patch）")

        val capAt = idPart.indexOf(CAP_INFIX)
        if (capAt <= 0) {
            return Result.Invalid(
                "缺少 `.cap.` 分隔：`$idPart` 应为 `<命名空间>.cap.<名字>`"
            )
        }
        val namespace = idPart.substring(0, capAt)
        val name = idPart.substring(capAt + CAP_INFIX.length)
        if (name.isEmpty()) return Result.Invalid("`.cap.` 之后没有能力名：`$idPart`")
        if (!isValidLabels(namespace)) return Result.Invalid("命名空间 `$namespace` 不合法")
        if (!isValidLabels(name)) return Result.Invalid("能力名 `$name` 不合法")

        return Result.Ok(Parsed(namespace, name, nums[0], nums[1], nums[2]))
    }

    /** 解析 `1.2.3`。拒绝 `1.2`、`v1.2.3`、`1.2.3-rc1`、`1.2.03`。 */
    private fun parseSemver(raw: String): List<Int>? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        return parts.map { p ->
            // 前导零要拒：否则 "1.2.03" 与 "1.2.3" 会被当成同一个版本，
            // 两个本该是不同契约的东西会互相冒充。
            if (p.isEmpty() || (p.length > 1 && p[0] == '0')) return null
            p.toIntOrNull() ?: return null
        }
    }

    private fun isValidLabels(text: String): Boolean =
        text.split('.').all { label ->
            label.isNotEmpty() && label[0].isLetter() &&
                label.all { it.isLowerCase() || it.isDigit() || it == '-' }
        }

    /**
     * 迁移期内仍然可读的旧名字。
     *
     * 这份清单**只增不改**：一旦某个名字从旧格式升到带命名空间的形式，
     * 它就该从这份清单里移除（并同步发版说明），否则两套写法会长期并存。
     */
    val LEGACY_NAMES: Set<String> = setOf(
        // JS 插件的宿主能力（TurboDL 的 JsCapability 的旧写法）
        "http", "crypto", "log", "storage", "time", "env", "timer",
    )
}
