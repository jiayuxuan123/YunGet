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
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
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
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复 WebDAV 备份") },
            text = { Text("恢复会更新备份里的网盘账号和下载设置。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    run { "已恢复 ${restoreSnapshot(snapshot)} 个网盘账号及备份中的下载设置" }
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!active) onDismiss() },
            title = { Text("WebDAV 加密备份") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在两台设备填写同一个 HTTPS 文件地址。上传会更新此文件，备份口令用于本机加密和解密。")
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
                    run {
                        WebDavBackup(url, user, serverPassword).upload(exportSnapshot(), backupPassword)
                        "已上传加密备份"
                    }
                }) { Text("上传备份") }
            },
            dismissButton = {
                TextButton(enabled = !active, onClick = onDismiss) { Text("关闭") }
                TextButton(enabled = ready, onClick = {
                    run {
                        pendingRestore = WebDavBackup(url, user, serverPassword).download(backupPassword)
                        "备份已解密，请确认恢复"
                    }
                }) { Text("读取备份") }
            },
        )
    }
}
