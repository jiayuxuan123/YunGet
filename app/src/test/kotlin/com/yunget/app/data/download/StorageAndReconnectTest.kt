package com.yunget.app.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAndReconnectTest {
    @Test
    fun storagePreflightAllowsSufficientSpaceAndBlocksOverflow() {
        val gb = 1024L * 1024L * 1024L
        assertNull(StoragePreflight.check(totalSize = 2 * gb, alreadyDownloaded = 0L, usableBytes = 3 * gb))
        assertNull(StoragePreflight.check(totalSize = 2 * gb, alreadyDownloaded = (1.5 * gb).toLong(), usableBytes = gb))
        val err = StoragePreflight.check(totalSize = 4 * gb, alreadyDownloaded = 0L, usableBytes = gb)
        assertNotNull(err)
        assertTrue(err!!.contains("存储空间不足"))
    }

    @Test
    fun reconnectMatcherOnlySelectsTransientNetworkErrors() {
        assertTrue(NetworkReconnectMonitor.isTransientNetworkFailure(null, "Connection reset by peer"))
        assertTrue(NetworkReconnectMonitor.isTransientNetworkFailure(null, "网络连接超时"))
        assertTrue(NetworkReconnectMonitor.isTransientNetworkFailure(null, "Unable to resolve host"))
        assertFalse(NetworkReconnectMonitor.isTransientNetworkFailure(403, "HTTP 403"))
        assertFalse(NetworkReconnectMonitor.isTransientNetworkFailure(503, "HTTP 503"))
        assertFalse(NetworkReconnectMonitor.isTransientNetworkFailure(null, "存储空间不足：还需约 2.0 GB"))
        assertFalse(NetworkReconnectMonitor.isTransientNetworkFailure(null, "源站持续限流"))
    }
}
