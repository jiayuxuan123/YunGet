package com.yunget.app.data.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

private const val TAG = "YunGet-DL"

/**
 * 多线程下载引擎（参考 aria2 与 IDM 的成熟经验重写）。
 *
 * 关键设计：
 *  - **固定满并发**：连接数固定为用户设定值（类似 aria2 `--max-connection-per-server` / `--split`），
 *    一开始就全部拉起（仅首次建连错峰），**绝不因吞吐波动收缩连接数**。
 *    这是相较上一版最关键的修正——上一版用 AIMD 动态调节，把正常的速率抖动误判为「服务器过载」，
 *    每 1.5s 减一个线程直到只剩 2 个；在「单连接被 CDN 限速」的网盘上，速度自然只有满并发的很小一部分
 *    （用户实测只能跑到 32~33KB/s 且忽上忽下，正是并发被砍到最低所致）。
 *  - **小固定块 + 工作窃取**：整文件按固定 4MB 块由单一顺序游标发放（aria2 的 min-split 思想），
 *    任一空闲线程立即领下一块；失败块回投共享 retry 双端队列被任意空闲线程窃取补下。
 *    固定小块天然无「单个大块长尾」问题：游标耗尽时，剩余在飞块各自 ≤4MB，收尾很快。
 *  - **连接复用 / HTTP2**：复用下载专用 OkHttp 客户端（HTTP/2 多路复用 + 大连接池 keep-alive）。
 */
class AdaptiveDownloadEngine(private val downloader: ChunkDownloader) {

    sealed class Outcome {
        object Completed : Outcome()
        object NeedSingleStreamFallback : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    companion object {
        /** 固定块大小：4MB（对齐 aria2 常用 1~4MB；小到尾部粒度细、抗长尾，大到请求开销低）。 */
        private const val BLOCK = 4 * 1024 * 1024L
        /** 单块瞬时失败回投 retry 队列的最大次数（超过判失败）。 */
        private const val MAX_BLOCK_ATTEMPTS = 5
        /** RANGE_IGNORED 容忍次数：偶发 200（CDN 限流中间态）不立即回退，超过才回退单流。 */
        private const val RANGE_IGNORED_TOLERANCE = 3
        /** 错峰建连：第 i 个 worker 首次建连前延迟 min(i,CAP)*STAGGER_MS。 */
        private const val STAGGER_CAP = 8
        private const val STAGGER_MS = 20L
    }

    /** 一个下载区间。 */
    private class Block(val start: Long, val end: Long, var attempts: Int = 0) {
        val size get() = end - start + 1
        fun file(dir: File) = File(dir, "seg_${start}_${end}.part")
    }

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
        // 固定并发：用户设定值；迅雷等平台按 hardCap 封顶；不再动态收缩。
        val workers = (hardCap?.let { min(targetWorkers, it) } ?: targetWorkers).coerceIn(1, 64)

        // ---------- 断点续传：扫描已完成块，推进游标 ----------
        val done = scanExistingBlocks(chunkDir)
        val downloaded = AtomicLong(0)
        var cursor = resumeFrom.coerceIn(0, total)
        val doneRanges = done.sortedBy { it.start }
        for (blk in doneRanges) {
            downloaded.addAndGet(blk.size)
            if (blk.start <= cursor && blk.end + 1 > cursor) cursor = blk.end + 1
        }
        downloaded.set(min(downloaded.get(), total))
        val doneList = doneRanges.map { it.start..it.end }

        val nextStart = AtomicLong(cursor)
        val retryDeque = ConcurrentLinkedDeque<Block>()
        val fallback = AtomicBoolean(false)
        val rangeIgnored = AtomicInteger(0)
        val failReason = AtomicReference<String?>(null)

        onWorkers(workers)

        val ok = try {
            val jobs = List(workers) { idx ->
                async(Dispatchers.IO) {
                    if (idx in 1..STAGGER_CAP) delay(idx.toLong() * STAGGER_MS)
                    while (isActive() && !fallback.get()) {
                        val block = claim(retryDeque, nextStart, total, doneList) ?: break
                        runBlock(
                            taskId, url, block, chunkDir, headers, total,
                            downloaded, onBytes, isActive,
                            retryDeque, fallback, rangeIgnored, failReason
                        )
                    }
                }
            }
            jobs.awaitAll()
            true
        } catch (e: CancellationException) {
            throw e
        }

        if (fallback.get()) return@coroutineScope Outcome.NeedSingleStreamFallback
        if (!isActive()) return@coroutineScope Outcome.Failed("任务已暂停")

        val (covered, missing) = verifyCoverage(chunkDir, total)
        if (!covered) {
            failReason.compareAndSet(null, "缺失区间 ${missing.size} 段")
            return@coroutineScope Outcome.Failed(failReason.get() ?: "下载不完整")
        }
        Outcome.Completed
    }

    /** 领块：优先窃取 retry 队列，其次顺序游标按 4MB 发放（原子推进，跳过续传遗留完整块）。 */
    private fun claim(
        retryDeque: ConcurrentLinkedDeque<Block>,
        nextStart: AtomicLong,
        total: Long,
        doneList: List<LongRange>
    ): Block? {
        retryDeque.pollFirst()?.let { return it }
        while (true) {
            var s = nextStart.get()
            if (s >= total) return null
            // 跳过断点续传遗留的已完成后块
            val skip = doneList.firstOrNull { it.first <= s && s <= it.last }
            if (skip != null) {
                nextStart.compareAndSet(s, skip.last + 1)
                continue
            }
            val e = min(s + BLOCK - 1, total - 1)
            if (nextStart.compareAndSet(s, e + 1)) return Block(s, e)
            // CAS 失败：其他线程已推进游标，重试
        }
    }

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
                else retryDeque.addLast(Block(block.start, block.end, block.attempts))
            }
            ChunkResult.FAILED -> {
                block.attempts++
                if (block.attempts >= MAX_BLOCK_ATTEMPTS) {
                    failReason.compareAndSet(null, "块 ${block.start}-${block.end} 重试 ${block.attempts} 次仍失败")
                    return
                }
                retryDeque.addLast(block)
            }
        }
    }

    /** 扫描目录已有完整块（不完整块删除重下）。 */
    private fun scanExistingBlocks(dir: File): List<Block> {
        return dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.mapNotNull { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                val blk = Block(s, e)
                if (f.length() >= blk.size) blk else { f.delete(); null }
            } ?: emptyList()
    }

    /** 校验 [0,total) 是否被完整块连续覆盖。 */
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

    /** 合并所有 seg 块（按 start 排序）。 */
    fun finalBlockFiles(chunkDir: File): List<File> =
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLongOrNull() ?: 0L }
            ?: emptyList()
}
