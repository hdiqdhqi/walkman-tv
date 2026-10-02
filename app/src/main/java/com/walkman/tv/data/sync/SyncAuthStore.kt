package com.walkman.tv.data.sync

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Credentials issued by the server for one clientId. [key] is the AES-128 key (base64) used
 * for the key-based `/ah` re-auth and for encrypting the WebSocket `t=` token - never log it.
 */
@Serializable
data class SyncKeyInfo(
    val clientId: String,
    val key: String,
    val serverName: String? = null,
    val deviceName: String? = null,
    val lastConnectDate: Long? = null,
)

@Serializable
private data class SyncAuthData(val servers: Map<String, SyncKeyInfo> = emptyMap())

/**
 * Persists `serverId -> SyncKeyInfo` so the connection code only has to be entered once.
 *
 * Stored as plain JSON under `filesDir` next to the other stores, matching the project's
 * existing persistence style. (Keystore-wrapping the key is a possible follow-up; stage 3A
 * deliberately does not introduce a new security framework.)
 */
class SyncAuthStore internal constructor(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val store = JsonStore(
        File(filesDir, "sync_auth.json"),
        SyncAuthData.serializer(),
        SyncAuthData(),
    )

    private val _servers = MutableStateFlow<Map<String, SyncKeyInfo>>(emptyMap())
    val servers: StateFlow<Map<String, SyncKeyInfo>> = _servers.asStateFlow()

    suspend fun loadAll() {
        _servers.value = withContext(Dispatchers.IO) { store.load().servers }
    }

    fun get(serverId: String): SyncKeyInfo? = _servers.value[serverId]

    suspend fun put(serverId: String, info: SyncKeyInfo) {
        _servers.value = _servers.value + (serverId to info)
        withContext(Dispatchers.IO) { store.save(SyncAuthData(_servers.value)) }
    }

    suspend fun remove(serverId: String) {
        _servers.value = _servers.value - serverId
        withContext(Dispatchers.IO) { store.save(SyncAuthData(_servers.value)) }
    }
}
