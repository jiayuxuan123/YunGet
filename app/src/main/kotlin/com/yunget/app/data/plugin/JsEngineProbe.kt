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
            val value = runCatching {
                engine.evaluate<String>(
                    """JSON.parse('{"a":1}').a + 1""",
                    "probe-basic.js",
                )
            }.getOrNull()
            val basicOk = value?.toString() == "2"
            steps += "求值 + JSON 往返" to basicOk

            // ---- 3) 执行超时能中断 ----
            // 期望：被引擎的看门狗打断（抛异常），而不是把调用方永远挂住
            var timeoutDetail = ""
            val timeoutOk = try {
                engine.evaluate<Any?>("while(true){}", "probe-loop.js")
                timeoutDetail = "死循环没有被中断"
                false
            } catch (t: Throwable) {
                timeoutDetail = t.javaClass.simpleName
                true
            }
            steps += "执行超时可中断" to timeoutOk

            // ---- 4) 中断之后同一实例仍可用 ----
            val afterInterrupt = runCatching {
                engine.evaluate<String>("1 + 1", "probe-after.js")?.toString() == "2"
            }.getOrDefault(false)
            steps += "中断后实例仍可用" to afterInterrupt

            // ---- 5) 内存上限生效 ----
            var memoryDetail = ""
            val memoryOk = try {
                engine.evaluate<Any?>(
                    "var a = []; while(true) { a.push(new Array(10000).fill('x')); }",
                    "probe-memory.js",
                )
                memoryDetail = "无界分配没有报错"
                false
            } catch (t: Throwable) {
                memoryDetail = t.javaClass.simpleName
                true
            }
            steps += "内存上限生效" to memoryOk

            val allOk = basicOk && timeoutOk && afterInterrupt && memoryOk
            Result(
                available = allOk,
                detail = if (allOk) {
                    "原生库与隔离机制均可用（超时中断=$timeoutDetail，内存上限=$memoryDetail）"
                } else {
                    "部分能力不可用：" +
                        steps.joinToString("、") { "${it.first}=${if (it.second) "通过" else "失败"}" }
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
