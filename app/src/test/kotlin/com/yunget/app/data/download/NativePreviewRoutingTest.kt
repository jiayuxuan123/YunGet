package com.yunget.app.data.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePreviewRoutingTest {
    @Test
    fun legacyTasksStayLegacyAndNewTaskKeepsItsBackendAcrossReload() {
        val oldPolicy = """{"version":1,"connections":32}"""
        assertFalse(NativePreviewRouting.selected(oldPolicy))
        val pinned = NativePreviewRouting.pin(oldPolicy, true)
        assertTrue(NativePreviewRouting.selected(JSONObject(pinned).toString()))
        assertEquals(32, JSONObject(pinned).getInt("connections"))
        assertFalse(NativePreviewRouting.selected(NativePreviewRouting.pin(pinned, false)))
    }

    @Test
    fun onlyOrdinaryHttpFilesAreEligible() {
        assertTrue(NativePreviewRouting.supportsDirectLink("https://example.org/file?id=1", "data.zip"))
        assertFalse(NativePreviewRouting.supportsDirectLink("https://example.org/test.M3U8?key=1", "video.mp4"))
        assertFalse(NativePreviewRouting.supportsDirectLink("https://example.org/stream", "source.mpd"))
        assertFalse(NativePreviewRouting.supportsDirectLink("ftp://example.org/a", "a.zip"))
        assertFalse(NativePreviewRouting.supportsDirectLink("broken address", "data.zip"))
    }

    @Test
    fun mediaProtocolIsDetectedFromUrlOrResolvedName() {
        assertEquals(
            NativePreviewRouting.MEDIA_HLS,
            NativePreviewRouting.mediaKind("https://example.org/play?token=1", "video.m3u8"),
        )
        assertEquals(
            NativePreviewRouting.MEDIA_DASH,
            NativePreviewRouting.mediaKind("https://example.org/manifest.mpd?token=1", "video"),
        )
        assertEquals(null, NativePreviewRouting.mediaKind("https://example.org/data.zip", "data.zip"))
        assertEquals(null, NativePreviewRouting.mediaKind("magnet:?xt=urn:btih:abc", "video.m3u8"))
    }

    @Test
    fun nativeMediaRouteSurvivesSourceRefreshAndEngineSelectionChanges() {
        val policy = NativePreviewRouting.pin("{}", true, NativePreviewRouting.MEDIA_DASH)
        assertEquals(
            mapOf(
                NativePreviewRouting.TAG to NativePreviewRouting.NATIVE,
                NativePreviewRouting.MEDIA_TAG to NativePreviewRouting.MEDIA_DASH,
            ),
            NativePreviewRouting.tags(policy),
        )
        assertTrue(NativePreviewRouting.tags(NativePreviewRouting.pin(policy, false)).isEmpty())
    }

    @Test
    fun nativeCoreUsesExistingTurboDlEntry() {
        assertTrue(NativePreviewRouting.isNativeProvider("turbodl"))
        assertFalse(NativePreviewRouting.isNativeProvider("native"))
        assertFalse(NativePreviewRouting.isNativeProvider("gopeed"))
    }

    @Test
    fun torrentFileDetectionUsesUrlOrResolvedName() {
        assertTrue(NativePreviewRouting.isTorrentFile("https://example.org/file.TORRENT?id=1"))
        assertTrue(NativePreviewRouting.isTorrentFile("https://example.org/file", "seed.torrent"))
        assertFalse(NativePreviewRouting.isTorrentFile("https://example.org/file?name=seed.torrent"))
        assertFalse(NativePreviewRouting.isTorrentFile("ftp://example.org/file.torrent"))
    }
}
