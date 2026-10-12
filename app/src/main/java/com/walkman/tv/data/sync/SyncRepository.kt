package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore

/**
 * The single place that turns Walkman's local library into an official LX `ListData` and back.
 *
 * History, downloads, local-folder records, lyrics and settings are never read here - only the
 * three protocol lists. All `Track <-> MusicInfo` conversion goes through [LxMusicMapper].
 */
class SyncRepository(private val libraryStore: LibraryStore) {

    /** Walkman -> protocol. Ids used for merging are `Track.id` / `MusicInfo.id`. */
    fun readListData(): LxListData = LxListMapper.toListData(libraryStore.syncLists())

    /** What `list_sync_get_md5` answers: the official serialization of our lists. */
    fun listDataMd5(data: LxListData = readListData()): String = LxListJson.md5(data)

    /**
     * Protocol -> Walkman: full replacement. Used for the *first* sync of an account (where the
     * user's 同步方式 decides the outcome); a daily sync goes through [LxListMerge] instead, so the
     * server's snapshot cannot silently drop local edits made since the last agreement.
     */
    suspend fun applyListData(data: LxListData) {
        libraryStore.overwriteSyncLists(toSyncLists(data))
    }

    /** Protocol -> Walkman without writing; also used by the `list_data_overwrite` action. */
    fun toSyncLists(data: LxListData): LibraryStore.SyncLists = LxListMapper.toSyncLists(data)

    companion object {
        /** Ids the protocol reserves for defaultList/loveList (and Walkman's local history). */
        val RESERVED_LIST_IDS = LxListMapper.RESERVED_LIST_IDS
    }
}
