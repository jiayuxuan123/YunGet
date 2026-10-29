package com.yunget.host

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 引擎宿主的入口：JSON 行协议（一行一个消息，stdin 进、stdout 出）。
 *
 * 为什么用 stdio 而不是本地端口：
 *  - 不需要监听端口，也就没有防火墙弹窗、端口冲突、同机其它进程伪造连接的问题；
 *  - 生命周期天然绑定：父进程（Flutter）退出时管道断开，本进程随之结束。
 *
 * 协议（请求一律带 `id`，宿主按 id 回执；事件不带 id）：
 * ```
 * {"id":"1","method":"ping"}
 * {"id":"2","method":"resolve","params":{"link":"...","pwd":"..."}}
 * {"id":"3","method":"list","params":{"session":"s1","dir":""}}
 * {"id":"4","method":"directLink","params":{"session":"s1","file":"f:123"}}
 * {"id":"5","method":"download","params":{"url":"...","fileName":"a.zip","headers":{}}}
 * {"id":"6","method":"pause"|"resume"|"cancel","params":{"task":5}}
 * {"id":"7","method":"settings","params":{...}}
 * {"event":"task","task":{...}}   ← 宿主主动推
 * ```
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val out = Output { line -> println(line); System.out.flush() }
    val host = Host(scope, out)

    // 父进程消失（管道断开）时 stdin 读到 EOF，循环结束，进程自然退出。
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { host.close() } })

    BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val request = runCatching { JSONObject(line) }.getOrNull()
            if (request == null) {
                out.error(null, "无法解析的请求：不是合法 JSON")
                continue
            }
            val id = request.optString("id").takeIf { it.isNotEmpty() }
            val method = request.optString("method")
            val params = request.optJSONObject("params") ?: JSONObject()
            // 请求之间不互相阻塞：下载是异步的，宿主必须能同时处理"再来一个任务"和"暂停"。
            scope.launch {
                try {
                    host.handle(method, id, params)
                } catch (t: Throwable) {
                    out.error(id, t.message ?: t::class.simpleName ?: "未知错误")
                }
            }
        }
    }
    runBlocking { host.awaitIdle() }
    scope.cancel()
}
