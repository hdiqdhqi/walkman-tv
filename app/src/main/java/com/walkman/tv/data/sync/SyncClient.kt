package com.walkman.tv.data.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Internal RPC failure (timeout / unsupported method / socket gone). Message is diagnostics only. */
internal class LxSyncRpcException(message: String) : Exception(message)

/**
 * Transport for the LX Music Sync protocol: HTTP `/hello` `/id` `/ah` authentication plus the
 * `message2call` WebSocket session (heartbeat + backoff reconnect + RPC request/response).
 *
 * Deliberately knows nothing about playlists or the library: [SyncManager] decides *what* to
 * sync, this class only moves protocol frames. Raw JSON never goes on the wire - everything
 * passes through [LxSyncCodec].
 */
open class SyncClient(
    private val httpClient: OkHttpClient,
    private val authStore: SyncAuthStore,
    private val scope: CoroutineScope,
) {
    /** Session lifecycle callbacks. Invoked on OkHttp's reader thread - keep them cheap. */
    interface SessionCallback {
        fun onState(state: SyncState)

        /** The server called `finished()`: the RPC surface is live. */
        fun onReady()
    }

    sealed interface AuthOutcome {
        data class Ok(
            val serverId: String,
            val serverVersion: Int,
            val keyInfo: SyncKeyInfo,
        ) : AuthOutcome

        /** [detail] is a human-readable, secret-free message for the settings UI. */
        data class Failed(val state: SyncState, val detail: String) : AuthOutcome
    }

    /**
     * Optional hook for the NEXT stage's `list_sync_*` handlers. Null (stage 3A) means those
     * calls are answered with an error - they only arrive if a client declares the feature.
     */
    var listSyncHandler: (suspend (path: List<String>, args: JsonArray) -> JsonElement?)? = null

    open val isReady: Boolean get() = ready

    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val rpcCounter = AtomicLong(0)

    /**
     * WebSocket transport, derived from the app-wide client so it shares its connection pool,
     * dispatcher and thread pools (building a fresh OkHttpClient would duplicate all three).
     *
     * Read/call timeouts are disabled here because they are tuned for short REST calls
     * (12s read / 25s call) whereas the server only pings every 30s: an idle-but-healthy
     * socket would otherwise be torn down by the read timeout. Liveness is enforced by our
     * own heartbeat below, and [CONNECT_TIMEOUT_MS] reproduces the official 2-minute connect
     * timeout that `callTimeout` would otherwise have provided.
     */
    private val socketClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null
    private var callback: SessionCallback? = null
    private var sessionConfig: SyncConfig? = null
    private var sessionKeyInfo: SyncKeyInfo? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var readySignal: CompletableDeferred<Boolean>? = null

    @Volatile private var reconnectEnabled = false
    @Volatile private var ready = false
    @Volatile private var socketOpened = false
    @Volatile private var lastPingAt = 0L
    private var failedAttempts = 0

    // ---------------------------------------------------------------- HTTP + authentication

    /**
     * `/hello` -> `/id` -> authenticate. Persists a fresh [SyncKeyInfo] on first-time code
     * auth, and reuses the stored key on every later connect (so the user only ever types the
     * connection code once).
     *
     * [onPhase] reports the coarse progress steps the settings UI shows (authenticating, …).
     */
    open suspend fun authenticate(config: SyncConfig, onPhase: (SyncState) -> Unit = {}): AuthOutcome {
        val startedAt = SystemClock.elapsedRealtime()

        val hello = try {
            httpGet(config.helloUrl)
        } catch (e: IOException) {
            Log.w(TAG, "hello unreachable: ${e.javaClass.simpleName}")
            return AuthOutcome.Failed(SyncState.ServiceUnavailable, "无法连接服务器")
        }
        Log.i(TAG, "hello -> ${hello.code} in ${SystemClock.elapsedRealtime() - startedAt}ms")

        if (hello.code != 200) {
            // The official server answers 401/404 for anything that is not /hello /id /ah,
            // which in practice means the user pasted the wrong address (or dropped /sync).
            val invalidAddress = hello.code == 404 || hello.code == 401
            return AuthOutcome.Failed(
                SyncState.ServiceUnavailable,
                if (invalidAddress) "服务地址无效（未找到 /hello）" else "服务不可用（HTTP ${hello.code}）",
            )
        }
        if (!LxSyncProtocol.isHelloOk(hello.body)) {
            if (!hello.body.startsWith(LxSyncProtocol.HELLO_PREFIX)) {
                Log.w(TAG, "hello unexpected body")
                return AuthOutcome.Failed(SyncState.ServiceUnavailable, "该地址不是 LX Music 同步服务")
            }
            val serverVersion = LxSyncProtocol.serverVersionFromHello(hello.body)
            Log.w(TAG, "hello version mismatch: v$serverVersion")
            val detail = when {
                serverVersion == null -> "服务版本不兼容"
                serverVersion > LxSyncProtocol.LOCAL_VERSION -> "服务版本过高（v$serverVersion），请升级本应用"
                else -> "服务版本过低（v$serverVersion），请升级同步服务端"
            }
            return AuthOutcome.Failed(SyncState.VersionIncompatible, detail)
        }

        val idResp = try {
            httpGet(config.idUrl)
        } catch (e: IOException) {
            Log.w(TAG, "id unreachable: ${e.javaClass.simpleName}")
            return AuthOutcome.Failed(SyncState.ServiceUnavailable, "无法连接服务器")
        }
        if (idResp.code != 200) {
            return AuthOutcome.Failed(SyncState.ServiceUnavailable, "服务不可用（HTTP ${idResp.code}）")
        }
        val serverId = LxSyncProtocol.parseServerId(idResp.body)
            ?: return AuthOutcome.Failed(SyncState.ServiceUnavailable, "服务地址无效（无法获取服务器 ID）")

        val serverVersion = LxSyncProtocol.serverVersionFromHello(hello.body) ?: LxSyncProtocol.LOCAL_VERSION
        onPhase(SyncState.Authenticating)
        val stored = authStore.get(serverId)
        if (stored != null) {
            when (val byKey = keyAuth(config, serverId, stored, serverVersion)) {
                is AuthOutcome.Ok -> return byKey
                is AuthOutcome.Failed -> {
                    // Only a credential rejection is worth retrying with the code; a network
                    // problem would fail identically and just costs an extra RSA round trip.
                    if (byKey.state == SyncState.ServiceUnavailable) return byKey
                    Log.w(TAG, "stored key rejected, falling back to the connection code")
                }
            }
        }

        if (!config.hasConnectionCode) {
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证信息已失效，请重新输入连接码")
        }
        return codeAuth(config, serverId, serverVersion)
    }

    /** First-time auth: RSA public key in the payload, RSA-OAEP encrypted JSON back. */
    private suspend fun codeAuth(config: SyncConfig, serverId: String, serverVersion: Int): AuthOutcome {
        // 2048-bit keygen is CPU bound (~100-300ms on TV hardware) - keep it off the main thread.
        val keyPair = withContext(Dispatchers.Default) { LxSyncProtocol.newRsaKeyPair() }
        val key = LxSyncProtocol.connectionCodeKey(config.connectionCode)
        val payload = LxSyncProtocol.firstAuthPayload(
            publicKeyBase64 = keyPair.publicKeyBase64,
            deviceName = LxSyncProtocol.deviceName(),
        )
        val encrypted = try {
            LxSyncProtocol.aesEncrypt(payload, key)
        } catch (e: LxSyncCryptoException) {
            Log.w(TAG, "code auth encrypt failed")
            return AuthOutcome.Failed(SyncState.Error("同步失败：加密不可用"), e.message ?: "crypto error")
        }

        val response = try {
            httpGet(config.ahUrl, mapOf(HEADER_M to encrypted))
        } catch (e: IOException) {
            Log.w(TAG, "ah unreachable: ${e.javaClass.simpleName}")
            return AuthOutcome.Failed(SyncState.ServiceUnavailable, "无法连接服务器")
        }
        val body = response.body.trim()
        if (body == LxSyncProtocol.MSG_BLOCKED_IP || response.code == 403) {
            Log.w(TAG, "ah blocked ($response.code)")
            return AuthOutcome.Failed(SyncState.BlockedIp, "IP 被临时封禁，请稍后再试")
        }
        if (response.code != 200 || body == LxSyncProtocol.MSG_AUTH_FAILED) {
            Log.w(TAG, "ah rejected ($response.code)")
            return AuthOutcome.Failed(SyncState.AuthFailed, "连接码错误")
        }

        val plain = try {
            LxSyncProtocol.rsaDecrypt(body, keyPair.privateKeyBase64)
        } catch (e: LxSyncCryptoException) {
            Log.w(TAG, "ah payload decrypt failed")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证响应无法解密")
        }
        val info = try {
            json.decodeFromString(SyncKeyInfo.serializer(), plain)
        } catch (e: Exception) {
            Log.w(TAG, "ah payload malformed: ${e.javaClass.simpleName}")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证响应格式错误")
        }

        val stored = info.copy(
            deviceName = LxSyncProtocol.deviceName(),
            lastConnectDate = System.currentTimeMillis(),
        )
        authStore.put(serverId, stored)
        Log.i(TAG, "code auth ok (server v$serverVersion)")
        return AuthOutcome.Ok(serverId, serverVersion, stored)
    }

    /** Subsequent auth: `i: clientId` + AES(`lx-music auth::<deviceName>`), answer is AES(hello). */
    private suspend fun keyAuth(
        config: SyncConfig,
        serverId: String,
        keyInfo: SyncKeyInfo,
        serverVersion: Int,
    ): AuthOutcome {
        val encrypted = try {
            LxSyncProtocol.aesEncrypt(LxSyncProtocol.keyAuthPayload(LxSyncProtocol.deviceName()), keyInfo.key)
        } catch (e: LxSyncCryptoException) {
            Log.w(TAG, "key auth encrypt failed")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证信息已损坏，请重新输入连接码")
        }

        val response = try {
            httpGet(config.ahUrl, mapOf(HEADER_I to keyInfo.clientId, HEADER_M to encrypted))
        } catch (e: IOException) {
            Log.w(TAG, "ah unreachable: ${e.javaClass.simpleName}")
            return AuthOutcome.Failed(SyncState.ServiceUnavailable, "无法连接服务器")
        }
        val body = response.body.trim()
        if (body == LxSyncProtocol.MSG_BLOCKED_IP || response.code == 403) {
            Log.w(TAG, "ah blocked ($response.code)")
            return AuthOutcome.Failed(SyncState.BlockedIp, "IP 被临时封禁，请稍后再试")
        }
        if (response.code != 200) {
            Log.w(TAG, "key auth rejected ($response.code)")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证信息已失效，请重新输入连接码")
        }
        val plain = try {
            LxSyncProtocol.aesDecrypt(body, keyInfo.key)
        } catch (e: LxSyncCryptoException) {
            Log.w(TAG, "key auth answer decrypt failed")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证信息已失效，请重新输入连接码")
        }
        if (plain != LxSyncProtocol.HELLO_MSG) {
            Log.w(TAG, "key auth answer mismatch")
            return AuthOutcome.Failed(SyncState.AuthFailed, "认证信息已失效，请重新输入连接码")
        }

        val refreshed = keyInfo.copy(
            deviceName = LxSyncProtocol.deviceName(),
            lastConnectDate = System.currentTimeMillis(),
        )
        authStore.put(serverId, refreshed)
        Log.i(TAG, "key auth ok (server v$serverVersion)")
        return AuthOutcome.Ok(serverId, serverVersion, refreshed)
    }

    // ------------------------------------------------------------------------ WebSocket session

    /** Opens (and keeps re-opening) a session. Replaces any session that is already running. */
    open fun openSession(config: SyncConfig, keyInfo: SyncKeyInfo, callback: SessionCallback) {
        closeSession()
        this.callback = callback
        sessionConfig = config
        sessionKeyInfo = keyInfo
        failedAttempts = 0
        reconnectEnabled = true
        readySignal = CompletableDeferred()
        callback.onState(SyncState.Connecting)
        openSocket(config, keyInfo)
    }

    /** Closes the socket and stops reconnecting. Safe to call when nothing is running. */
    open fun closeSession() {
        reconnectEnabled = false
        connectJob?.cancel(); connectJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        reconnectJob?.cancel(); reconnectJob = null
        readySignal?.complete(false)
        readySignal = null
        ready = false
        socketOpened = false
        val socket = webSocket
        webSocket = null
        callback = null
        failAllPending("session closed")
        socket?.close(LxSyncProtocol.CLOSE_NORMAL, null)
    }

    /** True once the server called `finished()`, false on timeout or session loss. */
    open suspend fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { readySignal?.await() ?: false } ?: false

    /**
     * message2call request. Only used from the next stage onward; shipped now so the RPC
     * transport is complete and exercised by `finished()`/`getEnabledFeatures`.
     */
    open suspend fun call(path: String, args: List<JsonElement> = emptyList()): JsonElement? {
        val socket = webSocket ?: throw LxSyncRpcException("not connected")
        val eventName = "${path}__${rpcCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement?>()
        pending[eventName] = deferred
        val payload = json.encodeToString(
            LxSyncMessage.serializer(),
            LxSyncMessage(name = eventName, path = path.split('.'), data = JsonArray(args)),
        )
        if (!socket.send(LxSyncCodec.encode(payload))) {
            pending.remove(eventName)
            throw LxSyncRpcException("send failed: $path")
        }
        return try {
            withTimeout(RPC_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw LxSyncRpcException("rpc timeout: $path")
        } finally {
            pending.remove(eventName)
        }
    }

    private fun openSocket(config: SyncConfig, keyInfo: SyncKeyInfo) {
        val token = try {
            LxSyncProtocol.aesEncrypt(LxSyncProtocol.MSG_CONNECT, keyInfo.key)
        } catch (e: LxSyncCryptoException) {
            Log.w(TAG, "socket token failed")
            onSessionGone(null)
            return
        }
        val url = "${config.socketUrl}?i=${urlEncode(keyInfo.clientId)}&t=${urlEncode(token)}"
        socketOpened = false
        ready = false
        lastPingAt = SystemClock.elapsedRealtime()

        val socket = socketClient.newWebSocket(Request.Builder().url(url).build(), listener)
        webSocket = socket
        Log.i(TAG, "socket connecting (${config.webSocketBase})")

        connectJob?.cancel()
        connectJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!socketOpened && webSocket === socket) {
                Log.w(TAG, "socket connect timeout")
                socket.cancel()
            }
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(PING_CHECK_INTERVAL_MS)
                if (!reconnectEnabled || webSocket !== socket) break
                if (SystemClock.elapsedRealtime() - lastPingAt > PING_TIMEOUT_MS) {
                    Log.w(TAG, "ping timeout")
                    socket.cancel()
                    break
                }
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (this@SyncClient.webSocket !== webSocket) return
            Log.i(TAG, "socket open")
            socketOpened = true
            lastPingAt = SystemClock.elapsedRealtime()
            connectJob?.cancel()
            connectJob = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (this@SyncClient.webSocket !== webSocket) return
            if (text == PING_TEXT) {
                lastPingAt = SystemClock.elapsedRealtime()
                return
            }
            handleIncoming(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@SyncClient.webSocket !== webSocket) return
            Log.i(TAG, "socket closed code=$code")
            onSessionGone(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@SyncClient.webSocket !== webSocket) return
            Log.w(TAG, "socket failure: ${t.javaClass.simpleName} ${sanitize(t.message)}")
            onSessionGone(null)
        }
    }

    /** Runs on OkHttp's reader thread - must never block. */
    private fun handleIncoming(text: String) {
        val parsed = try {
            json.decodeFromString(LxSyncMessage.serializer(), LxSyncCodec.decode(text))
        } catch (e: Exception) {
            Log.w(TAG, "undecodable message: ${e.javaClass.simpleName}")
            return
        }
        if (parsed.isIncomingCall) {
            val name = parsed.name ?: return
            val path = parsed.path.orEmpty()
            scope.launch { respondToCall(name, path, parsed.data) }
            return
        }
        // ConcurrentHashMap.remove(null) throws, so resolve the name first.
        val replyName = parsed.name ?: return
        val deferred = pending.remove(replyName) ?: return
        val error = parsed.error
        if (error != null) {
            deferred.completeExceptionally(LxSyncRpcException(error))
        } else {
            deferred.complete(parsed.data)
        }
    }

    private suspend fun respondToCall(name: String, path: List<String>, args: JsonElement?) {
        val label = path.joinToString(".")
        val result = try {
            dispatch(path, args as? JsonArray ?: JsonArray(emptyList()))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "rpc $label -> error (${e.javaClass.simpleName})")
            send(LxSyncMessage(name = name, error = e.message ?: "error"))
            return
        }
        send(LxSyncMessage(name = name, error = null, data = result))
    }

    private suspend fun dispatch(path: List<String>, args: JsonArray): JsonElement? =
        when (val method = path.joinToString(".")) {
            "getEnabledFeatures" -> enabledFeatures(args)
            "finished" -> {
                markReady()
                null
            }
            else -> listSyncHandler?.invoke(path, args)
                ?: throw LxSyncRpcException("$method: not supported in this version")
        }

    /**
     * Feature negotiation. The official client enables a feature only when the server's feature
     * version equals ours, and always asks for snapshot-based merging (`skipSnapshot = false`) -
     * this client relies on the server's 3-way merge for deletion semantics.
     * `dislike` is not implemented, so it is deliberately left out.
     */
    private fun enabledFeatures(args: JsonArray): JsonElement {
        val versions = args.getOrNull(1) as? JsonObject
        val listVersion = (versions?.get("list") as? JsonPrimitive)?.content
        val supported = listVersion?.toIntOrNull() == LIST_FEATURE_VERSION
        Log.i(
            TAG,
            "server features: list=$listVersion (declared: ${if (supported) "list skipSnapshot=false" else "none"})",
        )
        return buildJsonObject {
            if (supported) {
                put("list", buildJsonObject { put("skipSnapshot", JsonPrimitive(false)) })
            }
        }
    }

    private fun markReady() {
        if (ready) return
        ready = true
        failedAttempts = 0
        connectJob?.cancel()
        connectJob = null
        Log.i(TAG, "session ready")
        readySignal?.complete(true)
        callback?.onReady()
    }

    private fun onSessionGone(code: Int?) {
        connectJob?.cancel(); connectJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        ready = false
        socketOpened = false
        webSocket = null
        failAllPending("socket gone")

        if (!reconnectEnabled) {
            callback?.onState(SyncState.Disconnected)
            return
        }
        if (code == LxSyncProtocol.CLOSE_NORMAL || code == LxSyncProtocol.CLOSE_FAILED) {
            // Official protocol: both codes are terminal. 1000 = another client with the same
            // clientId took over; 4100 = the server rejected our credentials during upgrade.
            Log.i(TAG, "session ended by server (code=$code)")
            reconnectEnabled = false
            readySignal?.complete(false)
            callback?.onState(
                if (code == LxSyncProtocol.CLOSE_FAILED) SyncState.AuthFailed else SyncState.Disconnected,
            )
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val config = sessionConfig ?: return
        val keyInfo = sessionKeyInfo ?: return
        failedAttempts += 1
        if (failedAttempts > MAX_RECONNECT_ATTEMPTS) {
            failedAttempts = 0
            reconnectEnabled = false
            readySignal?.complete(false)
            Log.w(TAG, "giving up after $MAX_RECONNECT_ATTEMPTS reconnect attempts")
            callback?.onState(SyncState.ServiceUnavailable)
            return
        }
        val waitMs = minOf(2000L + (failedAttempts / 2) * 3000L, 30_000L)
        Log.i(TAG, "reconnect #$failedAttempts in ${waitMs}ms")
        callback?.onState(SyncState.Connecting)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!reconnectEnabled) return@launch
            openSocket(config, keyInfo)
        }
    }

    private fun send(message: LxSyncMessage) {
        val socket = webSocket ?: return
        socket.send(LxSyncCodec.encode(json.encodeToString(LxSyncMessage.serializer(), message)))
    }

    private fun failAllPending(reason: String) {
        if (pending.isEmpty()) return
        val inflight = pending.values.toList()
        pending.clear()
        for (deferred in inflight) deferred.completeExceptionally(LxSyncRpcException(reason))
    }

    // ---------------------------------------------------------------------------------- helpers

    private data class HttpResponse(val code: Int, val body: String)

    private suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val builder = Request.Builder().url(url).get()
        for ((name, value) in headers) builder.header(name, value)
        val call = httpClient.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { HttpResponse(it.code, it.body?.string().orEmpty()) }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Strips anything query-like before a message reaches logcat, so the socket URL (which
     * carries the AES-encrypted `t=` token and the clientId `i=`) can never leak.
     */
    private fun sanitize(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        return message.substringBefore('?').take(120)
    }

    internal companion object {
        const val TAG = "LXSync"

        /** Official `msgConnect`-adjacent liveness signal: the server sends text `ping`. */
        const val PING_TEXT = "ping"

        /** Official: 30s server ping interval + 1s slack. */
        const val PING_TIMEOUT_MS = 30_000L + 1000L
        const val PING_CHECK_INTERVAL_MS = 5_000L

        /** Official: 2 minutes to bring the socket up. */
        const val CONNECT_TIMEOUT_MS = 2 * 60 * 1000L

        /** Official: 120s per message2call invocation. */
        const val RPC_TIMEOUT_MS = 120 * 1000L

        /** Official: max 100000 reconnect attempts. */
        const val MAX_RECONNECT_ATTEMPTS = 100_000

        const val READY_TIMEOUT_MS = 30_000L

        /** Official eatureVersion.list this client speaks. */
        const val LIST_FEATURE_VERSION = 1

        const val HEADER_I = "i"
        const val HEADER_M = "m"
    }
}
