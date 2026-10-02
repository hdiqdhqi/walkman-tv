package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot / 3-way-merge semantics from the client's side.
 *
 * The official server owns the merge; opting in with `skipSnapshot = false` means the client must
 * (a) report its lists exactly - including deletions - so the server's snapshot diff can detect
 * them, and (b) apply the server's result as an exact replacement, never as a union.
 */
class SyncSnapshotSemanticsTest {

    private fun repo(store: LibraryStore) = SyncRepository(store)

    @Test
    fun `local deletion is visible in the upload and changes the md5`() {
        val store = LibraryStore(tempDir())
        val repository = repo(store)
        runBlocking {
            store.overwriteSyncLists(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a"), track(songmid = "b")), emptyList()))
            val before = repository.readListData()
            val md5Before = repository.listDataMd5(before)
            assertTrue(LxListJson.encode(before).contains("kw_b"))

            store.mutateSyncLists { lists -> lists.copy(love = lists.love.filterNot { it.id == "kw_b" }) }

            val after = repository.readListData()
            assertEquals(listOf("kw_a"), after.loveList.map { it.id })
            assertNotEquals(md5Before, repository.listDataMd5(after))
        }
    }

    @Test
    fun `applying the server result twice is idempotent and md5-stable`() {
        val store = LibraryStore(tempDir())
        val repository = repo(store)
        runBlocking {
            val serverData = listData(
                loveList = listOf(musicInfo(songmid = "a"), musicInfo(songmid = "b")),
                userList = listOf(userList(id = "pl", name = "歌单", tracks = listOf(musicInfo(songmid = "x")))),
            )
            repository.applyListData(serverData)
            val first = repository.readListData()
            val md5First = repository.listDataMd5(first)

            // Re-delivered message / late duplicate: must not duplicate anything or churn the md5.
            repository.applyListData(LxListJson.decode(LxListJson.encode(serverData)))
            val second = repository.readListData()
            assertEquals(md5First, repository.listDataMd5(second))
            assertEquals(listOf("kw_a", "kw_b"), second.loveList.map { it.id })
            assertEquals(listOf("kw_x"), second.userList[0].list.map { it.id })
        }
    }

    @Test
    fun `a locally added song is uploaded and survives the server write-back`() {
        val store = LibraryStore(tempDir())
        val repository = repo(store)
        runBlocking {
            // Nothing local, nothing remote: the client must still complete cleanly.
            repository.applyListData(listData())
            assertEquals(emptyList<String>(), repository.readListData().loveList.map { it.id })

            // Local add -> uploaded -> server echoes the merged list back.
            store.overwriteSyncLists(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "local-add")), emptyList()))
            val uploaded = repository.readListData()
            repository.applyListData(uploaded)
            assertEquals(listOf("kw_local-add"), repository.readListData().loveList.map { it.id })
        }
    }

    @Test
    fun `same-name playlists with different ids are never merged`() {
        val store = LibraryStore(tempDir())
        val repository = repo(store)
        runBlocking {
            store.overwriteSyncLists(
                LibraryStore.SyncLists(
                    emptyList(),
                    emptyList(),
                    listOf(
                        Playlist(id = "id-1", name = "同名", tracks = listOf(track(songmid = "a"))),
                        Playlist(id = "id-2", name = "同名", tracks = listOf(track(songmid = "b"))),
                    ),
                ),
            )
            val data = repository.readListData()
            assertEquals(listOf("id-1", "id-2"), data.userList.map { it.id })
            assertEquals(listOf("kw_a", "kw_b"), data.userList.map { it.list.first().id })

            // The server removes one of them by id; the other must stay untouched.
            repository.applyListData(data.copy(userList = data.userList.filter { it.id == "id-2" }))
            val after = store.syncLists().userLists
            assertEquals(listOf("id-2"), after.map { it.id })
            assertEquals(listOf("kw_b"), after[0].tracks.map { it.id })
        }
    }

    @Test
    fun `server-side deletion of a playlist is applied, not resurrected`() {
        val store = LibraryStore(tempDir())
        val repository = repo(store)
        runBlocking {
            store.overwriteSyncLists(
                LibraryStore.SyncLists(
                    emptyList(),
                    listOf(track(songmid = "a"), track(songmid = "b")),
                    listOf(Playlist(id = "pl", name = "歌单", tracks = listOf(track(songmid = "x")))),
                ),
            )
            // The server decided "b" and "pl" are gone everywhere.
            repository.applyListData(listData(loveList = listOf(musicInfo(songmid = "a"))))
            assertEquals(listOf("kw_a"), store.syncLists().love.map { it.id })
            assertEquals(emptyList<Playlist>(), store.syncLists().userLists)
        }
    }
}