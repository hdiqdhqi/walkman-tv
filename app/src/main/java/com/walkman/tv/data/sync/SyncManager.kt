package com.walkman.tv.data.sync

import android.util.Log
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.Settings
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the sync lifecycle: when to connect, what the user-visible state is, and making sure only
 * one connect/auth/sync task runs at a time. The UI talks to this class only - it never touches
 * the WebSocket, AES, RSA or the library store.
 *
 * The LX Sync Server drives the whole list exchange: once the session is up it calls
 * `getEnabledFeatures`, `list_sync_get_md5`, `list_sync_get_sync_mode`, `list_sync_get_list_data`,
 * `list_sync_set_list_data` and finally `list_sync_finished` on *us*. [LxListSyncService] answers
 * those, [LxSyncSession]/[LxListMerge] decide what may be written, [SyncRepository]/[SyncApplier] do
 * the mechanical data work, and [LxListPusher] sends this device's own edits back as the official
 * `onListSyncAction` calls - the direction that makes the exchange truly two-way. The server's
 * snapshot merge (which we opted into with `skipSnapshot = false`) keeps doing its half.
 */
class SyncManager(
    private val settingsStore: SettingsStore,
    private val client: SyncClient,
    private val libraryStore: LibraryStore,
    private val scope: CoroutineScope,
    /** Where the last agreed state is kept between sessions (see [SyncBaseline]). */
    private val baselineStore: SyncBaselineStore,
) {
    private val repository = SyncRepository(libraryStore)
    private val applier = SyncApplier(libraryStore, repository)
    private val listSync = LxListSyncService(repository, applier) { requestSyncMode(interactiveRun) }

    /**
     * Delivers this device's own edits as official `onListSyncAction` calls while a session is up.
     * Only usable once the server reported `list_sync_finished` for this session - the same gate the
     * official clients use (`moduleReadys.list`).
     */
    private val pusher = LxListPusher(
        libraryStore = libraryStore,
        baselineStore = baselineStore,
        isLive = { listSync.moduleReady && client.isReady },
        send = { action -> client.call(LxListAction.PUSH_METHOD, listOf(LxListAction.encode(action))) },
    )

    private val _state = MutableStateFlow<SyncState>(SyncState.Disconnected)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Human-readable result of the last manual action. Never contains secrets. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** e.g. `服务版本：v4 · 服务器：我的服务器`. */
    private val _serverInfo = MutableStateFlow<String?>(null)
    val serverInfo: StateFlow<String?> = _serverInfo.asStateFlow()

    /** Non-null while the server waits for the user to pick a list sync mode. */
    private val _syncModeRequest = MutableStateFlow<SyncModeRequest?>(null)
    val syncModeRequest: StateFlow<SyncModeRequest?> = _syncModeRequest.asStateFlow()

    /** [manual] = opened from Settings (store only); false = the server is waiting for an answer. */
    data class SyncModeRequest(val serverName: String?, val manual: Boolean)

    /** Single-flight guard: pressing 立即同步 repeatedly must not start parallel tasks, and
     *  auto-sync can never run next to a manual sync. */
    private val gate = Mutex()
    private var settingsJob: Job? = null
    private var autoSyncJob: Job? = null

    private var pendingMode: CompletableDeferred<String>? = null

    /** True for a user-triggered sync (立即同步 / 测试连接), false for auto-sync. */
    @Volatile private var interactiveRun = false

    /** Set when the server asked for a mode but the user has never chosen one. */
    @Volatile private var needsModeChoice = false

    private var activeConfig: SyncConfig? = null
    private var activeServerId: String? = null
    private var activeServerVersion: Int = 0
    private var activeServerName: String? = null

    private val sessionCallback = object : SyncClient.SessionCallback {
        override fun onState(state: SyncState) {
            _state.value = state
        }

        override fun onReady() {
            // While a sync is running, runSync() owns the terminal state - don't let the
            // "session is up" callback overwrite 同步中/同步完成 with 已连接.
            if (_state.value is SyncState.Syncing) return
            _state.value = SyncState.Connected
            if (_message.value == null) _message.value = "已连接"
            Log.i(SyncClient.TAG, "state -> Connected")
        }
    }

    init {
        client.listSyncHandler = { path, args -> listSync.handle(path.joinToString("."), args) }
    }

    /**
     * Starts observing settings: sync turns off -> tear the session down; it turns on with
     * 自动同步 -> sync once in the background. Called once from `AppContainer.bootstrap()`.
     */
    fun start() {
        if (settingsJob != null) return
        settingsJob = scope.launch {
            settingsStore.settings
                .map { SyncSettings.of(it) }
                .distinctUntilChanged()
                .collect { settings ->
                    if (!settings.syncEnabled) {
                        endSession()
                        activeConfig = null
                        _state.value = SyncState.Disabled
                        _message.value = null
                        _serverInfo.value = null
                    } else if (settings.autoSync && currentConfig() != null) {
                        // Official behaviour: sync when the client comes up / is enabled. Never
                        // blocks the UI (this runs in appScope) and never fails app startup.
                        gate.withLock { runSyncGuarded() }
                    } else if (_state.value !is SyncState.Disabled) {
                        _state.value = SyncState.Disconnected
                    }
                }
        }
        observeLocalChanges()
    }

    /** `/hello` + `/id` + authenticate, no WebSocket. Result feeds the 状态/服务器 rows. */
    suspend fun testConnection(): SyncResult {
        if (!gate.tryLock()) return busy()
        return try {
            val config = currentConfig() ?: return missingAddress()
            activeConfig = null
            closeSession()
            _state.value = SyncState.Connecting
            when (val auth = client.authenticate(config) { _state.value = it }) {
                is SyncClient.AuthOutcome.Failed -> fail(auth.state, auth.detail)
                is SyncClient.AuthOutcome.Ok -> {
                    rememberAuth(config, auth)
                    _state.value = SyncState.Connected
                    _message.value = "连接成功"
                    Log.i(SyncClient.TAG, "test connection ok (v${auth.serverVersion})")
                    SyncResult.Success(auth.serverId, auth.serverVersion, auth.keyInfo.serverName)
                }
            }
        } finally {
            gate.unlock()
        }
    }

    /**
     * One full list sync: open a fresh session (the server syncs on connection), let the server
     * drive the protocol, then wait for `finished()`. Manual 立即同步 and auto-sync both end up
     * here - there is no second code path.
     */
    suspend fun syncNow(): SyncResult {
        if (!gate.tryLock()) return busy()
        return try {
            runSync(interactive = true)
        } finally {
            gate.unlock()
        }
    }

    /** Explicit teardown; also used when the user switches sync off. */
    fun disconnect() {
        endSession()
        activeConfig = null
        _state.value = if (settingsStore.settings.value.syncEnabled) {
            SyncState.Disconnected
        } else {
            SyncState.Disabled
        }
    }

    /**
     * Opens the mode picker from Settings. Purely a preference change - it never starts a sync, and
     * it is ignored while a sync is already waiting for an answer (that dialog is authoritative).
     */
    fun requestSyncModeChange() {
        if (pendingMode != null || _syncModeRequest.value != null) return
        _syncModeRequest.value = SyncModeRequest(activeServerName, manual = true)
    }

    /**
     * The UI answers the mode question. During a sync this resumes the server call; opened from
     * Settings it only stores the new default, which applies from the next sync on (a mode change
     * never mutates a sync that is already running).
     */
    fun chooseSyncMode(mode: String) {
        val deferred = pendingMode
        if (deferred != null) {
            pendingMode = null
            _syncModeRequest.value = null
            deferred.complete(mode)
            return
        }
        _syncModeRequest.value = null
        if (LxSyncProtocol.isSelectableSyncMode(mode)) {
            _message.value = "同步方式已更新，将在下次同步时使用"
            scope.launch { persistSyncMode(mode) }
        }
    }

    fun cancelSyncMode() = chooseSyncMode(LxSyncProtocol.SYNC_MODE_CANCEL)

    // ------------------------------------------------------------------ server -> client RPCs

    /**
     * Answers the server's `list_sync_get_sync_mode` call.
     *
     * The official client prompts on every request; here a previously stored choice answers
     * immediately, so the modal only appears the first time (or after the user clears it). The
     * wire protocol is untouched: whatever we answer is still an official `SyncMode` value.
     *
     * A non-interactive (auto) sync must never block on a TV dialog: when nothing is stored yet it
     * answers `cancel`, marks [needsModeChoice] and lets the caller surface 需要选择同步方式.
     * The server's own message2call timeout is 120s, so an interactive answer respects it too.
     *
     * The returned [SyncModeAnswer] also says whether the user was really asked: an answer taken
     * from the stored default is not a fresh decision and must not switch the merge off for a
     * session that already has something agreed with this server (spec §5.6).
     */
    private suspend fun requestSyncMode(interactive: Boolean): SyncModeAnswer {
        settingsStore.settings.value.syncMode
            ?.takeIf { LxSyncProtocol.isSelectableSyncMode(it) }
            ?.let { return SyncModeAnswer(it, prompted = false) }

        if (!interactive) {
            needsModeChoice = true
            Log.i(SyncClient.TAG, "list: sync mode required but never chosen; auto sync will skip")
            return SyncModeAnswer(LxSyncProtocol.SYNC_MODE_CANCEL, prompted = false)
        }

        val deferred = CompletableDeferred<String>()
        pendingMode = deferred
        _syncModeRequest.value = SyncModeRequest(activeServerName, manual = false)
        val chosen = try {
            withTimeoutOrNull(SYNC_MODE_TIMEOUT_MS) { deferred.await() }
                ?: LxSyncProtocol.SYNC_MODE_CANCEL
        } finally {
            pendingMode = null
            _syncModeRequest.value = null
        }
        if (LxSyncProtocol.isSelectableSyncMode(chosen)) persistSyncMode(chosen)
        return SyncModeAnswer(chosen, prompted = true)
    }

    /** Stores the user choice so later syncs (and the next app launch) reuse it. Never `cancel`. */
    private suspend fun persistSyncMode(mode: String) {
        if (!LxSyncProtocol.isSelectableSyncMode(mode)) return
        settingsStore.update { it.copy(syncMode = mode) }
        Log.i(SyncClient.TAG, "list: remembered sync mode")
    }

    // --------------------------------------------------------------------------------- internals

    private suspend fun runSync(interactive: Boolean): SyncResult {
        interactiveRun = interactive
        needsModeChoice = false
        // The policy for the session that is about to start. It has to be in place before the socket
        // opens: the server begins calling `list_sync_*` on us the moment the session is up.
        listSync.beginSession(
            LxSyncSession(
                libraryStore = libraryStore,
                repository = repository,
                baselineStore = baselineStore,
                serverId = { activeServerId },
                onAgreed = { serverView ->
                    pusher.adoptAgreement(serverView)
                    // Whatever this device still owes the server (edits made while the merge ran, or
                    // kept because they are newer than the server's state) goes out right after.
                    afterSession()
                },
            ),
        )
        val connected = connect(forceNewSession = true)
        if (connected !is SyncResult.Success) return connected
        _state.value = SyncState.Syncing
        _message.value = "正在同步…"
        if (!client.awaitReady(SYNC_READY_TIMEOUT_MS)) {
            closeSession()
            return fail(SyncState.Error("同步超时"), "server never finished")
        }
        return if (listSync.finished) {
            val now = System.currentTimeMillis()
            settingsStore.update { it.copy(syncLastSyncTime = now) }
            _state.value = SyncState.Success
            _message.value = "同步完成"
            Log.i(SyncClient.TAG, "sync complete")
            SyncResult.Success(activeServerId.orEmpty(), activeServerVersion, activeServerName)
        } else if (needsModeChoice) {
            // Auto sync aborted because the user has never picked a mode. Nothing was written;
            // 立即同步 (interactive) would have shown the dialog instead.
            _state.value = SyncState.NeedSyncMode
            _message.value = "请先在设置中选择同步方式"
            SyncResult.Failure(SyncState.NeedSyncMode, "sync mode not chosen")
        } else {
            // The server always calls `finished()`, even when the list module threw (user chose
            // 取消, module error, ...); the missing `list_sync_finished` is the reliable signal.
            _state.value = SyncState.Error("同步已取消")
            _message.value = "同步已取消"
            SyncResult.Failure(SyncState.Error("同步已取消"), "list sync not completed")
        }
    }

    /** [runSync] with a hard "never crash the caller" wrapper - used by auto-sync. */
    private suspend fun runSyncGuarded() {
        try {
            runSync(interactive = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(SyncState.Error("同步失败"), "unexpected ${e.javaClass.simpleName}")
        }
    }

    /** Connects (or reuses the live session) and opens the WebSocket. Caller holds the gate. */
    private suspend fun connect(forceNewSession: Boolean = false): SyncResult {
        val config = currentConfig() ?: return missingAddress()
        if (!forceNewSession && client.isReady && activeConfig == config) {
            return SyncResult.Success(activeServerId.orEmpty(), activeServerVersion, activeServerName)
        }
        closeSession()
        activeConfig = null
        _state.value = SyncState.Connecting
        return when (val auth = client.authenticate(config) { _state.value = it }) {
            is SyncClient.AuthOutcome.Failed -> fail(auth.state, auth.detail)
            is SyncClient.AuthOutcome.Ok -> {
                rememberAuth(config, auth)
                client.openSession(config, auth.keyInfo, sessionCallback)
                SyncResult.Success(auth.serverId, auth.serverVersion, auth.keyInfo.serverName)
            }
        }
    }

    /**
     * Watches the local lists: a user edit *and* a real online-songlist refresh are this device's
     * own change, so both are delivered as an incremental `onListSyncAction` while a session is up,
     * and by a whole session when they cannot be pushed - see [deliverEdit]. The applier never
     * emits [LibraryStore.changes], so applying a server action cannot re-trigger a sync here -
     * this module's equivalent of the official `isApplyingRemoteSync` recursion guard.
     *
     * Edits are consumed one at a time from a conflated channel instead of "cancel the previous
     * job": a burst (a batch import) still collapses into a single delivery, but a delivery that is
     * already talking to the server is never cancelled half-way - a session killed mid-flight left
     * the edit neither on the server nor on its way (the old `pending?.cancel()` could abort a
     * whole session that way).
     */
    private fun observeLocalChanges() {
        if (autoSyncJob != null) return
        autoSyncJob = scope.launch {
            val pending = Channel<Unit>(Channel.CONFLATED)
            val userEditPending = AtomicBoolean(false)
            launch {
                libraryStore.changes.collect { source ->
                    if (source == LibraryStore.ListChangeSource.USER) userEditPending.set(true)
                    pending.trySend(Unit)
                }
            }
            for (ignored in pending) {
                val userEdit = userEditPending.getAndSet(false)
                SyncLog.newChain()
                SyncLog.emit(
                    SyncLog.EVENT_CHANGE_DETECTED,
                    listOf("source" to if (userEdit) "user" else "online_refresh") +
                        SyncLog.sizes(libraryStore.syncLists()),
                )
                // A user edit *and* a real online-songlist refresh are both this device's own
                // change, so both are delivered as an incremental official action as soon as a
                // session is up - that is the official behaviour and it is the uplink that was
                // missing (the refresh used to be swallowed entirely, which is why "我喜欢"
                // gained a song on the TV and the server never heard about it, spec §4.1/§4.3).
                delay(PUSH_DEBOUNCE_MS)
                deliverEdit(
                    userEdit = userEdit,
                    reason = if (userEdit) TRIGGER_LOCAL_EDIT else TRIGGER_ONLINE_REFRESH,
                )
            }
        }
    }

    /**
     * Gets one local change to the server. Step 1 is the official incremental push, which needs a
     * live session *and* something already agreed with this server; step 2 is the whole-session
     * fallback for whatever step 1 could not deliver. There is no third step - a change that reaches
     * neither is still in the library and travels with the next sync.
     *
     * Two rules keep a change from being lost silently, which is exactly how "the TV has the song,
     * the server does not" happened:
     *
     *  - **The gate is waited for, never skipped.** [gate] is shared with whole sessions and can be
     *    held for minutes, so the old `tryLock` used to drop this delivery with no retry - the change
     *    the user had just made stayed on the TV until some unrelated edit came along.
     *  - **An edit the user made is always uploaded while sync is on.** 自动同步 governs *unattended*
     *    syncing - the sync on start-up and the fallback of a platform refresh - never whether the
     *    thing the user just did leaves the device. Gating the fallback on it turned the default
     *    configuration (自动同步 off) into a read-only client: every edit sat in `library.json` and
     *    no other device ever heard about it.
     */
    private suspend fun deliverEdit(userEdit: Boolean, reason: String) {
        if (pushLocalEdits(reason)) return
        if (!userEdit) {
            // A platform check runs every few minutes and can fire repeatedly; wait out the settle
            // window so a burst cannot start a session each, and leave a background refresh under
            // the 自动同步 setting. An order-only re-read built no action at all, so it neither
            // pushes nor starts a sync (spec §4.3).
            delay(AUTO_SYNC_DEBOUNCE_MS)
            if (!settingsStore.settings.value.syncAutoSync) return
        }
        openSessionForEdit(reason)
    }

    /**
     * The whole-session fallback: opens a session so an edit the incremental push could not deliver
     * still reaches the server. Runs under the gate and *waits* for it - the edit matters, not saving
     * a session.
     */
    private suspend fun openSessionForEdit(reason: String) {
        val settings = settingsStore.settings.value
        if (!settings.syncEnabled || currentConfig() == null) return
        gate.withLock {
            // While this waited for the gate a session may have come up, or the post-session flush
            // may have carried the change: retry the (cheap) push once under the lock before spending
            // a whole session on it.
            when (pusher.pushPending(reason)) {
                is LxListPusher.Outcome.Pushed, LxListPusher.Outcome.NoChange -> return
                // Still nothing to push over, or the server refused an action: a full session
                // re-merges everything and reports the real outcome (spec §6.7).
                else -> runSyncGuarded()
            }
        }
    }

    /**
     * Sends this device's pending edits as official actions, the way the official clients do.
     *
     * Returns true when the change is already delivered (or there was nothing to deliver), false
     * when the caller should fall back to a full session: no live session to push over, no agreed
     * baseline yet, or the server refused an action.
     *
     * The gate is *waited for*, never skipped: an edit that lands while the post-session flush (or a
     * whole session) holds it must still be delivered - returning early here would silently lose the
     * user's change until some unrelated edit came along (spec §7.5).
     */
    private suspend fun pushLocalEdits(reason: String): Boolean = gate.withLock {
        when (pusher.pushPending(reason)) {
            is LxListPusher.Outcome.Pushed, LxListPusher.Outcome.NoChange -> true
            // The server rejected an action: do not pretend it was delivered - a full session
            // re-merges everything and reports the real outcome (spec §6.7).
            is LxListPusher.Outcome.Failed -> {
                runSyncGuarded()
                true
            }
            LxListPusher.Outcome.Offline, LxListPusher.Outcome.NoBaseline -> false
        }
    }

    /** One deferred attempt to flush local edits after a session (never blocks the session itself). */
    private fun afterSession() {
        scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            pushLocalEdits(TRIGGER_AFTER_SYNC)
        }
    }

    private fun closeSession() {
        pendingMode?.complete(LxSyncProtocol.SYNC_MODE_CANCEL)
        pendingMode = null
        _syncModeRequest.value = null
        listSync.onSessionClosed()
        client.closeSession()
    }

    /**
     * Like [closeSession], but also drops the session policy: the user turned sync off or
     * disconnected, so no merge/push policy may outlive the connection.
     */
    private fun endSession() {
        pendingMode?.complete(LxSyncProtocol.SYNC_MODE_CANCEL)
        pendingMode = null
        _syncModeRequest.value = null
        listSync.clearSession()
        client.closeSession()
    }

    private fun rememberAuth(config: SyncConfig, auth: SyncClient.AuthOutcome.Ok) {
        activeConfig = config
        activeServerId = auth.serverId
        activeServerVersion = auth.serverVersion
        activeServerName = auth.keyInfo.serverName
        _serverInfo.value = buildString {
            append("服务版本：v").append(auth.serverVersion)
            auth.keyInfo.serverName?.takeIf { it.isNotBlank() }?.let { append(" · 服务器：").append(it) }
        }
    }

    private fun currentConfig(): SyncConfig? = SyncConfig.from(settingsStore.settings.value)

    private fun missingAddress(): SyncResult {
        _state.value = SyncState.Disconnected
        _message.value = "请先填写服务地址（例如 https://c.wwwweb.top/sync）"
        _serverInfo.value = null
        return SyncResult.Failure(SyncState.Error("请先填写服务地址"), "no server url")
    }

    private fun fail(state: SyncState, detail: String): SyncResult {
        Log.w(SyncClient.TAG, "sync failure: $detail")
        _state.value = state
        _message.value = detail
        return SyncResult.Failure(state, detail)
    }

    private fun busy(): SyncResult {
        _message.value = "同步任务正在进行中，请稍候…"
        return SyncResult.Failure(SyncState.Syncing, "busy")
    }

    /** The four settings values that decide whether a session should exist. */
    private data class SyncSettings(
        val syncEnabled: Boolean,
        val serverUrl: String,
        val connectionCode: String,
        val autoSync: Boolean,
    ) {
        companion object {
            fun of(s: Settings) = SyncSettings(
                s.syncEnabled,
                s.syncServerUrl,
                s.syncConnectionCode,
                s.syncAutoSync,
            )
        }
    }

    private companion object {
        /** The server's own list_sync RPC timeout is 120s - answer the mode question before it. */
        const val SYNC_MODE_TIMEOUT_MS = 110_000L

        /** Generous: the server sends `finished()` after the merge, and the mode dialog may run. */
        const val SYNC_READY_TIMEOUT_MS = 150_000L

        /** Coalesce a burst of library edits into one sync; never polls. */
        const val AUTO_SYNC_DEBOUNCE_MS = 20_000L

        /**
         * A user edit is pushed incrementally, so it only needs to be coalesced against the tap
         * that follows it - not against a whole sync session.
         */
        const val PUSH_DEBOUNCE_MS = 1_500L

        /** `reason` values for the push log lines (spec §6: the five paths stay distinguishable). */
        const val TRIGGER_LOCAL_EDIT = "local_edit"
        const val TRIGGER_AFTER_SYNC = "after_sync"

        /** An imported online songlist was re-read: a membership change of it is a local edit too. */
        const val TRIGGER_ONLINE_REFRESH = "online_refresh"
    }
}
