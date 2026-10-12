package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.Track

/**
 * What a refresh would do, computed *before* anything is written.
 *
 * [finalTracks] is the exact list the local playlist should end up with; the caller decides whether
 * to apply it (see [SonglistUpdatePlan.needsConfirmation]).
 */
data class SonglistUpdatePlan(
    /** Ids the platform returned, de-duplicated, in platform order. */
    val onlineIds: List<String>,
    /** Ids that are new compared to the previous platform snapshot. */
    val added: List<String>,
    /** Ids that were in the previous platform snapshot and still existed locally. */
    val removed: List<String>,
    /** Tracks kept because they were added by hand (not part of the platform snapshot). */
    val kept: List<Track>,
    val finalTracks: List<Track>,
    val reordered: Boolean,
    /** True when applying this plan needs an explicit user confirmation first. */
    val needsConfirmation: Boolean,
    val confirmReason: String?,
)

/**
 * The mirror-update rules of an imported playlist, ported from LX Music's `syncSourceList` +
 * `overwriteListMusics` but with the two safety properties this project needs:
 *
 *  1. **hand-added tracks are never deleted.** The origin record remembers which ids the platform
 *     returned at the last successful write; anything else in the local list was added by hand and
 *     is appended after the mirrored block. When the previous snapshot is unknown (a list adopted
 *     through LX sync), *everything* not currently online is preserved — the safe reading.
 *  2. **a suspicious collapse needs confirmation.** If the platform suddenly reports far fewer
 *     songs than last time (below [ANOMALY_DROP_RATIO] of a non-trivial snapshot), the plan is
 *     flagged instead of applied, so a broken/partial platform response cannot wipe a playlist.
 */
object SonglistUpdatePlanner {

    /** Below this many previously-online songs, a drop is not treated as suspicious. */
    const val ANOMALY_MIN_PREVIOUS = 5

    /** A new snapshot smaller than this fraction of the previous one is suspicious. */
    const val ANOMALY_DROP_RATIO = 0.5

    fun plan(
        current: List<Track>,
        online: List<Track>,
        previousOnlineIds: Set<String>,
        keepLocalTracks: Boolean = true,
    ): SonglistUpdatePlan {
        // Platform order, first occurrence wins: a playlist that lists the same song twice must not
        // produce a duplicated local entry.
        val onlineUnique = LinkedHashMap<String, Track>(online.size)
        for (track in online) onlineUnique.putIfAbsent(track.id, track)

        val currentIds = current.mapTo(HashSet()) { it.id }
        val onlineIds = onlineUnique.keys.toList()

        val added = onlineIds.filter { it !in previousOnlineIds }
        val removed = previousOnlineIds.filter { it !in onlineUnique && it in currentIds }

        val kept = if (!keepLocalTracks) {
            emptyList()
        } else {
            current.filter { track -> track.id !in previousOnlineIds && track.id !in onlineUnique }
        }

        val finalTracks = onlineUnique.values.toList() + kept
        val reordered = finalTracks.map { it.id } != current.map { it.id }

        val suspiciousDrop = previousOnlineIds.size >= ANOMALY_MIN_PREVIOUS &&
            onlineUnique.size < previousOnlineIds.size * ANOMALY_DROP_RATIO
        val confirmReason = when {
            suspiciousDrop ->
                "平台只返回 ${onlineUnique.size} 首，上次是 ${previousOnlineIds.size} 首，数量异常下降"
            else -> null
        }

        return SonglistUpdatePlan(
            onlineIds = onlineIds,
            added = added,
            removed = removed,
            kept = kept,
            finalTracks = finalTracks,
            reordered = reordered,
            needsConfirmation = confirmReason != null,
            confirmReason = confirmReason,
        )
    }
}
