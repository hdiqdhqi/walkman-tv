package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore

/**
 * The single place that turns Walkman's local library into an official LX `ListData` and back.
 *
 * History, downloads, local-folder records, lyrics and settings are never read here - only the
 * three protocol lists. All `Track <-> MusicInfo` conversion goes through [LxMusicMapper].
 */
class SyncRepository(private val libraryStore: LibraryStore) {

    /** Walkman -> protocol. Ids used for merging are `Track.id` / `MusicInfo.id`. */
    fun readListData(): LxListData {
        val lists = libraryStore.syncLists()
        return LxListData(
            defaultList = lists.defaultList.mapNotNull(LxMusicMapper::toMusicInfo),
            loveList = lists.love.mapNotNull(LxMusicMapper::toMusicInfo),
            userList = lists.userLists.map { playlist ->
                LxUserList(
                    id = playlist.id,
                    name = playlist.name,
                    source = playlist.source,
                    sourceListId = playlist.sourceListId,
                    // 0 is this module's in-memory stand-in for the protocol's null (the server
                    // itself compares with `?? 0`), so a null timestamp round-trips as null and
                    // does not make the MD5 drift on every sync.
                    locationUpdateTime = playlist.updatedAt.takeIf { it > 0L },
                    list = playlist.tracks.mapNotNull(LxMusicMapper::toMusicInfo),
                )
            },
        )
    }

    /** What `list_sync_get_md5` answers: the official serialization of our lists. */
    fun listDataMd5(data: LxListData = readListData()): String = LxListJson.md5(data)

    /** Protocol -> Walkman: full replacement (used by `list_sync_set_list_data`). */
    suspend fun applyListData(data: LxListData) {
        libraryStore.overwriteSyncLists(toSyncLists(data))
    }

    /** Protocol -> Walkman without writing; also used by the `list_data_overwrite` action. */
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

    companion object {
        /** Ids the protocol reserves for defaultList/loveList (and Walkman's local history). */
        val RESERVED_LIST_IDS = setOf(Playlist.DEFAULT, Playlist.LOVE, Playlist.HISTORY, "temp")
    }
}