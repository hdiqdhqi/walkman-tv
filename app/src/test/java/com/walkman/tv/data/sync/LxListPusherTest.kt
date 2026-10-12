package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The push half of the protocol: what this device sends as `onListSyncAction`, and - just as
 * important - when it stays silent. The agreement may only advance after the server accepted the
 * whole batch (spec §6.6/§6.7).
 */
class LxListPusherTest {

    private val server = "server-1"
    private lateinit var dir: File
    private lateinit var library: LibraryStore
    private lateinit var baselines: SyncBaselineStore

    @Before
    fun setUp() = runBlocking {
        dir = tempDir()
        library = LibraryStore(dir)
        library.loadAll()
        baselines = SyncBaselineStore(dir)
        baselines.loadAll()
    }

    private fun state(love: List<String> = emptyList()) = LibraryStore.SyncLists(
        defaultList = emptyList(),
        love = love.map { track(songmid = it) },
        userLists = emptyList(),
    )

    private suspend fun seed(love: List<String>) = library.overwriteSyncLists(state(love))

    private suspend fun agree(love: List<String>) =
        baselines.save(SyncBaselines.of(state(love), serverId = server))

    private class Recorder {
        val sent = ArrayList<LxListAction>()
        val send: suspend (LxListAction) -> Unit = { sent += it }
    }

    @Test
    fun `without a live session the edit stays for the next sync`() = runBlocking {
        seed(listOf("a"))
        agree(emptyList())
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { false }, send = recorder.send)

        assertEquals(LxListPusher.Outcome.Offline, pusher.pushPending("test"))
        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `never having synced means there is nothing to push`() = runBlocking {
        seed(listOf("a"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        assertEquals(LxListPusher.Outcome.NoBaseline, pusher.pushPending("test"))
        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `nothing changed is not pushed`() = runBlocking {
        seed(listOf("a"))
        agree(listOf("a"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        assertEquals(LxListPusher.Outcome.NoChange, pusher.pushPending("test"))
        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `a delivered batch advances the agreement`() = runBlocking {
        seed(listOf("a", "b"))
        agree(listOf("a"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        val outcome = pusher.pushPending("test")
        assertTrue(outcome is LxListPusher.Outcome.Pushed)
        assertEquals(listOf("kw_b"), (recorder.sent.single() as LxListAction.AddMusic).musicInfos.map { it.id })
        assertEquals(listOf("kw_a", "kw_b"), baselines.current()!!.loveList)
    }

    @Test
    fun `the agreement keeps the server order, not this device's order`() = runBlocking {
        // The server keeps its own order for songs it already had and appends what we pushed.
        seed(listOf("b", "a", "new"))
        agree(listOf("a", "b"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        assertTrue(pusher.pushPending("test") is LxListPusher.Outcome.Pushed)
        assertEquals(listOf("kw_a", "kw_b", "kw_new"), baselines.current()!!.loveList)
        assertEquals(listOf("kw_b", "kw_a", "kw_new"), library.syncLists().love.map { it.id })
    }

    @Test
    fun `a favourite pushed to the top is recorded in front`() = runBlocking {
        seed(listOf("new", "a"))
        agree(listOf("a"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        pusher.pushPending("test")
        assertEquals(AddMusicLocation.TOP, (recorder.sent.single() as LxListAction.AddMusic).location)
        assertEquals(listOf("kw_new", "kw_a"), baselines.current()!!.loveList)
    }

    @Test
    fun `a failed action neither advances the agreement nor loses the edit`() = runBlocking {
        seed(listOf("a", "b"))
        agree(listOf("a"))
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = { throw LxSyncRpcException("boom") })

        assertTrue(pusher.pushPending("test") is LxListPusher.Outcome.Failed)
        assertEquals("the previous agreement must survive", listOf("kw_a"), baselines.current()!!.loveList)
        // The edit is still pending: the next push tries the very same action again.
        val recorder = Recorder()
        val retry = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)
        assertTrue(retry.pushPending("retry") is LxListPusher.Outcome.Pushed)
        assertEquals(listOf("kw_b"), (recorder.sent.single() as LxListAction.AddMusic).musicInfos.map { it.id })
    }

    @Test
    fun `a half delivered batch is retried as a whole`() = runBlocking {
        library.overwriteSyncLists(
            LibraryStore.SyncLists(
                defaultList = emptyList(),
                love = listOf(track(songmid = "b")),
                userLists = listOf(Playlist("p", "歌单", listOf(track(songmid = "c")))),
            ),
        )
        agree(listOf("a"))
        var calls = 0
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = { calls++; if (calls == 2) throw LxSyncRpcException("boom") })

        assertTrue(pusher.pushPending("test") is LxListPusher.Outcome.Failed)
        assertEquals(2, calls)
        assertEquals(listOf("kw_a"), baselines.current()!!.loveList)
        // The whole batch is still pending: a retry starts from the first action again.
        val recorder = Recorder()
        LxListPusher(library, baselines, isLive = { true }, send = recorder.send).pushPending("retry")
        // The whole batch (create the list, drop `a`, add `b`, add the new list's song) is retried
        // from the first action - nothing is skipped because an earlier attempt got through.
        assertEquals(recorder.sent.toString(), 4, recorder.sent.size)
        assertTrue(recorder.sent.any { it is LxListAction.RemoveMusic })
    }

    @Test
    fun `two concurrent pushes send the change exactly once`() = runBlocking {
        seed(listOf("a"))
        agree(emptyList())
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        val first = async { pusher.pushPending("one") }
        val second = async { pusher.pushPending("two") }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, recorder.sent.size)
        assertEquals(1, outcomes.count { it is LxListPusher.Outcome.Pushed })
        assertEquals(1, outcomes.count { it is LxListPusher.Outcome.NoChange })
    }

    @Test
    fun `adopting the server view replaces the agreement`() = runBlocking {
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = { })
        pusher.adoptAgreement(SyncBaselines.of(state(listOf("a")), serverId = server))
        assertEquals(listOf("kw_a"), baselines.current()!!.loveList)
    }

    @Test
    fun `a deleted song is pushed as a removal`() = runBlocking {
        seed(listOf("b"))
        agree(listOf("a", "b"))
        val recorder = Recorder()
        val pusher = LxListPusher(library, baselines, isLive = { true }, send = recorder.send)

        pusher.pushPending("test")
        val remove = recorder.sent.single() as LxListAction.RemoveMusic
        assertEquals(Playlist.LOVE, remove.listId)
        assertEquals(listOf("kw_a"), remove.ids)
        assertEquals(listOf("kw_b"), baselines.current()!!.loveList)
    }
}
