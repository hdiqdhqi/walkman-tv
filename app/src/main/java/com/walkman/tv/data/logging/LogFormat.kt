package com.walkman.tv.data.logging

/** Severity of a running-log line. `rank` is used by the low-disk fallback (WARN+ only). */
enum class LogLevel(val label: String, val rank: Int) {
    DEBUG("DEBUG", 0),
    INFO("INFO", 1),
    WARN("WARN", 2),
    ERROR("ERROR", 3),
}

/**
 * Single line format for the running log (spec §19):
 *
 * `2026-10-03T12:30:01.412 INFO Playback RESOLVE_START transition=t31 track=xxx source=kg`
 *
 * Plain text, one event per line, fields in insertion order. Everything is passed through
 * [LogSanitizer] and truncated, so a line can never leak a credential or grow unbounded.
 */
object LogFormat {

    /** e.g. `2026-10-03T12:30:01.412` - 23 chars, parseable by [parseTimestamp]. */
    const val TIMESTAMP_LENGTH = 23
    private const val MAX_VALUE = 200

    private val formatter = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
    }
    private val dayFormatter = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    }
    private val stampFormatter = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    }

    fun timestamp(ms: Long): String = formatter.get()!!.format(java.util.Date(ms))

    fun day(ms: Long): String = dayFormatter.get()!!.format(java.util.Date(ms))

    fun fileStamp(ms: Long): String = stampFormatter.get()!!.format(java.util.Date(ms))

    /** True when the line is the untouched timestamp of a new day - used to bucket retention. */
    fun line(
        ms: Long,
        level: LogLevel,
        component: String,
        event: String,
        transitionId: Long? = null,
        fields: Map<String, Any?> = emptyMap(),
    ): String {
        val sb = StringBuilder(96)
        sb.append(timestamp(ms)).append(' ')
            .append(level.label).append(' ')
            .append(sanitizeToken(component)).append(' ')
            .append(sanitizeToken(event))
        if (transitionId != null && transitionId > 0L) sb.append(" transition=t").append(transitionId)
        for ((key, value) in fields) {
            if (value == null) continue
            if (key == "transition") continue
            sb.append(' ').append(sanitizeToken(key)).append('=').append(valueOf(value))
        }
        return sb.toString()
    }

    /** Parse the leading timestamp of a line; null when the line is not a well-formed entry. */
    fun parseTimestamp(line: String): Long? {
        if (line.length < TIMESTAMP_LENGTH) return null
        return runCatching { formatter.get()!!.parse(line.substring(0, TIMESTAMP_LENGTH))?.time }.getOrNull()
    }

    private fun valueOf(value: Any?): String {
        val raw = LogSanitizer.sanitizeValue(value)
        val flat = raw.replace('\n', ' ').replace('\r', ' ')
        return if (flat.length > MAX_VALUE) flat.substring(0, MAX_VALUE) else flat
    }

    /** Component / event / key names are code-controlled, but keep the line format injective. */
    private fun sanitizeToken(raw: String): String =
        raw.replace(' ', '_').replace('\n', '_').replace('\r', '_')
}
