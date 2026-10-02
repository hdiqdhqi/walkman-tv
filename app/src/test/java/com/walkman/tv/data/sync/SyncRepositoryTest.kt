package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Walkman library <-> official LX `ListData` boundary. */
class SyncRepositoryTest {

    private fun store(): LibraryStore = LibraryStore(tempDir())

    private fun seed(store: LibraryStore, lists: LibraryStore.SyncLists) = runBlocking {
        store.overwriteSyncLists(lists)
    }

    @Test
    fun `love list maps both ways`() {
        val store = store()
        val a = track(songmid = "a", name = "甲")
        val b = track(songmid = "b", name = "乙")
        seed(store, LibraryStore.SyncLists(emptyList(), listOf(a, b), emptyList()))
        val repo = SyncRepository(store)

        assertEquals(listOf("kw_a", "kw_b"), repo.readListData().loveList.map { it.id })
        assertEquals(emptyList<String>(), repo.readListData().defaultList.map { it.id })

        val written = repo.toSyncLists(listData(loveList = listOf(musicInfo(songmid = "b", name = "乙"))))
        assertEquals(listOf("kw_b"), written.love.map { it.id })
        assertEquals("乙", written.love[0].name)
    }

    @Test
    fun `user lists keep id name metadata and track order`() {
        val store = store()
        seed(
            store,
            LibraryStore.SyncLists(
                defaultList = emptyList(),
                love = emptyList(),
                userLists = listOf(
                    Playlist(id = "pl-1", name = "清晨", tracks = listOf(track(songmid = "x"), track(songmid = "y"))),
                    Playlist(
                        id = "pl-2",
                        name = "夜晚",
                        tracks = listOf(track(songmid = "z")),
                        source = "kg",
                        sourceListId = "sl-9",
                        updatedAt = 1700000000000L,
                    ),
                ),
            ),
        )
        val repo = SyncRepository(store)
        val data = repo.readListData()

        assertEquals(listOf("pl-1", "pl-2"), data.userList.map { it.id })
        assertEquals(listOf("清晨", "夜晚"), data.userList.map { it.name })
        assertEquals(listOf("kw_x", "kw_y"), data.userList[0].list.map { it.id })
        assertEquals("kg", data.userList[1].source)
        assertEquals("sl-9", data.userList[1].sourceListId)
        assertEquals(1700000000000L, data.userList[1].locationUpdateTime)

        val back = repo.toSyncLists(data).userLists
        assertEquals(listOf("pl-1", "pl-2"), back.map { it.id })
        assertEquals("kg", back[1].source)
        assertEquals("sl-9", back[1].sourceListId)
        assertEquals(1700000000000L, back[1].updatedAt)
        assertEquals(listOf("kw_x", "kw_y", "kw_z"), back.flatMap { p -> p.tracks.map { it.id } })
    }

    @Test
    fun `defaultList round trips but is never love or history`() {
        val store = store()
        seed(
            store,
            LibraryStore.SyncLists(
                defaultList = listOf(track(songmid = "d1"), track(songmid = "d2")),
                love = listOf(track(songmid = "l1")),
                userLists = emptyList(),
            ),
        )
        val repo = SyncRepository(store)
        val data = repo.readListData()

        assertEquals(listOf("kw_d1", "kw_d2"), data.defaultList.map { it.id })
        assertEquals(listOf("kw_l1"), data.loveList.map { it.id })
        assertTrue(data.userList.isEmpty())

        val written = repo.toSyncLists(data)
        assertEquals(listOf("kw_d1", "kw_d2"), written.defaultList.map { it.id })
        assertEquals(listOf("kw_l1"), written.love.map { it.id })
        assertTrue(written.userLists.isEmpty())
    }

    @Test
    fun `reserved and duplicate user list ids are dropped`() {
        val store = store()
        val repo = SyncRepository(store)
        val written = repo.toSyncLists(
            listData(
                userList = listOf(
                    userList(id = "love", name = "冒充收藏"),
                    userList(id = "history", name = "冒充历史"),
                    userList(id = "default", name = "冒充默认"),
                    userList(id = "temp", name = "临时"),
                    userList(id = "", name = "空id"),
                    userList(id = "pl-1", name = "正常"),
                    userList(id = "pl-1", name = "重复"),
                ),
            ),
        )
        assertEquals(listOf("pl-1"), written.userLists.map { it.id })
        assertEquals("正常", written.userLists[0].name)
    }

    @Test
    fun `unrepresentable music info is dropped instead of corrupting the library`() {
        val store = store()
        val repo = SyncRepository(store)
        val written = repo.toSyncLists(
            listData(
                loveList = listOf(
                    MusicInfo(id = "kw_ok", name = "好", source = "kw"),
                    MusicInfo(id = "zz_bad", name = "坏源", source = "zz"),
                    MusicInfo(id = "kw_noname", name = "", source = "kw"),
                ),
            ),
        )
        assertEquals(listOf("kw_ok"), written.love.map { it.id })
    }

    @Test
    fun `history is never read into the sync payload`() {
        val store = store()
        val favorite = track(songmid = "fav", name = "收藏的")
        val historyOnly = track(songmid = "hist", name = "只听过")
        seed(store, LibraryStore.SyncLists(emptyList(), listOf(favorite), emptyList()))
        runBlocking { store.recordHistory(historyOnly) }

        val repo = SyncRepository(store)
        val data = repo.readListData()
        val serialized = LxListJson.encode(data)

        assertFalse(serialized, serialized.contains("hist"))
        assertEquals(listOf("kw_fav"), data.loveList.map { it.id })
        // Local history is still intact on this device.
        assertEquals(listOf("kw_hist"), store.history.value.tracks.map { it.id })
    }

    @Test
    fun `md5 tracks the serialized lists`() {
        val store = store()
        val repo = SyncRepository(store)
        seed(store, LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a")), emptyList()))
        val md5A = repo.listDataMd5()
        assertEquals(md5A, LxListJson.md5(repo.readListData()))
        seed(store, LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a"), track(songmid = "b")), emptyList()))
        assertNotEquals(md5A, repo.listDataMd5())
    }

    @Test
    fun `applying server data replaces lists - it is not a union`() {
        val store = store()
        seed(
            store,
            LibraryStore.SyncLists(
                defaultList = emptyList(),
                love = listOf(track(songmid = "a"), track(songmid = "b")),
                userLists = listOf(Playlist(id = "pl-1", name = "旧", tracks = listOf(track(songmid = "x")))),
            ),
        )
        val repo = SyncRepository(store)
        runBlocking {
            repo.applyListData(
                listData(
                    loveList = listOf(musicInfo(songmid = "a")),
                    userList = listOf(userList(id = "pl-2", name = "新", tracks = listOf(musicInfo(songmid = "y")))),
                ),
            )
        }
        val lists = store.syncLists()
        // "b" and "pl-1" disappeared: the server explicitly said they are gone.
        assertEquals(listOf("kw_a"), lists.love.map { it.id })
        assertEquals(listOf("pl-2"), lists.userLists.map { it.id })
        assertEquals(listOf("kw_y"), lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `server writes never emit a user change event`() {
        val store = store()
        runBlocking {
            var emissions = 0
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { store.changes.collect { emissions++ } }
            try {
                // Let the collector subscribe, then prove it is live with a real user edit.
                kotlinx.coroutines.delay(100)
                store.toggleFavorite(track(songmid = "user-edit"))
                kotlinx.coroutines.delay(100)
                assertEquals("user edit must be observed", 1, emissions)

                // Remote writes apply the same data but must not look like user edits, otherwise
                // 自动同步 would re-trigger itself forever.
                store.overwriteSyncLists(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a")), emptyList()))
                store.mutateSyncLists { it.copy(love = emptyList()) }
                kotlinx.coroutines.delay(100)
                assertEquals("remote writes must stay silent", 1, emissions)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `a music info with nested meta survives the library round trip`() {
        val store = store()
        val repo = SyncRepository(store)
        val info = MusicInfo(
            id = "kg_nested",
            name = "嵌套",
            singer = "手",
            source = "kg",
            interval = "02:00",
            meta = kotlinx.serialization.json.buildJsonObject {
                put("songId", JsonPrimitive("nested"))
                put("custom", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("flac")) })
                put(
                    "qualitys",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", JsonPrimitive("flac"))
                                put("size", JsonPrimitive("28.1M"))
                                put("hash", JsonPrimitive("kg-flac-hash"))
                            },
                        )
                    },
                )
            },
        )
        runBlocking { repo.applyListData(listData(loveList = listOf(info))) }
        val again = repo.readListData().loveList[0]
        // Nested meta survives verbatim - a lossy rewrite here would make the protocol MD5
        // change on every round trip and keep the server's snapshot permanently dirty.
        assertEquals(info.meta["custom"], again.meta["custom"])
        assertEquals(info.meta["qualitys"], again.meta["qualitys"])
        assertEquals("nested", (again.meta["songId"] as JsonPrimitive).content)
    }
}