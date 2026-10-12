package com.walkman.tv.playback.external

import java.net.URI

/**
 * Running-log events for the external-player path (spec 21).
 *
 * Events: [EVENT_START], [EVENT_SUCCESS], [EVENT_NO_HANDLER], [EVENT_START_FAILED],
 * [EVENT_DEFAULT_USED], [EVENT_DEFAULT_INVALID], [EVENT_DEFAULT_CLEARED], [EVENT_SELECTED].
 *
 * Safety: the full URL **never** reaches the log. Only the host is recorded, so a signed
 * `?token=...&sign=...&expires=...` play URL can never leak a credential into a log the user may
 * share (spec 11 / 21). Fields are built here, in one place, and the sink is wired to RunLog by
 * AppContainer — this keeps the logging logic testable without Android.
 */
object ExternalPlayerLog {

    const val EVENT_START = "EXTERNAL_PLAYER_START"
    const val EVENT_SUCCESS = "EXTERNAL_PLAYER_SUCCESS"
    const val EVENT_NO_HANDLER = "EXTERNAL_PLAYER_NO_HANDLER"
    const val EVENT_START_FAILED = "EXTERNAL_PLAYER_START_FAILED"
    const val EVENT_DEFAULT_USED = "EXTERNAL_PLAYER_DEFAULT_USED"
    const val EVENT_DEFAULT_INVALID = "EXTERNAL_PLAYER_DEFAULT_INVALID"
    const val EVENT_DEFAULT_CLEARED = "EXTERNAL_PLAYER_DEFAULT_CLEARED"
    const val EVENT_SELECTED = "EXTERNAL_PLAYER_SELECTED"

    /** Set once by AppContainer; null in unit tests / before bootstrap. */
    @Volatile
    var sink: ((transitionId: Long, event: String, fields: List<Pair<String, Any?>>) -> Unit)? = null

    /**
     * Build the safe field set: transitionId is carried separately, the rest are plain
     * identifiers + MIME/codec + host. Never a URL, query string, token or cookie.
     */
    fun fields(
        target: ExternalPlaybackTarget,
        packageName: String?,
        label: String?,
    ): List<Pair<String, Any?>> = buildList {
        add("trackId" to target.track.id)
        add("source" to target.track.source.key)
        target.quality?.let { add("quality" to it.key) }
        add("package" to (packageName ?: "none"))
        label?.let { add("label" to it) }
        add("mime" to target.mimeType)
        target.codec?.let { add("codec" to it) }
        add("host" to hostOf(target.url))
    }

    /** Emit one external-player event; no-op when the sink is not wired. */
    fun emit(event: String, target: ExternalPlaybackTarget, packageName: String?, label: String?) {
        sink?.invoke(target.transitionId, event, fields(target, packageName, label))
    }

    /** Emit an event that is not tied to a concrete track (e.g. "no handler for audio/mpeg"). */
    fun emitNoHandler(transitionId: Long, mimeType: String) {
        sink?.invoke(
            transitionId,
            EVENT_NO_HANDLER,
            listOf("package" to "none", "mime" to mimeType),
        )
    }

    /** Emit an event with no track attached (e.g. the default was cleared from Settings). */
    fun emitStandalone(event: String, packageName: String? = null, label: String? = null) {
        sink?.invoke(
            0L,
            event,
            buildList {
                add("package" to (packageName ?: "none"))
                label?.let { add("label" to it) }
            },
        )
    }

    /** Emit when the user picked a player from the system chooser. */
    fun emitSelected(transitionId: Long, packageName: String?, label: String?) {
        sink?.invoke(
            transitionId,
            EVENT_SELECTED,
            buildList {
                add("package" to (packageName ?: "none"))
                label?.let { add("label" to it) }
            },
        )
    }

    /**
     * Host of [url], or `local` for a file URI. Never the full URL: query, fragment and userinfo
     * (where signed tokens live) are dropped, and an unparseable URL degrades to `?`.
     */
    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "?"
        return runCatching {
            val uri = URI(url)
            when {
                !uri.host.isNullOrBlank() -> uri.host
                uri.scheme.equals("file", ignoreCase = true) -> "local"
                else -> uri.scheme ?: "?"
            }
        }.getOrDefault("?")
    }

    /** Test hook: detach the sink. */
    fun reset() {
        sink = null
    }
}
