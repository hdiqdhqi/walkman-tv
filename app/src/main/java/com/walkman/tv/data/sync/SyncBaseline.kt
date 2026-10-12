package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.serialization.Serializable

/**
 * The last state this device and the sync server *agreed* on - the baseline of the three-way merge
 * (baseline / local / remote) and the diff base for pushing local edits.
 *
 * Why this exists: the LX protocol transfers whole snapshots (`LX.Sync.List.ListData`), so a payload
 * on its own cannot say "this song was deleted" - it can only say "this song is not here". Without a
 * baseline a client can only (a) union both sides, in which case a deletion can never win, or
 * (b) take one side wholesale, which is exactly the "服务器快照无条件覆盖本地" behaviour we must not
 * have. Both official clients solve this by keeping the server-side per-device snapshot; a client
 * that has a baseline of its own can do the same locally (see [LxListMerge]) and can also answer
 * "what do I still owe the server?" without re-uploading 2000 songs.
 *
 * Only list identity, names and ordered song ids are stored: enough to merge membership and order,
 * small enough to persist on every sync. It is *purely local* - it is never put on the wire, so it
 * cannot break compatibility with the server or with LX Music mobile/desktop.
 */
@Serializable
data class SyncBaseline(
    /** Server/account this baseline belongs to; a different one means "no baseline". */
    val serverId: String = "",
    val savedAt: Long = 0L,
    val defaultList: List<String> = emptyList(),
    val loveList: List<String> = emptyList(),
    val userLists: List<BaselineList> = emptyList(),
) {
    /** Ordered song ids of one protocol list, or null when the baseline does not know that list. */
    fun trackIds(listId: String): List<String>? = when (listId) {
        Playlist.DEFAULT -> defaultList
        Playlist.LOVE -> loveList
        else -> userLists.firstOrNull { it.id == listId }?.ids
    }

    fun baselineList(listId: String): BaselineList? = userLists.firstOrNull { it.id == listId }

    /** Ids of the user lists this baseline knows (defaultList/love are not user lists). */
    fun listIds(): List<String> = userLists.map { it.id }

    val isEmpty: Boolean
        get() = defaultList.isEmpty() && loveList.isEmpty() && userLists.isEmpty()
}

/** One user list inside a [SyncBaseline]. */
@Serializable
data class BaselineList(
    val id: String,
    val name: String = "",
    val source: String? = null,
    val sourceListId: String? = null,
    val ids: List<String> = emptyList(),
)

/** Builds a [SyncBaseline] from either side of the exchange. */
object SyncBaselines {

    fun of(lists: LibraryStore.SyncLists, serverId: String, savedAt: Long = System.currentTimeMillis()): SyncBaseline =
        SyncBaseline(
            serverId = serverId,
            savedAt = savedAt,
            defaultList = lists.defaultList.map { it.id },
            loveList = lists.love.map { it.id },
            userLists = lists.userLists.map { list ->
                BaselineList(
                    id = list.id,
                    name = list.name,
                    source = list.source,
                    sourceListId = list.sourceListId,
                    ids = list.tracks.map { it.id },
                )
            },
        )

    fun of(data: LxListData, serverId: String, savedAt: Long = System.currentTimeMillis()): SyncBaseline =
        SyncBaseline(
            serverId = serverId,
            savedAt = savedAt,
            defaultList = data.defaultList.map { it.id },
            loveList = data.loveList.map { it.id },
            userLists = data.userList.map { list ->
                BaselineList(
                    id = list.id,
                    name = list.name,
                    source = list.source,
                    sourceListId = list.sourceListId,
                    ids = list.list.map { it.id },
                )
            },
        )

    /**
     * The state the server holds *after* [actions] were applied - the baseline to record once a push
     * succeeded ([LxListPusher]).
     *
     * Membership is this device's ([current]), but the *order* is not: the server keeps its own order
     * for the songs it already had and only inserts the pushed additions (`top` -> in front,
     * `bottom` -> at the end). Recording the local order here would make the next merge read the
     * server as "the side that reordered" and clobber a local reorder that was just preserved.
     */
    fun afterPush(
        previous: SyncBaseline,
        current: LibraryStore.SyncLists,
        serverId: String,
        actions: List<LxListAction>,
    ): SyncBaseline {
        val fresh = of(current, serverId)
        val insertedAtTop = actions.filterIsInstance<LxListAction.AddMusic>()
            .filter { it.location == AddMusicLocation.TOP }
            .map { it.listId }
            .toHashSet()

        fun serverOrder(listId: String, ids: List<String>, previousIds: List<String>): List<String> {
            val present = ids.toHashSet()
            val known = previousIds.filter { it in present }
            val knownSet = known.toHashSet()
            val added = ids.filter { it !in knownSet }
            return if (listId in insertedAtTop) added + known else known + added
        }

        return fresh.copy(
            defaultList = serverOrder(Playlist.DEFAULT, fresh.defaultList, previous.defaultList),
            loveList = serverOrder(Playlist.LOVE, fresh.loveList, previous.loveList),
            userLists = fresh.userLists.map { list ->
                list.copy(ids = serverOrder(list.id, list.ids, previous.baselineList(list.id)?.ids.orEmpty()))
            },
        )
    }
}
