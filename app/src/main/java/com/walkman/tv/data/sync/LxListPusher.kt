package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes this device's own list edits to the server, using the official `onListSyncAction` call that
 * the LX clients use for exactly this purpose (`lx-music-desktop`/`lx-music-mobile`
 * `client/modules/list/localEvent.ts`): the server applies the action to its stored lists, creates a
 * new snapshot and broadcasts the same action to every other device.
 *
 * Without it a local edit only travels at the *next* whole-snapshot merge, and some of them never
 * travel at all: the server keeps its own order for songs both sides have, so a device-side reorder
 * cannot get through, and a deletion is only recognised once the server's per-device snapshot
 * diffing has something to compare against.
 *
 * Safety rules:
 *  - **Only while a session is up and the module reported ready** ([isLive]). Offline edits stay in
 *    the library and are carried by the next sync's merge - nothing is queued, nothing is lost.
 *  - **Never without a baseline.** A first sync has nothing agreed yet; pushing "everything the
 *    baseline lacks" would re-create the whole library on the server. That case is left to the
 *    normal sync, which the user's 同步方式 governs.
 *  - **All or nothing.** When one action of a batch fails the agreement is *not* updated, so the
 *    next push retries the whole batch (idempotent on the server side: adding a song twice, removing
 *    an absent id or re-creating a known list id are all no-ops).
 */
class LxListPusher(
    private val libraryStore: LibraryStore,
    private val baselineStore: SyncBaselineStore,
    private val isLive: () -> Boolean,
    private val send: suspend (LxListAction) -> Unit,
) {

    sealed interface Outcome {
        /** [actions] official actions were delivered; the baseline now matches this device. */
        data class Pushed(val actions: Int) : Outcome

        /** Nothing to say: this device has not changed anything since the last agreement. */
        data object NoChange : Outcome

        /** No live session (or none that finished a sync): the next sync carries the change. */
        data object Offline : Outcome

        /** Never synced, so there is no agreed state to diff against. */
        data object NoBaseline : Outcome

        /** The server refused something; nothing was recorded as agreed. */
        data class Failed(val detail: String) : Outcome
    }

    private val mutex = Mutex()

    /** Delivers everything this device has changed since the last agreement. */
    suspend fun pushPending(reason: String): Outcome = mutex.withLock {
        if (!isLive()) return skip(reason, "offline")
        val baseline = baselineStore.current() ?: return skip(reason, "no_baseline")
        val current = libraryStore.syncLists()
        val actions = LxListDiffer.diff(baseline, current)
        val summary = LxListDiffer.summarize(baseline, current)
        // The line that answers "the TV holds song A, why is nothing going out?": how many songs
        // the agreement lacks (they have to be pushed), how many it still holds that the device no
        // longer has (they have to be removed), which lists only changed their order (never
        // pushed, spec §4.3) and how many official actions were really built for the change.
        SyncLog.emit(
            SyncLog.EVENT_DIFF,
            listOf(
                "reason" to reason,
                "added" to summary.added,
                "removed" to summary.removed,
                "reorderOnly" to summary.reordered.size,
                "reorderLists" to summary.reordered.joinToString(","),
                "actions" to actions.size,
                "targets" to targets(actions).joinToString(","),
            ) + SyncLog.sizes(current),
        )
        if (actions.isEmpty()) return skip(reason, "unchanged")

        SyncLog.emit(
            SyncLog.EVENT_PUSH_START,
            "reason" to reason,
            "actions" to actions.size,
            "targets" to targets(actions).joinToString(","),
        )
        for (action in actions) {
            try {
                send(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncLog.emit(
                    SyncLog.EVENT_PUSH_FAIL,
                    "reason" to reason,
                    "action" to action::class.simpleName,
                    "targets" to targets(listOf(action)).joinToString(","),
                    "sent" to 0,
                    // The batch stays pending: the agreement is not advanced, so the next push
                    // (or the fallback session) retries the whole change (spec §5.4/§6.4).
                    "kept" to true,
                    "detail" to SyncLog.safe(e.message),
                )
                return Outcome.Failed(e.javaClass.simpleName)
            }
        }
        // Only a fully delivered batch advances the agreement, and it is advanced to the state the
        // *server* now holds (see [SyncBaselines.afterPush]): membership as pushed, order as the
        // server keeps it. A later local edit stays pending and is diffed again next time.
        baselineStore.save(SyncBaselines.afterPush(baseline, current, baseline.serverId, actions))
        SyncLog.emit(
            SyncLog.EVENT_PUSH_OK,
            "reason" to reason,
            "actions" to actions.size,
            "targets" to targets(actions).joinToString(","),
            "sent" to actions.size,
            "accepted" to true,
        )
        return Outcome.Pushed(actions.size)
    }

    /**
     * Adopts the state a finished sync agreed on: [serverView] is what the server held when it sent
     * `list_sync_finished`, so it becomes the diff base for the next push. Called by the session -
     * never by a UI path.
     */
    suspend fun adoptAgreement(serverView: SyncBaseline) {
        baselineStore.save(serverView)
    }

    private fun skip(reason: String, why: String): Outcome {
        SyncLog.emit(SyncLog.EVENT_PUSH_SKIP, "reason" to reason, "why" to why)
        return when (why) {
            "offline" -> Outcome.Offline
            "no_baseline" -> Outcome.NoBaseline
            else -> Outcome.NoChange
        }
    }

    /** The logical lists an action addresses, for the log - never a song list, never a payload. */
    private fun targets(actions: List<LxListAction>): List<String> = actions.flatMap { action ->
        when (action) {
            is LxListAction.AddMusic -> listOf(action.listId)
            is LxListAction.MoveMusic -> listOf(action.fromId, action.toId)
            is LxListAction.RemoveMusic -> listOf(action.listId)
            is LxListAction.UpdateMusic -> action.entries.map { it.listId }
            is LxListAction.UpdateMusicPosition -> listOf(action.listId)
            is LxListAction.OverwriteMusic -> listOf(action.listId)
            is LxListAction.ClearMusic -> action.listIds
            is LxListAction.CreateLists -> action.listInfos.map { it.id }
            is LxListAction.RemoveLists -> action.ids
            is LxListAction.UpdateLists -> action.lists.map { it.id }
            is LxListAction.UpdateListPosition -> action.ids
            is LxListAction.DataOverwrite -> emptyList()
        }
    }.distinct()
}
