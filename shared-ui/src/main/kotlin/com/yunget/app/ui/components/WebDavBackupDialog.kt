package com.yunget.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yunget.app.data.backup.WebDavBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import javax.crypto.AEADBadTagException

@Composable
internal fun WebDavBackupDialog(
    onDismiss: () -> Unit,
    exportSnapshot: suspend () -> JSONObject,
    restoreSnapshot: suspend (JSONObject) -> Int,
    initialUrl: String = "",
    initialUser: String = "",
    onRememberEndpoint: (url: String, user: String) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var user by remember(initialUser) { mutableStateOf(initialUser) }
    var serverPassword by remember { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<JSONObject?>(null) }
    var active by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val ready = url.isNotBlank() && backupPassword.length >= 8 && !active

    fun run(operation: suspend () -> String) {
        active = true
        result = "正在处理…"
        scope.launch {
            try {
                result = operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AEADBadTagException) {
                result = "备份口令错误或密文已损坏"
            } catch (_: IOException) {
                result = "无法连接 WebDAV，请检查地址、网络和证书"
            } catch (_: org.json.JSONException) {
                result = "无法读取该备份内容"
            } catch (failure: IllegalArgumentException) {
                result = failure.message ?: "备份参数无效"
            } catch (failure: IllegalStateException) {
                result = failure.message ?: "备份操作失败"
            } finally {
                active = false
            }
        }
    }

    val snapshot = pendingRestore
    if (snapshot != null) {
        val primaryCount = snapshot.optJSONArray("accounts")?.length() ?: 0
        val extraCount = snapshot.optJSONArray("extraAccounts")?.length() ?: 0
        val bookmarkCount = snapshot.optJSONArray("bookmarks")?.length() ?: 0
        val summaryParts = buildList {
            add("${primaryCount + extraCount} 个网盘账号")
            if (bookmarkCount > 0) add("$bookmarkCount 条收藏链接")
            if (snapshot.optJSONObject("downloadSettings") != null) add("下载与引擎设置")
        }.joinToString("、")
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复 WebDAV 备份") },
            text = { Text("检测到 $summaryParts。确认后将合并/更新到本机。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    run {
                        val restoredAccounts = restoreSnapshot(snapshot)
                        if (bookmarkCount > 0) {
                            "已恢复 $restoredAccounts 个账号、$bookmarkCount 条收藏及下载设置"
                        } else {
                            "已恢复 $restoredAccounts 个网盘账号及备份中的下载设置"
                        }
                    }
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!active) onDismiss() },
            title = { Text("WebDAV 加密同步") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在手机和电脑填写同一个 HTTPS 文件地址，即可加密同步全部多账号、收藏链接与下载设置。口令仅用于本机加解密。")
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !active, singleLine = true,
                        label = { Text("备份文件地址") })
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), enabled = !active, singleLine = true,
                        label = { Text("WebDAV 用户名") })
                    OutlinedTextField(serverPassword, { serverPassword = it }, Modifier.fillMaxWidth(), enabled = !active,
                        singleLine = true, label = { Text("WebDAV 密码") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(backupPassword, { backupPassword = it }, Modifier.fillMaxWidth(), enabled = !active,
                        singleLine = true, label = { Text("备份口令（至少 8 位）") }, visualTransformation = PasswordVisualTransformation())
                    if (result.isNotBlank()) Text(result)
                }
            },
            confirmButton = {
                TextButton(enabled = ready, onClick = {
                    onRememberEndpoint(url.trim(), user.trim())
                    run {
                        WebDavBackup(url, user, serverPassword).upload(exportSnapshot(), backupPassword)
                        "已上传加密备份（含账号、收藏与设置）"
                    }
                }) { Text("上传备份") }
            },
            dismissButton = {
                TextButton(enabled = !active, onClick = onDismiss) { Text("关闭") }
                TextButton(enabled = ready, onClick = {
                    onRememberEndpoint(url.trim(), user.trim())
                    run {
                        pendingRestore = WebDavBackup(url, user, serverPassword).download(backupPassword)
                        "备份已解密，请确认恢复"
                    }
                }) { Text("读取备份") }
            },
        )
    }
}
