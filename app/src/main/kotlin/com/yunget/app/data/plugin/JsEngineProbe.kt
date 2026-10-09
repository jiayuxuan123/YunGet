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

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.yunget.app.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QuickJS 原生库在**这台设备上能不能真的起来**的自检。
 *
 * 为什么需要它：TurboDL 的 `turbo-plugin-js` 是 Kotlin/JVM 模块，默认依赖的 `quickjs-kt-jvm`
 * 只带 linux/macos/windows 的原生库 —— 在 Android 上加载必然失败（会去 dlopen
 * `jni/linux_aarch64/libquickjs.so`，那是 glibc 构建）。App 侧改引同版本的
 * `quickjs-kt-android`（含四个 Android ABI、minSdk 23）之后，"能不能起来"这件事不该靠读文档相信，
 * 而应该在真机上跑一次并留下可核对的证据。
 *
 * 检查项刻意覆盖后面真正会用到的能力，而不是只测"能 new 一个引擎"：
 *  1. 原生库能否加载（唯一的硬前提）；
 *  2. 基本求值 + JSON 往返（宿主与脚本之间的 ABI 传参就靠 JSON）；
 *  3. **执行超时能中断**（脚本写 `while(true){}` 时必须能救回来，这是跑用户脚本的底线）；
 *  4. **内存上限生效**（脚本疯狂分配时报错，而不是把宿主进程拖死）；
 *  5. 中断之后同一个实例**仍然可用**（否则每次超时都要重建引擎，代价很大）。
 *
 * 结果写 [DiagLog]，随「设置 → 导出日志」一起回传 —— 与 aria2 可用性检测同一套做法。
 */
object JsEngineProbe {

    private const val TAG = "JsEngineProbe"

    /** 探测用的引擎上限：够跑通检查即可，不必是生产值。 */
    private const val PROBE_MEMORY_LIMIT = 8L * 1024 * 1024
    private const val PROBE_STACK_LIMIT = 256L * 1024

    /** 死循环那一步的预算。够短（不拖时间）又够长（不误伤慢设备上的正常求值）。 */
    private const val PROBE_TIMEOUT_MS = 1_500L

    /** 单次探测的结论，供 UI 与日志展示。 */
    data class Result(
        val available: Boolean,
        /** 一句话结论，含确切原因（可用时给出验证过的能力清单）。 */
        val detail: String,
        /** 逐项子结果，便于定位是哪一步失败。 */
        val steps: List<Pair<String, Boolean>> = emptyList(),
    )

    /**
     * 跑一遍全部检查。**会创建引擎并执行脚本**，必须从后台线程调用（本函数内部已切到 IO）。
     */
    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Pair<String, Boolean>>()
        // 失败原因：只写"失败"两个字的话，真机上就只能靠猜（2.7.0 就这么误报过一次）
        var basicDetail = ""

        // ---- 1) 原生库能否加载 ----
        val engine = try {
            QuickJs.create(jobDispatcher = Dispatchers.IO)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / NoClassDefFoundError 都会落在这里 —— 这正是"jvm 版混进来"的症状
            steps += "加载原生库" to false
            return@withContext Result(
                available = false,
                detail = "引擎创建失败：${t.javaClass.simpleName} ${t.message.orEmpty()}".trim(),
                steps = steps,
            )
        }
        steps += "加载原生库" to true

        try {
            // 上限要在跑任何脚本之前设好：一条无界语句就是脚本绕过后续所有检查的方式
            engine.memoryLimit = PROBE_MEMORY_LIMIT
            engine.maxStackSize = PROBE_STACK_LIMIT
            engine.evaluationTimeoutMillis = PROBE_TIMEOUT_MS

            // ---- 2) 基本求值 + JSON 往返 ----
            // 【必须用 evaluate<Any?>，不能写 evaluate<String>】JS 的数字是 double，
            // `JSON.parse(...).a + 1` 求值出来是 Double(2.0)。引擎的类型转换表里
            // **没有 Double→String 这条路径**，请求 String 会抛
            // "No such type converter to convert 'kotlin.Double' to 'kotlin.String'" ——
            // 那不是引擎坏了，是问它要了一个它没有的类型。
            // 2.7.0 的真机上就是这么误报成"求值失败"的：引擎完全正常，探测本身写错了。
            // 生产代码（TurboDL 的 JsRuntime）同样一律用 evaluate<Any?>，这里与它保持一致。
            val value = runCatching {
                engine.evaluate<Any?>(
                    """JSON.parse('{"a":1}').a + 1""",
                    "probe-basic.js",
                )
            }
            val basicOk = value.getOrNull()?.let { (it as? Number)?.toDouble() == 2.0 } == true
            steps += "求值 + JSON 往返" to basicOk
            if (!basicOk) {
                basicDetail = value.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
                    ?: "结果不是 2：${value.getOrNull()}"
            }

            // ---- 3) 执行超时能中断 ----
            // 期望：被引擎的看门狗**以中断的形式**打断，而不是任何异常都算数。
            // 之前这里把"抛了异常"直接当成通过 —— 那样连"引擎根本不支持 evaluate"
            // 都会显示为"超时可中断"，是个只会给出假绿的空检查。
            var timeoutDetail = ""
            val timeoutOk = try {
                engine.evaluate<Any?>("while(true){}", "probe-loop.js")
                timeoutDetail = "死循环没有被中断"
                false
            } catch (t: com.dokar.quickjs.QuickJsInterruptedException) {
                timeoutDetail = t.javaClass.simpleName
                true
            } catch (t: Throwable) {
                // 是异常，但不是"被中断"—— 不能算通过
                timeoutDetail = "非中断异常：${t.javaClass.simpleName}"
                false
            }
            steps += "执行超时可中断" to timeoutOk

            // ---- 4) 中断之后同一实例仍可用 ----
            val afterInterrupt = runCatching {
                engine.evaluate<Any?>("1 + 1", "probe-after.js")?.let { (it as? Number)?.toDouble() == 2.0 } == true
            }.getOrDefault(false)
            steps += "中断后实例仍可用" to afterInterrupt

            // ---- 5) 内存上限生效 ----
            // 同样要求"是内存类错误"，而不是"抛了异常就算过"。
            var memoryDetail = ""
            val memoryOk: Boolean = try {
                engine.evaluate<Any?>(
                    "var a = []; while(true) { a.push(new Array(10000).fill('x')); }",
                    "probe-memory.js",
                )
                memoryDetail = "无界分配没有报错"
                false
            } catch (t: Throwable) {
                // 引擎把堆超限报成 QuickJsException（消息里带 memoryLimit / out of memory），
                // 也可能表现为中断（分配不停时看门狗先到）。两者都算上限生效。
                val msg = (t.message.orEmpty() + " " + t.javaClass.simpleName).lowercase()
                val isMemoryOrInterrupt = t is com.dokar.quickjs.QuickJsInterruptedException ||
                    "memory" in msg || "out of memory" in msg || "heap" in msg
                memoryDetail = "${t.javaClass.simpleName}${if (isMemoryOrInterrupt) "" else "（非内存类）"}"
                isMemoryOrInterrupt
            }
            steps += "内存上限生效" to memoryOk

            val allOk = basicOk && timeoutOk && afterInterrupt && memoryOk
            Result(
                available = allOk,
                detail = if (allOk) {
                    "原生库与隔离机制均可用（超时中断=$timeoutDetail，内存上限=$memoryDetail）"
                } else {
                    "部分能力不可用：" +
                        steps.joinToString("、") { "${it.first}=${if (it.second) "通过" else "失败"}" } +
                        // 求值失败时把**真实原因**带上：只报"失败"等于把排查成本推给用户
                        if (basicDetail.isNotEmpty()) "（求值失败原因：$basicDetail）" else ""
                },
                steps = steps,
            )
        } catch (t: Throwable) {
            steps += "运行期检查" to false
            Result(
                available = false,
                detail = "运行期异常：${t.javaClass.simpleName} ${t.message.orEmpty()}".trim(),
                steps = steps,
            )
        } finally {
            runCatching { engine.close() }
        }
    }

    /** 探测并落盘（logcat + diag.log），结果随「导出日志」一起回传。 */
    suspend fun probeAndLog(context: Context): Result {
        val r = probe()
        runCatching {
            DiagLog.i(
                context,
                "JS引擎",
                (if (r.available) "可用: " else "不可用: ") + r.detail +
                    " | " + r.steps.joinToString("、") { "${it.first}=${if (it.second) "✓" else "✗"}" },
            )
        }
        android.util.Log.i(TAG, "JS 引擎自检: available=${r.available} ${r.detail}")
        return r
    }
}
