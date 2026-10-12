package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore

/**
 * Turns "what this device changed since the last agreement" into the official
 * `LX.Sync.List.ActionList` entries that the server applies to its own lists and then broadcasts to
 * every other connected device.
 *
 * This is the half of the protocol Walkman TV was missing: the official clients register a listener
 * on their local list events after `list_sync_finished` and push each change as an action
 * (`lx-music-desktop/src/main/modules/sync/client/modules/list/localEvent.ts`), which is what makes
 * 收藏 / 加歌 / 删歌 / 新建歌单 / 删除歌单 show up on the phone and the PC immediately instead of
 * waiting for the next whole-snapshot merge.
 *
 * Deliberately **not** produced here:
 *  - order-only changes. The server keeps its own order for songs both sides have, and an online
 *    songlist refresh mirrors the platform's running order; pushing that would overwrite the order
 *    the user curated on another device and would re-trigger itself on every automatic check
 *    (spec §2/§七). Membership changes are always pushed, the order is not.
 *  - `list_data_overwrite`: a full-snapshot push would hand the server this device's view of every
 *    list at once, which is exactly the "one side overwrites everything" behaviour to avoid.
 */
object LxListDiffer {

    fun diff(baseline: SyncBaseline, current: LibraryStore.SyncLists): List<LxListAction> {
        val actions = ArrayList<LxListAction>()

        // 1) Playlists this device created (first: their songs need the list to exist) and renamed.
        current.userLists.forEachIndexed { index, list ->
            // `temp` / the built-in ids are never part of the protocol (see [syncedLists]).
            if (list.id in LxListMapper.RESERVED_LIST_IDS) return@forEachIndexed
            val base = baseline.baselineList(list.id)
            when {
                base == null -> actions += LxListAction.CreateLists(index, listOf(info(list)))
                metadataChanged(base, list) -> actions += LxListAction.UpdateLists(listOf(info(list)))
            }
        }

        // 2) Playlists this device deleted.
        val removedLists = baseline.listIds()
            .filter { id -> id !in LxListMapper.RESERVED_LIST_IDS }
            .filter { id -> current.userLists.none { it.id == id } }
        if (removedLists.isNotEmpty()) actions += LxListAction.RemoveLists(removedLists)

        // 3) Songs added to / removed from each list. `temp` is never synced (official rule).
        for ((listId, tracks) in LxListMapper.syncedLists(current)) {
            val currentIds = tracks.map { it.id }
            val baselineIds = baseline.trackIds(listId).orEmpty()
            val known = baselineIds.toHashSet()

            val removedIds = baselineIds.filter { it !in currentIds }
            if (removedIds.isNotEmpty()) actions += LxListAction.RemoveMusic(listId, removedIds)

            val addedIds = currentIds.filter { it !in known }
            if (addedIds.isNotEmpty()) {
                val added = tracks.filter { it.id in addedIds.toHashSet() }.mapNotNull(LxMusicMapper::toMusicInfo)
                if (added.isNotEmpty()) {
                    actions += LxListAction.AddMusic(
                        listId = listId,
                        musicInfos = added,
                        location = insertionLocation(baselineIds, currentIds, addedIds),
                    )
                }
            }
        }
        return actions
    }

    /**
     * Numbers for the `TV_SYNC_DIFF` log line: how many songs this device added to / removed from
     * the agreement, and which lists only changed their order. A platform reorder is deliberately
     * never pushed, so the log has to say "nothing to send, order only" instead of staying silent
     * (spec §4.3) - that is the line that tells an order-only refresh from a lost one.
     */
    fun summarize(baseline: SyncBaseline, current: LibraryStore.SyncLists): Summary {
        var added = 0
        var removed = 0
        val reordered = ArrayList<String>()
        for ((listId, tracks) in LxListMapper.syncedLists(current)) {
            val currentIds = tracks.map { it.id }
            val baselineIds = baseline.trackIds(listId).orEmpty()
            val known = baselineIds.toHashSet()
            val present = currentIds.toHashSet()
            added += currentIds.count { it !in known }
            removed += baselineIds.count { it !in present }
            if (currentIds != baselineIds && present == known) reordered += listId
        }
        return Summary(added, removed, reordered)
    }

    /** Result of [summarize]: song counts plus the ids of the lists that only changed order. */
    data class Summary(val added: Int, val removed: Int, val reordered: List<String>)

    /**
     * Where the server should insert the added songs so its list ends up shaped like this device's:
     * 收藏 inserts a new favorite at the top (`toggleFavorite`), 加歌 appends at the bottom.
     */
    private fun insertionLocation(baselineIds: List<String>, currentIds: List<String>, addedIds: List<String>): AddMusicLocation {
        if (baselineIds.isEmpty()) return AddMusicLocation.BOTTOM
        val firstKnown = currentIds.indexOfFirst { it in baselineIds.toHashSet() }
        if (firstKnown < 0) return AddMusicLocation.BOTTOM
        val allAddedInFront = addedIds.all { currentIds.indexOf(it) in 0 until firstKnown }
        return if (allAddedInFront) AddMusicLocation.TOP else AddMusicLocation.BOTTOM
    }

    private fun metadataChanged(base: BaselineList, list: Playlist): Boolean =
        base.name != list.name || base.source != list.source || base.sourceListId != list.sourceListId

    private fun info(list: Playlist) = LxUserListInfo(
        id = list.id,
        name = list.name,
        source = list.source,
        sourceListId = list.sourceListId,
        locationUpdateTime = list.updatedAt.takeIf { it > 0L },
    )
}
