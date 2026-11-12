package com.yunget.app.data.analytics

import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalyticsDeliveryTest {
    private class Store : AnalyticsQueueStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }
    private fun event(id: String = UUID.randomUUID().toString()) = JSONObject()
        .put("event_id", id).put("event", "app_open").put("app_version", "2.7.32")

    @Test fun failedUploadAndProcessRecreationKeepOriginalEventId() {
        val store = Store()
        val queue = AnalyticsQueue(store).apply { setEnabled(true) }
        val original = event()
        queue.enqueue(queue.ticket()!!, original)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":1}"))
            val client = AnalyticsUpload.client()
            try {
                assertFalse(AnalyticsUpload(queue, client, server.url("/analytics").toString())
                    .send(queue.peek()!!, UUID.randomUUID().toString()))
                val recreated = AnalyticsQueue(store).apply { setEnabled(true) }
                assertTrue(recreated.peek()!!.ids.contains(original.getString("event_id")))
                assertTrue(AnalyticsUpload(recreated, client, server.url("/analytics").toString())
                    .send(recreated.peek()!!, UUID.randomUUID().toString()))
                val first = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("events")
                val retry = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("events")
                assertEquals(first.getJSONObject(0).getString("event_id"), retry.getJSONObject(0).getString("event_id"))
                assertNull(recreated.peek())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun optOutRejectsLateEnqueueAndLateAckDoesNotDeleteNewConsentEvents() {
        val store = Store()
        val queue = AnalyticsQueue(store).apply { setEnabled(true) }
        val oldTicket = queue.ticket()!!
        queue.enqueue(oldTicket, event())
        val oldBatch = queue.peek()!!
        queue.setEnabled(false)
        assertNull(store.value)
        assertFalse(queue.enqueue(oldTicket, event()))
        queue.setEnabled(true)
        val fresh = event()
        queue.enqueue(queue.ticket()!!, fresh)
        queue.acknowledge(oldBatch)
        assertEquals(setOf(fresh.getString("event_id")), queue.peek()!!.ids)
    }

    @Test fun acknowledgedBatchDoesNotRemoveEventsEnqueuedWhileSending() {
        val queue = AnalyticsQueue(Store()).apply { setEnabled(true) }
        repeat(8) { queue.enqueue(queue.ticket()!!, event()) }
        val sending = queue.peek()!!
        val later = event()
        queue.enqueue(queue.ticket()!!, later)
        queue.acknowledge(sending)
        assertEquals(setOf(later.getString("event_id")), queue.peek()!!.ids)
    }
}
