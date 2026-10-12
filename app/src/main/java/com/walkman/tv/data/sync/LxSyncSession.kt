package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore

/**
 * One list-sync session's data policy - the part of the protocol exchange that decides *what* gets
 * written, kept apart from [LxListSyncService], which only parses and answers messages.
 *
 * Where the payload lands depends on whether this account has ever agreed on anything:
 *
 *  - **First sync of an account / server** (no [SyncBaseline]): the server's result is applied
 *    verbatim, exactly like before this class existed. The user's 同步方式 (merge / 覆盖) decides
 *    the outcome and nothing here second-guesses it (spec §4.1).
 *  - **Daily sync** (a baseline exists): the payload goes through [LxListMerge] *before* it is
 *    written, so the server's snapshot can never silently drop a song that this device added or
 *    deleted while the sync was running, and a deletion that happened on only one side still wins
 *    (§4.2, §5.3, §6). The single exception is the session the *user* answered the 同步方式 question
 *    in: the server computed that result with the answer, so the answer governs (§4.1). A mode
 *    taken from the stored default does not count - a default must never turn the write into a
 *    wholesale replace that drops changes this device has not uploaded yet (§5.6).
 *
 * At `list_sync_finished` the state the server now holds becomes the new baseline, and [onAgreed]
 * tells the owner to flush whatever this device still owes the server (see [LxListPusher]). A
 * cancelled mode question agrees on nothing, so the baseline is left alone.
 */
class LxSyncSession(
    private val libraryStore: LibraryStore,
    private val repository: SyncRepository,
    private val baselineStore: SyncBaselineStore,
    /** The server/account of the live session; resolved lazily, after authentication. */
    private val serverId: () -> String?,
    private val onAgreed: suspend (serverView: SyncBaseline) -> Unit,
) {
    private var uploaded: LxListData? = null
    private var appliedRemote: LxListData? = null
    private var modeAsked = false
    private var modeAnswer: String? = null

    /**
     * The agreement as it stands *right now*, re-read for every payload. A session installs the
     * very first baseline when it finishes, and a broadcast that arrives later in the same session
     * has to merge against what was just agreed - reading the agreement once at session start
     * (when it was still null) is what let a stale server snapshot drop a local song.
     */
    private fun agreement(): SyncBaseline? = baselineStore.forServer(serverId())

    /** Logged on the first protocol call so a user log shows first vs daily sync and both sizes. */
    suspend fun onMd5(): String {
        val data = repository.readListData()
        uploaded = data
        SyncLog.newChain()
        SyncLog.emit(
            SyncLog.EVENT_SESSION_START,
            listOf(
                "kind" to if (agreement() == null) "first" else "daily",
                "server" to !serverId().isNullOrBlank(),
            ) + SyncLog.sizes(data),
        )
        return repository.listDataMd5(data)
    }

    /** What the server receives as this device's current lists - reported exactly, deletions too. */
    suspend fun onGetListData(): LxListData {
        val data = repository.readListData()
        uploaded = data
        return data
    }

    /**
     * The server asked which 同步方式 to use. [prompted] says whether the *user* was asked for it
     * now - the server has just computed its result with that fresh answer, so the answer governs
     * and the payload is applied as-is. A value that merely came from the stored default answers
     * without a dialog and must not switch the merge off (spec §5.6).
     */
    fun onModeAnswer(mode: String, prompted: Boolean = true) {
        modeAnswer = mode
        if (prompted) modeAsked = true
    }

    /**
     * Applies whatever the server decided to send: `list_sync_set_list_data` (the merge result for
     * this session) or a `list_data_overwrite` broadcast (another device replaced the whole server
     * state). [reason] only names the source of the payload in the log.
     */
    suspend fun applyRemote(data: LxListData, reason: String = REASON_SERVER_RESULT) {
        val local = libraryStore.syncLists()
        val base = agreement()
        SyncLog.emit(
            SyncLog.EVENT_RECEIVE,
            listOf(
                "kind" to reason,
                "agreed" to (base != null),
            ) + SyncLog.sizes(data),
        )
        if (base == null || modeAsked) {
            // Nothing agreed yet (or the user just answered the 同步方式 question for this server):
            // keep the previous, well-defined behaviour - the server's result is applied as-is.
            val dropped = if (base == null) 0 else pendingOnly(base, local, data)
            repository.applyListData(data)
            appliedRemote = data
            SyncLog.emit(
                SyncLog.EVENT_APPLY,
                listOf(
                    "mode" to "verbatim",
                    "why" to if (base == null) "first_sync" else "mode_answered",
                    // Songs this device had that the payload did not carry; 0 means nothing was
                    // at risk. Non-zero is the log's answer to "why did the device lose the song?".
                    "droppedLocalOnly" to dropped,
                ) + SyncLog.sizes(data),
            )
            return
        }
        val merged = LxListMerge.merge(base, local, data)
        // The songs the payload was missing while this device already had them: exactly what a
        // wholesale write would have thrown away, and what the merge keeps (spec §5.6).
        val kept = pendingOnly(base, local, data)
        if (merged.lists != local) {
            libraryStore.overwriteSyncLists(merged.lists)
        }
        appliedRemote = data
        SyncLog.emit(
            SyncLog.EVENT_APPLY,
            listOf(
                "mode" to "three_way",
                "applied" to if (merged.lists == local) "unchanged" else "merged",
                "keptLocalOnly" to kept,
                "added" to merged.summary.addedTracks,
                "removed" to merged.summary.removedTracks,
                "reordered" to merged.summary.reorderedLists,
            ) + SyncLog.sizes(data),
        )
    }

    /** `list_sync_finished`: the exchange is over, so record what both sides now hold. */
    suspend fun onFinished() {
        if (modeAnswer == LxSyncProtocol.SYNC_MODE_CANCEL) {
            // 取消: the server threw before merging, so *nothing* was agreed - keeping the old
            // baseline is what makes the next sync compare against the real last common state.
            SyncLog.emit(SyncLog.EVENT_BASELINE, "saved" to false, "why" to "cancelled")
            return
        }
        val serverView = serverView() ?: return
        onAgreed(serverView)
        SyncLog.emit(
            SyncLog.EVENT_BASELINE,
            "saved" to true,
            "why" to if (appliedRemote == null) "uploaded" else "server_payload",
            "lists" to serverView.userLists.size,
        )
    }

    /**
     * What the server holds now: the payload it sent (that is the merged state it stored and
     * broadcast) or, when it sent none, exactly what this device uploaded.
     */
    private fun serverView(): SyncBaseline? {
        val source = appliedRemote ?: uploaded ?: return null
        val id = serverId() ?: return null
        return SyncBaselines.of(source, id)
    }

    /**
     * How many songs this device holds that the agreement does not know and the payload does not
     * carry - the ones a wholesale write would silently drop (spec §5.6). Counted, never listed.
     */
    private fun pendingOnly(baseline: SyncBaseline, local: LibraryStore.SyncLists, remote: LxListData): Int {
        val carried = LxListMapper.syncedLists(LxListMapper.toSyncLists(remote)).toMap()
        var count = 0
        for ((listId, tracks) in LxListMapper.syncedLists(local)) {
            val agreed = baseline.trackIds(listId)?.toHashSet() ?: emptySet()
            val inPayload = carried[listId].orEmpty().mapTo(HashSet()) { it.id }
            count += tracks.count { it.id !in agreed && it.id !in inPayload }
        }
        return count
    }

    companion object {
        /** A `list_sync_set_list_data` payload: the result the server computed for this session. */
        const val REASON_SERVER_RESULT = "server_result"

        /** An `onListSyncAction` payload: the server broadcasting a state to every other device. */
        const val REASON_BROADCAST = "broadcast"
    }
}
