package com.walkman.tv.data.logging

import com.walkman.tv.data.sync.SyncState

/**
 * Maps the LX sync state machine onto the long-term `SYNC_*` event names (spec §11) without
 * touching the protocol or the state machine - the log layer only observes [SyncState].
 *
 * No secret ever appears here: only the public display text, a busy flag and the state's class
 * name (a fixed, code-controlled category).
 */
object SyncEventMapping {

    fun event(state: SyncState): String = when (state) {
        SyncState.Disabled -> "SYNC_DISABLED"
        SyncState.Disconnected -> "SYNC_WS_CLOSE"
        SyncState.Connecting -> "SYNC_CONNECT_START"
        SyncState.Authenticating -> "SYNC_AUTH_START"
        SyncState.Connected -> "SYNC_AUTH_OK"
        SyncState.Syncing -> "SYNC_START"
        SyncState.Success -> "SYNC_SUCCESS"
        SyncState.NeedSyncMode -> "SYNC_MODE"
        SyncState.AuthFailed -> "SYNC_AUTH_FAIL"
        SyncState.BlockedIp -> "SYNC_FAIL"
        SyncState.ServiceUnavailable -> "SYNC_FAIL"
        SyncState.VersionIncompatible -> "SYNC_FAIL"
        is SyncState.Error -> "SYNC_FAIL"
    }

    fun level(state: SyncState): LogLevel = when (state) {
        SyncState.AuthFailed,
        SyncState.BlockedIp,
        SyncState.ServiceUnavailable,
        SyncState.VersionIncompatible,
        is SyncState.Error,
        -> LogLevel.WARN

        else -> LogLevel.INFO
    }

    fun fields(state: SyncState): Map<String, Any?> = mapOf(
        "state" to state.displayText,
        "busy" to state.busy,
        "category" to state::class.simpleName,
    )
}
