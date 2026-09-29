package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进度落盘节流的并发契约测试。
 *
 * ## 为什么必须测并发
 *
 * 下载进度回调由**多个 worker 线程并发**触发。节流逻辑若写成
 * 「读 map → 判断 → 写 map」（非原子），两个线程可能同时读到过期的 lastTs、
 * 同时通过判断、同时写库 —— 结果是**重复 UPDATE + 多次全表 Flow 重发**，
 * 而每次重发都会让主线程重组整个下载列表。这正是上游 #64 报告的 ANR 成因
 * （按字节节流时高速下载每秒写库几十次）。
 *
 * 单线程测试**发现不了**这个问题：串行调用下"读-判断-写"永远是对的。
 * 因此这里用真实多线程 + 屏障同步来放大竞态窗口。
 */
class ProgressPersistThrottleTest {

    /** 与 TurboDownloadManager.shouldWriteDb 同构的实现（含 CAS 占位）。 */
    private class Throttle(
        private val intervalMs: Long = 800,
        private val intervalBytes: Long = 1024 * 1024,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val lastTs = ConcurrentHashMap<Long, Long>()
        private val lastBytes = ConcurrentHashMap<Long, Long>()

        fun shouldWrite(roomId: Long, downloaded: Long): Boolean {
            val now = clock()
            val prev = lastTs[roomId]
            // 首次必须用 putIfAbsent：ConcurrentHashMap.replace 对**不存在的键**返回 false，
            // 若首次也走 replace，该任务将永远无法写库（进度永不落盘）。
            if (prev == null) {
                if (lastTs.putIfAbsent(roomId, now) != null) return false
                lastBytes[roomId] = downloaded
                return true
            }
            // 纯时间节流：加"字节阈值"会在高速下载下退化成每秒几十次写库（ANR 成因）
            if (now - prev < intervalMs) return false
            if (!lastTs.replace(roomId, prev, now)) return false
            lastBytes[roomId] = downloaded
            return true
        }
    }

    // ---------------------------------------------------------------- 基本节流

    @Test
    fun firstWriteIsAllowed() {
        val t = Throttle(clock = { 1_000L })
        assertTrue("首次进度必须允许写库（否则进度永远不落盘）", t.shouldWrite(1L, 100))
    }

    @Test
    fun rapidCallsWithinWindowAreSuppressed() {
        var now = 10_000L
        val t = Throttle(clock = { now })
        assertTrue(t.shouldWrite(1L, 0))
        // 800ms 窗口内、增量不足 1MB：全部抑制
        now += 100; assertTrue("窗口内不应写库", !t.shouldWrite(1L, 50_000))
        now += 100; assertTrue("窗口内不应写库", !t.shouldWrite(1L, 200_000))
        now += 100; assertTrue("窗口内不应写库", !t.shouldWrite(1L, 500_000))
    }

    @Test
    fun timeThresholdAllowsNextWrite() {
        var now = 10_000L
        val t = Throttle(clock = { now })
        assertTrue(t.shouldWrite(1L, 0))
        now += 799; assertTrue("未到 800ms 不应写", !t.shouldWrite(1L, 10))
        now += 2;   assertTrue("超过 800ms 应允许写", t.shouldWrite(1L, 10))
    }

    @Test
    fun byteGrowthAloneDoesNotBypassTimeWindow() {
        // 这是 ANR 的关键防线：即使瞬间增长 100MB，也不能绕过时间窗口写库。
        // 若这里返回 true，高速下载（50MB/s）就会每秒写库几十次 → 主线程重组洪峰 → ANR。
        var now = 10_000L
        val t = Throttle(clock = { now })
        assertTrue(t.shouldWrite(1L, 0))
        now += 10
        assertTrue(
            "字节增长不得绕过时间窗口（否则高速下载会高频写库 → ANR）",
            !t.shouldWrite(1L, 100L * 1024 * 1024),
        )
    }

    @Test
    fun tasksAreThrottledIndependently() {
        var now = 10_000L
        val t = Throttle(clock = { now })
        assertTrue(t.shouldWrite(1L, 0))
        assertTrue("任务 2 的首个进度不应被任务 1 的节流影响", t.shouldWrite(2L, 0))
        now += 100
        assertTrue("任务 1 仍在窗口内", !t.shouldWrite(1L, 10))
        assertTrue("任务 2 也在自己的窗口内", !t.shouldWrite(2L, 10))
    }

    // ---------------------------------------------------------------- 并发（核心）

    @Test
    fun concurrentCallersProduceExactlyOneWrite() {
        // 固定时钟 + 极小增量 → 窗口内只有第一个能通过
        val t = Throttle(clock = { 50_000L })
        val threads = 32
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val allowed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                start.await()                       // 屏障：尽量让所有线程同时进入
                if (t.shouldWrite(1L, 0)) allowed.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发测试应在 10s 内完成", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(
            "同一窗口内 32 个并发调用**必须只有一个**写库 —— " +
                "多于一个说明节流判定不是原子的（会造成重复 UPDATE + 全表 Flow 重发）",
            1, allowed.get(),
        )
    }

    @Test
    fun concurrentWritesAcrossWindowsAreBoundedByTime() {
        // 真实时钟 + 高并发：统计 1 秒内的写库次数，应接近 1000/800 ≈ 1~2 次
        val t = Throttle()
        val threads = 16
        val stopAt = System.currentTimeMillis() + 1_000
        val writes = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                var bytes = 0L
                while (System.currentTimeMillis() < stopAt) {
                    if (t.shouldWrite(1L, bytes)) writes.incrementAndGet()
                    bytes += 1024   // 每次只增 1KB：远达不到 1MB 字节阈值，只能靠时间
                }
                done.countDown()
            }
        }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        pool.shutdown()

        val n = writes.get()
        assertTrue(
            "1 秒内写库次数应被时间阈值限制在个位数，实际 $n 次" +
                "（若为几十次即上游 #64 的 ANR 成因：按字节节流导致高频写库）",
            n in 1..4,
        )
    }
}
