package com.walkman.tv.data.sync

import com.walkman.tv.data.store.Settings

/**
 * Immutable, normalized view of the LX Music sync settings.
 *
 * The whole protocol is addressed by URL suffix, and the server routes on the LAST path
 * segment (`/hello`, `/id`, `/ah`), so a server hosted under a path prefix such as
 * `https://c.wwwweb.top/sync` must keep that prefix on every derived endpoint. Everything
 * below therefore string-appends to [serverUrl] instead of replacing its path.
 *
 * NOTE: this object holds the connection code - never log it wholesale.
 */
data class SyncConfig(
    /** Normalized origin + path prefix, no trailing slash, e.g. `https://c.wwwweb.top/sync`. */
    val serverUrl: String,
    val connectionCode: String,
    val autoSync: Boolean,
) {
    val helloUrl: String get() = "$serverUrl/hello"
    val idUrl: String get() = "$serverUrl/id"
    val ahUrl: String get() = "$serverUrl/ah"

    /** `https://` -> `wss://`, `http://` -> `ws://`, path prefix preserved. */
    val webSocketBase: String
        get() = if (serverUrl.startsWith("https://")) {
            "wss://" + serverUrl.removePrefix("https://")
        } else {
            "ws://" + serverUrl.removePrefix("http://")
        }

    val socketUrl: String get() = "$webSocketBase/socket"

    val hasConnectionCode: Boolean get() = connectionCode.isNotBlank()

    companion object {
        /** Projects the persisted settings, or null when no usable address has been entered. */
        fun from(settings: Settings): SyncConfig? {
            val url = normalizeServerUrl(settings.syncServerUrl) ?: return null
            return SyncConfig(
                serverUrl = url,
                connectionCode = settings.syncConnectionCode.trim(),
                autoSync = settings.syncAutoSync,
            )
        }

        /**
         * Accepts what users actually type/paste and returns the canonical base, or null when
         * it cannot possibly be an LX Sync server address:
         *
         * ```
         * c.wwwweb.top/sync               -> https://c.wwwweb.top/sync  (scheme defaults to https)
         * https://c.wwwweb.top/sync/      -> https://c.wwwweb.top/sync
         * https://c.wwwweb.top/sync/hello -> https://c.wwwweb.top/sync  (endpoint suffix trimmed)
         * ftp://host/sync                 -> null
         * ```
         */
        fun normalizeServerUrl(raw: String): String? {
            var text = raw.trim().replace(Regex("\\s+"), "")
            if (text.isEmpty()) return null

            if (!text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true)
            ) {
                // A bare `host/path` is the common paste; anything else with a scheme is junk.
                if (text.contains("://")) return null
                text = "https://$text"
            }

            val schemeEnd = text.indexOf("://")
            val scheme = text.substring(0, schemeEnd).lowercase()
            var rest = text.substring(schemeEnd + 3)
            while (rest.endsWith("/")) rest = rest.dropLast(1)

            // Tolerate pasting a full endpoint instead of the base address.
            for (suffix in listOf("/hello", "/id", "/ah", "/socket")) {
                if (rest.endsWith(suffix)) rest = rest.dropLast(suffix.length)
            }
            while (rest.endsWith("/")) rest = rest.dropLast(1)

            if (rest.isEmpty() || rest.startsWith("/")) return null
            if (rest.substringBefore('/').isEmpty()) return null
            if (rest.contains('#') || rest.contains('?')) return null
            return "$scheme://$rest"
        }
    }
}
