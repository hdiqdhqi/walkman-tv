package com.walkman.tv.data.logging

import com.walkman.tv.playback.PlaybackTrace
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Verifies that the existing [PlaybackTrace] play-chain events are funnelled into the running log
 * (spec §5 / §26 / §27) and that the transition id survives the hop.
 */
class PlaybackTraceSinkTest {

    @After
    fun clearSink() {
        PlaybackTrace.sink = null
    }

    @Test
    fun forwardsTransitionIdAndFieldsToTheRunningLog() {
        val now = 1_759_000_000_000L
        val dir = Files.createTempDirectory("pl-trace").toFile()
        val log = RunLog(dir, clock = { now })
        log.start()
        PlaybackTrace.sink = { id, step, fields ->
            log.event(LogLevel.INFO, "Playback", step, id, fields.toMap())
        }
        try {
            val trace = PlaybackTrace(debuggable = false)
            trace.step(31L, "RESOLVE_OK", "quality" to "320k", "source" to "kg")
        } finally {
            PlaybackTrace.sink = null
        }
        assertTrue(log.awaitIdle())
        val text = log.readAll().joinToString("") { it.text }
        assertTrue(text.contains("Playback RESOLVE_OK transition=t31"))
        assertTrue(text.contains("quality=320k"))
        assertTrue(text.contains("source=kg"))
        log.close()
    }

    @Test
    fun omitsTransitionWhenIdIsZero() {
        val now = 1_759_000_000_000L
        val dir = Files.createTempDirectory("pl-trace0").toFile()
        val log = RunLog(dir, clock = { now })
        log.start()
        PlaybackTrace.sink = { id, step, fields ->
            log.event(LogLevel.INFO, "Playback", step, id, fields.toMap())
        }
        try {
            PlaybackTrace(debuggable = false).step(0L, "APP_NOTE")
        } finally {
            PlaybackTrace.sink = null
        }
        assertTrue(log.awaitIdle())
        val text = log.readAll().joinToString("") { it.text }
        assertTrue(text.contains("Playback APP_NOTE"))
        assertFalse(text.contains("transition="))
        log.close()
    }

    @Test
    fun traceWithoutSinkDoesNotThrow() {
        PlaybackTrace.sink = null
        PlaybackTrace(debuggable = false).step(7L, "RESOLVE_START", "track" to "x")
    }
}
