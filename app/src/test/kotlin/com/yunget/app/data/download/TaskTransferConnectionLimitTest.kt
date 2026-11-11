package com.yunget.app.data.download

import dev.turbodl.core.TurboConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTransferConnectionLimitTest {
    @Test
    fun `frozen sixteen remains sixteen when current limit grows to 256`() {
        val json = encodeConnections(16)

        assertEquals(16, JSONObject(json).getInt("connections"))
        assertEquals(16, TaskTransferPolicies.connectionLimit(json, 256))
    }

    @Test
    fun `frozen 256 remains bounded by current limit 32`() {
        val json = encodeConnections(256)

        assertEquals(256, JSONObject(json).getInt("connections"))
        assertEquals(32, TaskTransferPolicies.connectionLimit(json, 32))
    }

    @Test
    fun `legacy and invalid policies fall back to the current limit`() {
        val currentLimit = 72

        assertEquals(currentLimit, TaskTransferPolicies.connectionLimit("{}", currentLimit))
        assertEquals(currentLimit, TaskTransferPolicies.connectionLimit("not-json", currentLimit))
        assertEquals(
            currentLimit,
            TaskTransferPolicies.connectionLimit(
                JSONObject()
                    .put("version", 2)
                    .put("connections", 16)
                    .toString(),
                currentLimit,
            ),
        )
        assertEquals(
            currentLimit,
            TaskTransferPolicies.connectionLimit(
                JSONObject()
                    .put("version", 1)
                    .put("connections", "16")
                    .toString(),
                currentLimit,
            ),
        )
    }

    @Test
    fun `decode rejects zero oversized and string connections`() {
        listOf<Any>(0, 257, "16").forEach { invalid ->
            val json = JSONObject()
                .put("version", 1)
                .put("connections", invalid)
                .toString()

            assertNull(
                "connections=$invalid must be rejected",
                TaskTransferPolicies.decode(json),
            )
        }
    }

    @Test
    fun `version one without connections remains compatible`() {
        val json = JSONObject().put("version", 1).toString()

        assertNotNull(TaskTransferPolicies.decode(json))
        assertEquals(200, TaskTransferPolicies.connectionLimit(json, 200))
    }

    @Test
    fun `user limit is clamped to one through 256`() {
        val cases = listOf(
            Int.MIN_VALUE to 1,
            -1 to 1,
            0 to 1,
            1 to 1,
            256 to 256,
            257 to 256,
            Int.MAX_VALUE to 256,
        )

        cases.forEach { (input, expected) ->
            assertEquals(
                "user limit $input",
                expected,
                TaskTransferPolicies.connectionLimit("{}", input),
            )
        }
    }

    private fun encodeConnections(connections: Int): String =
        TaskTransferPolicies.encode(
            TurboConfig(
                maxConnectionsPerTask = connections,
                minSegmentSize = 512L * 1024,
                segmentsPerConnection = 4,
            ),
            platform = "quark",
            accountId = "",
        )
}
