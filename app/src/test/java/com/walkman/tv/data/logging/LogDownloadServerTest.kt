package com.walkman.tv.data.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LogDownloadServerTest {

    private var server: LogDownloadServer? = null

    @After
    fun tearDown() {
        server?.stopNow()
        server = null
    }

    private fun get(url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3_000
        c.readTimeout = 3_000
        return c
    }

    private fun startServer(token: LogAccessToken = LogAccessToken(), ttlMs: Long = 60_000L): LogDownloadServer {
        val s = LogDownloadServer.start(
            "127.0.0.1",
            zip = { "ZIPBYTES".toByteArray() },
            ttlMs = ttlMs,
            token = token,
        )
        assertNotNull(s)
        server = s
        return s!!
    }

    private fun base(s: LogDownloadServer, token: LogAccessToken): String =
        "http://127.0.0.1:" + s.boundPort + "/log/" + token.current()

    @Test
    fun validTokenServesPageAndZip() {
        val token = LogAccessToken()
        val s = startServer(token)
        val page = get(base(s, token))
        assertEquals(200, page.responseCode)
        val html = page.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html.contains("/download"))
        page.disconnect()

        val zip = get(base(s, token) + "/download")
        assertEquals(200, zip.responseCode)
        assertEquals("ZIPBYTES", zip.inputStream.readBytes().toString(Charsets.UTF_8))
        zip.disconnect()
    }

    @Test
    fun wrongTokenIsUnauthorizedAndLeaksNothing() {
        val s = startServer()
        val c = get("http://127.0.0.1:" + s.boundPort + "/log/deadbeefdeadbeefdeadbeef")
        assertEquals(401, c.responseCode)
        val body = c.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.length < 200)
    }

    @Test
    fun missingTokenIsUnauthorized() {
        val s = startServer()
        assertEquals(401, get("http://127.0.0.1:" + s.boundPort + "/log").responseCode)
    }

    @Test
    fun nonLogPathIsNotFound() {
        val s = startServer()
        assertEquals(404, get("http://127.0.0.1:" + s.boundPort + "/files").responseCode)
        assertEquals(404, get("http://127.0.0.1:" + s.boundPort + "/").responseCode)
    }

    @Test
    fun postIsRejected() {
        val token = LogAccessToken()
        val s = startServer(token)
        assertEquals(405, get(base(s, token), "POST").responseCode)
    }

    @Test
    fun since30mSelectsTheWindow() {
        var seen: Long? = -1L
        val token = LogAccessToken()
        val s = LogDownloadServer.start(
            "127.0.0.1",
            zip = { since -> seen = since; ByteArray(1) },
            token = token,
        )!!
        server = s
        val c = get(base(s, token) + "/download?since=30m")
        assertEquals(200, c.responseCode)
        c.inputStream.readBytes()
        assertNotNull(seen)
        val expected = System.currentTimeMillis() - 30 * 60_000L
        assertTrue(Math.abs(seen!! - expected) < 60_000L)
    }

    @Test
    fun allDownloadHasNoSinceFilter() {
        var seen: Long? = -1L
        val token = LogAccessToken()
        val s = LogDownloadServer.start(
            "127.0.0.1",
            zip = { since -> seen = since; ByteArray(1) },
            token = token,
        )!!
        server = s
        get(base(s, token) + "/download").inputStream.readBytes()
        assertNull(seen)
    }

    @Test
    fun serverAutoClosesAndTokenDies() {
        val token = LogAccessToken()
        val s = LogDownloadServer.start(
            "127.0.0.1",
            zip = { ByteArray(0) },
            ttlMs = 250L,
            token = token,
        )!!
        val url = base(s, token)
        Thread.sleep(700L)
        assertNull(token.current())
        val code = runCatching { get(url).responseCode }.getOrNull()
        assertTrue(code == null || code == 401 || code == 404)
    }

    @Test
    fun manualStopInvalidatesToken() {
        val token = LogAccessToken()
        val s = startServer(token)
        val url = base(s, token)
        s.stopNow()
        assertNull(token.current())
        val code = runCatching { get(url).responseCode }.getOrNull()
        assertTrue(code == null || code == 401 || code == 404)
    }

    @Test
    fun stopIsIdempotent() {
        val s = startServer()
        s.stopNow()
        s.stopNow()
    }
}
