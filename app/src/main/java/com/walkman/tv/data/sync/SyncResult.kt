package com.walkman.tv.data.sync

/**
 * Outcome of a one-shot sync operation (test connection / sync now).
 *
 * Failures carry a [SyncState] instead of a raw exception so the UI can render a fixed,
 * translatable string. [logDetail] is diagnostics only - it is logged, never shown.
 */
sealed interface SyncResult {
    val ok: Boolean get() = this is Success

    data class Success(
        val serverId: String,
        val serverVersion: Int,
        val serverName: String?,
    ) : SyncResult

    data class Failure(
        val state: SyncState,
        val logDetail: String? = null,
    ) : SyncResult
}
