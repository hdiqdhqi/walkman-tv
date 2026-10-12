package com.walkman.tv.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunLogTest {

    private var now = at(2026, 10, 3, 12)

    private fun tempDir(): File = Files.createTempDirectory("runlog-").toFile()

    private fun RunLog.lines(): List<String> =
        readAll().flatMap { it.text.lineSequence().toList() }.filter { it.isNotEmpty() }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // --- 1-3: the three levels ---------------------------------------------------------------

    @Test
    fun writesInfoWarnAndError() {
        val log = RunLog(tempDir(), clock = { now })
        log.start()
        log.event(LogLevel.INFO, "Playback", "RESOLVE_START", 31L, mapOf("track" to "t1"))
        log.event(LogLevel.WARN, "Playback", "RESOLVE_TIMEOUT")
        log.event(LogLevel.ERROR, "Playback", "PLAYER_ERROR", fields = mapOf("code" to 1001))
        assertTrue(log.awaitIdle())
        val text = log.lines().joinToString("\n")
        assertTrue(text.contains("INFO Playback RESOLVE_START transition=t31 track=t1"))
        assertTrue(text.contains("WARN Playback RESOLVE_TIMEOUT"))
        assertTrue(text.contains("ERROR Playback PLAYER_ERROR code=1001"))
        log.close()
    }

    // --- 4: concurrent writes ----------------------------------------------------------------

    @Test
    fun concurrentWritesAreAllPersisted() {
        val log = RunLog(tempDir(), clock = { now }, queueCapacity = 8_192)
        log.start()
        val threads = (1..8).map { id ->
            Thread { repeat(100) { log.event(LogLevel.INFO, "T", "EVENT", fields = mapOf("id" to id)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(log.awaitIdle())
        assertEquals(800, log.lines().size)
        log.close()
    }

    // --- 5: rotation ------------------------------------------------------------------------

    @Test
    fun rotatesWhenTheCalendarDayChanges() {
        val dir = tempDir()
        val log = RunLog(dir, clock = { now })
        log.start()
        log.event(LogLevel.INFO, "A", "DAY_ONE")
        assertTrue(log.awaitIdle())
        now = at(2026, 10, 4, 12)
        log.event(LogLevel.INFO, "A", "DAY_TWO")
        assertTrue(log.awaitIdle())
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("2026-10-03.log", "2026-10-04.log"), names)
        log.close()
    }

    @Test
    fun splitsOneDayIntoSegmentsWhenFileGrows() {
        val dir = tempDir()
        val log = RunLog(dir, clock = { now }, maxFileBytes = 120, maxTotalBytes = 10_000_000)
        log.start()
        repeat(20) { i -> log.event(LogLevel.INFO, "A", "EVENT_NUMBER", fields = mapOf("i" to i)) }
        assertTrue(log.awaitIdle())
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.size >= 3)
        assertTrue(names.contains("2026-10-03.log"))
        assertTrue(names.contains("2026-10-03-1.log"))
        log.close()
    }

    // --- 6: total-size cap -------------------------------------------------------------------

    @Test
    fun deletesOldestFilesWhenTotalSizeExceedsCap() {
        val dir = tempDir()
        // Start the log on day 1 so the periodic prune (60s apart) actually fires across the run.
        now = at(2026, 10, 1, 12)
        val log = RunLog(dir, clock = { now }, maxFileBytes = 1_000_000, maxTotalBytes = 900)
        log.start()
        for (day in 1..3) {
            now = at(2026, 10, day, 12)
            repeat(20) {
                log.event(LogLevel.INFO, "A", "X", fields = mapOf("v" to "aaaaaaaaaaaaaaaaaaaa"))
            }
            assertTrue(log.awaitIdle())
        }
        log.close()
        val names = dir.listFiles()!!.map { it.name }
        assertFalse(names.contains("2026-10-01.log"))
        assertTrue(names.contains("2026-10-03.log"))
    }

    // --- 7: 3-day retention ------------------------------------------------------------------

    @Test
    fun deletesFilesOlderThanRetentionWindow() {
        val dir = tempDir()
        for (date in listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03")) {
            File(dir, date + ".log").writeText("2026-10-03T10:00:00.000 INFO A E\n")
        }
        val log = RunLog(dir, clock = { now })
        log.start()
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("2026-10-01.log", "2026-10-02.log", "2026-10-03.log"), names)
        log.close()
    }

    // --- 8: low disk -------------------------------------------------------------------------

    @Test
    fun lowDiskKeepsOnlyWarnAndAbove() {
        val log = RunLog(tempDir(), clock = { now }, freeBytes = { 1L })
        log.start()
        log.event(LogLevel.INFO, "A", "INFO_EVENT")
        log.event(LogLevel.WARN, "A", "WARN_EVENT")
        assertTrue(log.awaitIdle())
        val text = log.lines().joinToString("\n")
        assertFalse(text.contains("INFO_EVENT"))
        assertTrue(text.contains("WARN_EVENT"))
        val status = log.status()
        assertTrue(status.restricted)
        assertEquals("存储空间不足，已降低日志级别", status.statusText)
        log.close()
    }

    // --- 16: last 30 minutes -----------------------------------------------------------------

    @Test
    fun filterSinceKeepsOnlyRecentLines() {
        val dir = tempDir()
        File(dir, "2026-10-03.log").writeText(
            "2026-10-03T11:00:00.000 INFO A OLD\n" +
                "2026-10-03T11:50:00.000 INFO A RECENT\n" +
                "2026-10-03T12:00:00.000 INFO A NEWEST\n" +
                "garbage line\n",
        )
        val log = RunLog(dir, clock = { now })
        log.start()
        val out = log.readAll(now - 30 * 60_000L).joinToString("") { it.text }
        assertFalse(out.contains("OLD"))
        assertTrue(out.contains("RECENT"))
        assertTrue(out.contains("NEWEST"))
        assertFalse(out.contains("garbage"))
        log.close()
    }

    // --- 17 / 30: empty + clear ---------------------------------------------------------------

    @Test
    fun emptyLogReadsBackEmpty() {
        val log = RunLog(tempDir(), clock = { now })
        log.start()
        assertTrue(log.readAll().isEmpty())
        assertEquals(0L, log.status().totalBytes)
        log.close()
    }

    @Test
    fun clearDeletesEverythingAndRestarts() {
        val dir = tempDir()
        val log = RunLog(dir, clock = { now })
        log.start()
        log.event(LogLevel.INFO, "A", "BEFORE_CLEAR")
        assertTrue(log.awaitIdle())
        assertTrue(log.lines().isNotEmpty())

        log.clear()
        assertTrue(log.awaitIdle())
        assertTrue(log.readAll().isEmpty())
        assertEquals(0, dir.listFiles()!!.size)

        log.event(LogLevel.INFO, "A", "AFTER_CLEAR")
        assertTrue(log.awaitIdle())
        assertTrue(log.lines().joinToString(" ").contains("AFTER_CLEAR"))
        log.close()
    }

    // --- rate limiting (spec §4) --------------------------------------------------------------

    @Test
    fun repeatedStatesAreRateLimitedButChangesAreNot() {
        val log = RunLog(tempDir(), clock = { now }, rateWindowMs = 5_000L)
        log.start()
        log.event(LogLevel.INFO, "Playback", "PLAYER_STATE", fields = mapOf("state" to "BUFFERING"))
        log.event(LogLevel.INFO, "Playback", "PLAYER_STATE", fields = mapOf("state" to "BUFFERING"))
        log.event(LogLevel.INFO, "Playback", "PLAYER_STATE", fields = mapOf("state" to "READY"))
        assertTrue(log.awaitIdle())
        assertEquals(2, log.lines().size)
        log.close()
    }

    // --- 24 / 25: never blocks, never crashes ------------------------------------------------

    @Test
    fun eventSurvivesAFailingFreeSpaceProbe() {
        val log = RunLog(tempDir(), clock = { now }, freeBytes = { throw IllegalStateException("no space probe") })
        log.start()
        log.event(LogLevel.WARN, "A", "STILL_OK")
        assertTrue(log.awaitIdle())
        assertTrue(log.lines().joinToString(" ").contains("STILL_OK"))
        log.close()
    }

    @Test
    fun startFailsGracefullyWhenDirectoryCannotBeCreated() {
        val file = File(tempDir(), "not-a-dir")
        file.writeText("x")
        val log = RunLog(file, clock = { now })
        log.start()
        assertFalse(log.status().recording)
        log.event(LogLevel.INFO, "A", "B") // must not throw
        assertEquals("日志未启用", log.status().statusText)
    }

    @Test
    fun eventCallsAreCheapEvenUnderLoad() {
        val log = RunLog(tempDir(), clock = { now }, queueCapacity = 256)
        log.start()
        val start = System.currentTimeMillis()
        repeat(2_000) { log.event(LogLevel.INFO, "A", "FLOOD") }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("event() took " + elapsed + " ms", elapsed < 2_000)
        log.close()
    }

    // --- 29: error format + status ------------------------------------------------------------

    @Test
    fun warnOrErrorBecomesLastProblem() {
        val nowMs = at(2026, 10, 3, 12, 32)
        val log = RunLog(tempDir(), clock = { nowMs })
        log.start()
        log.event(LogLevel.ERROR, "Playback", "PLAYER_ERROR", fields = mapOf("code" to 1001))
        assertTrue(log.awaitIdle())
        val problem = log.status().lastProblem
        assertNotNull(problem)
        assertTrue(problem!!.contains("Playback PLAYER_ERROR"))
        assertTrue(problem.contains("12:32"))
        log.close()
    }

    @Test
    fun statusReflectsRecording() {
        val log = RunLog(tempDir(), clock = { now })
        assertEquals("日志未启用", log.status().statusText)
        log.start()
        assertEquals("正在记录", log.status().statusText)
        log.close()
    }
}
