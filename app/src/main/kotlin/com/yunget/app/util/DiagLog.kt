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

package com.yunget.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 诊断日志的**分级 + 分类 + 实时**通道。
 *
 * ## 为什么要重做（旧版的三个实际缺陷）
 *
 * 旧版是"一个 `i()` 方法、一个文件、没有级别也没有分类"，导出日志后表现为：
 *
 *  1. **信息与错误混在一起** —— 想知道"有没有出错"必须通读全部行；
 *  2. **分不清是哪个子系统** —— 插件失败与下载失败长得一样，只能靠读文字判断；
 *  3. **覆盖面窄** —— 只有 30 来个调用点落盘，网络/下载/解析这些关键路径
 *     大多只走 `Log.d`（logcat），而新系统上 logcat 缓冲只保留几秒，
 *     **等于这些子系统出问题时根本没有证据可回传**。
 *
 * ## 现在的形态
 *
 * | 维度 | 做什么 |
 * |---|---|
 * | **级别** | [Level]：VERBOSE/DEBUG/INFO/WARN/ERROR。文件里每行都带级别，可 `grep ' [E] '` 直接挑出错误 |
 * | **分类** | [Category]：启动/网络/下载/解析/账号/插件/存储/UI/更新。每类一个文件，也可以合并导出 |
 * | **实时** | 进程内环形缓冲（[recent]），设置页能**当场看到**最近若干行，不必先导出再回传 |
 * | **落盘** | 按级别过滤后写入对应分类文件；ERROR 会**额外**写一份汇总文件，便于只看错误 |
 *
 * ## 三条设计约束
 *
 * 1. **写日志绝不影响主流程**：所有落盘都在 `runCatching` 里，失败只写一行 logcat。
 *    日志系统把应用搞崩是最不可接受的缺陷。
 * 2. **不做异步队列**：本项目的日志量很小（每次下载几十行），同步写 + 进程内锁足够；
 *    引入队列会带来"崩溃时队列里的日志丢了"这个问题 —— 而崩溃时恰恰最需要日志。
 * 3. **不记录敏感内容**：这个方法被大量调用，调用方负责不要把 cookie/token/密码传进来。
 *    这里额外做一层**兜底脱敏**（见 [redact]），因为"某个调用点忘了"是必然会发生的事。
 */
object DiagLog {

    private const val TAG = "YunGet-Diag"

    /** 单个分类文件的上限：超出后保留后半（丢弃最旧一半）。 */
    private const val MAX_BYTES_PER_CATEGORY = 512L * 1024

    /** 进程内环形缓冲保留的行数（设置页实时查看用）。 */
    private const val RING_CAPACITY = 400

    private const val DIR = "diag"

    /**
     * 日志级别。
     *
     * 顺序即严重程度，可以直接比较（`level >= Level.WARN`）—— 过滤逻辑靠它，
     * 所以**不要重排**。
     */
    enum class Level(val mark: Char, val label: String) {
        /** 详细追踪：只在排查特定问题时才有价值。默认不落盘。 */
        VERBOSE('V', "详细"),

        /** 调试：流程节点。默认不落盘（摇一摇/开发者模式可开）。 */
        DEBUG('D', "调试"),

        /** 信息：用户可感知的正常事件（开始下载、插件已启用）。默认落盘。 */
        INFO('I', "信息"),

        /** 警告：可以继续，但值得注意（重试、降级、回退）。默认落盘。 */
        WARN('W', "警告"),

        /** 错误：功能未达成。默认落盘，并额外进错误汇总。 */
        ERROR('E', "错误"),
    }

    /**
     * 日志分类。
     *
     * 每一类落一个文件。之所以按**子系统**而不是按级别分文件：排查时的问题是
     * "下载为什么慢"，而不是"这周有哪些错误" —— 后者由错误汇总文件回答。
     */
    enum class Category(val fileName: String, val label: String) {
        /** 应用启动、初始化、自愈。 */
        BOOT("boot.log", "启动"),

        /** 出站请求、DNS、TLS、代理。 */
        NET("net.log", "网络"),

        /** 下载任务的提交、分片、重试、合并、保存。 */
        DOWNLOAD("download.log", "下载"),

        /** 分享链接解析、网盘 API 交互。 */
        RESOLVE("resolve.log", "解析"),

        /** 网盘 Provider 的路由与回退（P0）。 */
        CLOUD("cloud.log", "网盘"),

        /** 登录、凭据、密钥自愈。 */
        ACCOUNT("account.log", "账号"),

        /** 插件的装载、校验、更新、回滚、能力。 */
        PLUGIN("plugin.log", "插件"),

        /** 文件落盘、目录、权限、SAF。 */
        STORAGE("storage.log", "存储"),

        /** 界面层事件（用户操作、页面异常）。 */
        UI("ui.log", "界面"),

        /** 更新检查与安装。 */
        UPDATE("update.log", "更新"),

        /** 未分类（旧调用点没给分类时的兜底）。 */
        OTHER("other.log", "其他"),
    }

    /** 错误汇总文件名（所有 ERROR 级别都会额外写一份到这里）。 */
    private const val ERROR_FILE = "errors.log"

    /** 进程内串行化写入，避免多任务并发写乱序/交错。 */
    private val lock = Any()

    /** 环形缓冲（实时查看用）。用固定数组 + 游标，避免 LinkedList 的对象开销。 */
    private val ring = arrayOfNulls<String>(RING_CAPACITY)
    private val ringCursor = AtomicLong(0)

    /** 已写入的总行数（界面显示用，也是"有没有漏"的对照值）。 */
    private val totalLines = AtomicLong(0)

    /**
     * 落盘的最低级别。
     *
     * 默认 [Level.INFO]：VERBOSE/DEBUG 只进环形缓冲（界面能看），不写文件 ——
     * 否则每次进度回调都会产生一次写盘，把 IO 放大到不可接受。
     * 排查时可以从设置页临时调到 DEBUG（见 [setMinLevel]），下一次复现就带上细节。
     */
    @Volatile
    private var minLevel: Level = Level.INFO

    /** 调整落盘级别（设置页的"详细日志"开关）。 */
    fun setMinLevel(level: Level) {
        minLevel = level
    }

    fun minLevel(): Level = minLevel

    // ---------------------------------------------------------------- 记录

    fun v(context: Context, category: Category, message: String) =
        log(context, Level.VERBOSE, category, message)

    fun d(context: Context, category: Category, message: String) =
        log(context, Level.DEBUG, category, message)

    fun i(context: Context, category: Category, message: String) =
        log(context, Level.INFO, category, message)

    fun w(context: Context, category: Category, message: String) =
        log(context, Level.WARN, category, message)

    fun e(context: Context, category: Category, message: String, error: Throwable? = null) =
        log(context, Level.ERROR, category, message, error)

    /**
     * 兼容旧调用点：`DiagLog.i(context, "标签", "消息")`。
     *
     * 库里原有 30 多个调用点用的是字符串标签。一次性全改成 [Category] 会让这次改动
     * 横跨十几个文件，而其中大部分逻辑与"日志分类"无关。所以保留这个重载：
     * 它把字符串标签**映射**到分类（见 [categoryFor]），映射不到的进 [Category.OTHER]。
     *
     * 这个重载的存在期限是"下次动到那些文件时顺手改掉"，不是永久兼容承诺。
     */
    fun i(context: Context, tag: String, message: String) =
        log(context, Level.INFO, categoryFor(tag), message)

    /**
     * 字符串标签 → 分类。映射不到就进 OTHER，并在文件里保留原标签（不丢信息）。
     *
     * internal 而不是 private：映射错了会让旧调用点的日志进错文件，
     * 而那属于"用户报问题时找不到证据"这一类缺陷，必须能被测试盯住。
     */
    internal fun categoryFor(tag: String): Category = when {
        tag.contains("启动") || tag.contains("自愈") -> Category.BOOT
        tag.contains("插件") -> Category.PLUGIN
        tag.contains("下载") || tag.contains("aria2") -> Category.DOWNLOAD
        tag.contains("解析") -> Category.RESOLVE
        tag.contains("账号") || tag.contains("登录") -> Category.ACCOUNT
        tag.contains("网络") || tag.contains("DNS") -> Category.NET
        tag.contains("更新") -> Category.UPDATE
        else -> Category.OTHER
    }

    /**
     * 记一行日志。
     *
     * @param error 有异常时一并记录类型与消息（**不记堆栈**：这类日志要能整份回传，
     *   堆栈会让文件膨胀好几倍，而第一行足够定位；需要堆栈时用 [e] 之外的手段）
     */
    fun log(
        context: Context,
        level: Level,
        category: Category,
        message: String,
        error: Throwable? = null,
    ) {
        val text = redact(buildString {
            append(message)
            if (error != null) {
                append("（").append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
                append("）")
            }
        })

        // logcat 始终写：开发和现场调试都靠它，且它不受落盘级别限制
        when (level) {
            Level.VERBOSE -> Log.v(TAG, "[${category.label}] $text")
            Level.DEBUG -> Log.d(TAG, "[${category.label}] $text")
            Level.INFO -> Log.i(TAG, "[${category.label}] $text")
            Level.WARN -> Log.w(TAG, "[${category.label}] $text")
            Level.ERROR -> Log.e(TAG, "[${category.label}] $text")
        }

        val line = format(level, category, text)
        pushToRing(line)

        if (level.ordinal >= minLevel.ordinal) {
            appendTo(context, category.fileName, line)
            // 错误额外进汇总：用户说"有问题"时，最先要看的是这个文件
            if (level == Level.ERROR) appendTo(context, ERROR_FILE, line)
        }
    }

    /** 一行的文件格式：`08-10 14:23:01.123 [I] [下载] 消息`。级别放在分类前，便于 grep。 */
    private fun format(level: Level, category: Category, message: String): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        return "$ts [${level.mark}] [${category.label}] $message"
    }

    // ---------------------------------------------------------------- 环形缓冲（实时）

    private fun pushToRing(line: String) {
        val n = ringCursor.getAndIncrement()
        ring[(n % RING_CAPACITY).toInt()] = line
        totalLines.incrementAndGet()
    }

    /**
     * 最近的日志行（新的在后）。
     *
     * 按 [minLevel] 过滤：界面默认只看 INFO 以上，避免被 DEBUG 刷屏。
     * 传 `null` 看全部。
     */
    fun recent(minLevel: Level? = null, limit: Int = 200): List<String> {
        val n = ringCursor.get()
        val count = minOf(n, RING_CAPACITY.toLong()).toInt()
        val start = (n - count).coerceAtLeast(0)
        val out = ArrayList<String>(minOf(count, limit))
        for (i in start until n) {
            val line = ring[(i % RING_CAPACITY).toInt()] ?: continue
            if (minLevel != null && !levelAtLeast(line, minLevel)) continue
            out.add(line)
        }
        return if (out.size > limit) out.subList(out.size - limit, out.size).toList() else out
    }

    /** 从格式化后的行里读级别标记。格式固定（见 [format]），所以按位置取即可。 */
    private fun levelAtLeast(line: String, min: Level): Boolean {
        val open = line.indexOf(" [")
        if (open < 0) return true
        val mark = line.getOrNull(open + 2) ?: return true
        val actual = Level.entries.firstOrNull { it.mark == mark } ?: return true
        return actual.ordinal >= min.ordinal
    }

    /** 已记录的总行数（含 VERBOSE/DEBUG）。 */
    fun totalLines(): Long = totalLines.get()

    // ---------------------------------------------------------------- 落盘

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** 某个分类的日志文件（导出与查看用）。 */
    fun file(context: Context, fileName: String = "download.log"): File =
        File(dir(context), fileName)

    /** 旧调用点用的默认文件（保持"应用自己的诊断文件"这个名字不变）。 */
    fun file(context: Context): File = file(context, Category.DOWNLOAD.fileName)

    /** 错误汇总文件。 */
    fun errorFile(context: Context): File = File(dir(context), ERROR_FILE)

    private fun appendTo(context: Context, fileName: String, line: String) {
        runCatching {
            synchronized(lock) {
                val f = File(dir(context), fileName)
                if (f.length() > MAX_BYTES_PER_CATEGORY) {
                    // 超限：保留后半，丢弃最旧一半（避免"最老的行把新的挤没"）
                    val kept = f.readText().takeLast((MAX_BYTES_PER_CATEGORY / 2).toInt())
                    f.writeText("…(已截断旧日志)…\n" + kept)
                }
                f.appendText(line + "\n")
            }
        }.onFailure {
            // 落盘失败绝不影响主流程，但要留痕（否则"以为写了其实没写"）
            Log.w(TAG, "日志落盘失败（$fileName）：${it.message}")
        }
    }

    /**
     * 崩溃报告进诊断日志体系（CrashHandler 在进程退出前调用）。
     *
     * ## 为什么不能只写 `filesDir/crash/`
     *
     * 崩溃报告原先只落在独立的 crash 目录里，而「日志」这一侧（错误汇总、实时页、
     * 导出）对它一无所知 —— 用户崩了一次，下次进来诊断日志里**毫无痕迹**，
     * 「报问题必须附日志」的流程就断了。这里把崩溃正文同步写进：
     *
     *  - `boot.log`（启动分类，逐行带 `[E] [启动]` 标记，级别过滤能筛出来）；
     *  - `errors.log`（错误汇总，导出时排在最前面 —— 用户报问题时第一眼看的就是它）。
     *
     * 调用发生在进程垂死之际：**同步写、锁内写**（环形缓冲此时没有意义，不推），
     * 全程 `runCatching` —— 日志系统绝不能反过来影响崩溃处理本身。
     * minLevel 在这里不生效：崩溃是最高优先级的证据，不受"详细日志"开关影响。
     */
    fun recordCrash(context: Context, crashText: String) {
        runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val body = crashText.trim().lines().joinToString("\n") { line ->
                if (line.isBlank()) "" else "$ts [E] [启动] $line"
            }
            appendTo(context, Category.BOOT.fileName, body)
            appendTo(context, ERROR_FILE, "===== 进程崩溃（下次启动仍可在本文件看到）=====\n$body")
        }
    }

    /** 读取某个分类的全部日志；不存在返回 null。 */
    fun read(context: Context, category: Category): String? = readFile(context, category.fileName)

    /** 读取错误汇总。 */
    fun readErrors(context: Context): String? = readFile(context, ERROR_FILE)

    /** 读取全部分类的日志（导出用），按分类分别标注。 */
    fun readAll(context: Context): String? {
        val parts = mutableListOf<String>()
        for (cat in Category.entries) {
            val text = readFile(context, cat.fileName) ?: continue
            if (text.isBlank()) continue
            parts += "---------- ${cat.label}（${cat.fileName}）----------\n$text"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun readFile(context: Context, fileName: String): String? = runCatching {
        val f = File(dir(context), fileName)
        if (f.isFile && f.length() > 0) f.readText() else null
    }.getOrNull()

    /** 清空全部日志（用户可主动清理）。 */
    fun clear(context: Context): Boolean = runCatching {
        synchronized(lock) { dir(context).listFiles()?.forEach { it.delete() } }
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 脱敏兜底

    /**
     * 兜底脱敏：把看起来像凭据的片段替换掉。
     *
     * 为什么做得进这一层：调用方**一定会**有忘记的时候（30 多个调用点，将来更多），
     * 而这份日志的设计目的就是"整份回传给开发者"。一次疏忽就等于把用户的
     * cookie 贴到聊天记录里。
     *
     * 规则刻意保守（宁可多脱一点）：只匹配**明显的键值对形状**，不改动普通句子。
     * 这不是加密，也不是完备的防泄漏 —— 它只挡最常见的那几种写法。
     */
    internal fun redact(text: String): String {
        var out = text
        for (key in SENSITIVE_KEYS) {
            // 两段式：先吃掉可选的 scheme 词（Bearer / Basic），再取真正的值。
            // 不做这一步会漏掉**最常见的一种写法** —— `Authorization: Bearer <token>`
            // 里真值在空格之后，而「值不含空格」的匹配会在 Bearer 处停下，
            // 结果 token 原样留在日志里（这个漏洞实测被测试抓到了，见 DiagLogTest）。
            //
            // 正则用原始字符串写：内部反斜杠是字面量，不必再叠一层转义。
            val re = Regex(
                """(?i)(${Regex.escape(key)}\s*["']?\s*[:=]\s*["']?\s*(?:(?:Bearer|Basic|Token|OAuth)\s+)?)(\S{8,})""",
            )
            out = re.replace(out) { m -> m.groupValues[1] + "***" + m.groupValues[2].length + "***" }
        }
        return out
    }

    /** 出现这些键名时，后面的值按凭据处理。 */
    private val SENSITIVE_KEYS = listOf(
        "cookie", "set-cookie", "authorization", "token", "access_token", "refresh_token",
        "password", "passwd", "pwd", "secret", "api_key", "apikey", "session", "sessionid",
        "signature", "sign", "credential", "auth",
    )
}
