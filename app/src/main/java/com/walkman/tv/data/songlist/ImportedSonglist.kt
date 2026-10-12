package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.SourceID
import kotlinx.serialization.Serializable

/** Outcome of the last automatic/manual refresh of an imported playlist. */
@Serializable
enum class UpdateStatus {
    /** No refresh has been attempted on this device yet. */
    NEVER,

    /** The platform was read and the local list now mirrors it. */
    OK,

    /** The platform was read, but the write was refused by a safety rule (needs manual confirm). */
    SKIPPED,

    /** The refresh failed; the local list is untouched. */
    FAILED,
}

/**
 * Origin record for a playlist imported from an online platform (网易云 / QQ音乐 / 酷狗 / 酷我).
 *
 * Ports the LX Music concepts that make an imported list "live":
 *  - `UserListInfo.source` + `sourceListId` — *what* to re-fetch (the same pair
 *    [com.walkman.tv.data.model.Playlist] carries for sync round-tripping),
 *  - `ListUpdateInfo.isAutoUpdate` — whether this device refreshes it on its own,
 *
 * plus the state this device needs: when it last fetched, what that fetch produced, and which
 * songs came from the platform.
 *
 * Deliberately NOT stored on [com.walkman.tv.data.model.Playlist]: the LX sync protocol has no
 * slot for the per-list flag or the refresh bookkeeping, and `list_sync_set_list_data` replaces
 * every user list wholesale — a field living on Playlist would be silently wiped by any sync with
 * another device. Kept next to the library instead, keyed by the local list id.
 */
@Serializable
data class ImportedSonglist(
    val listId: String,
    val source: SourceID,
    /** Platform-side playlist id (or the share short link, resolved on first fetch). */
    val sourceListId: String,
    /** Exactly what the user pasted, for the detail screen and for diagnostics. */
    val rawUrl: String = "",
    /** Refresh on the background schedule. On by default: importing the link was an explicit act. */
    val autoUpdate: Boolean = true,
    /** Wall-clock of the last successful fetch from the platform. */
    val lastFetchedAt: Long = 0L,
    /** Wall-clock of the last successful mirror write. */
    val lastUpdatedAt: Long = 0L,
    /** Wall-clock of the last attempt (success or failure) — rate-limits retries. */
    val lastAttemptAt: Long = 0L,
    val lastStatus: UpdateStatus = UpdateStatus.NEVER,
    /** Songs the local list held after the last successful fetch. */
    val lastTrackCount: Int = 0,
    /** Songs the platform said the playlist holds at the last fetch (null when it does not say). */
    val lastDeclaredTotal: Int? = null,
    /**
     * Track ids the platform returned at the last **successful mirror write**.
     *
     * Everything in the local list that is *not* in here was added by hand (or was already gone
     * from the platform) and is therefore preserved by the next mirror instead of being deleted.
     * Empty means "unknown", which is treated as "preserve everything not currently online".
     */
    val onlineTrackIds: Set<String> = emptySet(),
    /** Consecutive failures, driving the exponential retry backoff. Reset on success. */
    val consecutiveFailures: Int = 0,
    /** Interval in force at the last attempt, in hours — shown in the playlist detail. */
    val lastIntervalHours: Int? = null,
    /** Last failure / skip reason, shown in the playlist detail. Cleared on success. */
    val lastError: String? = null,
)
