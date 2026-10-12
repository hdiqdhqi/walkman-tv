package com.walkman.tv.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    @Test
    fun lineHasTheDocumentedShape() {
        val line = LogFormat.line(
            1_759_000_000_123L, LogLevel.INFO, "Playback", "RESOLVE_START", 31L,
            mapOf("track" to "t1", "source" to "kg"),
        )
        assertTrue(line.endsWith("INFO Playback RESOLVE_START transition=t31 track=t1 source=kg"))
    }

    @Test
    fun timestampRoundTrips() {
        val ms = 1_759_000_000_123L
        val line = LogFormat.line(ms, LogLevel.WARN, "Sync", "SYNC_FAIL")
        assertEquals(ms, LogFormat.parseTimestamp(line))
    }

    @Test
    fun zeroTransitionIdIsOmitted() {
        val line = LogFormat.line(0L, LogLevel.INFO, "A", "B", 0L)
        assertFalse(line.contains("transition="))
    }

    @Test
    fun valuesAreSanitized() {
        val line = LogFormat.line(
            0L, LogLevel.INFO, "Net", "GET",
            fields = mapOf("url" to "https://a.com/x?token=abcd1234efgh"),
        )
        assertFalse(line.contains("abcd1234efgh"))
    }

    @Test
    fun longValuesAreTruncated() {
        val line = LogFormat.line(0L, LogLevel.INFO, "A", "B", fields = mapOf("v" to "x".repeat(500)))
        assertTrue(line.length < 400)
    }

    @Test
    fun newlinesAreFlattenedToOneLine() {
        val line = LogFormat.line(0L, LogLevel.ERROR, "A", "B", fields = mapOf("msg" to "line1\nline2"))
        assertEquals(1, line.lines().size)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(LogFormat.parseTimestamp("not a log line at all"))
        assertNull(LogFormat.parseTimestamp("short"))
    }
}
