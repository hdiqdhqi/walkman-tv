package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The locally kept "last state both sides agreed on" (spec §8). */
class SyncBaselineStoreTest {

    private fun state(love: List<String> = emptyList(), user: List<Playlist> = emptyList()) =
        LibraryStore.SyncLists(
            defaultList = emptyList(),
            love = love.map { track(songmid = it) },
            userLists = user,
        )

    @Test
    fun `a baseline round trips through the file`() = runBlocking {
        val dir = tempDir()
        val store = SyncBaselineStore(dir)
        store.loadAll()
        assertNull("a fresh install has no agreement", store.current())

        val baseline = SyncBaselines.of(
            state(love = listOf("a"), user = listOf(Playlist("p", "歌单", listOf(track(songmid = "b"))))),
            serverId = "server-1",
            savedAt = 42L,
        )
        store.save(baseline)

        val reopened = SyncBaselineStore(dir)
        reopened.loadAll()
        assertEquals(baseline, reopened.current())
        assertEquals(listOf("kw_a"), reopened.current()!!.loveList)
        assertEquals(listOf("kw_b"), reopened.current()!!.baselineList("p")!!.ids)
    }

    @Test
    fun `a different server or account has no baseline`() = runBlocking {
        val store = SyncBaselineStore(tempDir())
        store.loadAll()
        store.save(SyncBaselines.of(state(love = listOf("a")), serverId = "server-1"))

        assertNull(store.forServer("server-2"))
        assertNull(store.forServer(null))
        assertNull(store.forServer(""))
        assertEquals(listOf("kw_a"), store.forServer("server-1")!!.loveList)
    }

    @Test
    fun `clearing forgets the agreement`() = runBlocking {
        val dir = tempDir()
        val store = SyncBaselineStore(dir)
        store.loadAll()
        store.save(SyncBaselines.of(state(love = listOf("a")), serverId = "server-1"))
        store.clear()

        assertNull(store.current())
        val reopened = SyncBaselineStore(dir)
        reopened.loadAll()
        assertNull(reopened.current())
    }

    @Test
    fun `a file written without a server id is treated as no agreement`() = runBlocking {
        val store = SyncBaselineStore(tempDir())
        store.loadAll()
        store.save(SyncBaseline())
        assertTrue(store.current() == null)
    }

    @Test
    fun `the stored baseline carries ids and names, never track titles or urls`() = runBlocking {
        val dir = tempDir()
        val store = SyncBaselineStore(dir)
        store.loadAll()
        store.save(
            SyncBaselines.of(
                state(
                    user = listOf(
                        Playlist("p", "歌单", listOf(track(songmid = "a", name = "很长的标题", picURL = "http://cover/x"))),
                    ),
                ),
                serverId = "server-1",
            ),
        )
        val raw = java.io.File(dir, "lxSyncBaseline.json").readText()
        assertTrue(raw.contains("kw_a"))
        assertTrue(raw.contains("歌单"))
        assertFalse(raw.contains("很长的标题"))
        assertFalse(raw.contains("http://cover/x"))
    }
}
