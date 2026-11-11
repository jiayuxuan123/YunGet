package com.yunget.app.data.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Bounds downloaded files waiting for storage as well as active network jobs. */
internal class PipelinePermits(private val networkLimit: () -> Int) {
    private val active = AtomicInteger()

    class Lease internal constructor(private val active: AtomicInteger) {
        private val released = AtomicBoolean()
        fun release() { if (released.compareAndSet(false, true)) active.decrementAndGet() }
    }

    suspend fun acquire(): Lease {
        while (true) {
            coroutineContext.ensureActive()
            val count = active.get()
            if (count < networkLimit().coerceIn(1, 64) + 2 && active.compareAndSet(count, count + 1)) {
                return Lease(active)
            }
            delay(50)
        }
    }
}
