package com.yunget.app.data.download

import dev.turbodl.core.AdaptiveConcurrencyMode
import dev.turbodl.core.TurboConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTransferPoliciesTest {
    private fun config(): TurboConfig = TurboConfig(
        minSegmentSize = 512L * 1024,
        blockSize = 8L * 1024 * 1024,
        segmentsPerConnection = 6,
        slowStart = false,
        slowStartInitial = 3,
        adaptiveConcurrencyMode = AdaptiveConcurrencyMode.UPWARD,
        maxConnectionsPerHost = 5,
    )

    @Test
    fun `round trip preserves every task field and hashes the account id`() {
        val json = TaskTransferPolicies.encode(config(), "quark", "account-123")
        val encoded = JSONObject(json)

        assertEquals(1, encoded.getInt("version"))
        assertEquals("quark:725a2fd11a2b5251", encoded.getString("backpressureGroup"))
        assertFalse(json.contains("account-123"))
        assertFalse(encoded.has("cookie"))
        assertFalse(encoded.has("Cookie"))

        val policy = TaskTransferPolicies.decode(json)!!
        assertEquals(512L * 1024, policy.minSegmentSize)
        assertEquals(8L * 1024 * 1024, policy.blockSize)
        assertEquals(6, policy.segmentsPerConnection)
        assertEquals(false, policy.slowStart)
        assertEquals(3, policy.slowStartInitial)
        assertEquals(AdaptiveConcurrencyMode.UPWARD, policy.adaptiveConcurrencyMode)
        assertEquals(5, policy.maxConnectionsPerHost)
        assertEquals("quark:725a2fd11a2b5251", policy.backpressureGroup)
    }

    @Test
    fun `blank account id leaves the backpressure group unset`() {
        for (accountId in listOf("", "   ")) {
            val json = TaskTransferPolicies.encode(config(), "quark", accountId)
            val encoded = JSONObject(json)

            assertFalse(encoded.has("backpressureGroup"))
            assertNull(TaskTransferPolicies.decode(json)!!.backpressureGroup)
        }
    }

    @Test
    fun `empty and malformed policies decode to null`() {
        assertNull(TaskTransferPolicies.decode(""))
        assertNull(TaskTransferPolicies.decode("   "))
        assertNull(TaskTransferPolicies.decode("{}"))
        assertNull(TaskTransferPolicies.decode("[]"))
        assertNull(TaskTransferPolicies.decode("not-json"))
        assertNull(TaskTransferPolicies.decode("""{"version":2}"""))
        assertNull(TaskTransferPolicies.decode("""{"version":1,"segmentsPerConnection":0}"""))
        assertNull(TaskTransferPolicies.decode("""{"version":1,"slowStart":"false"}"""))
        assertNull(TaskTransferPolicies.decode("""{"version":1,"adaptiveConcurrencyMode":"SIDEWAYS"}"""))
        assertNull(TaskTransferPolicies.decode("""{"version":1,"backpressureGroup":" "}"""))
    }

    @Test
    fun `partial version one policy keeps explicit values and nullable fields`() {
        val policy = TaskTransferPolicies.decode(
            """{"version":1,"minSegmentSize":1048576,"segmentsPerConnection":2,"slowStart":false}""",
        )!!

        assertEquals(1024L * 1024, policy.minSegmentSize)
        assertEquals(2, policy.segmentsPerConnection)
        assertEquals(false, policy.slowStart)
        assertNull(policy.blockSize)
        assertNull(policy.slowStartInitial)
        assertNull(policy.adaptiveConcurrencyMode)
        assertNull(policy.maxConnectionsPerHost)
        assertNull(policy.backpressureGroup)
    }

    @Test
    fun `label describes the visible policy dimensions`() {
        val defaultConfig = TurboConfig(
            minSegmentSize = 256L * 1024,
            segmentsPerConnection = 4,
        )

        assertEquals(
            "256 KiB · 4 分片/连接",
            TaskTransferPolicies.label(TaskTransferPolicies.encode(defaultConfig, "quark", "")),
        )
        assertEquals("默认策略", TaskTransferPolicies.label(""))
        assertEquals("默认策略", TaskTransferPolicies.label("{}"))
        assertEquals("默认策略", TaskTransferPolicies.label("""{"version":1}"""))
        assertEquals(
            "4 KiB · 2 分片/连接",
            TaskTransferPolicies.label(
                """{"version":1,"minSegmentSize":4096,"segmentsPerConnection":2}""",
            ),
        )
    }
}
