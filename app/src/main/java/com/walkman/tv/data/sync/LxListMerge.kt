package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.LibraryStore

/**
 * Three-way merge of the LX lists: **baseline** (the last state both sides agreed on, see
 * [SyncBaseline]), **local** (this device right now) and **remote** (what the server just sent).
 *
 * The protocol only moves whole snapshots, so membership has to be inferred from how each side
 * moved away from the baseline:
 *
 *  - a song present in the baseline and gone from one side was **deleted** on that side, and the
 *    deletion wins - otherwise `list_music_remove` / a deletion on another device could never
 *    propagate and deleted songs would come back on every sync (spec §5.3);
 *  - a song the baseline does not know is an **addition**; additions from both sides are unioned and
 *    deduplicated by `Track.id` (source + song id), never by title/singer text (spec §5.1);
 *  - everything else is unchanged and survives untouched.
 *
 * Order follows spec §5.4 and is resolved separately from membership (see [resolveOrder]): the order
 * of the songs both sides keep is the side that actually reordered relative to the baseline, so a
 * reorder made here is not clobbered by the server and a reorder made elsewhere is accepted. When
 * both sides reordered differently the server's order wins - a deterministic, stable choice that
 * makes every device converge instead of flipping back and forth. Songs only one side added are
 * appended in that side's order.
 *
 * Playlist identity is `Playlist.id` (never the name), song identity is `Track.id`; lists are merged
 * independently, so two lists can hold the same song without being merged (spec §5.1).
 */
object LxListMerge {

    data class Summary(
        val addedTracks: Int = 0,
        val removedTracks: Int = 0,
        val addedLists: Int = 0,
        val removedLists: Int = 0,
        val renamedLists: Int = 0,
        val reorderedLists: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = addedTracks == 0 && removedTracks == 0 && addedLists == 0 &&
                removedLists == 0 && renamedLists == 0 && reorderedLists == 0
    }

    data class Result(val lists: LibraryStore.SyncLists, val summary: Summary)

    fun merge(baseline: SyncBaseline, local: LibraryStore.SyncLists, remote: LxListData): Result {
        val remoteLists = LxListMapper.toSyncLists(remote)
        val counters = Counters()

        val defaultTracks = mergeTracks(baseline.defaultList, local.defaultList, remoteLists.defaultList, counters)
        val loveTracks = mergeTracks(baseline.loveList, local.love, remoteLists.love, counters)

        val localById = local.userLists.associateBy { it.id }
        val remoteById = remoteLists.userLists.associateBy { it.id }
        val baselineById = baseline.userLists.associateBy { it.id }

        val userLists = ArrayList<Playlist>(remoteLists.userLists.size + local.userLists.size)
        // 1) The server's playlist order first - the same shape the official merge produces.
        for (remoteList in remoteLists.userLists) {
            val localList = localById[remoteList.id]
            val base = baselineById[remoteList.id]
            if (localList == null) {
                if (base == null) {
                    userLists += remoteList
                    counters.addedLists++
                } else {
                    counters.removedLists++
                }
                continue
            }
            val name = pick(base?.name, localList.name, remoteList.name).orEmpty().ifBlank { localList.name }
            if (name != localList.name) counters.renamedLists++
            val tracks = mergeTracks(base?.ids.orEmpty(), localList.tracks, remoteList.tracks, counters)
            userLists += Playlist(
                id = remoteList.id,
                name = name,
                tracks = tracks,
                updatedAt = maxOf(localList.updatedAt, remoteList.updatedAt),
                source = pick(base?.source, localList.source, remoteList.source),
                sourceListId = pick(base?.sourceListId, localList.sourceListId, remoteList.sourceListId),
            )
        }
        // 2) Lists the server no longer has: either this device deleted them (deletion wins) or they
        //    are local-only additions. A list the user changed here after the last agreement is kept
        //    and pushed again rather than silently thrown away (spec §5.5).
        for (localList in local.userLists) {
            if (remoteById.containsKey(localList.id)) continue
            val base = baselineById[localList.id]
            if (base == null || !sameAsBaseline(localList, base)) {
                userLists += localList
                counters.addedLists++
            } else {
                counters.removedLists++
            }
        }

        return Result(
            lists = LibraryStore.SyncLists(defaultList = defaultTracks, love = loveTracks, userLists = userLists),
            summary = counters.toSummary(),
        )
    }

    /**
     * Membership merge of one ordered song list. See the class comment for the rules; [baselineIds]
     * are the ids the two sides agreed on (empty when neither side knew this list before).
     */
    private fun mergeTracks(
        baselineIds: List<String>,
        local: List<Track>,
        remote: List<Track>,
        counters: Counters,
    ): List<Track> {
        val baseline = baselineIds.toHashSet()
        val localIds = HashSet<String>(local.size)
        for (track in local) localIds.add(track.id)
        val remoteIds = HashSet<String>(remote.size)
        for (track in remote) remoteIds.add(track.id)

        val byId = HashMap<String, Track>(remote.size + local.size)
        for (track in remote) byId[track.id] = track
        // This device's own entry wins for a song both sides have: source/quality/extras are playback
        // metadata, and a difference in them is never treated as add/remove.
        for (track in local) byId[track.id] = track

        // Kept entries split by provenance: `shared` are songs both sides have (or both added),
        // `remoteOnly` / `localOnly` are one-sided additions the baseline does not know about.
        val shared = ArrayList<String>(minOf(local.size, remote.size))
        for (track in remote) if (track.id in localIds) shared += track.id
        val remoteOnly = ArrayList<String>()
        for (track in remote) {
            if (track.id in localIds) continue
            if (keep(track.id, baseline, localIds, remoteIds)) remoteOnly += track.id
        }
        val localOnly = ArrayList<String>()
        for (track in local) {
            if (track.id in remoteIds) continue
            if (keep(track.id, baseline, localIds, remoteIds)) localOnly += track.id
        }

        val orderedShared = resolveOrder(baselineIds, shared, local, remote)
        val merged = ArrayList<Track>(orderedShared.size + remoteOnly.size + localOnly.size)
        for (id in orderedShared) merged += byId.getValue(id)
        for (id in remoteOnly) merged += byId.getValue(id)
        for (id in localOnly) merged += byId.getValue(id)
        val mergedIds = HashSet<String>(merged.size)
        for (track in merged) mergedIds.add(track.id)
        counters.addedTracks += mergedIds.count { it !in localIds }
        counters.removedTracks += localIds.count { it !in mergedIds }
        if (local.size == merged.size && local.map { it.id } != merged.map { it.id }) {
            counters.reorderedLists++
        }
        return merged
    }

    /**
     * Order of the songs both sides keep (spec §5.4): the side that actually reordered away from the
     * baseline wins, and when both did, the server's order wins so every device converges on the same
     * result instead of swinging back and forth. A song both sides added independently is not part of
     * the comparison - nobody "reordered" it.
     */
    private fun resolveOrder(
        baselineIds: List<String>,
        sharedIds: List<String>,
        local: List<Track>,
        remote: List<Track>,
    ): List<String> {
        val shared = sharedIds.toHashSet()
        val localOrder = local.map { it.id }.filter { it in shared }
        val remoteOrder = remote.map { it.id }.filter { it in shared }

        // Only the baseline-known songs can tell "reordered" from "a song both sides just added".
        val common = baselineIds.filter { it in shared }.toHashSet()
        val baseOrder = baselineIds.filter { it in common }
        val localCommon = localOrder.filter { it in common }
        val remoteCommon = remoteOrder.filter { it in common }

        val localReordered = localCommon != baseOrder
        val remoteReordered = remoteCommon != baseOrder
        return if (localReordered && !remoteReordered) localOrder else remoteOrder
    }

    private fun keep(
        id: String,
        baseline: Set<String>,
        localIds: Set<String>,
        remoteIds: Set<String>,
    ): Boolean {
        val inLocal = id in localIds
        val inRemote = id in remoteIds
        val inBaseline = id in baseline
        return when {
            // Both sides still have it (or both added it): keep exactly one entry.
            inLocal && inRemote -> true
            // Gone from the server, still here: kept only when this device added it after the
            // agreement - otherwise the other device deleted it and the deletion must win.
            inLocal -> !inBaseline
            // Gone here, still on the server: kept only when the server added it after the
            // agreement - otherwise this device deleted it and the deletion must win.
            inRemote -> !inBaseline
            else -> false
        }
    }

    /** Upstream `selectData(snapshot, local, remote)`: local wins unless it is the untouched side. */
    private fun pick(baseline: String?, local: String?, remote: String?): String? = when {
        baseline == null -> local ?: remote
        baseline == local -> remote
        else -> local
    }

    private fun sameAsBaseline(list: Playlist, baseline: BaselineList): Boolean =
        list.name == baseline.name &&
            list.source == baseline.source &&
            list.sourceListId == baseline.sourceListId &&
            list.tracks.size == baseline.ids.size &&
            list.tracks.withIndex().all { (index, track) -> track.id == baseline.ids[index] }

    private class Counters {
        var addedTracks = 0
        var removedTracks = 0
        var addedLists = 0
        var removedLists = 0
        var renamedLists = 0
        var reorderedLists = 0

        fun toSummary() = Summary(
            addedTracks = addedTracks,
            removedTracks = removedTracks,
            addedLists = addedLists,
            removedLists = removedLists,
            renamedLists = renamedLists,
            reorderedLists = reorderedLists,
        )
    }
}
