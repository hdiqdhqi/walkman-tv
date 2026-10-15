package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * SyncManager lifecycle tests against a fake transport. The fake plays the *server*: after the
 * session opens it walks the official `list_sync_*` sequence through the handler SyncManager
 * installed, so the whole client half of the protocol runs offline.
 */
class SyncManagerTest {

    private lateinit var dir: File
    private lateinit var settingsStore: SettingsStore
    private lateinit var libraryStore: LibraryStore
    private lateinit var scope: CoroutineScope
    private lateinit var client: FakeSyncClient
    private lateinit var baselineStore: SyncBaselineStore
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        dir = tempDir()
        settingsStore = SettingsStore(dir)
        libraryStore = LibraryStore(dir)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        client = FakeSyncClient(scope)
        baselineStore = SyncBaselineStore(dir)
        manager = SyncManager(settingsStore, client, libraryStore, scope, baselineStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun enableSync(autoSync: Boolean = false) = runBlocking {
        settingsStore.update {
            it.copy(
                syncEnabled = true,
                syncServerUrl = "https://example.com/sync",
                syncConnectionCode = "1234",
                syncAutoSync = autoSync,
            )
        }
    }

    private fun rememberMode(mode: String) = runBlocking {
        settingsStore.update { it.copy(syncMode = mode) }
    }

    private fun seedLove(vararg mids: String) = runBlocking {
        libraryStore.overwriteSyncLists(
            LibraryStore.SyncLists(emptyList(), mids.map { track(songmid = it) }, emptyList()),
        )
    }

    @Test
    fun `manual sync uploads the local lists and applies the server merge result`() {
        enableSync()
        seedLove("a", "b")
        client.serverData = listData(loveList = listOf(musicInfo(songmid = "a")))
        client.askMode = true

        val result = runBlocking {
            val job = async { manager.syncNow() }
            manager.syncModeRequest.first { it != null }
            manager.chooseSyncMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
            job.await()
        }

        assertTrue(result.ok)
        // The client told the server about "b" (so its removal could be a real snapshot diff)...
        assertTrue(client.lastUpload!!.contains("kw_b"))
        // ...and then applied exactly what the server's merge returned.
        assertEquals(listOf("kw_a"), libraryStore.syncLists().love.map { it.id })
        assertEquals(SyncState.Success, manager.state.value)
        assertEquals("同步完成", manager.message.value)
        assertTrue(settingsStore.settings.value.syncLastSyncTime!! > 0L)
        assertEquals(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE, settingsStore.settings.value.syncMode)
        assertEquals(1, client.sessions.get())
    }

    @Test
    fun `empty local plus empty server finishes without error`() {
        enableSync()
        val result = runBlocking { manager.syncNow() }
        assertTrue(result.ok)
        assertEquals(emptyList<String>(), libraryStore.syncLists().love.map { it.id })
        assertEquals(SyncState.Success, manager.state.value)
    }

    @Test(timeout = 30_000)
    fun `the mode is asked once and reused by later syncs`() {
        enableSync()
        client.askMode = true

        // First sync: nothing stored -> the server's question reaches the UI exactly once.
        runBlocking {
            val job = async { manager.syncNow() }
            val request = manager.syncModeRequest.first { it != null }!!
            assertFalse("a server-driven request is not a manual change", request.manual)
            manager.chooseSyncMode(LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL)
            assertTrue(job.await().ok)
        }
        assertEquals(LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL, settingsStore.settings.value.syncMode)

        // Second sync: the fake server still asks, but the stored answer is returned with no dialog.
        client.lastMode = null
        val second = runBlocking {
            val job = async { manager.syncNow() }
            delay(150)
            assertNull("the picker must not appear again", manager.syncModeRequest.value)
            job.await()
        }
        assertTrue(second.ok)
        assertEquals(LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL, client.lastMode)
        assertEquals(2, client.sessions.get())
    }

    @Test
    fun `a remembered mode survives a restart`() {
        enableSync()
        rememberMode(LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL)

        // A fresh process = a fresh SettingsStore over the same files dir.
        val reopened = SettingsStore(dir)
        runBlocking { reopened.loadAll() }
        assertEquals(LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL, reopened.settings.value.syncMode)
    }

    @Test
    fun `every official mode is forwarded verbatim`() {
        enableSync()
        client.askMode = true
        val modes = LxSyncProtocol.SELECTABLE_SYNC_MODES
        runBlocking {
            for (mode in modes) {
                settingsStore.update { it.copy(syncMode = null) }
                val job = async { manager.syncNow() }
                manager.syncModeRequest.first { it != null }
                manager.chooseSyncMode(mode)
                val result = job.await()
                assertTrue("sync with $mode", result.ok)
                assertEquals(mode, client.lastMode)
            }
        }
    }

    @Test
    fun `choosing cancel leaves local data untouched and is never remembered`() {
        enableSync()
        seedLove("keep")
        client.askMode = true

        val result = runBlocking {
            val job = async { manager.syncNow() }
            manager.syncModeRequest.first { it != null }
            manager.cancelSyncMode()
            job.await()
        }

        assertFalse(result.ok)
        assertEquals(SyncState.Error("同步已取消"), manager.state.value)
        assertEquals("同步已取消", manager.message.value)
        // A cancelled sync must not have written anything, including the preference.
        assertEquals(listOf("kw_keep"), libraryStore.syncLists().love.map { it.id })
        assertNull(settingsStore.settings.value.syncLastSyncTime)
        assertNull(settingsStore.settings.value.syncMode)
    }

    @Test(timeout = 30_000)
    fun `double tapping sync runs a single session`() {
        enableSync()
        client.askMode = true
        runBlocking {
            val job = async { manager.syncNow() }
            manager.syncModeRequest.first { it != null }
            val second = manager.syncNow()
            assertFalse(second.ok)
            assertEquals("busy", (second as SyncResult.Failure).logDetail)
            assertEquals(1, client.sessions.get())
            manager.cancelSyncMode()
            job.await()
        }
    }

    @Test(timeout = 30_000)
    fun `autoSync and manual sync cannot run at the same time`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        client.holdBeforeFinish = CompletableDeferred()
        manager.start()
        runBlocking {
            manager.state.first { it == SyncState.Syncing }
            val manual = manager.syncNow()
            assertFalse(manual.ok)
            assertEquals(1, client.sessions.get())
            client.holdBeforeFinish!!.complete(Unit)
            manager.state.first { it == SyncState.Success }
        }
        assertEquals(1, client.sessions.get())
    }

    @Test
    fun `autoSync fires once when sync is enabled on start`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        client.serverData = listData(loveList = listOf(musicInfo(songmid = "srv")))
        manager.start()
        runBlocking {
            manager.state.first { it == SyncState.Success }
        }
        assertEquals(1, client.sessions.get())
        assertEquals(listOf("kw_srv"), libraryStore.syncLists().love.map { it.id })
    }

    @Test(timeout = 30_000)
    fun `auto sync without a stored mode never shows a dialog and does not block`() {
        enableSync(autoSync = true)
        client.askMode = true
        seedLove("keep")
        manager.start()
        runBlocking {
            // It reaches a terminal state promptly instead of waiting on a background dialog.
            manager.state.first { it == SyncState.NeedSyncMode }
            delay(100)
            assertNull("auto sync must never open the picker", manager.syncModeRequest.value)
            assertEquals("请先在设置中选择同步方式", manager.message.value)
        }
        assertFalse("auto sync must not wait on the user", manager.state.value.busy)
        assertEquals(listOf("kw_keep"), libraryStore.syncLists().love.map { it.id })
        assertNull(settingsStore.settings.value.syncMode)
        assertEquals(1, client.sessions.get())
    }

    @Test(timeout = 30_000)
    fun `changing the mode from settings stores it and applies to the next sync`() {
        enableSync()
        client.askMode = true

        // Opening the picker from Settings is a preference change: it must not start a sync.
        manager.requestSyncModeChange()
        val request = manager.syncModeRequest.value
        assertTrue(request != null && request.manual)
        assertEquals(0, client.sessions.get())

        runBlocking {
            manager.chooseSyncMode(LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL)
            settingsStore.settings.first { it.syncMode != null }
        }
        assertEquals(
            LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL,
            settingsStore.settings.value.syncMode,
        )
        assertNull("picking a mode from Settings closes the picker", manager.syncModeRequest.value)

        // The next sync uses the new value without asking.
        val result = runBlocking { manager.syncNow() }
        assertTrue(result.ok)
        assertEquals(LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL, client.lastMode)
        assertNull(manager.syncModeRequest.value)
    }

    @Test(timeout = 30_000)
    fun `a mode change while a sync is running does not affect that sync`() {
        enableSync()
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        client.askMode = true
        client.holdBeforeFinish = CompletableDeferred()

        runBlocking {
            val job = async { manager.syncNow() }
            // openSession has run by the time state is Syncing, so `parked` is this session's.
            manager.state.first { it == SyncState.Syncing }
            // The running sync has already read the stored mode; it is now parked before finishing.
            client.parked.await()
            manager.requestSyncModeChange()
            manager.chooseSyncMode(LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL)
            settingsStore.settings.first { it.syncMode == LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL }

            client.holdBeforeFinish!!.complete(Unit)
            assertTrue(job.await().ok)
        }
        // The in-flight sync used the old mode; only later syncs see the new one.
        assertEquals(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE, client.lastMode)
        assertEquals(
            LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL,
            settingsStore.settings.value.syncMode,
        )
    }

    @Test
    fun `auth failure surfaces a readable state and opens no session`() {
        enableSync()
        client.authFailure = SyncClient.AuthOutcome.Failed(SyncState.AuthFailed, "连接码错误")
        val result = runBlocking { manager.syncNow() }
        assertFalse(result.ok)
        assertEquals(SyncState.AuthFailed, manager.state.value)
        assertEquals("连接码错误", manager.message.value)
        assertEquals(0, client.sessions.get())
    }

    @Test
    fun `server unavailable surfaces ServiceUnavailable`() {
        enableSync()
        client.authFailure = SyncClient.AuthOutcome.Failed(SyncState.ServiceUnavailable, "无法连接服务器")
        val result = runBlocking { manager.syncNow() }
        assertFalse(result.ok)
        assertEquals(SyncState.ServiceUnavailable, manager.state.value)
    }

    @Test
    fun `a dropped socket never completes the sync and never writes`() {
        enableSync()
        seedLove("keep")
        client.failReady = true
        val result = runBlocking { manager.syncNow() }
        assertFalse(result.ok)
        assertTrue(manager.state.value is SyncState.Error)
        assertEquals(listOf("kw_keep"), libraryStore.syncLists().love.map { it.id })
        assertNull(settingsStore.settings.value.syncLastSyncTime)
    }

    @Test
    fun `missing server url is reported before any network work`() {
        runBlocking { settingsStore.update { it.copy(syncEnabled = true) } }
        val result = runBlocking { manager.syncNow() }
        assertFalse(result.ok)
        assertEquals(SyncState.Disconnected, manager.state.value)
        assertEquals(0, client.sessions.get())
    }

    @Test
    fun `test connection authenticates without opening a list session`() {
        enableSync()
        val result = runBlocking { manager.testConnection() }
        assertTrue(result.ok)
        assertEquals(SyncState.Connected, manager.state.value)
        assertEquals("连接成功", manager.message.value)
        assertEquals(0, client.sessions.get())
    }

    @Test
    fun `turning sync off disconnects and clears the state`() {
        enableSync()
        manager.start()
        runBlocking {
            settingsStore.update { it.copy(syncEnabled = false) }
            manager.state.first { it == SyncState.Disabled }
        }
        assertEquals(SyncState.Disabled, manager.state.value)
    }

    // --------------------------------------------------------------- the push direction

    /** Waits until the fake server received an official action matching [predicate]. */
    private fun awaitAction(predicate: (LxListAction) -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 20_000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            found = client.pushedActions.any { runCatching { predicate(LxListAction.parse(it)) }.getOrDefault(false) }
            if (!found) delay(50)
        }
        assertTrue("no matching push within 20s: ${client.pushedActions}", found)
    }

    /** Every action the fake server received, parsed. */
    private fun pushedActions(): List<LxListAction> = client.pushedActions.map { LxListAction.parse(it) }

    private fun addedIds(action: LxListAction) = when (action) {
        is LxListAction.AddMusic -> action.musicInfos.map { it.id }
        else -> emptyList()
    }

    @Test(timeout = 40_000)
    fun `a user edit is pushed as an official action without a full re-sync`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        runBlocking { manager.state.first { it == SyncState.Success } }
        assertEquals(1, client.sessions.get())

        runBlocking { libraryStore.toggleFavorite(track(songmid = "new")) }
        awaitAction { it is LxListAction.AddMusic }

        assertEquals("the edit must not need a second session", 1, client.sessions.get())
        val adds = pushedActions().filterIsInstance<LxListAction.AddMusic>()
        assertEquals(listOf(listOf("kw_new")), adds.map { addedIds(it) })
    }

    @Test(timeout = 40_000)
    fun `deleting a song is pushed as a removal`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        runBlocking { manager.state.first { it == SyncState.Success } }
        runBlocking { libraryStore.toggleFavorite(track(songmid = "a")) }
        awaitAction { it is LxListAction.AddMusic }

        runBlocking { libraryStore.toggleFavorite(track(songmid = "a")) }
        awaitAction { it is LxListAction.RemoveMusic }

        val removals = pushedActions().filterIsInstance<LxListAction.RemoveMusic>()
        assertEquals(listOf("kw_a"), removals.last().ids)
        assertEquals(1, client.sessions.get())
    }

    @Test(timeout = 40_000)
    fun `an online-songlist refresh never pushes the platform order`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        runBlocking { manager.state.first { it == SyncState.Success } }

        // The importer wrote the list and the last agreement already holds exactly these songs.
        runBlocking {
            libraryStore.overwriteSyncLists(
                LibraryStore.SyncLists(
                    defaultList = emptyList(),
                    love = emptyList(),
                    userLists = listOf(
                        com.walkman.tv.data.model.Playlist(
                            "p",
                            "导入歌单",
                            listOf(track(songmid = "a"), track(songmid = "b")),
                            source = "tx",
                            sourceListId = "1",
                        ),
                    ),
                ),
            )
            baselineStore.save(SyncBaselines.of(libraryStore.syncLists(), "server-1"))
            // The platform handed the same songs back in a different running order.
            libraryStore.replaceListTracks(
                "p",
                listOf(track(songmid = "b"), track(songmid = "a")),
                LibraryStore.ListChangeSource.ONLINE_REFRESH,
            )
        }

        runBlocking { delay(3_000) }

        assertTrue(
            "a platform reorder must never be pushed: ${client.pushedActions}",
            client.pushedActions.isEmpty(),
        )
        assertEquals(1, client.sessions.get())
    }

    @Test(timeout = 40_000)
    fun `a refresh that really changes the songs still reaches the server`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        runBlocking { manager.state.first { it == SyncState.Success } }

        runBlocking {
            libraryStore.overwriteSyncLists(
                LibraryStore.SyncLists(
                    defaultList = emptyList(),
                    love = emptyList(),
                    userLists = listOf(
                        com.walkman.tv.data.model.Playlist(
                            "p",
                            "导入歌单",
                            listOf(track(songmid = "a")),
                            source = "tx",
                            sourceListId = "1",
                        ),
                    ),
                ),
            )
            baselineStore.save(SyncBaselines.of(libraryStore.syncLists(), "server-1"))
            libraryStore.replaceListTracks(
                "p",
                listOf(track(songmid = "a"), track(songmid = "b")),
                LibraryStore.ListChangeSource.ONLINE_REFRESH,
            )
        }

        awaitAction { it is LxListAction.AddMusic && it.listId == "p" }

        assertEquals(1, client.sessions.get())
    }

    @Test(timeout = 60_000)
    fun `an edit with no live session falls back to a full sync`() {
        enableSync(autoSync = true)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        runBlocking { manager.state.first { it == SyncState.Success } }
        assertEquals(1, client.sessions.get())

        manager.disconnect()
        runBlocking { libraryStore.toggleFavorite(track(songmid = "new")) }
        runBlocking {
            val deadline = System.currentTimeMillis() + 40_000
            while (client.sessions.get() < 2 && System.currentTimeMillis() < deadline) delay(50)
        }

        assertEquals(
            "no live session, so the change travels via a session (state=${manager.state.value}, msg=${manager.message.value})",
            2,
            client.sessions.get(),
        )
        assertTrue(client.pushedActions.isEmpty())
        assertEquals(listOf("kw_new"), libraryStore.syncLists().love.map { it.id })
    }

    @Test(timeout = 60_000)
    fun `an edit is uploaded even though auto sync is off (the TV default)`() {
        // The reported setup: sync configured, 合并 chosen, 自动同步 off - i.e. the TV never has a
        // live session, so nothing can be pushed incrementally. The edit still has to leave the
        // device: otherwise the server never hears about it and no other device can ever pick it up,
        // which is exactly "电视端修改无法同步到电脑或手机". 自动同步 governs *unattended* syncing
        // (start-up, background platform refreshes), never whether the user's own edit is uploaded.
        enableSync(autoSync = false)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        assertEquals("nothing to sync yet, so no session", 0, client.sessions.get())

        runBlocking { libraryStore.toggleFavorite(track(songmid = "new")) }

        runBlocking {
            val deadline = System.currentTimeMillis() + 40_000
            while (manager.state.value != SyncState.Success && System.currentTimeMillis() < deadline) delay(50)
        }
        assertEquals("the edit must open a session and reach the server", 1, client.sessions.get())
        assertEquals(SyncState.Success, manager.state.value)
        assertTrue("the upload must carry the edit", client.lastUpload!!.contains("kw_new"))
        assertEquals(listOf("kw_new"), libraryStore.syncLists().love.map { it.id })
    }
}

/**
 * Test double for [SyncClient]. Plays the server side of the list-sync protocol by calling the
 * handler SyncManager installed, and lets a test control auth failures, mode questions and
 * whether the socket ever comes up.
 */
private class FakeSyncClient(
    private val driverScope: CoroutineScope,
    var serverData: LxListData? = null,
    var askMode: Boolean = false,
    var failReady: Boolean = false,
    var authFailure: SyncClient.AuthOutcome.Failed? = null,
    var serverVersion: Int = 4,
    var serverName: String? = "我的服务器",
) : SyncClient(OkHttpClient(), SyncAuthStore(tempDir()), driverScope) {

    val sessions = AtomicInteger(0)
    val md5Calls = AtomicInteger(0)
    val setDataCalls = AtomicInteger(0)

    @Volatile var lastMode: String? = null

    @Volatile var lastUpload: String? = null

    /** When set, the fake server parks here until the test releases it. */
    var holdBeforeFinish: CompletableDeferred<Unit>? = null

    /** Completed by the fake server right before it parks on [holdBeforeFinish]. */
    var parked = CompletableDeferred<Unit>()
        private set

    private var sessionCallback: SessionCallback? = null
    private var readyGate = CompletableDeferred<Boolean>()

    override val isReady: Boolean get() = readyGate.isCompleted

    /** Every official `onListSyncAction` payload this fake server received, in order. */
    val pushedActions = java.util.concurrent.CopyOnWriteArrayList<JsonElement>()

    override suspend fun call(path: String, args: List<JsonElement>): JsonElement? {
        if (path == LxListAction.PUSH_METHOD) {
            args.firstOrNull()?.let { pushedActions += it }
            return JsonPrimitive(true)
        }
        return super.call(path, args)
    }

    override suspend fun authenticate(config: SyncConfig, onPhase: (SyncState) -> Unit): AuthOutcome =
        authFailure
            ?: AuthOutcome.Ok("server-1", serverVersion, SyncKeyInfo(clientId = "client-1", key = "key", serverName = serverName))

    override fun openSession(config: SyncConfig, keyInfo: SyncKeyInfo, callback: SessionCallback) {
        sessions.incrementAndGet()
        sessionCallback = callback
        readyGate = CompletableDeferred()
        parked = CompletableDeferred()
        callback.onState(SyncState.Connecting)
        if (failReady) {
            // Socket never comes up: no server protocol runs and no action is ever applied.
            callback.onState(SyncState.Disconnected)
            return
        }
        driverScope.launch { driveServer() }
    }

    override fun closeSession() {
        readyGate.complete(false)
        sessionCallback = null
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean =
        if (failReady) false else readyGate.await()

    private suspend fun driveServer() {
        val handler = listSyncHandler
        if (handler == null) {
            finishReady()
            return
        }
        handler(listOf("list_sync_get_md5"), JsonArray(emptyList()))
        md5Calls.incrementAndGet()
        lastUpload = handler(listOf("list_sync_get_list_data"), JsonArray(emptyList()))?.toString()
        if (askMode) {
            val mode = (handler(listOf("list_sync_get_sync_mode"), JsonArray(emptyList())) as? JsonPrimitive)?.content
            lastMode = mode
            if (mode == LxSyncProtocol.SYNC_MODE_CANCEL) {
                finishReady()
                return
            }
        }
        serverData?.let { data ->
            handler(
                listOf("list_sync_set_list_data"),
                JsonArray(listOf(LxListJson.json.encodeToJsonElement(LxListData.serializer(), data))),
            )
            setDataCalls.incrementAndGet()
        }
        parked.complete(Unit)
        holdBeforeFinish?.await()
        handler(listOf("list_sync_finished"), JsonArray(emptyList()))
        finishReady()
    }

    private fun finishReady() {
        sessionCallback?.onReady()
        readyGate.complete(true)
    }
}
