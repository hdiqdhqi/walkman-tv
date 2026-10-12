package com.walkman.tv.data.online

import com.walkman.tv.data.logging.LogSanitizer
import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SourceID

/**
 * Running-log events for the online-playlist feature.
 *
 * One place decides the event names and the field set, exactly like
 * [com.walkman.tv.playback.external.ExternalPlayerLog], so nothing else can leak a URL or a
 * credential into the log the user may share: only the platform key, the (sanitized) playlist id
 * and counts / state / durations are ever recorded. The sink is wired to
 * [com.walkman.tv.data.logging.RunLog] by AppContainer, which keeps this testable without Android.
 */
object OnlinePlaylistLog {

    /** RunLog component name. */
    const val COMPONENT = "Songlist"

    const val EVENT_IMPORT_START = "SONGLIST_IMPORT_START"
    const val EVENT_IMPORT_FETCH = "SONGLIST_IMPORT_FETCH"
    const val EVENT_IMPORT_OK = "SONGLIST_IMPORT_OK"
    const val EVENT_IMPORT_FAIL = "SONGLIST_IMPORT_FAIL"
    const val EVENT_UPDATE_START = "SONGLIST_UPDATE_START"
    const val EVENT_UPDATE_OK = "SONGLIST_UPDATE_OK"
    const val EVENT_UPDATE_SKIP = "SONGLIST_UPDATE_SKIP"
    const val EVENT_UPDATE_FAIL = "SONGLIST_UPDATE_FAIL"
    const val EVENT_SCHEDULE = "SONGLIST_SCHEDULE"

    /**
     * Automatic-update bookkeeping (spec §6 日志与可验证性). [EVENT_UPDATE_START] carries a
     * `trigger` field naming *why* the platform is being read (startup / 恢复前台 / 前台定时 /
     * WorkManager / 手动), so the five paths are distinguishable in a user-supplied log.
     *
     * [EVENT_CHECK_SKIP] is a whole-run decision (开关关闭 / 未到间隔 / 已有一次更新在跑);
     * [EVENT_CHECK_NO_CHANGE] is a per-list outcome — the platform was read and matched the local
     * mirror, so nothing was written (and therefore nothing was pushed to LX Sync).
     */
    const val EVENT_CHECK_SKIP = "SONGLIST_CHECK_SKIP"
    const val EVENT_CHECK_NO_CHANGE = "SONGLIST_CHECK_NO_CHANGE"

    /**
     * Read-pipeline stages (spec §9). One fetch emits FETCH_START, then exactly one of
     * FETCH_OK / FETCH_HTTP_ERROR / FETCH_PARSE_ERROR / FETCH_PAGE_ERROR / FETCH_INCOMPLETE, so a
     * user's report can be turned into "which platform, which stage, which category" without the
     * response body.
     */
    const val EVENT_FETCH_START = "SONGLIST_FETCH_START"
    const val EVENT_FETCH_OK = "SONGLIST_FETCH_OK"
    const val EVENT_FETCH_HTTP_ERROR = "SONGLIST_FETCH_HTTP_ERROR"
    const val EVENT_FETCH_PARSE_ERROR = "SONGLIST_FETCH_PARSE_ERROR"
    const val EVENT_FETCH_PAGE_ERROR = "SONGLIST_FETCH_PAGE_ERROR"
    const val EVENT_FETCH_INCOMPLETE = "SONGLIST_FETCH_INCOMPLETE"

    /** Set once by AppContainer; null in unit tests and before bootstrap. */
    @Volatile
    var sink: ((event: String, fields: List<Pair<String, Any?>>) -> Unit)? = null

    fun emit(event: String, fields: List<Pair<String, Any?>>) {
        sink?.invoke(event, fields)
    }

    fun emit(event: String, vararg fields: Pair<String, Any?>) = emit(event, fields.toList())

    /** Which path started a check/refresh, as a log field. See [EVENT_CHECK_SKIP]. */
    fun trigger(key: String): List<Pair<String, Any?>> = listOf("trigger" to key)

    /**
     * The stage-specific event name for a failure, so the log distinguishes "transport said no"
     * from "we could not read what came back" from "the platform stopped mid-list".
     */
    fun failureEvent(kind: OnlinePlaylistException.Kind): String = when (kind) {
        OnlinePlaylistException.Kind.NETWORK,
        OnlinePlaylistException.Kind.HTTP,
        OnlinePlaylistException.Kind.AUTH,
        OnlinePlaylistException.Kind.RATE_LIMIT,
        -> EVENT_FETCH_HTTP_ERROR

        OnlinePlaylistException.Kind.INCOMPLETE -> EVENT_FETCH_INCOMPLETE

        OnlinePlaylistException.Kind.PARSE,
        OnlinePlaylistException.Kind.CONTENT,
        OnlinePlaylistException.Kind.NOT_FOUND,
        OnlinePlaylistException.Kind.EMPTY,
        OnlinePlaylistException.Kind.BAD_ID,
        OnlinePlaylistException.Kind.UNSUPPORTED,
        -> EVENT_FETCH_PARSE_ERROR
    }

    /** Redacted free-form diagnostic detail - never the response body, never a credential. */
    fun safe(text: String?): String? = text?.let { LogSanitizer.sanitizeText(it).take(300) }

    /**
     * The safe identifier pair. A short link is never logged whole - [LogSanitizer.sanitizeUrl]
     * keeps `scheme/host/path` and drops the query string where signed parameters live.
     */
    fun ref(source: SourceID, playlistId: String): List<Pair<String, Any?>> = listOf(
        "source" to source.key,
        "playlistId" to if (playlistId.startsWith("http", ignoreCase = true)) {
            LogSanitizer.sanitizeUrl(playlistId)
        } else {
            playlistId
        },
    )

    /** Test hook: detach the sink. */
    fun reset() {
        sink = null
    }
}
