package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兜底引擎（内置兼容 / aria2）的读缓冲摊薄测试。
 *
 * ## 事故背景（2026-10-10，真机）
 *
 * 用户报 `OutOfMemoryError`，堆 100% 占满（256MiB）。根因是**每连接固定分配读缓冲**
 * 再乘上连接数：
 *
 * - 默认引擎 TurboDL：1MB/连接 × 256 连接 = **256MB = Android 默认整堆**（真凶）；
 * - 兜底引擎 ChunkDownloader：256KB/连接 × 256 连接 = 64MB（同类缺陷，一并管住）。
 *
 * 缓冲都是活对象（正被下载协程引用），GC 回收不掉，所以是"占满堆"而不是"垃圾堆积"。
 *
 * ## 为什么分母要乘并发任务数
 *
 * 预算是**进程级**的：真实的连接总数 = 同时跑的任务数 × 每任务连接数。
 * 只按单任务连接数摊，多任务并行时照样突破 —— App 允许 5 个任务同时下载
 * （设置页 `listOf(1, 2, 3, 5, 8)`），5 × 256 × 256KB = 320MB。
 */
class IoBufferBudgetTest {

    @Test
    fun `默认设置保持 256KB 不变`() {
        // App 默认：16 线程 × 1 个并发任务上限（DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1）
        // 32MB / 16 = 2MB > 256KB 上限 → 取 256KB。日常行为不变。
        assertEquals(256 * 1024, ioBufferSizeFor(connections = 16, concurrentTasks = 1))
    }

    @Test
    fun `事故场景：256 线程下总占用不超预算`() {
        val perConn = ioBufferSizeFor(connections = 256, concurrentTasks = 1)
        val total = perConn.toLong() * 256
        assertTrue(
            "256 连接总缓冲 $total 应 ≤ 32MB",
            total <= 32L * 1024 * 1024,
        )
        assertEquals(128 * 1024, perConn)
    }

    @Test
    fun `多任务并行时仍不超预算`() {
        // 设置页最多允许 5 个任务同时下载
        val tasks = 5
        val conns = 256
        val perConn = ioBufferSizeFor(connections = conns, concurrentTasks = tasks)
        val total = perConn.toLong() * conns * tasks
        assertTrue(
            "$tasks 任务 × $conns 连接的总缓冲 $total 应 ≤ 32MB",
            total <= 32L * 1024 * 1024,
        )
    }

    @Test
    fun `全部可达设置组合都不超预算且不低于下限`() {
        // 设置页的实际选项：线程 listOf(1,2,4,8,16,32,64,128,192,256)
        //                  并发 listOf(1,2,3,5,8)
        val threadOptions = listOf(1, 2, 4, 8, 16, 32, 64, 128, 192, 256)
        val concurrentOptions = listOf(1, 2, 3, 5, 8)
        var worst = 0L

        for (conns in threadOptions) {
            for (tasks in concurrentOptions) {
                val perConn = ioBufferSizeFor(conns, tasks)
                val total = perConn.toLong() * conns * tasks
                if (total > worst) worst = total

                assertTrue(
                    "线程=$conns 任务=$tasks 单连接缓冲 $perConn 低于 8KB 下限",
                    perConn >= 8 * 1024,
                )
                assertTrue(
                    "线程=$conns 任务=$tasks 总缓冲 $total 超出 32MB 预算",
                    total <= 32L * 1024 * 1024,
                )
            }
        }
        assertTrue("最坏总占用 $worst 应仍在预算内", worst <= 32L * 1024 * 1024)
    }

    @Test
    fun `非法线程数不产生零长度缓冲`() {
        // ByteArray(0) 会让读循环空转（read 永远返回 0），表现为"下载卡住不动"
        assertTrue(ioBufferSizeFor(0, 0) >= 8 * 1024)
        assertTrue(ioBufferSizeFor(-3, 1) >= 8 * 1024)
        assertTrue(ioBufferSizeFor(16, -1) >= 8 * 1024)
    }
}
