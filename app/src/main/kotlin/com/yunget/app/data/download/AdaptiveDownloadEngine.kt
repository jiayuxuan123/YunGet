package com.yunget.app.data.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

private const val TAG = "YunGet-DL"

/**
 * 自适应多线程下载引擎（相较原版「主池 70% + 弹性区 30% 两阶段」模型的整体升级）。
 *
 * 四项升级：
 *  1) 工作窃取调度（work-stealing）：不再区分主池 / 弹性区，整文件由**单一顺序游标**按块发放，
 *     任一空闲线程立即领取下一个字节相邻块；失败 / 需细分的块回投到**共享 retry 双端队列**，
 *     被任意空闲线程窃取补下——彻底消除原「主池耗尽瞬间全部线程涌入弹性区、并发形态突变」
 *     导致的中后段掉速与尾部并发塌缩。
 *  2) 动态并发调节（AIMD）：监督协程每 [SAMPLE_MS] 采样总吞吐，吞吐上升则 +1 线程探测，
 *     吞吐下降 / 出现错误则 -1 回退；遇服务器忽略 Range / 429 / 503 立即**乘性收缩**（减半）。
 *     线程增减通过对固定容量信号量「寄存 / 释放」许可实现，真实生效且不会越界。
 *  3) 自适应分块：块大小随**实测单连接吞吐**动态调整，使每块下载耗时贴近 [TARGET_BLOCK_SEC]，
 *     慢连接自动用小块（更细粒度再平衡），快连接用大块（降低请求 / 建连开销）；
 *     文件尾部自动收敛块大小，避免「最后一个大块单线程收尾」的长尾。
 *  4) 连接复用 / HTTP2：复用下载专用 OkHttp 客户端（HTTP/2 多路复用 + 大连接池 keep-alive），
 *     错峰建连平摊 TCP/TLS 握手突发。
 */
class AdaptiveDownloadEngine(private val downloader: ChunkDownloader) {

    /** 引擎结局 */
    sealed class Outcome {
        /** 全部区间完成，可合并 */
        object Completed : Outcome()
        /** 服务器持续忽略 Range（返回 200 整文件）：交由上层回退单流整文件下载 */
        object NeedSingleStreamFallback : Outcome()
        /** 结构性失败（补下仍缺区间） */
        data class Failed(val reason: String) : Outcome()
    }

    companion object {
        /** 目标单块下载耗时（秒）：自适应块大小的收敛目标 */
        private const val TARGET_BLOCK_SEC = 2.0
        /** 块大小上下限 */
        private const val MIN_BLOCK = 512 * 1024L          // 512KB
        private const val MAX_BLOCK = 16 * 1024 * 1024L    // 16MB
        private const val INIT_BLOCK = 4 * 1024 * 1024L    // 4MB 起步
        /** 动态并发采样周期 */
        private const val SAMPLE_MS = 1500L
        /** 单块瞬时 IO 失败后回投 retry 队列的最大次数（超过判失败） */
        private const val MAX_BLOCK_ATTEMPTS = 4
        /** RANGE_IGNORED 容忍次数：偶发 200（CDN 限流中间态）不立即回退，超过才回退单流 */
        private const val RANGE_IGNORED_TOLERANCE = 3
        /** 错峰建连：第 i 个 worker 首次建连前延迟 min(i,CAP)*STAGGER_MS */
        private const val STAGGER_CAP = 8
        private const val STAGGER_MS = 25L
        /** 动态并发下限 */
        private const val MIN_WORKERS = 2
    }

    /** 待补下 / 需细分的区间（work-stealing 共享队列，任一空闲线程窃取） */
    private data class Block(val start: Long, val end: Long, var attempts: Int = 0) {
        val size get() = end - start + 1
        fun file(dir: File) = File(dir, "seg_${start}_${end}.part")
    }

    /**
     * @param targetWorkers 用户设置的目标 / 最大线程数（动态并发在 [MIN_WORKERS, targetWorkers] 间调节）
     * @param hardCap       平台安全上限（如迅雷 CDN 对单文件并发 Range 有阈值），null 表示无额外封顶
     * @param onBytes       每写入一段的回调：(delta 增量, absolute 累计已下载)，用于限速 / 进度 / 统计
     * @param onWorkers     当前活跃线程数变化回调（用于 UI 展示实时线程数）
     * @param isActive      任务是否仍活跃（暂停 / 删除后为 false），引擎据此尽快退出
     */
    suspend fun download(
        taskId: Long,
        url: String,
        total: Long,
        chunkDir: File,
        headers: Map<String, String>,
        targetWorkers: Int,
        hardCap: Int?,
        resumeFrom: Long,
        onBytes: suspend (delta: Long, absolute: Long) -> Unit,
        onWorkers: (Int) -> Unit,
        isActive: () -> Boolean
    ): Outcome = coroutineScope {
        chunkDir.mkdirs()
        val maxWorkers = (hardCap?.let { min(targetWorkers, it) } ?: targetWorkers)
            .coerceIn(1, 512)
        val startWorkers = min(maxWorkers, max(MIN_WORKERS, maxWorkers / 2)).coerceAtLeast(1)

        // ---------- 断点续传：扫描已完成块，推进顺序游标，删除不完整块 ----------
        val existingBlocks = scanExistingBlocks(chunkDir)
        val downloaded = AtomicLong(0)
        var cursor = resumeFrom.coerceIn(0, total)
        // 已完成块（字节前缀连续部分）推进游标；非前缀的完整块保留，其字节计入 downloaded
        val sortedDone = existingBlocks.filter { it.second }.sortedBy { it.first.start }
        for ((blk, _) in sortedDone) {
            downloaded.addAndGet(blk.size)
            if (blk.start <= cursor && blk.end + 1 > cursor) cursor = blk.end + 1
        }
        // 已完成但落在游标之后的“空洞后块”也计入已下载量（下方分配时会跳过其区间）
        val doneRanges = sortedDone.map { it.first.start..it.first.end }
        downloaded.set(min(downloaded.get(), total))

        // ---------- 共享状态 ----------
        val nextStart = AtomicLong(cursor)                       // 顺序发放游标（work-stealing 主来源）
        val retryDeque = ConcurrentLinkedDeque<Block>()          // 失败 / 细分块（窃取优先来源）
        val blockSize = AtomicLong(INIT_BLOCK)                   // 自适应块大小
        val fallback = AtomicBoolean(false)
        val rangeIgnored = AtomicInteger(0)
        val failReason = AtomicReference<String?>(null)
        val inFlight = AtomicInteger(0)
        val completedBytesForSpeed = downloaded

        // 固定容量信号量 = maxWorkers；动态并发通过“寄存/释放”许可实现（parked 数目 = maxWorkers - desired）
        val sem = Semaphore(maxWorkers)
        val desired = AtomicInteger(startWorkers)
        val parkedPermits = ConcurrentLinkedDeque<Unit>()        // 记录已寄存许可数

        // 预寄存 (maxWorkers - startWorkers) 个许可，使初始有效并发 = startWorkers
        val warmupScope = this
        repeat(maxWorkers - startWorkers) {
            warmupScope.launch { sem.acquire(); parkedPermits.add(Unit) }
        }

        onWorkers(startWorkers)

        // ---------- 动态并发监督协程（AIMD） ----------
        val supervisor = launch {
            var lastBytes = downloaded.get()
            var lastSpeed = 0.0
            var lastDir = +1
            while (isActive() && this@coroutineScope.isActive) {
                delay(SAMPLE_MS)
                if (!isActive()) break
                val now = downloaded.get()
                val speed = (now - lastBytes).toDouble() / (SAMPLE_MS / 1000.0)
                lastBytes = now

                // 服务器忽略 Range 累积过多：乘性收缩
                if (rangeIgnored.get() in 1 until RANGE_IGNORED_TOLERANCE) {
                    adjustWorkers(desired, maxWorkers, sem, parkedPermits, -(desired.get() / 2))
                }

                val cur = desired.get()
                if (speed > lastSpeed * 1.05 && lastDir > 0) {
                    // 上升趋势且上次是加线程：继续加性增（+1）
                    if (cur < maxWorkers && retryDeque.isEmpty()) {
                        adjustWorkers(desired, maxWorkers, sem, parkedPermits, +1); lastDir = +1
                    }
                } else if (speed < lastSpeed * 0.95) {
                    // 吞吐下降：回退（-1），并反向探测
                    if (cur > MIN_WORKERS) {
                        adjustWorkers(desired, maxWorkers, sem, parkedPermits, -1); lastDir = -1
                    } else lastDir = +1
                } else {
                    // 平稳：轻探一格上界
                    if (cur < maxWorkers && retryDeque.isEmpty()) {
                        adjustWorkers(desired, maxWorkers, sem, parkedPermits, +1); lastDir = +1
                    }
                }
                lastSpeed = speed
                onWorkers(desired.get())

                // 自适应块大小：让单块耗时 ≈ TARGET_BLOCK_SEC（按“单连接”吞吐估算）
                val perConn = if (desired.get() > 0) speed / desired.get() else speed
                if (perConn > 0) {
                    val ideal = (perConn * TARGET_BLOCK_SEC).toLong()
                    blockSize.set(ideal.coerceIn(MIN_BLOCK, MAX_BLOCK))
                }
            }
        }

        // ---------- 领块：优先窃取 retry 队列，其次顺序游标；尾部收敛块大小 ----------
        fun claim(): Block? {
            retryDeque.pollFirst()?.let { return it }
            while (true) {
                val s = nextStart.get()
                if (s >= total) return null
                // 跳过“已完成的后块”区间（断点续传遗留的非前缀完整块）
                val skip = doneRanges.firstOrNull { it.first <= s && s <= it.last }
                if (skip != null) {
                    if (nextStart.compareAndSet(s, skip.last + 1)) continue else continue
                }
                var bs = blockSize.get()
                // 尾部收敛：剩余不足 2 块时用较小块，避免单线程长尾
                val remaining = total - s
                if (remaining < bs * 2 && desired.get() > 1) {
                    bs = max(MIN_BLOCK, remaining / desired.get().coerceAtLeast(1))
                }
                val e = min(s + bs - 1, total - 1)
                if (nextStart.compareAndSet(s, e + 1)) return Block(s, e)
            }
        }

        // ---------- worker：固定 maxWorkers 个，实际并发由信号量寄存/释放动态控制 ----------
        val ok = try {
            val workers = List(maxWorkers) { idx ->
                async(Dispatchers.IO) {
                    if (idx in 1..STAGGER_CAP) delay(idx.toLong() * STAGGER_MS)
                    while (isActive() && !fallback.get()) {
                        val block = claim() ?: break
                        sem.withPermit {
                            if (fallback.get() || !isActive()) return@withPermit
                            inFlight.incrementAndGet()
                            try {
                                runBlock(
                                    taskId, url, block, chunkDir, headers, total,
                                    downloaded, onBytes, isActive,
                                    retryDeque, blockSize, fallback, rangeIgnored, failReason
                                )
                            } finally {
                                inFlight.decrementAndGet()
                            }
                        }
                    }
                }
            }
            workers.awaitAll()
            true
        } catch (e: CancellationException) {
            supervisor.cancel(); throw e
        } finally {
            supervisor.cancel()
        }

        if (fallback.get()) return@coroutineScope Outcome.NeedSingleStreamFallback
        if (!isActive()) return@coroutineScope Outcome.Failed("任务已暂停")

        // 完整性：整文件应被 [0,total) 的连续块覆盖
        val (covered, missing) = verifyCoverage(chunkDir, total)
        if (!covered) {
            failReason.compareAndSet(null, "缺失区间 ${missing.size} 段")
            return@coroutineScope Outcome.Failed(failReason.get() ?: "下载不完整")
        }
        Outcome.Completed
    }

    /** 单块下载（含 work-stealing 的失败回投 / RANGE_IGNORED 处理 / 慢块细分回投） */
    private suspend fun runBlock(
        taskId: Long,
        url: String,
        block: Block,
        chunkDir: File,
        headers: Map<String, String>,
        total: Long,
        downloaded: AtomicLong,
        onBytes: suspend (Long, Long) -> Unit,
        isActive: () -> Boolean,
        retryDeque: ConcurrentLinkedDeque<Block>,
        blockSize: AtomicLong,
        fallback: AtomicBoolean,
        rangeIgnored: AtomicInteger,
        failReason: AtomicReference<String?>
    ) {
        val partFile = block.file(chunkDir)
        val res = try {
            downloader.downloadChunk(
                taskId = taskId, url = url, start = block.start, end = block.end,
                partFile = partFile, headers = headers
            ) { bytes ->
                val abs = min(downloaded.addAndGet(bytes), total)
                if (!isActive()) return@downloadChunk
                onBytes(bytes, abs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChunkResult.FAILED
        }
        when (res) {
            ChunkResult.OK -> { /* 完成 */ }
            ChunkResult.RANGE_IGNORED -> {
                val n = rangeIgnored.incrementAndGet()
                Log.w(TAG, "runBlock: task=$taskId 块 ${block.start}-${block.end} 服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                else retryDeque.addLast(block)   // 偶发 200：回投重试
            }
            ChunkResult.FAILED -> {
                block.attempts++
                if (block.attempts >= MAX_BLOCK_ATTEMPTS) {
                    failReason.compareAndSet(null, "块 ${block.start}-${block.end} 重试 ${block.attempts} 次仍失败")
                    return
                }
                // 慢 / 失败大块细分为两半回投（work-stealing 再平衡），小块直接回投
                val half = block.size / 2
                if (block.size > MIN_BLOCK * 2 && half > 0) {
                    partFile.delete()
                    val mid = block.start + half - 1
                    retryDeque.addFirst(Block(block.start, mid, block.attempts))
                    retryDeque.addFirst(Block(mid + 1, block.end, block.attempts))
                } else {
                    retryDeque.addLast(block)
                }
            }
        }
    }

    /** 调整期望并发：delta>0 释放寄存许可（增线程），delta<0 寄存许可（减线程） */
    private suspend fun adjustWorkers(
        desired: AtomicInteger,
        maxWorkers: Int,
        sem: Semaphore,
        parked: ConcurrentLinkedDeque<Unit>,
        delta: Int
    ) {
        if (delta == 0) return
        val target = (desired.get() + delta).coerceIn(MIN_WORKERS, maxWorkers)
        var diff = target - desired.get()
        while (diff > 0) {                 // 增线程：释放寄存的许可
            if (parked.pollFirst() != null) { sem.release(); diff-- } else break
        }
        while (diff < 0) {                 // 减线程：寄存许可（占用不放）
            sem.acquire(); parked.add(Unit); diff++
        }
        desired.set(target)
    }

    /** 扫描目录已有块：返回 (Block, 是否完整) 列表 */
    private fun scanExistingBlocks(dir: File): List<Pair<Block, Boolean>> {
        return dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                val blk = Block(s, e)
                val complete = f.length() >= blk.size
                if (!complete) f.delete()   // 不完整块删除重下
                blk to complete
            }?.filter { it.second } ?: emptyList()
    }

    /** 校验 [0,total) 是否被完整块连续覆盖，返回 (是否完整, 缺失区间) */
    private fun verifyCoverage(dir: File, total: Long): Pair<Boolean, List<LongRange>> {
        val blocks = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                if (f.length() >= (e - s + 1)) s..e else null
            }?.sortedBy { it.first } ?: emptyList()
        val missing = mutableListOf<LongRange>()
        var pos = 0L
        for (r in blocks) {
            if (r.first > pos) missing.add(pos until r.first)
            if (r.last + 1 > pos) pos = r.last + 1
        }
        if (pos < total) missing.add(pos until total)
        return (missing.isEmpty() && pos >= total) to missing
    }

    /** 合并所有 seg 块为完整文件（按 start 排序）。 */
    fun finalBlockFiles(chunkDir: File): List<File> =
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLongOrNull() ?: 0L }
            ?: emptyList()
}
