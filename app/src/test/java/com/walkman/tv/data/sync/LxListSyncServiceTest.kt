package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives the client half of the protocol the way the real server does, with no WebSocket:
 * getEnabledFeatures is answered inside SyncClient, so the fake server here starts at
 * `list_sync_get_md5` and walks the official sequence.
 */
class LxListSyncServiceTest {

    private lateinit var store: LibraryStore
    private lateinit var repository: SyncRepository
    private lateinit var service: LxListSyncService
    private var requestedMode = LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE

    private fun setup(initial: LibraryStore.SyncLists = LibraryStore.SyncLists(emptyList(), emptyList(), emptyList())) {
        store = LibraryStore(tempDir())
        repository = SyncRepository(store)
        service = LxListSyncService(repository, SyncApplier(store, repository)) { SyncModeAnswer(requestedMode) }
        runBlocking { store.overwriteSyncLists(initial) }
    }

    private fun handle(method: String, vararg args: JsonElement): JsonElement? =
        runBlocking { service.handle(method, JsonArray(args.toList())) }

    private fun listAction(name: String, data: JsonElement) = buildJsonObject {
        put("action", JsonPrimitive(name))
        put("data", data)
    }

    private fun music(id: String) = buildJsonObject {
        put("id", JsonPrimitive("kw_$id"))
        put("name", JsonPrimitive("歌"))
        put("singer", JsonPrimitive("手"))
        put("source", JsonPrimitive("kw"))
        put("interval", JsonPrimitive("03:00"))
        put("meta", buildJsonObject { put("songId", JsonPrimitive(id)) })
    }

    @Test
    fun `get_md5 answers the protocol serialization of the local lists`() {
        setup(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a")), emptyList()))
        val answer = handle("list_sync_get_md5") as JsonPrimitive
        assertEquals(repository.listDataMd5(), answer.content)
        assertEquals(32, answer.content.length)
    }

    @Test
    fun `get_list_data uploads the local lists in protocol shape`() {
        setup(
            LibraryStore.SyncLists(
                defaultList = listOf(track(songmid = "d")),
                love = listOf(track(songmid = "l")),
                userLists = listOf(Playlist(id = "u", name = "歌单", tracks = listOf(track(songmid = "x")))),
            ),
        )
        val answer = handle("list_sync_get_list_data") as JsonObject
        assertTrue(answer.containsKey("defaultList"))
        assertTrue(answer.containsKey("loveList"))
        assertTrue(answer.containsKey("userList"))
        val decoded = LxListJson.decode(answer.toString())
        assertEquals(listOf("kw_l"), decoded.loveList.map { it.id })
        assertEquals(listOf("u"), decoded.userList.map { it.id })
        assertEquals(listOf("kw_x"), decoded.userList[0].list.map { it.id })
        // History is not part of the payload type at all.
        assertFalse(answer.toString().contains("history"))
    }

    @Test
    fun `get_sync_mode forwards the user's choice`() {
        setup()
        requestedMode = LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL
        val answer = handle("list_sync_get_sync_mode") as JsonPrimitive
        assertEquals(LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL, answer.content)
    }

    @Test
    fun `set_list_data applies the server merge result to the library`() {
        setup(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "old")), emptyList()))
        val payload = LxListJson.json.encodeToJsonElement(
            LxListData.serializer(),
            listData(
                loveList = listOf(musicInfo(songmid = "srv")),
                userList = listOf(userList(id = "pl", name = "服务端歌单", tracks = listOf(musicInfo(songmid = "t")))),
            ),
        )
        handle("list_sync_set_list_data", payload)
        val lists = store.syncLists()
        assertEquals(listOf("kw_srv"), lists.love.map { it.id })
        assertEquals(listOf("pl"), lists.userLists.map { it.id })
    }

    @Test
    fun `actions are ignored until the module reported ready`() {
        setup(LibraryStore.SyncLists(emptyList(), emptyList(), emptyList()))
        // Even a malformed action must be silently dropped before ready (official behaviour).
        handle("onListSyncAction", JsonPrimitive("not-an-action"))
        handle("onListSyncAction", listAction("list_remove", buildJsonArray { add(JsonPrimitive("x")) }))
        assertFalse(service.moduleReady)
        assertEquals(emptyList<Playlist>(), store.syncLists().userLists)
    }

    @Test
    fun `finished flips the flags and enables actions`() {
        setup(LibraryStore.SyncLists(emptyList(), emptyList(), emptyList()))
        handle("list_sync_finished")
        assertTrue(service.moduleReady)
        assertTrue(service.finished)

        handle(
            "onListSyncAction",
            listAction(
                "list_create",
                buildJsonObject {
                    put("position", JsonPrimitive(0))
                    put("listInfos", buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive("l1")); put("name", JsonPrimitive("新歌单")) }) })
                },
            ),
        )
        assertEquals(listOf("l1"), store.syncLists().userLists.map { it.id })

        handle(
            "onListSyncAction",
            listAction(
                "list_music_add",
                buildJsonObject {
                    put("id", JsonPrimitive("l1"))
                    put("musicInfos", buildJsonArray { add(music("a")) })
                    put("addMusicLocationType", JsonPrimitive("bottom"))
                },
            ),
        )
        assertEquals(listOf("kw_a"), store.syncLists().userLists[0].tracks.map { it.id })
    }

    @Test
    fun `malformed payloads surface as protocol errors after ready`() {
        setup()
        handle("list_sync_finished")
        try {
            handle("onListSyncAction", listAction("list_music_add", JsonPrimitive("nope")))
            fail("expected LxSyncRpcException")
        } catch (expected: LxSyncRpcException) {
            // The client lets this travel back to the server, which closes with 4100.
        }
        try {
            handle("list_sync_set_list_data")
            fail("expected LxSyncRpcException")
        } catch (expected: LxSyncRpcException) {
            // expected
        }
        try {
            handle("list_sync_do_something_else")
            fail("expected LxSyncRpcException")
        } catch (expected: LxSyncRpcException) {
            // expected
        }
    }

    @Test
    fun `a new session starts from a clean protocol state`() {
        setup()
        handle("list_sync_finished")
        service.onSessionClosed()
        assertFalse(service.moduleReady)
        assertFalse(service.finished)
        handle("onListSyncAction", listAction("list_remove", buildJsonArray { add(JsonPrimitive("l1")) }))
        assertEquals(emptyList<Playlist>(), store.syncLists().userLists)
    }

    @Test
    fun `server payload with unknown fields and null interval is accepted`() {
        setup()
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"loveList":[{"id":"kw_a","name":"歌","source":"kw","interval":null,"meta":{},"future":1}],"userList":[],"defaultList":[],"extra":true}""",
        )
        handle("list_sync_set_list_data", payload)
        assertEquals(listOf("kw_a"), store.syncLists().love.map { it.id })
        assertNull(store.syncLists().love[0].duration)
    }
}
