package com.walkman.tv.data.store

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class LibraryData(
    val love: Playlist = Playlist(Playlist.LOVE, Playlist.LOVE_NAME),
    val history: Playlist = Playlist(Playlist.HISTORY, Playlist.HISTORY_NAME),
    val userLists: List<Playlist> = emptyList(),
    /**
     * The official LX `defaultList` slot ("试听列表"). Walkman TV has no user-visible default list
     * (the former RN one was replaced by the auto-recorded history), but the sync protocol carries
     * a defaultList, so it is stored here instead of being dropped - dropping another device's
     * entries would make the server's 3-way merge read the omission as a deletion and delete them
     * everywhere. It is never mapped onto love/history and is never listed in the library UI.
     */
    val defaultList: Playlist = Playlist(Playlist.DEFAULT, Playlist.DEFAULT_NAME),
)

/**
 * Favorites (我的收藏), auto-recorded play history (取代「试听列表」), user playlists, and the hidden
 * LX `defaultList` slot. Persisted as one JSON file; exposed as StateFlows for the UI.
 *
 * Takes the files dir (not a Context) so the sync tests can drive a real store on the JVM.
 */
class LibraryStore(filesDir: File) {
    private val store = JsonStore(
        File(filesDir, "library.json"),
        LibraryData.serializer(),
        LibraryData(),
    )

    /**
     * Serializes every mutation. The sync applier does read-modify-write cycles on the same
     * StateFlows the player/library UI writes to, so without this a playlist edit made while a
     * remote action is being applied could be lost (or vice versa).
     */
    private val writeLock = Mutex()

    private val _love = MutableStateFlow(Playlist(Playlist.LOVE, Playlist.LOVE_NAME))
    val love: StateFlow<Playlist> = _love.asStateFlow()

    private val _history = MutableStateFlow(Playlist(Playlist.HISTORY, Playlist.HISTORY_NAME))
    val history: StateFlow<Playlist> = _history.asStateFlow()

    private val _userLists = MutableStateFlow<List<Playlist>>(emptyList())
    val userLists: StateFlow<List<Playlist>> = _userLists.asStateFlow()

    /** Official LX `defaultList` slot. Deliberately not surfaced in the library UI. */
    private val _defaultList = MutableStateFlow(Playlist(Playlist.DEFAULT, Playlist.DEFAULT_NAME))
    val defaultList: StateFlow<Playlist> = _defaultList.asStateFlow()

    /**
     * Where a change to a synced list came from. LX Sync pushes *user* edits to the server as
     * soon as they happen (the official clients do this too), but a refresh of an imported online
     * songlist must not drag the platform's running order onto the other devices - it only has to
     * propagate the songs that were actually added or removed. The two paths therefore announce
     * themselves separately.
     */
    enum class ListChangeSource {
        /** The TV's user edited a list: 收藏 / 加歌 / 删歌 / 新建 / 删除 / 改名 / 导入. */
        USER,

        /** An imported online songlist was re-read from its platform (see `data/songlist/`). */
        ONLINE_REFRESH,
    }

    /**
     * Emits once after every change to a synced list (收藏 / 自建歌单 / defaultList), carrying
     * [ListChangeSource] so the sync layer can tell a user edit from an online refresh.
     * Sync-applied writes deliberately do NOT emit, so auto-sync cannot re-trigger itself.
     * History and downloads never emit - neither is part of LX sync.
     */
    private val _changes = MutableSharedFlow<ListChangeSource>(extraBufferCapacity = 4)
    val changes: SharedFlow<ListChangeSource> = _changes.asSharedFlow()

    suspend fun loadAll() {
        val data = withContext(Dispatchers.IO) { store.load() }
        // Force the built-in names to whatever the current model declares (so renames in the
        // model — e.g. "我的收藏" → "我喜欢的" — apply on next launch without a migration step).
        _love.value = data.love.copy(name = Playlist.LOVE_NAME)
        _history.value = data.history.copy(name = Playlist.HISTORY_NAME)
        _userLists.value = data.userLists
        _defaultList.value = data.defaultList.copy(name = Playlist.DEFAULT_NAME)
    }

    fun isFavorite(trackId: String): Boolean = _love.value.tracks.any { it.id == trackId }

    suspend fun toggleFavorite(track: Track) = writeLock.withLock {
        val tracks = _love.value.tracks
        _love.value = _love.value.copy(
            tracks = if (tracks.any { it.id == track.id }) tracks.filter { it.id != track.id }
            else listOf(track) + tracks,
            updatedAt = System.currentTimeMillis(),
        )
        persist()
        notifyChanged()
    }

    suspend fun recordHistory(track: Track) = writeLock.withLock {
        val deduped = listOf(track) + _history.value.tracks.filter { it.id != track.id }
        _history.value = _history.value.copy(tracks = deduped.take(200), updatedAt = System.currentTimeMillis())
        persist()
    }

    suspend fun clearHistory() = writeLock.withLock {
        _history.value = _history.value.copy(tracks = emptyList())
        persist()
    }

    /**
     * [source] / [sourceListId] are set only by the online-songlist importer, so a list created
     * from a platform share link keeps its origin and can be refreshed later (see
     * `data/songlist/SonglistAutoUpdater`). Hand-built lists leave both null.
     */
    suspend fun createList(
        name: String,
        source: String? = null,
        sourceListId: String? = null,
    ): Playlist {
        val list = Playlist(name = name, source = source, sourceListId = sourceListId)
        writeLock.withLock {
            _userLists.value = _userLists.value + list
            persist()
            notifyChanged()
        }
        return list
    }

    suspend fun deleteList(id: String) = writeLock.withLock {
        _userLists.value = _userLists.value.filter { it.id != id }
        persist()
        notifyChanged()
    }

    suspend fun addToList(listId: String, track: Track) = writeLock.withLock {
        _userLists.value = _userLists.value.map {
            if (it.id == listId && it.tracks.none { t -> t.id == track.id }) {
                it.copy(tracks = it.tracks + track, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        persist()
        notifyChanged()
    }

    /** Batch add — one StateFlow update + one disk write. Songlist import uses this instead of
     *  per-track [addToList] so a several-hundred-track import doesn't rewrite library.json once
     *  per song (O(n²) serialization work that can stall low-end TVs). Dedupes against the list
     *  and within [tracks]. */
    suspend fun addAllToList(listId: String, tracks: List<Track>) = writeLock.withLock {
        _userLists.value = _userLists.value.map { pl ->
            if (pl.id == listId) {
                val seen = pl.tracks.mapTo(HashSet()) { it.id }
                val fresh = tracks.filter { seen.add(it.id) }
                if (fresh.isEmpty()) pl
                else pl.copy(tracks = pl.tracks + fresh, updatedAt = System.currentTimeMillis())
            } else {
                pl
            }
        }
        persist()
        notifyChanged()
    }

    suspend fun removeFromList(listId: String, trackId: String) = writeLock.withLock {
        _userLists.value = _userLists.value.map {
            if (it.id == listId) it.copy(tracks = it.tracks.filter { t -> t.id != trackId }) else it
        }
        persist()
        notifyChanged()
    }

    /**
     * Full replacement of a user list's tracks — LX Music's `overwriteListMusics`, used by the
     * imported-songlist refresh (`data/songlist/`). One StateFlow update + one disk write, and it
     * emits [changes] so the refresh also rolls into LX sync. [source] defaults to a user edit; the
     * online refresher passes [ListChangeSource.ONLINE_REFRESH] so the platform's playing order is
     * not pushed to the other devices. Returns false when the list no longer exists (deleted
     * locally or by a remote sync action).
     */
    suspend fun replaceListTracks(
        listId: String,
        tracks: List<Track>,
        source: ListChangeSource = ListChangeSource.USER,
    ): Boolean = writeLock.withLock {
        val seen = HashSet<String>()
        val deduped = tracks.filter { seen.add(it.id) }
        var found = false
        _userLists.value = _userLists.value.map { pl ->
            if (pl.id == listId) {
                found = true
                pl.copy(tracks = deduped, updatedAt = System.currentTimeMillis())
            } else {
                pl
            }
        }
        if (found) {
            persist()
            notifyChanged(source)
        }
        found
    }

    // ---------------------------------------------------------------- LX Music sync surface

    /** The three lists the LX protocol syncs, in official order. History is never part of this. */
    data class SyncLists(
        val defaultList: List<Track>,
        val love: List<Track>,
        val userLists: List<Playlist>,
    )

    fun syncLists(): SyncLists = SyncLists(
        defaultList = _defaultList.value.tracks,
        love = _love.value.tracks,
        userLists = _userLists.value,
    )

    /**
     * Replaces all three synced lists (a `list_data_overwrite` / `list_sync_set_list_data`).
     * Does NOT emit [changes]: this is a remote write and must not start another sync.
     */
    suspend fun overwriteSyncLists(lists: SyncLists) = writeLock.withLock { applySyncLists(lists) }

    /**
     * Read-modify-write of the three synced lists, serialized against UI edits so a single
     * server action can't interleave with a user tapping 收藏 at the same moment.
     */
    suspend fun mutateSyncLists(transform: (SyncLists) -> SyncLists) = writeLock.withLock {
        applySyncLists(transform(syncLists()))
    }

    private suspend fun applySyncLists(lists: SyncLists) {
        val now = System.currentTimeMillis()
        _defaultList.value = _defaultList.value.copy(tracks = lists.defaultList, updatedAt = now)
        _love.value = _love.value.copy(tracks = lists.love, updatedAt = now)
        _userLists.value = lists.userLists
        persist()
    }

    private fun notifyChanged(source: ListChangeSource = ListChangeSource.USER) {
        _changes.tryEmit(source)
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        store.save(LibraryData(_love.value, _history.value, _userLists.value, _defaultList.value))
    }
}
