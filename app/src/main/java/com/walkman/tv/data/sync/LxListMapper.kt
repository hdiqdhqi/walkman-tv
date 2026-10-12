package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.LibraryStore

/**
 * Pure `library lists <-> LX.Sync.List.ListData` conversion, shared by [SyncRepository] (upload /
 * apply) and [LxListMerge] (three-way merge). No store, no I/O, so the merge can be unit-tested
 * against fixed snapshots exactly like the official payloads.
 */
object LxListMapper {

    /** Ids the protocol reserves for defaultList/loveList (and Walkman's local history). */
    val RESERVED_LIST_IDS = setOf(Playlist.DEFAULT, Playlist.LOVE, Playlist.HISTORY, "temp")

    /**
     * The protocol lists of [lists] in the order the server stores them - `defaultList`, `loveList`
     * then the user lists - with Walkman's `temp` list filtered out (it is never part of the sync,
     * official rule). Shared by [LxListDiffer] (what still has to be pushed) and the session log.
     */
    fun syncedLists(lists: LibraryStore.SyncLists): List<Pair<String, List<Track>>> {
        val out = ArrayList<Pair<String, List<Track>>>(2 + lists.userLists.size)
        out += Playlist.DEFAULT to lists.defaultList
        out += Playlist.LOVE to lists.love
        for (list in lists.userLists) {
            if (list.id == "temp") continue
            out += list.id to list.tracks
        }
        return out
    }

    /** Walkman -> protocol. Ids used for merging are `Track.id` / `MusicInfo.id`. */
    fun toListData(lists: LibraryStore.SyncLists): LxListData = LxListData(
        defaultList = lists.defaultList.mapNotNull(LxMusicMapper::toMusicInfo),
        loveList = lists.love.mapNotNull(LxMusicMapper::toMusicInfo),
        userList = lists.userLists.map { playlist ->
            LxUserList(
                id = playlist.id,
                name = playlist.name,
                source = playlist.source,
                sourceListId = playlist.sourceListId,
                // 0 is this module's in-memory stand-in for the protocol's null (the server itself
                // compares with `?? 0`), so a null timestamp round-trips as null and does not make
                // the MD5 drift on every sync.
                locationUpdateTime = playlist.updatedAt.takeIf { it > 0L },
                list = playlist.tracks.mapNotNull(LxMusicMapper::toMusicInfo),
            )
        },
    )

    /** Protocol -> Walkman without writing anything. */
    fun toSyncLists(data: LxListData): LibraryStore.SyncLists {
        val seen = HashSet<String>()
        val userLists = ArrayList<Playlist>(data.userList.size)
        for (info in data.userList) {
            // Built-in ids are carried by defaultList/loveList; a userList entry claiming one of
            // them (or a duplicate id) would corrupt a built-in list, so it is dropped.
            if (info.id.isEmpty() || info.id in RESERVED_LIST_IDS || !seen.add(info.id)) continue
            userLists += Playlist(
                id = info.id,
                name = info.name,
                tracks = info.list.mapNotNull(LxMusicMapper::toTrack),
                updatedAt = info.locationUpdateTime ?: 0L,
                source = info.source,
                sourceListId = info.sourceListId,
            )
        }
        return LibraryStore.SyncLists(
            defaultList = data.defaultList.mapNotNull(LxMusicMapper::toTrack),
            love = data.loveList.mapNotNull(LxMusicMapper::toTrack),
            userLists = userLists,
        )
    }
}
