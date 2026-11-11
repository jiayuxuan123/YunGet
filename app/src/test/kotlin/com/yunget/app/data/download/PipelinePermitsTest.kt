package com.yunget.app.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinePermitsTest {
    @Test
    fun `limit one admits three leases and resumes the fourth after release`() = runBlocking {
        val permits = PipelinePermits { 1 }
        val leases = withTimeout(2_000) {
            List(3) { permits.acquire() }
        }
        val fourthLease = CompletableDeferred<PipelinePermits.Lease>()
        val fourthWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            fourthLease.complete(permits.acquire())
        }

        assertFalse("the fourth lease must wait", fourthLease.isCompleted)
        leases[0].release()

        val admitted = withTimeout(2_000) { fourthLease.await() }
        assertTrue("the waiter should continue after a release", fourthWaiter.isCompleted)

        admitted.release()
        leases.drop(1).forEach { it.release() }
        fourthWaiter.join()
    }

    @Test
    fun `releasing the same lease twice does not admit another waiter`() = runBlocking {
        val permits = PipelinePermits { 1 }
        val leases = withTimeout(2_000) {
            List(3) { permits.acquire() }
        }

        leases[0].release()
        leases[0].release()

        val fourthLease = CompletableDeferred<PipelinePermits.Lease>()
        val fourthWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            fourthLease.complete(permits.acquire())
        }
        val admittedFourth = withTimeout(2_000) { fourthLease.await() }

        val fifthLease = CompletableDeferred<PipelinePermits.Lease>()
        val fifthWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            fifthLease.complete(permits.acquire())
        }
        assertFalse(
            "a second release of the same lease must not create extra capacity",
            fifthLease.isCompleted,
        )

        leases[1].release()
        val admittedFifth = withTimeout(2_000) { fifthLease.await() }

        admittedFifth.release()
        admittedFourth.release()
        leases[2].release()
        fourthWaiter.join()
        fifthWaiter.join()
    }

    @Test
    fun `cancelling a waiting acquire completes promptly`() = runBlocking {
        val permits = PipelinePermits { 1 }
        val leases = withTimeout(2_000) {
            List(3) { permits.acquire() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            permits.acquire()
        }

        assertFalse("the extra lease must be waiting", waiter.isCompleted)
        withTimeout(1_000) {
            waiter.cancelAndJoin()
        }
        assertTrue("cancellation must complete without waiting for a permit", waiter.isCancelled)

        leases.forEach { it.release() }
    }
}
