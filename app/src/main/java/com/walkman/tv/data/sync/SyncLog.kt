package com.walkman.tv.data.sync

import com.walkman.tv.data.logging.LogSanitizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Running-log events for the list-sync data path (spec section 8/9).
 *
 * One place decides the names and the fields, exactly like
 * [com.walkman.tv.data.online.OnlinePlaylistLog] does for the online songlists: only counts,
 * categories, reasons, list ids and durations are ever recorded. Connection codes, tokens, device
 * keys and full URLs never reach a field, and a batch of songs is summarised as a number rather
 * than printed out title by title. The sink is wired to [com.walkman.tv.data.logging.RunLog] by
 * AppContainer, so this stays testable without Android.
 *
 * Every line carries a short correlation id. One change-detector run, its diff, its push
 * attempt and the session that follows it share the id, so the uplink question - "this device holds
 * song A, why does the server not have it?" - can be answered from the log alone:
 *
 *   TV_CHANGE_DETECTED -> TV_SYNC_DIFF -> TV_SYNC_PUSH_START/OK/FAIL/SKIP
 *   TV_SYNC_RECEIVE    -> TV_SYNC_APPLY -> TV_SYNC_BASELINE
 */
object SyncLog {

    /** RunLog component name. */
    const val COMPONENT = "Sync"

    /**
     * A local change reached the sync layer (a user edit or an online-songlist refresh) with the
     * library sizes at that moment. First line of every uplink chain.
     */
    const val EVENT_CHANGE_DETECTED = "TV_CHANGE_DETECTED"

    /** A session started: first sync of this account or a daily one, and how big both sides are. */
    const val EVENT_SESSION_START = "TV_SYNC_SESSION"

    /** A server payload arrived (`list_sync_set_list_data` or a `list_data_overwrite` broadcast). */
    const val EVENT_RECEIVE = "TV_SYNC_RECEIVE"

    /** What the session did with that payload: applied verbatim, or merged against the agreement. */
    const val EVENT_APPLY = "TV_SYNC_APPLY"

    /**
     * What this device still owes the server: songs the agreement lacks, songs it holds that the
     * device no longer has, lists that only changed their order, and whether an official action was
     * really built for the change.
     */
    const val EVENT_DIFF = "TV_SYNC_DIFF"

    /** This device's own edits were pushed to the server as official `onListSyncAction` calls. */
    const val EVENT_PUSH_START = "TV_SYNC_PUSH_START"
    const val EVENT_PUSH_OK = "TV_SYNC_PUSH_OK"
    const val EVENT_PUSH_FAIL = "TV_SYNC_PUSH_FAIL"

    /** A push was skipped, with the reason (offline / nothing agreed yet / nothing to send). */
    const val EVENT_PUSH_SKIP = "TV_SYNC_PUSH_SKIP"

    /** The last-agreed baseline was stored, or deliberately not stored, with the reason. */
    const val EVENT_BASELINE = "TV_SYNC_BASELINE"

    private val chainCounter = AtomicInteger(0)

    @Volatile
    private var currentChain: String = "c0"

    /** Starts a new chain (one local change, or one sync session) and returns its id. */
    fun newChain(): String {
        currentChain = "c" + chainCounter.incrementAndGet()
        return currentChain
    }

    /** Set once by AppContainer; null in unit tests and before bootstrap. */
    @Volatile
    var sink: ((event: String, fields: List<Pair<String, Any?>>) -> Unit)? = null

    fun emit(event: String, fields: List<Pair<String, Any?>>) {
        sink?.invoke(event, fields + ("corr" to currentChain))
    }

    fun emit(event: String, vararg fields: Pair<String, Any?>) = emit(event, fields.toList())

    /** Redacted free-form detail - never a payload, never a credential. */
    fun safe(text: String?): String? = text?.let { LogSanitizer.sanitizeText(it).take(200) }

    /** Sizes of both sides, for the session line: no titles, just counts. */
    fun sizes(lists: com.walkman.tv.data.store.LibraryStore.SyncLists): List<Pair<String, Any?>> = listOf(
        "lists" to lists.userLists.size,
        "tracks" to (lists.love.size + lists.defaultList.size + lists.userLists.sumOf { it.tracks.size }),
    )

    fun sizes(data: LxListData): List<Pair<String, Any?>> = listOf(
        "lists" to data.userList.size,
        "tracks" to (data.loveList.size + data.defaultList.size + data.userList.sumOf { it.list.size }),
    )

    /** Test hook: detach the sink. */
    fun reset() {
        sink = null
    }
}
