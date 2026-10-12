package com.walkman.tv.data.songlist

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Persistence for [ImportedSonglist] records, keyed by local playlist id.
 *
 * Lives beside `library.json` (not inside it) so the LX sync module never sees the per-list
 * auto-update flag or the refresh bookkeeping, and can never wipe them. Unknown fields are ignored
 * on read (see [JsonStore]), so an older device's file still loads.
 */
class ImportedSonglistStore internal constructor(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val store = JsonStore(
        File(filesDir, "importedSonglists.json"),
        MapSerializer(String.serializer(), ImportedSonglist.serializer()),
        emptyMap(),
    )

    private val _records = MutableStateFlow<Map<String, ImportedSonglist>>(emptyMap())
    val records: StateFlow<Map<String, ImportedSonglist>> = _records.asStateFlow()

    suspend fun loadAll() {
        _records.value = withContext(Dispatchers.IO) { store.load() }
    }

    fun get(listId: String): ImportedSonglist? = _records.value[listId]

    fun all(): List<ImportedSonglist> = _records.value.values.toList()

    /** Insert or replace the record for its list id. */
    suspend fun upsert(record: ImportedSonglist) = mutate { it + (record.listId to record) }

    /** Create the record from [template] when this list has none yet, else leave it untouched. */
    suspend fun ensure(template: ImportedSonglist) {
        if (_records.value.containsKey(template.listId)) return
        upsert(template)
    }

    suspend fun setAutoUpdate(listId: String, on: Boolean) =
        update(listId) { it.copy(autoUpdate = on) }

    /** A successful mirror write: the platform was read *and* the local list now mirrors it. */
    suspend fun markApplied(
        listId: String,
        onlineTrackIds: Set<String>,
        trackCount: Int,
        declaredTotal: Int?,
        intervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ) = update(listId) {
        it.copy(
            lastFetchedAt = now,
            lastUpdatedAt = now,
            lastAttemptAt = now,
            lastStatus = UpdateStatus.OK,
            lastTrackCount = trackCount,
            lastDeclaredTotal = declaredTotal,
            onlineTrackIds = onlineTrackIds,
            consecutiveFailures = 0,
            lastIntervalHours = intervalHours,
            lastError = null,
        )
    }

    /**
     * The platform was read and its songs already match the local mirror: the check succeeded but
     * nothing had to be written.
     *
     * Deliberately does **not** touch `lastUpdatedAt` (the content did not change — spec §5 only
     * advances 最近成功更新 on a real change) and never calls `LibraryStore.replaceListTracks`, so no
     * `changes` event is raised and LX Sync is not asked to push an unchanged playlist.
     *
     * `onlineTrackIds` *is* refreshed: when the previous snapshot was unknown (a list adopted
     * through LX Sync, or a hand-built one) this is what establishes it, which is what later lets
     * the anomaly guard tell hand-added songs apart from platform songs.
     */
    suspend fun markCheckedUnchanged(
        listId: String,
        onlineTrackIds: Set<String>,
        trackCount: Int,
        declaredTotal: Int?,
        intervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ) = update(listId) {
        it.copy(
            lastFetchedAt = now,
            lastAttemptAt = now,
            lastStatus = UpdateStatus.OK,
            lastTrackCount = trackCount,
            lastDeclaredTotal = declaredTotal,
            onlineTrackIds = onlineTrackIds,
            consecutiveFailures = 0,
            lastIntervalHours = intervalHours,
            lastError = null,
        )
    }

    /** The platform was read, but a safety rule refused the write. Local tracks are unchanged. */
    suspend fun markSkipped(
        listId: String,
        reason: String,
        trackCount: Int,
        declaredTotal: Int?,
        intervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ) = update(listId) {
        it.copy(
            lastFetchedAt = now,
            lastAttemptAt = now,
            lastStatus = UpdateStatus.SKIPPED,
            lastTrackCount = trackCount,
            lastDeclaredTotal = declaredTotal,
            lastIntervalHours = intervalHours,
            lastError = reason,
        )
    }

    /** A failed attempt: nothing was written and the retry backoff grows. */
    suspend fun markFailed(
        listId: String,
        message: String,
        intervalHours: Int,
        now: Long = System.currentTimeMillis(),
    ) = update(listId) {
        it.copy(
            lastAttemptAt = now,
            lastStatus = UpdateStatus.FAILED,
            consecutiveFailures = it.consecutiveFailures + 1,
            lastIntervalHours = intervalHours,
            lastError = message,
        )
    }

    suspend fun remove(listId: String) = mutate { it - listId }

    /** Drop records whose list no longer exists (deleted by hand or by a remote sync action). */
    suspend fun prune(existingListIds: Set<String>) =
        mutate { records -> records.filterKeys { it in existingListIds } }

    private suspend fun update(listId: String, transform: (ImportedSonglist) -> ImportedSonglist) =
        mutate { records -> records[listId]?.let { records + (listId to transform(it)) } ?: records }

    private suspend fun mutate(transform: (Map<String, ImportedSonglist>) -> Map<String, ImportedSonglist>) {
        val next = transform(_records.value)
        if (next == _records.value) return
        _records.value = next
        withContext(Dispatchers.IO) { store.save(next) }
    }
}
