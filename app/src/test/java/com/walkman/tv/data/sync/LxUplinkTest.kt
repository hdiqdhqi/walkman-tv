package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The uplink regression suite: every change the TV makes to a synced list must end up on the
 * *server*, and the server's snapshots must never be able to undo a change the TV has not managed
 * to deliver yet.
 *
 * Unlike the pure merge tests these drive the real [SyncManager] against [LxSyncServerSim] - a port
 * of the official server's list module - so they assert on the actions that actually reached the
 * protocol layer, on the target list id, and on the list the server ends up holding. This is the
 * shape of the reported bug: the TV had the new song, the server did not.
 */
class LxUplinkTest {

    private lateinit var dir: File
    private lateinit var settingsStore: SettingsStore
    private lateinit var library: LibraryStore
    private lateinit var baselines: SyncBaselineStore
    private lateinit var scope: CoroutineScope
    private lateinit var sim: LxSyncServerSim
    private lateinit var client: SimSyncClient
    private lateinit var manager: SyncManager

    @Before
    fun setUp() = runBlocking {
        dir = tempDir()
        settingsStore = SettingsStore(dir)
        settingsStore.loadAll()
        library = LibraryStore(dir)
        library.loadAll()
        baselines = SyncBaselineStore(dir)
        baselines.loadAll()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        sim = LxSyncServerSim()
        client = SimSyncClient(scope, sim)
        manager = SyncManager(settingsStore, client, library, scope, baselines)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --------------------------------------------------------------------------------- helpers

    private fun enableSync(autoSync: Boolean) = runBlocking {
        settingsStore.update {
            it.copy(
                syncEnabled = true,
                syncServerUrl = "https://example.com/sync",
                syncConnectionCode = "1234",
                syncAutoSync = autoSync,
            )
        }
    }

    private fun rememberMode(mode: String) = runBlocking { settingsStore.update { it.copy(syncMode = mode) } }

    /** The TV's imported "我喜欢" list, written the way the songlist importer writes it. */
    private fun importedList(id: String, name: String, songmids: List<String>) = Playlist(
        id = id,
        name = name,
        tracks = songmids.map { track(songmid = it) },
        source = "tx",
        sourceListId = "platform-$id",
    )

    private fun serverImportedList(id: String, name: String, songmids: List<String>) = userList(
        id = id,
        name = name,
        tracks = songmids.map { musicInfo(songmid = it) },
        source = "tx",
        sourceListId = "platform-$id",
    )

    private suspend fun seedTvUserList(list: Playlist) = library.overwriteSyncLists(
        LibraryStore.SyncLists(defaultList = emptyList(), love = emptyList(), userLists = listOf(list)),
    )

    private suspend fun tvIds(listId: String): List<String> =
        library.syncLists().userLists.firstOrNull { it.id == listId }?.tracks?.map { it.id }.orEmpty()

    private fun serverIds(listId: String): List<String> =
        sim.listData.userList.firstOrNull { it.id == listId }?.list?.map { it.id }.orEmpty()

    private fun pushedActions(): List<LxListAction> = sim.receivedActions.map { LxListAction.parse(it) }

    /** Polls until the push reached the *protocol layer* (the simulated server). */
    private suspend fun awaitPush(timeoutMs: Long = 15_000, predicate: (LxListAction) -> Boolean): LxListAction {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pushedActions().firstOrNull(predicate)?.let { return it }
            delay(25)
        }
        throw AssertionError("no matching push within ${timeoutMs}ms; received=${pushedActions()}")
    }

    private suspend fun awaitState(state: SyncState, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (manager.state.value == state) return
            delay(20)
        }
        throw AssertionError("state never reached $state (now ${manager.state.value}, msg=${manager.message.value})")
    }

    /** Delivers a server push (broadcast) to the TV, exactly like the live socket would. */
    private suspend fun deliver(action: LxListAction) {
        val handler = client.listSyncHandler ?: throw AssertionError("no list handler")
        handler(listOf("onListSyncAction"), JsonArray(listOf(LxListAction.encode(action))))
    }

    private suspend fun startSynced(autoSync: Boolean = true) {
        enableSync(autoSync)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        awaitState(SyncState.Success)
    }

    /** Waits for the post-session flush to settle, so a later assertion can only see new pushes. */
    private suspend fun settleAfterSession() = delay(3_000)

    // ------------------------------------------------------------------------------ 场景 A

    @Test(timeout = 60_000)
    fun `A an online refresh reaches the server as an incremental action`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a")))))
        startSynced()
        settleAfterSession()
        assertEquals("the first sync is a full exchange", 1, sim.sessions)
        assertEquals(listOf("kw_a"), serverIds("p"))

        // The platform handed a new song back; the TV wrote it locally as an ONLINE_REFRESH.
        library.replaceListTracks("p", listOf(track(songmid = "a"), track(songmid = "new")), LibraryStore.ListChangeSource.ONLINE_REFRESH)

        val pushed = awaitPush { it is LxListAction.AddMusic }
        val add = pushed as LxListAction.AddMusic
        assertEquals("the action must target the logical list that changed", "p", add.listId)
        assertEquals(listOf("kw_new"), add.musicInfos.map { it.id })
        assertEquals("the server now holds the new song", listOf("kw_a", "kw_new"), serverIds("p"))
        assertEquals("a membership change must not need a second full sync", 1, sim.sessions)

        // 8-9: the server broadcasts its new state; the TV must not lose the song it just added.
        deliver(LxListAction.DataOverwrite(sim.listData))
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))
    }

    // ------------------------------------------------------------------------------ 场景 B

    @Test(timeout = 60_000)
    fun `B an old server snapshot cannot drop a local change that is still pending`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a")))))
        startSynced()
        settleAfterSession()
        assertEquals(listOf("kw_a"), serverIds("p"))

        // The platform handed a new song back and the TV wrote it locally. The push that carries it
        // is debounced, so at this instant it is a change the server has not seen yet - exactly the
        // window the reported bug lost it in.
        library.replaceListTracks("p", listOf(track(songmid = "a"), track(songmid = "new")), LibraryStore.ListChangeSource.ONLINE_REFRESH)
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))

        // While the change is still un-uploaded the server, still on the old list, pushes its
        // snapshot down: the two cross on the wire. It must not be able to take the song away again.
        deliver(LxListAction.DataOverwrite(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a"))))))

        assertEquals("an old server snapshot must never delete an un-uploaded local change", listOf("kw_a", "kw_new"), tvIds("p"))
        assertEquals("and it must not pretend the server is up to date either", listOf("kw_a"), serverIds("p"))

        // The pending change still goes out on its own, without waiting for a whole session.
        val pushed = awaitPush { it is LxListAction.AddMusic } as LxListAction.AddMusic
        assertEquals(listOf("kw_new"), pushed.musicInfos.map { it.id })
        assertEquals(listOf("kw_a", "kw_new"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))

        // A further sync must be a no-op - and must not duplicate the song.
        assertTrue(manager.syncNow().ok)
        assertEquals(listOf("kw_a", "kw_new"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))

        assertTrue(manager.syncNow().ok)
        assertEquals("repeated syncs are idempotent", listOf("kw_a", "kw_new"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))
    }

    // ------------------------------------------------------------------------------ 场景 C

    @Test(timeout = 60_000)
    fun `C a real deletion propagates and does not come back`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a", "b")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a", "b")))))
        startSynced()
        settleAfterSession()
        assertEquals(listOf("kw_a", "kw_b"), serverIds("p"))

        library.replaceListTracks("p", listOf(track(songmid = "a")), LibraryStore.ListChangeSource.ONLINE_REFRESH)

        val remove = awaitPush { it is LxListAction.RemoveMusic } as LxListAction.RemoveMusic
        assertEquals("p", remove.listId)
        assertEquals(listOf("kw_b"), remove.ids)
        assertEquals(listOf("kw_a"), serverIds("p"))

        // The server broadcasts the result; a re-sync must not resurrect the song.
        deliver(LxListAction.DataOverwrite(sim.listData))
        assertTrue(manager.syncNow().ok)
        assertEquals(listOf("kw_a"), serverIds("p"))
        assertEquals(listOf("kw_a"), tvIds("p"))
    }

    // ------------------------------------------------------------------------------ 场景 D

    @Test(timeout = 60_000)
    fun `D an order-only refresh neither pushes nor starts a sync`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a", "b")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a", "b")))))
        startSynced()
        settleAfterSession()

        // The platform returned the same songs in a different running order.
        library.replaceListTracks("p", listOf(track(songmid = "b"), track(songmid = "a")), LibraryStore.ListChangeSource.ONLINE_REFRESH)
        delay(6_000)

        assertTrue("a platform reorder must never be pushed: ${pushedActions()}", pushedActions().isEmpty())
        assertEquals("and it must not start a full sync either", 1, sim.sessions)

        // Re-delivering the server's own state must not self-trigger anything either.
        repeat(2) { deliver(LxListAction.DataOverwrite(sim.listData)) }
        delay(2_000)
        assertTrue(pushedActions().isEmpty())
        assertEquals(1, sim.sessions)
    }

    // --------------------------------------------------------------- 缺少基线的已有电视用户

    @Test(timeout = 60_000)
    fun `an existing TV without a baseline keeps its songs and hands them to the server`() = runBlocking {
        // No baseline (fresh install over an existing library): TV and server disagree. The user
        // picked 合并, so nothing may be deleted on either side and the server must receive the song.
        seedTvUserList(importedList("p", "我喜欢", listOf("a", "tv-only")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a", "srv-only")))))
        sim.mode = LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE
        enableSync(autoSync = false)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)

        assertTrue(manager.syncNow().ok)

        // The server's list first (the merge source), then this device's additions.
        assertEquals(listOf("kw_a", "kw_srv-only", "kw_tv-only"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_srv-only", "kw_tv-only"), tvIds("p"))
        assertNotNull("the agreement must exist after a first sync", baselines.current())
    }

    // ------------------------------------------------------------------ 同步期间的本地修改

    // --------------------------------------------------------------- 同名但内部 ID 不同的歌单

    @Test(timeout = 60_000)
    fun `two lists with the same name but different ids stay two lists`() = runBlocking {
        // The TV's imported "我喜欢" and the server's own "我喜欢" are different lists: the
        // protocol matches by list id, never by name, so neither may absorb the other's songs
        // (spec §4.2 - a name-based identity would quietly lose one of them).
        seedTvUserList(importedList("tv-love", "我喜欢", listOf("a")))
        sim.setListData(listData(userList = listOf(serverImportedList("srv-love", "我喜欢", listOf("b")))))
        enableSync(autoSync = false)
        rememberMode(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        manager.start()
        assertTrue(manager.syncNow().ok)

        assertEquals("the TV list keeps its own song", listOf("kw_a"), tvIds("tv-love"))
        assertEquals("the server list keeps its own song", listOf("kw_b"), tvIds("srv-love"))
        assertEquals(listOf("kw_a"), serverIds("tv-love"))
        assertEquals(listOf("kw_b"), serverIds("srv-love"))
    }

    // ------------------------------------------------------------------ 同步期间的本地修改
    @Test(timeout = 60_000)
    fun `an edit made while a sync is running is still delivered exactly once`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a")))))
        startSynced()
        settleAfterSession()

        sim.holdBeforeFinish = CompletableDeferred()
        val sync = scope.launch { manager.syncNow() }
        // The new session (and its upload) must be under way before the late edit happens.
        awaitState(SyncState.Syncing)
        client.parked.await()
        // The upload already happened, so this edit can only travel through the post-session flush.
        library.replaceListTracks("p", listOf(track(songmid = "a"), track(songmid = "late")), LibraryStore.ListChangeSource.USER)
        client.releaseHeldFinish()
        sync.join()
        awaitState(SyncState.Success)

        awaitPush { it is LxListAction.AddMusic && (it as LxListAction.AddMusic).musicInfos.any { music -> music.id == "kw_late" } }
        delay(2_000)
        assertEquals(listOf("kw_a", "kw_late"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_late"), tvIds("p"))
        assertEquals(
            "the late edit must not be sent twice",
            1,
            pushedActions().filterIsInstance<LxListAction.AddMusic>().count { action -> action.musicInfos.any { it.id == "kw_late" } },
        )
    }

    // ------------------------------------------------------------------------- 推送失败后重试

    @Test(timeout = 60_000)
    fun `a refused push keeps the change and a retry still delivers it`() = runBlocking {
        seedTvUserList(importedList("p", "我喜欢", listOf("a")))
        sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a")))))
        startSynced()
        settleAfterSession()
        assertEquals(listOf("kw_a"), baselines.current()!!.userLists.first().ids)

        // The server refuses the incremental action (the socket dropped mid-push).
        client.pushFailure = "boom"
        library.replaceListTracks("p", listOf(track(songmid = "a"), track(songmid = "new")), LibraryStore.ListChangeSource.USER)

        // The change may not be lost: a retry (here the manager's fallback session) still hands it
        // over, and it is added exactly once.
        val deadline = System.currentTimeMillis() + 30_000
        while (serverIds("p") != listOf("kw_a", "kw_new") && System.currentTimeMillis() < deadline) delay(50)
        assertEquals(listOf("kw_a", "kw_new"), serverIds("p"))
        assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))
        assertEquals("idempotent: the song is added exactly once", 1, tvIds("p").count { it == "kw_new" })
    }

    // ------------------------------------------------------- 日志：电视有歌，服务器为什么没有

    @Test(timeout = 60_000)
    fun `the log answers why a song the TV holds did not reach the server`() = runBlocking {
        val lines = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        SyncLog.sink = { event, fields -> lines += event to fields.toMap() }
        try {
            seedTvUserList(importedList("p", "我喜欢", listOf("a")))
            sim.setListData(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a")))))
            startSynced()
            settleAfterSession()
            lines.clear()

            library.replaceListTracks("p", listOf(track(songmid = "a"), track(songmid = "new")), LibraryStore.ListChangeSource.ONLINE_REFRESH)

            // The download chain: a stale server snapshot arrives while the change is still pending
            // (the push that carries it is debounced). The log has to say that the snapshot was
            // merged against the agreement and how much it tried to drop.
            deliver(LxListAction.DataOverwrite(listData(userList = listOf(serverImportedList("p", "我喜欢", listOf("a"))))))
            val received = lines.last { it.first == SyncLog.EVENT_RECEIVE }
            assertEquals("broadcast", received.second["kind"])
            assertEquals(true, received.second["agreed"])
            val applied = lines.last { it.first == SyncLog.EVENT_APPLY }
            assertEquals("three_way", applied.second["mode"])
            assertEquals("the local-only song was kept", 1, applied.second["keptLocalOnly"])
            assertEquals(listOf("kw_a", "kw_new"), tvIds("p"))

            awaitPush { it is LxListAction.AddMusic }

            // The upload chain: which list changed, how many songs, and whether an action went out.
            val detected = lines.last { it.first == SyncLog.EVENT_CHANGE_DETECTED }
            assertEquals("online_refresh", detected.second["source"])
            val diff = lines.last { it.first == SyncLog.EVENT_DIFF }
            assertEquals("a song the agreement lacks", 1, diff.second["added"])
            assertEquals(0, diff.second["removed"])
            assertEquals("one official action was built", 1, diff.second["actions"])
            assertEquals("for the list that changed", "p", diff.second["targets"])
            val pushed = lines.last { it.first == SyncLog.EVENT_PUSH_OK }
            assertEquals("and it really went out", 1, pushed.second["sent"])
            assertEquals("p", pushed.second["targets"])

            // One correlation id ties the change, the diff and the push together.
            val corr = detected.second["corr"]
            assertNotNull("every line carries a correlation id", corr)
            assertEquals(corr, diff.second["corr"])
            assertEquals(corr, pushed.second["corr"])
        } finally {
            SyncLog.reset()
        }
    }
}
