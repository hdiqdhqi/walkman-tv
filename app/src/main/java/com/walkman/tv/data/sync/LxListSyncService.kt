package com.walkman.tv.data.sync

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The answer to the server's `list_sync_get_sync_mode` call: the official `SyncMode` value, plus
 * whether the *user* was asked for it in this session (see [LxSyncSession.onModeAnswer]).
 */
data class SyncModeAnswer(val mode: String, val prompted: Boolean = false)

/**
 * The client half of the official list-sync protocol.
 *
 * The server drives everything: after feature negotiation it calls `list_sync_get_md5`,
 * `list_sync_get_sync_mode`, `list_sync_get_list_data`, `list_sync_set_list_data` and finally
 * `list_sync_finished` on this client, and later pushes `onListSyncAction` broadcasts. This class
 * answers those calls using [SyncRepository]/[SyncApplier] and nothing else - no WebSocket, no
 * HTTP, no Android API beyond logging - so unit tests can drive a full exchange through an
 * offline fake server.
 */
class LxListSyncService(
    private val repository: SyncRepository,
    private val applier: SyncApplier,
    private val requestSyncMode: suspend () -> SyncModeAnswer,
) {
    /**
     * The data policy of the session that is currently up, installed by `SyncManager` *before* the
     * socket opens. Left null the service behaves like the pure protocol adapter it used to be:
     * every payload is applied verbatim (which is what the offline protocol tests exercise).
     */
    @Volatile
    private var session: LxSyncSession? = null

    /** Official `socket.moduleReadys.list`: actions are ignored until the server says finished. */
    @Volatile
    var moduleReady: Boolean = false
        private set

    /** True once the server called `list_sync_finished` - the only reliable "sync succeeded" signal. */
    @Volatile
    var finished: Boolean = false
        private set

    /** Installs the policy for a session that is about to start. */
    fun beginSession(session: LxSyncSession?) {
        this.session = session
    }

    /**
     * Called whenever a socket closes - including the one `connect()` tears down immediately before
     * it opens the new socket, which is *after* `beginSession` installed the policy for that new
     * session. So this resets the protocol flags only: dropping the policy here would silently
     * disable the merge, the baseline and the push for the session that is about to start (it also
     * keeps a finished session able to serve the server's later `onListSyncAction` broadcasts).
     */
    fun onSessionClosed() {
        moduleReady = false
        finished = false
    }

    /** Drops the policy as well: sync was turned off / the user disconnected, so nothing applies. */
    fun clearSession() {
        onSessionClosed()
        session = null
    }

    /** Handles one incoming message2call method. Throws [LxSyncRpcException] on bad input. */
    suspend fun handle(method: String, args: JsonArray): JsonElement? = when (method) {
        "list_sync_get_md5" -> {
            Log.i(SyncClient.TAG, "list: get md5")
            JsonPrimitive(session?.onMd5() ?: repository.listDataMd5())
        }

        "list_sync_get_list_data" -> {
            val data = session?.onGetListData() ?: repository.readListData()
            Log.i(
                SyncClient.TAG,
                "list: get list data (love=${data.loveList.size}, userLists=${data.userList.size})",
            )
            LxListJson.json.encodeToJsonElement(LxListData.serializer(), data)
        }

        "list_sync_get_sync_mode" -> {
            val answer = requestSyncMode()
            session?.onModeAnswer(answer.mode, answer.prompted)
            JsonPrimitive(answer.mode)
        }

        "list_sync_set_list_data" -> {
            val payload = args.getOrNull(0)
                ?: throw LxSyncRpcException("$method: missing data")
            val data = LxListJson.json.decodeFromJsonElement(LxListData.serializer(), payload)
            val policy = session
            if (policy == null) {
                repository.applyListData(data)
            } else {
                policy.applyRemote(data, LxSyncSession.REASON_SERVER_RESULT)
            }
            Log.i(
                SyncClient.TAG,
                "list: applied server data (love=${data.loveList.size}, userLists=${data.userList.size})",
            )
            null
        }

        "list_sync_finished" -> {
            moduleReady = true
            finished = true
            session?.onFinished()
            Log.i(SyncClient.TAG, "list: finished")
            null
        }

        "onListSyncAction" -> {
            if (!moduleReady) {
                // Official handlers ignore actions until the module reported ready.
                null
            } else {
                val payload = args.getOrNull(0)
                    ?: throw LxSyncRpcException("$method: missing action")
                val action = LxListAction.parse(payload)
                // A wholesale overwrite broadcast is the *server's* current state, so it goes
                // through the same merge as `list_sync_set_list_data` - applying it blindly would
                // throw away what this device changed since the last agreement (spec §4.2).
                if (action is LxListAction.DataOverwrite && session != null) {
                    session?.applyRemote(action.data, LxSyncSession.REASON_BROADCAST)
                } else {
                    applier.apply(action)
                }
                Log.i(SyncClient.TAG, "list: applied ${action::class.simpleName}")
                null
            }
        }

        else -> throw LxSyncRpcException("$method: unsupported list sync method")
    }
}
