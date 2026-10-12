package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Offline simulation of the official `lx-music-sync-server` list module, faithful enough to drive
 * the real client half end to end ([SyncManager] -> [LxListSyncService] -> [LxSyncSession] /
 * [LxListPusher]).
 *
 * Ported from `lx-music-sync-server/src/modules/list/sync/sync.ts` and `.../sync/handler.ts`:
 *  - the `list_sync_*` server-driven exchange, including the `checkListLatest` md5 gate,
 *  - the first-sync mode merge (`merge_local_remote` / the overwrite variants) and the daily
 *    per-device snapshot merge (`mergeListDataFromSnapshot`),
 *  - `onListSyncAction` application and storage of the resulting per-device snapshot.
 *
 * It is a *server*: it owns the stored list, never the client's library. Tests assert on
 * [listData] / [receivedActions] - i.e. on what the server actually ended up holding.
 */
class LxSyncServerSim(val deviceId: String = "tv-device") {
    var listData: LxListData = LxListData()
        private set

    /** The 同步方式 the server uses for a device it has no snapshot for. */
    var mode: String = LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE

    /** Official `list.addMusicLocationType` server config. */
    var addMusicLocationType: String = "bottom"

    /** How many `list_sync_*` exchanges ran (a full session, not an incremental push). */
    var sessions: Int = 0
        private set

    /** Every official `onListSyncAction` payload the server received, in order. */
    val receivedActions = CopyOnWriteArrayList<JsonElement>()

    /** Invoked right after the exchange's `list_sync_set_list_data`, before any hold. */
    var onExchange: (suspend () -> Unit)? = null

    /** When set, the exchange parks here before `list_sync_finished` ("edit during sync"). */
    var holdBeforeFinish: CompletableDeferred<Unit>? = null

    private val snapshots = HashMap<String, LxListData>()

    fun setListData(data: LxListData) { listData = data }

    fun snapshotOf(id: String = deviceId): LxListData? = snapshots[id]

    /** Seeds the last-agreed snapshot for a device, as a previous sync would have left it. */
    fun seedSnapshot(data: LxListData, id: String = deviceId) { snapshots[id] = data }

    fun serverKey(): String = LxListJson.md5(listData)

    /** Plays the server's `list_sync_*` sequence against a client handler. */
    suspend fun runSync(
        handler: suspend (String, JsonArray) -> JsonElement?,
        clientId: String = deviceId,
    ): LxListData {
        sessions++
        val snapshot = snapshots[clientId]
        if (snapshot != null) {
            // Official `checkListLatest`: with a per-device snapshot the server first compares the
            // client's md5 with the state it already holds and skips the merge when they match.
            // `sync()` still calls `list_sync_finished` afterwards - the session ends either way,
            // which is what the client's `finished` flag (and the 同步完成 state) depends on.
            val md5 = (handler("list_sync_get_md5", JsonArray(emptyList())) as? JsonPrimitive)?.content
            if (md5 == serverKey()) {
                handler("list_sync_finished", JsonArray(emptyList()))
                return listData
            }
        }
        val uploaded = decodeListData(handler("list_sync_get_list_data", JsonArray(emptyList())))
        val merged =
            if (snapshot != null) mergeFromSnapshot(listData, uploaded, snapshot)
            else mergeByMode(listData, uploaded)
        listData = merged
        snapshots[clientId] = merged
        handler("list_sync_set_list_data", JsonArray(listOf(encodeListData(merged))))
        onExchange?.invoke()
        holdBeforeFinish?.await()
        handler("list_sync_finished", JsonArray(emptyList()))
        return merged
    }

    /** The server's `onListSyncAction` handler: applies the action, stores the new snapshot. */
    fun applyAction(actionJson: JsonElement, clientId: String = deviceId) {
        receivedActions += actionJson
        listData = applyAction(listData, LxListAction.parse(actionJson))
        snapshots[clientId] = listData
    }

    // ------------------------------------------------------------------- official merge ports

    private fun mergeByMode(server: LxListData, client: LxListData): LxListData = when (mode) {
        LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE -> mergeList(server, client)
        LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL -> mergeList(client, server)
        LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE -> overwriteList(server, client)
        LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL -> overwriteList(client, server)
        LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL -> server
        LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL -> client
        else -> throw IllegalStateException("mode not selectable: $mode")
    }

    /** `mergeList(socket, sourceListData, targetListData)`. */
    private fun mergeList(source: LxListData, target: LxListData): LxListData {
        val defaultList = handleMergeList(source.defaultList, target.defaultList)
        val loveList = handleMergeList(source.loveList, target.loveList)
        val lists = ArrayList(source.userList)
        for ((index, list) in target.userList.withIndex()) {
            val at = lists.indexOfFirst { it.id == list.id }
            if (at >= 0) {
                val existing = lists[at]
                var updated = existing.copy(list = handleMergeList(existing.list, list.list))
                val targetTime = list.locationUpdateTime ?: 0L
                if (targetTime < (existing.locationUpdateTime ?: 0L)) {
                    updated = updated.copy(locationUpdateTime = targetTime)
                    lists.removeAt(at)
                    lists.add(minOf(index, lists.size), updated)
                } else {
                    lists[at] = updated
                }
            } else if ((list.locationUpdateTime ?: 0L) != 0L) {
                lists.add(minOf(index, lists.size), list)
            } else {
                lists += list
            }
        }
        return LxListData(defaultList, loveList, lists)
    }

    /** `overwriteList(sourceListData, targetListData)`. */
    private fun overwriteList(source: LxListData, target: LxListData): LxListData {
        val known = source.userList.mapTo(HashSet()) { it.id }
        val lists = ArrayList(source.userList)
        for ((index, list) in target.userList.withIndex()) {
            if (list.id in known) continue
            if ((list.locationUpdateTime ?: 0L) != 0L) lists.add(minOf(index, lists.size), list)
            else lists += list
        }
        return source.copy(userList = lists)
    }

    /** `mergeListDataFromSnapshot(localListData, remoteListData, snapshot, ...)`. */
    private fun mergeFromSnapshot(local: LxListData, remote: LxListData, snapshot: LxListData): LxListData {
        val defaultList = mergeTracksFromSnapshot(local.defaultList, remote.defaultList, snapshot.defaultList)
        val loveList = mergeTracksFromSnapshot(local.loveList, remote.loveList, snapshot.loveList)

        val localById = local.userList.associateBy { it.id }
        val remoteById = remote.userList.associateBy { it.id }
        val snapshotById = snapshot.userList.associateBy { it.id }

        val removed = HashSet<String>()
        for (list in snapshot.userList) {
            if (list.id !in localById || list.id !in remoteById) removed += list.id
        }

        val lists = ArrayList<LxUserList>()
        for (list in local.userList) {
            if (list.id in removed) continue
            val remoteList = remoteById[list.id]
            if (remoteList == null) {
                lists += list
                continue
            }
            val base = snapshotById[list.id]
                ?: LxUserList(id = list.id, name = "", source = null, sourceListId = null, locationUpdateTime = null)
            lists += LxUserList(
                id = list.id,
                name = selectData(base.name, list.name, remoteList.name).orEmpty(),
                source = selectData(base.source, list.source, remoteList.source),
                sourceListId = selectData(base.sourceListId, list.sourceListId, remoteList.sourceListId),
                locationUpdateTime = list.locationUpdateTime,
                list = mergeTracksFromSnapshot(list.list, remoteList.list, base.list),
            )
        }

        for ((index, list) in remote.userList.withIndex()) {
            if (list.id in removed) continue
            val remoteTime = list.locationUpdateTime ?: 0L
            val localList = localById[list.id]
            if (localList != null) {
                val localTime = localList.locationUpdateTime ?: 0L
                if (localTime >= remoteTime) continue
                val at = lists.indexOfFirst { it.id == list.id }
                if (at >= 0) {
                    val moved = lists.removeAt(at).copy(locationUpdateTime = localTime)
                    lists.add(minOf(index, lists.size), moved)
                }
            } else if (remoteTime != 0L) {
                lists.add(minOf(index, lists.size), list)
            } else {
                lists += list
            }
        }
        return LxListData(defaultList, loveList, lists)
    }

    private fun mergeTracksFromSnapshot(
        local: List<MusicInfo>,
        remote: List<MusicInfo>,
        snapshot: List<MusicInfo>,
    ): List<MusicInfo> {
        val localIds = local.mapTo(HashSet()) { it.id }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val removed = snapshot.mapNotNullTo(HashSet()) { if (it.id !in localIds || it.id !in remoteIds) it.id else null }
        return mergeTracks(local, remote, removed)
    }

    private fun handleMergeList(source: List<MusicInfo>, target: List<MusicInfo>): List<MusicInfo> =
        mergeTracks(source, target, emptySet())

    private fun mergeTracks(
        source: List<MusicInfo>,
        target: List<MusicInfo>,
        removed: Set<String>,
    ): List<MusicInfo> {
        val map = LinkedHashMap<String, MusicInfo>()
        val ordered = if (addMusicLocationType == "top") target + source else source + target
        val ids = ArrayList<String>(ordered.size)
        if (addMusicLocationType == "top") {
            for (i in ordered.indices.reversed()) {
                val item = ordered[i]
                if (item.id in map || item.id in removed) continue
                ids.add(0, item.id)
                map[item.id] = item
            }
        } else {
            for (item in ordered) {
                if (item.id in map || item.id in removed) continue
                ids += item.id
                map[item.id] = item
            }
        }
        return ids.mapNotNull { map[it] }
    }

    private fun selectData(snapshot: String?, local: String?, remote: String?): String? =
        if (snapshot == local) remote else local

    // -------------------------------------------------------- official list action (server side)

    private fun applyAction(data: LxListData, action: LxListAction): LxListData = when (action) {
        is LxListAction.DataOverwrite -> action.data
        is LxListAction.CreateLists -> {
            val lists = ArrayList(data.userList)
            for (info in action.listInfos) {
                if (info.id.isEmpty() || lists.any { it.id == info.id }) continue
                val entry = LxUserList(info.id, info.name, info.source, info.sourceListId, info.locationUpdateTime)
                if (action.position < 0 || action.position >= lists.size) lists += entry
                else lists.add(action.position, entry)
            }
            data.copy(userList = lists)
        }
        is LxListAction.RemoveLists -> data.copy(userList = data.userList.filter { it.id !in action.ids })
        is LxListAction.UpdateLists -> data.copy(
            userList = data.userList.map { list ->
                val info = action.lists.firstOrNull { it.id == list.id } ?: return@map list
                list.copy(
                    name = info.name,
                    source = info.source,
                    sourceListId = info.sourceListId,
                    locationUpdateTime = info.locationUpdateTime ?: list.locationUpdateTime,
                )
            },
        )
        is LxListAction.UpdateListPosition -> data.copy(userList = reorderLists(data.userList, action.position, action.ids))
        is LxListAction.AddMusic -> data.mapList(action.listId) { addMusic(it, action.musicInfos, action.location) }
        is LxListAction.MoveMusic -> data
            .mapList(action.fromId) { removeMusic(it, action.musicInfos.map { music -> music.id }.toSet()) }
            .mapList(action.toId) { addMusic(it, action.musicInfos, action.location) }
        is LxListAction.RemoveMusic -> data.mapList(action.listId) { removeMusic(it, action.ids.toSet()) }
        is LxListAction.UpdateMusic -> {
            var out = data
            for (entry in action.entries) {
                out = out.mapList(entry.listId) { list -> list.map { if (it.id == entry.musicInfo.id) entry.musicInfo else it } }
            }
            out
        }
        is LxListAction.UpdateMusicPosition -> data.mapList(action.listId) { moveMusicTo(it, action.position, action.ids) }
        is LxListAction.OverwriteMusic -> data.mapList(action.listId) { action.musicInfos }
        is LxListAction.ClearMusic -> {
            var out = data
            for (id in action.listIds) out = out.mapList(id) { emptyList() }
            out
        }
    }

    private fun reorderLists(lists: List<LxUserList>, position: Int, ids: List<String>): List<LxUserList> {
        val moving = ids.mapNotNull { id -> lists.firstOrNull { it.id == id } }
        if (moving.isEmpty()) return lists
        val movingIds = ids.toHashSet()
        val out = ArrayList(lists.filter { it.id !in movingIds })
        out.addAll(position.coerceIn(0, out.size), moving)
        return out
    }

    private fun addMusic(list: List<MusicInfo>, infos: List<MusicInfo>, location: AddMusicLocation): List<MusicInfo> {
        val present = list.mapTo(HashSet()) { it.id }
        val fresh = infos.filter { present.add(it.id) }
        if (fresh.isEmpty()) return list
        return if (location == AddMusicLocation.TOP) fresh + list else list + fresh
    }

    private fun removeMusic(list: List<MusicInfo>, ids: Set<String>): List<MusicInfo> = list.filter { it.id !in ids }

    private fun moveMusicTo(list: List<MusicInfo>, position: Int, ids: List<String>): List<MusicInfo> {
        val movingIds = ids.toHashSet()
        val moving = list.filter { it.id in movingIds }
        if (moving.isEmpty()) return list
        val out = ArrayList(list.filter { it.id !in movingIds })
        out.addAll(position.coerceIn(0, out.size), moving)
        return out
    }

    private fun LxListData.mapList(
        listId: String,
        transform: (List<MusicInfo>) -> List<MusicInfo>,
    ): LxListData = when (listId) {
        Playlist.DEFAULT -> copy(defaultList = transform(defaultList))
        Playlist.LOVE -> copy(loveList = transform(loveList))
        else -> copy(userList = userList.map { if (it.id == listId) it.copy(list = transform(it.list)) else it })
    }

    private fun decodeListData(element: JsonElement?): LxListData {
        val payload = element ?: throw IllegalStateException("server sim: no list data payload")
        return LxListJson.json.decodeFromJsonElement(LxListData.serializer(), payload)
    }

    private fun encodeListData(data: LxListData): JsonElement =
        LxListJson.json.encodeToJsonElement(LxListData.serializer(), data)
}

/**
 * Test double for [SyncClient] that plays the *server* against the client handler SyncManager
 * installed, exactly like the real server does: the module exchange runs first, then `finished()`.
 */
class SimSyncClient(
    private val driverScope: CoroutineScope,
    private val server: LxSyncServerSim,
    private val clientId: String = server.deviceId,
) : SyncClient(OkHttpClient(), SyncAuthStore(tempDir()), driverScope) {

    /** Completed by the server right after the exchange, before `list_sync_finished`. */
    var parked: CompletableDeferred<Unit> = CompletableDeferred()

    /** When non-null the next push throws this (server refused an action). */
    var pushFailure: String? = null

    /** Every id the client uploaded in the last exchange, for assertions on the wire payload. */
    var lastUpload: LxListData? = null
        private set

    private var readyGate = CompletableDeferred<Boolean>()

    override val isReady: Boolean get() = readyGate.isCompleted

    override suspend fun authenticate(config: SyncConfig, onPhase: (SyncState) -> Unit): AuthOutcome =
        AuthOutcome.Ok("server-1", 4, SyncKeyInfo(clientId = clientId, key = "key", serverName = "我的服务器"))

    override fun openSession(config: SyncConfig, keyInfo: SyncKeyInfo, callback: SessionCallback) {
        readyGate = CompletableDeferred()
        parked = CompletableDeferred()
        callback.onState(SyncState.Connecting)
        val handler = listSyncHandler
        if (handler == null) {
            readyGate.complete(false)
            return
        }
        server.onExchange = {
            runCatching { parked.complete(Unit) }
        }
        driverScope.launch {
            val collector: suspend (String, JsonArray) -> JsonElement? = { path, args ->
                val result = handler(listOf(path), args)
                if (path == "list_sync_get_list_data") {
                    lastUpload = result?.let { element ->
                        LxListJson.json.decodeFromJsonElement(LxListData.serializer(), element)
                    }
                }
                result
            }
            server.runSync(collector, clientId)
            callback.onReady()
            readyGate.complete(true)
        }
    }

    /** Releases a session parked before `list_sync_finished` (see [LxSyncServerSim.holdBeforeFinish]). */
    fun releaseHeldFinish() {
        server.holdBeforeFinish?.complete(Unit)
        server.holdBeforeFinish = null
    }

    override fun closeSession() {
        readyGate.complete(false)
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean = readyGate.await()

    override suspend fun call(path: String, args: List<JsonElement>): JsonElement? {
        if (path == LxListAction.PUSH_METHOD) {
            pushFailure?.let { failure -> throw LxSyncRpcException(failure) }
            args.firstOrNull()?.let { server.applyAction(it, clientId) }
            return JsonPrimitive(true)
        }
        return super.call(path, args)
    }
}
