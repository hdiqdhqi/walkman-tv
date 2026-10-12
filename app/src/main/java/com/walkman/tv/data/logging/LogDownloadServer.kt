package com.walkman.tv.data.logging

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * Temporary, LAN-only HTTP server that lets a phone download the running-log ZIP (spec §12-§18,
 * §25-§27).
 *
 * It is only started when the user taps 扫码下载日志, binds to this device's LAN IPv4 on a random
 * ephemeral port, and is torn down again after [ttlMs], when the user leaves the page, or when the
 * app is destroyed. Security model:
 *  - every request must carry the one-shot [LogAccessToken] in the path, anything else is 401;
 *  - GET only - no uploads, no directory listing, no raw file paths;
 *  - the token dies with the server, so an old QR code / link stops working immediately.
 */
class LogDownloadServer private constructor(
    private val hostName: String,
    private val ttlMs: Long,
    private val token: LogAccessToken,
    private val clock: () -> Long,
    private val zip: (Long?) -> ByteArray,
    private val onStopped: () -> Unit,
) : NanoHTTPD(hostName, 0) {

    @Volatile private var expiryThread: Thread? = null
    @Volatile private var stopped = false

    /** Actual port chosen by the OS; valid once [start] returned non-null. */
    val boundPort: Int get() = listeningPort

    /** `http://<lan-ip>:<port>/log/<token>` - the QR payload. */
    fun pageUrl(): String = "http://" + hostName + ":" + boundPort + "/log/" + token.current().orEmpty()

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method != Method.GET) {
                text(Response.Status.METHOD_NOT_ALLOWED, "Method Not Allowed")
            } else {
                val parts = session.uri.split('/').filter { it.isNotEmpty() }
                when {
                    parts.isEmpty() || parts[0] != "log" ->
                        text(Response.Status.NOT_FOUND, "Not Found")

                    // Missing / wrong / expired / already-invalidated token -> no page, no list.
                    !token.isValid(parts.getOrNull(1), clock()) ->
                        text(Response.Status.UNAUTHORIZED, "Unauthorized")

                    parts.size == 2 -> html(page(parts[1]))
                    parts.size == 3 && parts[2] == "download" -> download(session)
                    else -> text(Response.Status.NOT_FOUND, "Not Found")
                }
            }
        } catch (t: Throwable) {
            text(Response.Status.INTERNAL_ERROR, t.message ?: "error")
        }
    }

    private fun download(session: IHTTPSession): Response {
        val rawSince = session.parameters["since"]?.firstOrNull()
        val windowMs = if (rawSince == "30m" || rawSince == "30min") SINCE_30M_MS else null
        val sinceMs = windowMs?.let { clock() - it }
        val bytes = zip(sinceMs)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/zip",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        response.addHeader(
            "Content-Disposition",
            "attachment; filename=\"" + LogExport.zipFileName(clock()) + "\"",
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun page(tokenValue: String): String {
        val safe = escapeHtml(tokenValue)
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>运行日志 · 随便听</title>$PAGE_CSS</head>
            <body>
              <h2>运行日志</h2>
              <p>下载电视上的运行日志（ZIP，包含 logs/ 日志文件与 device.txt 设备信息）。</p>
              <div class="card"><a class="btn" href="/log/$safe/download">下载全部日志</a></div>
              <div class="card"><a class="btn" href="/log/$safe/download?since=30m">下载最近 30 分钟</a></div>
              <p class="hint">链接仅在本次扫码后有效，关闭页面或 5 分钟后自动失效。</p>
            </body></html>
        """.trimIndent()
    }

    private fun html(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    /**
     * Idempotent teardown: close the socket, stop the expiry timer, invalidate the token so every
     * already-handed-out URL dies with it (spec §27).
     */
    fun stopNow() {
        if (stopped) return
        stopped = true
        runCatching { stop() }
        expiryThread?.interrupt()
        expiryThread = null
        token.invalidate()
        runCatching { onStopped() }
    }

    private fun scheduleExpiry() {
        val thread = Thread({
            try {
                Thread.sleep(ttlMs)
            } catch (e: InterruptedException) {
                return@Thread
            }
            stopNow()
        }, "walkman-logshare-ttl").apply {
            isDaemon = true
            start()
        }
        expiryThread = thread
    }

    companion object {
        /** Auto-expiry for the whole server + token (spec §13/§14). */
        const val DEFAULT_TTL_MS = 5 * 60_000L
        const val SINCE_30M_MS = 30 * 60_000L

        /**
         * Bind to [host] (the device LAN IPv4) on a random free port and issue a fresh token.
         * Returns null when the socket cannot be bound.
         */
        fun start(
            host: String,
            zip: (Long?) -> ByteArray,
            ttlMs: Long = DEFAULT_TTL_MS,
            token: LogAccessToken = LogAccessToken(),
            clock: () -> Long = System::currentTimeMillis,
            onStopped: () -> Unit = {},
        ): LogDownloadServer? {
            val server = LogDownloadServer(host, ttlMs, token, clock, zip, onStopped)
            token.issue(clock(), ttlMs)
            val started = runCatching {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            }.isSuccess
            if (!started) {
                token.invalidate()
                return null
            }
            server.scheduleExpiry()
            return server
        }

        private val PAGE_CSS = """
            <style>
              body{margin:0;padding:24px;font-family:-apple-system,system-ui,sans-serif;
                   background:#0A0D14;color:#fff;}
              h2{margin:0 0 16px;font-weight:700;color:#4ADE80;}
              p{color:#a0a0b0;margin:0 0 18px;line-height:1.5;font-size:14px;}
              .hint{color:#666;font-size:12px;}
              .card{background:#14171F;border-radius:12px;padding:4px;margin-bottom:14px;}
              .btn{display:block;padding:16px;text-align:center;color:#4ADE80;
                   font-size:16px;font-weight:700;text-decoration:none;}
            </style>
        """.trimIndent()

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }
}
