package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 电视 → 电脑 / 电视 → 手机: the acceptance direction of the reported 合并-mode bug.
 *
 * Three real clients (the TV, a PC, a phone) talk to one [LxSyncServerSim] - the offline port of the
 * official `lx-music-sync-server` list module. Each device keeps its own `library.json`, its own
 * agreement ([SyncBaselineStore]) and its own per-device snapshot on the server, exactly like a
 * household that has synced before. Every test asserts on the *server's* stored list and on the
 * *peers'* libraries after their next sync - never on "no exception was thrown".
 *
 * The TV is deliberately left at its defaults (`自动同步` off): a change the user makes on the TV has
 * to leave the device on its own, without anyone pressing 立即同步 and without a background setting.
 */
class LxSyncCrossDeviceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var sim: LxSyncServerSim
    private lateinit var tv: Device
    private lateinit var pc: Device
    private lateinit var phone: Device

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        sim = LxSyncServerSim(DEVICE_TV)
        tv = Device(DEVICE_TV, "电视", tempDir())
        pc = Device(DEVICE_PC, "电脑", tempDir())
        phone = Device(DEVICE_PHONE, "手机", tempDir())
        listOf(tv, pc, phone).forEach { it.start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------- scenarios

    @Test(timeout = 60_000)
    fun `电视新增歌曲后，电脑和手机在下次同步时都会拿到`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        tv.library.addToList("p", track(songmid = "b"))

        tv.awaitServer { serverIds("p") == listOf("kw_a", "kw_b") }
        pc.syncNow()
        phone.syncNow()

        assertEquals("电脑必须拿到电视新增的歌曲", listOf("kw_a", "kw_b"), pc.ids("p"))
        assertEquals("手机必须拿到电视新增的歌曲", listOf("kw_a", "kw_b"), phone.ids("p"))
    }

    @Test(timeout = 60_000)
    fun `电视删除歌曲后，电脑和手机在下次同步时都会删掉`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a", "b")))

        tv.library.removeFromList("p", track(songmid = "b").id)

        tv.awaitServer { serverIds("p") == listOf("kw_a") }
        pc.syncNow()
        phone.syncNow()

        assertEquals("删除必须传播到电脑，而不能被旧快照带回来", listOf("kw_a"), pc.ids("p"))
        assertEquals("删除必须传播到手机", listOf("kw_a"), phone.ids("p"))

        // A further round must be a no-op on every side: the deletion may not come back.
        pc.syncNow()
        phone.syncNow()
        assertEquals(listOf("kw_a"), serverIds("p"))
        assertEquals(listOf("kw_a"), tv.ids("p"))
    }

    @Test(timeout = 60_000)
    fun `电视新建歌单后，电脑和手机在下次同步时都会拿到`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        val created = tv.library.createList("新歌单")
        tv.library.addAllToList(created.id, listOf(track(songmid = "n")))

        tv.awaitServer { sim.listData.userList.size == 2 }
        pc.syncNow()
        phone.syncNow()

        for (peer in listOf(pc, phone)) {
            assertEquals("${peer.label}: 新歌单必须存在", 2, peer.library.syncLists().userLists.size)
            assertEquals("${peer.label}: 新歌单必须带着它的歌曲", listOf("kw_n"), peer.ids(created.id))
            assertEquals("${peer.label}: 名字必须跟着走", "新歌单", peer.name(created.id))
        }
    }

    @Test(timeout = 60_000)
    fun `电视修改歌单名称后，电脑和手机在下次同步时都读到新名称`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        tv.library.renameList("p", "改过的名字")

        tv.awaitServer { serverName("p") == "改过的名字" }
        pc.syncNow()
        phone.syncNow()

        assertEquals("电脑必须读到新名称", "改过的名字", pc.name("p"))
        assertEquals("手机必须读到新名称", "改过的名字", phone.name("p"))
        // The rename is metadata only: the songs stay where they were.
        assertEquals(listOf("kw_a"), pc.ids("p"))
    }

    @Test(timeout = 60_000)
    fun `电脑或手机修改后，电视在下次同步时也会拿到`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        pc.library.addToList("p", track(songmid = "c"))
        pc.awaitServer { serverIds("p") == listOf("kw_a", "kw_c") }

        tv.syncNow()
        assertEquals("反方向也必须通", listOf("kw_a", "kw_c"), tv.ids("p"))

        // …and the phone gets it too, without the TV's copy ever being re-uploaded by accident.
        phone.syncNow()
        assertEquals(listOf("kw_a", "kw_c"), phone.ids("p"))
    }

    @Test(timeout = 60_000)
    fun `重复执行合并同步不会产生重复歌单或歌曲`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        tv.library.addToList("p", track(songmid = "b"))
        tv.awaitServer { serverIds("p") == listOf("kw_a", "kw_b") }

        repeat(3) {
            tv.syncNow()
            pc.syncNow()
            phone.syncNow()
        }

        assertEquals("电视不得出现重复歌曲", listOf("kw_a", "kw_b"), tv.ids("p"))
        assertEquals("电脑不得出现重复歌曲", listOf("kw_a", "kw_b"), pc.ids("p"))
        assertEquals("手机不得出现重复歌曲", listOf("kw_a", "kw_b"), phone.ids("p"))
        assertEquals("服务器不得出现重复歌曲", listOf("kw_a", "kw_b"), serverIds("p"))
        assertEquals("不得出现重复歌单", 1, tv.library.syncLists().userLists.size)
        assertEquals("不得出现重复歌单", 1, pc.library.syncLists().userLists.size)
    }

    @Test(timeout = 60_000)
    fun `重新打开客户端后，已同步的修改仍然存在`() = runBlocking {
        seedAgreement(agreed("p", "歌单", listOf("a")))

        tv.library.addToList("p", track(songmid = "b"))
        tv.awaitServer { serverIds("p") == listOf("kw_a", "kw_b") }
        phone.syncNow()

        // Reopen the phone on the same files dir: a fresh store reading library.json + the baseline.
        val reopened = Device(DEVICE_PHONE, "手机(重启后)", phone.dir)
        reopened.start()
        assertEquals("重启后本地修改仍在", listOf("kw_a", "kw_b"), reopened.ids("p"))

        // …and its next sync is a no-op that neither duplicates nor undoes anything.
        reopened.syncNow()
        assertEquals(listOf("kw_a", "kw_b"), reopened.ids("p"))
        assertEquals(listOf("kw_a", "kw_b"), serverIds("p"))
    }

    // ------------------------------------------------------------------------------------ helpers

    /** The state all three devices and the server agreed on before the scenario: the server's list,
     *  every device's own copy, its baseline and the server's per-device snapshot. */
    private suspend fun seedAgreement(state: LxListData) {
        sim.setListData(state)
        for (device in listOf(tv, pc, phone)) {
            device.library.overwriteSyncLists(LxListMapper.toSyncLists(state))
            device.baselines.save(SyncBaselines.of(state, SERVER_ID))
        }
        for (clientId in ALL_DEVICES) sim.seedSnapshot(state, clientId)
    }

    private fun agreed(listId: String, name: String, songmids: List<String>) = listData(
        userList = listOf(
            userList(
                id = listId,
                name = name,
                tracks = songmids.map { musicInfo(songmid = it) },
                locationUpdateTime = 1000L,
            ),
        ),
    )

    private fun serverIds(listId: String): List<String> =
        sim.listData.userList.firstOrNull { it.id == listId }?.list?.map { it.id }.orEmpty()

    private fun serverName(listId: String): String? =
        sim.listData.userList.firstOrNull { it.id == listId }?.name

    /** One LX client: its own store, settings, agreement and transport, plus the helpers to read the
     *  lists the way the protocol sees them. */
    private inner class Device(private val clientId: String, val label: String, val dir: File) {
        val settingsStore = SettingsStore(dir)
        val library = LibraryStore(dir)
        val baselines = SyncBaselineStore(dir)
        lateinit var client: SimSyncClient
        lateinit var manager: SyncManager

        /** Loads everything from disk and starts the manager, exactly like AppContainer.bootstrap. */
        suspend fun start() {
            settingsStore.loadAll()
            library.loadAll()
            baselines.loadAll()
            client = SimSyncClient(scope, sim, clientId = clientId)
            manager = SyncManager(settingsStore, client, library, scope, baselines)
            settingsStore.update {
                it.copy(
                    syncEnabled = true,
                    syncServerUrl = "https://example.com/sync",
                    syncConnectionCode = "1234",
                    // The TV's defaults: no background syncing, 合并 chosen once.
                    syncAutoSync = false,
                    syncMode = LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE,
                )
            }
            manager.start()
        }

        /** 立即同步, asserted to have really succeeded. */
        suspend fun syncNow() {
            assertTrue(
                "$label 同步失败: state=${manager.state.value}, msg=${manager.message.value}",
                manager.syncNow().ok,
            )
        }

        /** Waits for this device's own edit to reach the server without a manual sync. */
        suspend fun awaitServer(timeoutMs: Long = 20_000, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (predicate()) return
                delay(25)
            }
            throw AssertionError(
                "$label 的修改没有到达服务器: state=${manager.state.value}, msg=${manager.message.value}",
            )
        }

        /** The device's list as the sync protocol reads it, in order. */
        fun ids(listId: String): List<String> =
            library.syncLists().userLists.firstOrNull { it.id == listId }?.tracks?.map { it.id }.orEmpty()

        fun name(listId: String): String? =
            library.syncLists().userLists.firstOrNull { it.id == listId }?.name
    }

    private companion object {
        const val DEVICE_TV = "tv-device"
        const val DEVICE_PC = "pc-device"
        const val DEVICE_PHONE = "phone-device"
        val ALL_DEVICES = listOf(DEVICE_TV, DEVICE_PC, DEVICE_PHONE)

        /** The fingerprint of the account on the server the fake auth hands out. */
        const val SERVER_ID = "server-1"
    }
}
