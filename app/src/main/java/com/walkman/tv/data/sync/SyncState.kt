package com.walkman.tv.data.sync

/**
 * Human-facing connection state of the LX Music sync module.
 *
 * Deliberately a sealed hierarchy instead of an enum so later stages can attach payloads
 * (progress, list counts, …) without breaking the `when` sites in the settings UI.
 * [displayText] is the ONLY thing the UI may render - never an exception message, never the
 * connection code, never the AES key.
 */
sealed interface SyncState {
    val displayText: String

    /** True while a connect / auth / sync attempt is in flight; the UI disables its buttons. */
    val busy: Boolean get() = false

    /** Sync switched off in settings. */
    data object Disabled : SyncState {
        override val displayText: String = "未启用"
    }

    data object Disconnected : SyncState {
        override val displayText: String = "未连接"
    }

    data object Connecting : SyncState {
        override val displayText: String = "连接中"
        override val busy: Boolean = true
    }

    data object Authenticating : SyncState {
        override val displayText: String = "认证中"
        override val busy: Boolean = true
    }

    data object Connected : SyncState {
        override val displayText: String = "已连接"
    }

    data object Syncing : SyncState {
        override val displayText: String = "同步中"
        override val busy: Boolean = true
    }

    /** Reserved for a completed list sync - stage 3A never reaches it (see [SyncManager]). */
    data object Success : SyncState {
        override val displayText: String = "同步完成"
    }

    /**
     * The server asked for a sync mode but the user has never chosen one, and the sync was a
     * background (auto) run so no dialog could be shown. Nothing was written; opening Settings /
     * tapping 立即同步 shows the picker.
     */
    data object NeedSyncMode : SyncState {
        override val displayText: String = "需要选择同步方式"
    }

    /** Wrong connection code (server answered 401 "Auth failed"). */
    data object AuthFailed : SyncState {
        override val displayText: String = "认证失败"
    }

    /** Server answered 403 "Blocked IP". */
    data object BlockedIp : SyncState {
        override val displayText: String = "IP 暂时被封禁"
    }

    /** DNS / TCP / TLS failure, or a non-200 answer from /hello or /id. */
    data object ServiceUnavailable : SyncState {
        override val displayText: String = "连接失败"
    }

    /** The server speaks a different sync protocol version than this client. */
    data object VersionIncompatible : SyncState {
        override val displayText: String = "服务版本不兼容"
    }

    /** Catch-all. [message] must already be human readable and free of secrets. */
    data class Error(val message: String = "同步失败") : SyncState {
        override val displayText: String = message
    }
}
