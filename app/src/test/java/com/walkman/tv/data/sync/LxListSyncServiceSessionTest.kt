package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The protocol adapter must keep working exactly as before when no session policy is installed, and
 * must route every payload through the session's merge once `SyncManager` installed one (spec §4.2).
 */
class LxListSyncServiceSessionTest {

    private val server = "server-1"
    private lateinit var dir: File
    private lateinit var library: LibraryStore
    private lateinit var baselines: SyncBaselineStore
    private lateinit var repository: SyncRepository

    @Before
    fun setUp() = runBlocking {
        dir = tempDir()
        library = LibraryStore(dir)
        library.loadAll()
        baselines = SyncBaselineStore(dir)
        baselines.loadAll()
        repository = SyncRepository(library)
    }

    private fun service() =
        // `prompted = true` models the user answering the picker: the server computed its result
        // with that answer, so the answer governs and the payload is applied as-is.
        LxListSyncService(repository, SyncApplier(library, repository)) {
            SyncModeAnswer(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE, prompted = true)
        }

    private fun session() = LxSyncSession(library, repository, baselines, { server }, {})

    private fun seed(love: List<String>) = runBlocking {
        library.overwriteSyncLists(
            LibraryStore.SyncLists(emptyList(), love.map { track(songmid = it) }, emptyList()),
        )
    }

    private fun agree() = runBlocking {
        baselines.save(SyncBaselines.of(library.syncLists(), serverId = server))
    }

    private fun loveIds() = library.syncLists().love.map { it.id }

    private fun payload(vararg loveMids: String) =
        listData(loveList = loveMids.map { musicInfo(songmid = it) })

    private fun argsOf(element: JsonElement) = JsonArray(listOf(element))

    private suspend fun setListData(service: LxListSyncService, data: LxListData) =
        service.handle(
            "list_sync_set_list_data",
            argsOf(LxListJson.json.encodeToJsonElement(LxListData.serializer(), data)),
        )

    @Test
    fun `set list data is merged while a session is live`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val service = service()
        service.beginSession(session())
        setListData(service, payload("a"))

        assertEquals(listOf("kw_a", "kw_local"), loveIds())
    }

    @Test
    fun `without a session the payload is applied as before`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        setListData(service(), payload("a"))

        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `the policy survives the reconnect that opens the socket`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val service = service()
        service.beginSession(session())
        // SyncManager installs the policy, then opens the socket - and opening it closes the
        // previous one. That reset must not throw the freshly installed policy away.
        service.onSessionClosed()
        setListData(service, payload("a"))

        assertEquals(listOf("kw_a", "kw_local"), loveIds())
    }

    @Test
    fun `clearing the session restores the plain adapter`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val service = service()
        service.beginSession(session())
        service.clearSession()
        setListData(service, payload("a"))

        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `an overwrite broadcast is merged while a session is live`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val service = service()
        service.beginSession(session())
        service.handle("list_sync_finished", JsonArray(emptyList()))
        service.handle(
            "onListSyncAction",
            argsOf(LxListAction.encode(LxListAction.DataOverwrite(payload("a")))),
        )

        assertEquals(listOf("kw_a", "kw_local"), loveIds())
    }

    @Test
    fun `an overwrite broadcast without a session still overwrites`() = runBlocking {
        seed(listOf("a", "local"))
        val service = service()
        service.handle("list_sync_finished", JsonArray(emptyList()))

        service.handle(
            "onListSyncAction",
            argsOf(LxListAction.encode(LxListAction.DataOverwrite(payload("a")))),
        )

        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `actions are ignored until the module reported ready`() = runBlocking {
        seed(listOf("a"))
        val service = service()
        service.beginSession(session())

        val result = service.handle(
            "onListSyncAction",
            argsOf(LxListAction.encode(LxListAction.AddMusic("love", listOf(musicInfo(songmid = "x")), AddMusicLocation.TOP))),
        )

        assertNull(result)
        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `the session answers the md5 and list data calls`() = runBlocking {
        seed(listOf("a"))
        val service = service()
        service.beginSession(session())

        val md5 = service.handle("list_sync_get_md5", JsonArray(emptyList()))
        val data = service.handle("list_sync_get_list_data", JsonArray(emptyList()))

        assertEquals(JsonPrimitive(repository.listDataMd5()), md5)
        assertEquals(
            listOf("kw_a"),
            LxListJson.decode(data.toString()).loveList.map { it.id },
        )
    }

    @Test
    fun `the session learns which 同步方式 the server was told`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val service = service()
        val session = session()
        service.beginSession(session)
        val mode = service.handle("list_sync_get_sync_mode", JsonArray(emptyList()))
        assertEquals(JsonPrimitive(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE), mode)

        setListData(service, payload("a"))

        // The user answered the question for this account, so the answer governs (no merge).
        assertEquals(listOf("kw_a"), loveIds())
    }
}
