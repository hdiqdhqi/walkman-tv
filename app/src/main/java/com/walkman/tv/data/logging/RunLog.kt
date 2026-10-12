package com.walkman.tv.data.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Always-on, size-bounded running log (spec §1/§7/§8).
 *
 * Design constraints this implementation is built around:
 *  - **always recording**: it is started at app launch and never needs the user to opt in, so an
 *    intermittent problem can be analysed afterwards without having predicted it;
 *  - **never in the way**: callers only ever hand over a plain data object; formatting, IO and
 *    rotation happen on a single daemon writer thread. The queue is bounded and drops (counted)
 *    instead of blocking the caller, and every failure is swallowed into [Status.lastError];
 *  - **bounded**: one file per day (auto-split at [maxFileBytes]), retention of
 *    [retentionDays] days, hard cap of [maxTotalBytes] in total - the oldest file is deleted
 *    first;
 *  - **disk-safe**: when free space drops below [lowSpaceBytes] only WARN/ERROR is kept;
 *  - **safe**: every value goes through [LogSanitizer].
 */
class RunLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeBytes: () -> Long? = { null },
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val lowSpaceBytes: Long = DEFAULT_LOW_SPACE_BYTES,
    private val rateWindowMs: Long = RATE_WINDOW_MS,
    private val queueCapacity: Int = QUEUE_CAPACITY,
) {

    data class LogSlice(val name: String, val text: String)

    data class Status(
        val recording: Boolean,
        val restricted: Boolean,
        val totalBytes: Long,
        val fileCount: Int,
        val oldestMs: Long?,
        val newestMs: Long?,
        val droppedLines: Long,
        val lastError: String?,
        val lastProblem: String?,
    ) {
        val statusText: String
            get() = when {
                !recording -> "日志未启用"
                restricted -> "存储空间不足，已降低日志级别"
                else -> "正在记录"
            }
    }

    private sealed interface Op {
        class Line(val entry: Entry) : Op
        data object Prune : Op
        data object Clear : Op
        data object Stop : Op
    }

    private class Entry(
        val ms: Long,
        val level: LogLevel,
        val component: String,
        val event: String,
        val transitionId: Long?,
        val fields: Map<String, Any?>,
    )

    private val queue = LinkedBlockingQueue<Op>(queueCapacity)
    private val dropped = AtomicLong(0)
    private val rateLimited = AtomicLong(0)
    private val lastEmit = ConcurrentHashMap<String, Long>()

    @Volatile private var running = false
    @Volatile private var restricted = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastProblem: String? = null
    @Volatile private var lastSpaceCheck = 0L
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var currentFile: File? = null
    @Volatile private var lastPruneMs = 0L
    @Volatile private var lastWriteMs = 0L

    private var thread: Thread? = null

    /** Idempotent: creates the directory, prunes once and starts the writer thread. */
    @Synchronized
    fun start() {
        if (running) return
        val created = runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
        if (!created) {
            lastError = "日志目录不可用"
            return
        }
        running = true
        thread = Thread({ drainLoop() }, "walkman-runlog").apply {
            isDaemon = true
            start()
        }
        runCatching { refreshRestricted() }.onFailure { lastError = it.message ?: it::class.simpleName }
        runCatching { prune() }.onFailure { lastError = it.message ?: it::class.simpleName }
    }

    /** Record one event. Safe to call from any thread; never throws, never blocks. */
    fun event(
        level: LogLevel,
        component: String,
        event: String,
        transitionId: Long? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (!running) return
        runCatching {
            if (refreshRestricted() && level.rank < LogLevel.WARN.rank) return
            if (!admit(event, fields)) return
            if (!queue.offer(Op.Line(Entry(clock(), level, component, event, transitionId, fields)))) {
                dropped.incrementAndGet()
            }
        }.onFailure { lastError = it.message ?: it::class.simpleName }
    }

    /** True when this event should be written (false = throttled). One emission per window. */
    private fun admit(event: String, fields: Map<String, Any?>): Boolean {
        if (event !in RATE_LIMITED_EVENTS) return true
        val key = buildString {
            append(event)
            append('|')
            append(fields["state"] ?: fields["mode"] ?: fields["value"] ?: "")
        }
        val now = clock()
        val last = lastEmit[key]
        if (last != null && now - last < rateWindowMs) {
            rateLimited.incrementAndGet()
            return false
        }
        lastEmit[key] = now
        return true
    }

    /** Delete every stored log and start over (spec §29). */
    fun clear() {
        if (!running) return
        runCatching { queue.offer(Op.Clear) }.onFailure { dropped.incrementAndGet() }
    }

    /** Wait until everything queued so far has been written. Test/diagnostic helper. */
    fun awaitIdle(timeoutMs: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) {
                Thread.sleep(15)
                if (queue.isEmpty()) return true
            }
            Thread.sleep(10)
        }
        return false
    }

    fun status(): Status {
        val files = logs()
        val oldest = files.firstOrNull()?.let { nameToMillis(it.name) }
        val newest = lastWriteMs.takeIf { it > 0 }
            ?: files.lastOrNull()?.let { nameToMillis(it.name) }
        return Status(
            recording = running,
            restricted = restricted,
            totalBytes = files.sumOf { it.length() },
            fileCount = files.size,
            oldestMs = oldest,
            newestMs = newest,
            droppedLines = dropped.get() + rateLimited.get(),
            lastError = lastError,
            lastProblem = lastProblem,
        )
    }

    /**
     * Read stored logs oldest-first. With [sinceMs] each file is line-filtered (spec §18: "最近
     * 30 分钟" filters by line timestamp and never deletes anything).
     */
    fun readAll(sinceMs: Long? = null): List<LogSlice> {
        val out = ArrayList<LogSlice>()
        for (file in logs()) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val filtered = if (sinceMs == null) text else filterSince(text, sinceMs)
            if (filtered.isNotBlank()) out.add(LogSlice(file.name, filtered))
        }
        return out
    }

    fun close() {
        if (!running) return
        running = false
        runCatching { queue.offer(Op.Stop) }
        runCatching { thread?.join(1_500) }
        thread = null
    }

    // ---- writer thread ------------------------------------------------------------------

    private fun drainLoop() {
        while (true) {
            val op = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.simpleName
                break
            }
            val stop = op is Op.Stop
            runCatching { handle(op) }.onFailure { lastError = it.message ?: it::class.simpleName }
            if (stop) break
        }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun handle(op: Op) {
        when (op) {
            is Op.Line -> writeLine(op.entry)
            Op.Prune -> prune()
            Op.Clear -> wipe()
            Op.Stop -> Unit
        }
    }

    private fun writeLine(entry: Entry) {
        if (refreshRestricted() && entry.level.rank < LogLevel.WARN.rank) return
        maybePrune(entry.ms)
        var out = writer
        val target = currentFile
        // Roll over at midnight too: one physical file per calendar day (spec §7), so retention
        // by day and the "newest/oldest" reporting stay meaningful.
        val dayChanged = target != null && segmentDay(target.name) != LogFormat.day(entry.ms)
        if (out == null || target == null || dayChanged || target.length() >= maxFileBytes) {
            runCatching { out?.close() }
            val file = rotate(entry.ms)
            currentFile = file
            out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            writer = out
        }
        val line = LogFormat.line(
            entry.ms, entry.level, entry.component, entry.event, entry.transitionId, entry.fields,
        )
        out.write(line)
        out.write("\n")
        out.flush()
        lastWriteMs = entry.ms
        if (entry.level.rank >= LogLevel.WARN.rank) {
            lastProblem = "${LogFormat.timestamp(entry.ms).substring(11, 19)} ${entry.component} ${entry.event}"
        }
    }

    /**
     * Never throws: a failing free-space probe or clock must not drop a log line or stop the app
     * (spec §8). On error the previous restriction flag is kept.
     */
    private fun refreshRestricted(): Boolean = runCatching {
        val free = freeBytes() ?: return@runCatching restricted
        val now = clock()
        if (now - lastSpaceCheck >= SPACE_CHECK_INTERVAL_MS) {
            lastSpaceCheck = now
            restricted = free < lowSpaceBytes
        }
        restricted
    }.getOrDefault(restricted)

    private fun maybePrune(nowMs: Long) {
        if (nowMs - lastPruneMs < PRUNE_INTERVAL_MS) return
        prune()
    }

    /** Retention by day, then the hard total cap (oldest first). */
    private fun prune() {
        lastPruneMs = clock()
        val files = logs()
        if (files.isEmpty()) return
        val keepFrom = dayStart(lastPruneMs) - (retentionDays - 1).coerceAtLeast(0) * DAY_MS
        for (file in files) {
            val day = nameToMillis(file.name) ?: continue
            if (day < keepFrom) runCatching { file.delete() }
        }
        var remaining = logs().toMutableList()
        while (remaining.size > 1 && remaining.sumOf { it.length() } > maxTotalBytes) {
            val oldest = remaining.firstOrNull() ?: break
            if (oldest == currentFile) break
            // A delete that fails (locked/permission) must not spin the writer thread forever.
            val deleted = runCatching { oldest.delete() }.getOrDefault(false)
            if (!deleted) break
            remaining = logs().toMutableList()
        }
    }

    private fun wipe() {
        runCatching { writer?.close() }
        writer = null
        currentFile = null
        for (file in logs()) runCatching { file.delete() }
        lastProblem = null
        lastPruneMs = 0L
        lastWriteMs = 0L
    }

    private fun rotate(nowMs: Long): File {
        val day = LogFormat.day(nowMs)
        var index = 0
        while (true) {
            val candidate = if (index == 0) File(dir, "$day.log") else File(dir, "$day-$index.log")
            if (!candidate.exists() || candidate.length() < maxFileBytes) return candidate
            index += 1
            if (index > MAX_SEGMENTS) return File(dir, "$day-$index.log")
        }
    }

    private fun logs(): List<File> =
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(LOG_SUFFIX) }
                // Same day: 2026-10-03.log, then 2026-10-03-1.log, ... (plain string order would
                // put "-1" before the base name, corrupting retention and "newest" reporting).
                ?.sortedWith(compareBy({ segmentDay(it.name) ?: "" }, { segmentIndex(it.name) }))
                ?: emptyList()
        }.getOrDefault(emptyList())

    private fun segmentDay(name: String): String? =
        LOG_NAME.matchEntire(name)?.groupValues?.get(1)

    private fun segmentIndex(name: String): Int =
        LOG_NAME.matchEntire(name)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

    private fun nameToMillis(name: String): Long? =
        segmentDay(name)?.let { runCatching { dayFormat.parse(it)?.time }.getOrNull() }

    private fun dayStart(ms: Long): Long = nameToMillis(LogFormat.day(ms) + LOG_SUFFIX) ?: ms

    companion object {
        const val DEFAULT_RETENTION_DAYS = 3
        const val DEFAULT_MAX_TOTAL_BYTES = 10L * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024
        const val DEFAULT_LOW_SPACE_BYTES = 200L * 1024 * 1024
        const val RATE_WINDOW_MS = 5_000L
        const val QUEUE_CAPACITY = 4_096
        private const val LOG_SUFFIX = ".log"
        private const val SPACE_CHECK_INTERVAL_MS = 30_000L
        private const val PRUNE_INTERVAL_MS = 60_000L
        private const val MAX_SEGMENTS = 99
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Events that can repeat with the same value and are throttled to one per window. */
        val RATE_LIMITED_EVENTS = setOf("PLAYER_STATE", "PLAYING", "PAUSED", "POSITION", "SYNC_STATE")

        private val LOG_NAME = Regex("""^(\d{4}-\d{2}-\d{2})(?:-(\d+))?\.log$""")
        private val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

        /** Filter a log file down to lines at/after [sinceMs]; unparsable lines are dropped. */
        fun filterSince(text: String, sinceMs: Long): String {
            val out = StringBuilder(text.length / 2)
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val ts = LogFormat.parseTimestamp(line) ?: continue
                if (ts >= sinceMs) out.append(line).append('\n')
            }
            return out.toString()
        }
    }
}
