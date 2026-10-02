package com.walkman.tv.data.sync

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
    private val requestSyncMode: suspend () -> String,
) {
    /** Official `socket.moduleReadys.list`: actions are ignored until the server says finished. */
    @Volatile
    var moduleReady: Boolean = false
        private set

    /** True once the server called `list_sync_finished` - the only reliable "sync succeeded" signal. */
    @Volatile
    var finished: Boolean = false
        private set

    /** Called whenever a session ends so a new session starts from a clean protocol state. */
    fun onSessionClosed() {
        moduleReady = false
        finished = false
    }

    /** Handles one incoming message2call method. Throws [LxSyncRpcException] on bad input. */
    suspend fun handle(method: String, args: JsonArray): JsonElement? = when (method) {
        "list_sync_get_md5" -> {
            Log.i(SyncClient.TAG, "list: get md5")
            JsonPrimitive(repository.listDataMd5())
        }

        "list_sync_get_list_data" -> {
            val data = repository.readListData()
            Log.i(
                SyncClient.TAG,
                "list: get list data (love=${data.loveList.size}, userLists=${data.userList.size})",
            )
            LxListJson.json.encodeToJsonElement(LxListData.serializer(), data)
        }

        "list_sync_get_sync_mode" -> JsonPrimitive(requestSyncMode())

        "list_sync_set_list_data" -> {
            val payload = args.getOrNull(0)
                ?: throw LxSyncRpcException("$method: missing data")
            val data = LxListJson.json.decodeFromJsonElement(LxListData.serializer(), payload)
            repository.applyListData(data)
            Log.i(
                SyncClient.TAG,
                "list: applied server data (love=${data.loveList.size}, userLists=${data.userList.size})",
            )
            null
        }

        "list_sync_finished" -> {
            moduleReady = true
            finished = true
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
                applier.apply(action)
                Log.i(SyncClient.TAG, "list: applied ${action::class.simpleName}")
                null
            }
        }

        else -> throw LxSyncRpcException("$method: unsupported list sync method")
    }
}