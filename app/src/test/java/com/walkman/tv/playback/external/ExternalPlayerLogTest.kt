package com.walkman.tv.playback.external

import com.walkman.tv.data.logging.LogFormat
import com.walkman.tv.data.logging.LogLevel
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * External-player logging (spec 11 / 21). The critical guarantees: the four event names exist,
 * the fields carry the ids/MIME/codec the spec asks for, and a signed play URL — including its
 * `token` / `sign` / `expires` query — can never reach a log line.
 */
class ExternalPlayerLogTest {

    @After
    fun tearDown() = ExternalPlayerLog.reset()

    private fun target(url: String): ExternalPlaybackTarget = ExternalPlaybackTarget(
        track = Track(id = "kg_1", name = "N", singer = "S", source = SourceID.KG, songmid = "1"),
        url = url,
        mimeType = AudioMime.fromUrl(url),
        quality = Quality.ATMOS,
        codec = "FLAC",
        transitionId = 77L,
    )

    @Test
    fun eventNamesMatchTheSpec() {
        assertEquals("EXTERNAL_PLAYER_START", ExternalPlayerLog.EVENT_START)
        assertEquals("EXTERNAL_PLAYER_SUCCESS", ExternalPlayerLog.EVENT_SUCCESS)
        assertEquals("EXTERNAL_PLAYER_NO_HANDLER", ExternalPlayerLog.EVENT_NO_HANDLER)
        assertEquals("EXTERNAL_PLAYER_START_FAILED", ExternalPlayerLog.EVENT_START_FAILED)
    }

    @Test
    fun defaultPlayerEventNamesMatchTheSpec() {
        assertEquals("EXTERNAL_PLAYER_DEFAULT_USED", ExternalPlayerLog.EVENT_DEFAULT_USED)
        assertEquals("EXTERNAL_PLAYER_DEFAULT_INVALID", ExternalPlayerLog.EVENT_DEFAULT_INVALID)
        assertEquals("EXTERNAL_PLAYER_DEFAULT_CLEARED", ExternalPlayerLog.EVENT_DEFAULT_CLEARED)
        assertEquals("EXTERNAL_PLAYER_SELECTED", ExternalPlayerLog.EVENT_SELECTED)
    }

    @Test
    fun standaloneAndSelectionEventsReachTheSink() {
        val seen = mutableListOf<Pair<String, List<Pair<String, Any?>>>>()
        ExternalPlayerLog.sink = { _, event, fields -> seen += event to fields }

        ExternalPlayerLog.emitStandalone(
            ExternalPlayerLog.EVENT_DEFAULT_CLEARED,
            packageName = "com.tcl.media",
            label = "媒体中心",
        )
        ExternalPlayerLog.emitSelected(9L, "com.tcl.media", "媒体中心")

        assertEquals("EXTERNAL_PLAYER_DEFAULT_CLEARED", seen[0].first)
        assertEquals("EXTERNAL_PLAYER_SELECTED", seen[1].first)
        assertTrue(seen[1].second.any { it.first == "package" && it.second == "com.tcl.media" })
        assertTrue(seen[1].second.none { it.second?.toString()?.contains("http") == true })
    }

    @Test
    fun logLineCarriesIdsButNeverTheUrlOrItsToken() {
        val url = "https://cdn.example.com/music/a.flac?token=SECRET123&sign=abc&expires=999"
        val fields = ExternalPlayerLog.fields(target(url), packageName = "org.videolan.vlc", label = "VLC")
        val line = LogFormat.line(
            ms = 1_759_000_000_000L,
            level = LogLevel.INFO,
            component = "ExternalPlayer",
            event = ExternalPlayerLog.EVENT_START,
            transitionId = 77L,
            fields = fields.toMap(),
        )

        // Must be present.
        assertTrue(line.contains("EXTERNAL_PLAYER_START"))
        assertTrue(line.contains("transition=t77"))
        assertTrue(line.contains("trackId=kg_1"))
        assertTrue(line.contains("source=kg"))
        assertTrue(line.contains("quality=atmos"))
        assertTrue(line.contains("package=org.videolan.vlc"))
        assertTrue(line.contains("mime=audio/flac"))
        assertTrue(line.contains("codec=FLAC"))
        assertTrue(line.contains("host=cdn.example.com"))

        // Must never be present.
        assertFalse(line.contains(url))
        assertFalse(line.contains("SECRET123"))
        assertFalse(line.contains("token="))
        assertFalse(line.contains("sign="))
        assertFalse(line.contains("expires="))
        assertFalse(line.contains("/music/a.flac"))
    }

    @Test
    fun emitForwardsEventAndTransitionIdToSink() {
        var seen: Triple<Long, String, List<Pair<String, Any?>>>? = null
        ExternalPlayerLog.sink = { id, event, fields -> seen = Triple(id, event, fields) }

        ExternalPlayerLog.emit(
            ExternalPlayerLog.EVENT_SUCCESS,
            target("https://x/a.mp3?token=z"),
            packageName = "pkg",
            label = "Player",
        )

        val captured = seen!!
        assertEquals(77L, captured.first)
        assertEquals("EXTERNAL_PLAYER_SUCCESS", captured.second)
        assertTrue(captured.third.none { it.second?.toString()?.contains("http") == true })
    }

    @Test
    fun noHandlerEventCarriesOnlyMime() {
        var seen: Triple<Long, String, List<Pair<String, Any?>>>? = null
        ExternalPlayerLog.sink = { id, event, fields -> seen = Triple(id, event, fields) }
        ExternalPlayerLog.emitNoHandler(12L, "audio/mpeg")
        assertEquals(12L, seen!!.first)
        assertEquals("EXTERNAL_PLAYER_NO_HANDLER", seen!!.second)
        assertTrue(seen!!.third.any { it.first == "mime" && it.second == "audio/mpeg" })
    }

    @Test
    fun hostOfDropsQueryAndHandlesLocalAndEmpty() {
        assertEquals("cdn.example.com", ExternalPlayerLog.hostOf("https://cdn.example.com/a.flac?token=1"))
        assertEquals("local", ExternalPlayerLog.hostOf("file:///sdcard/music/a.flac"))
        assertEquals("?", ExternalPlayerLog.hostOf(null))
        assertEquals("?", ExternalPlayerLog.hostOf(""))
    }
}
