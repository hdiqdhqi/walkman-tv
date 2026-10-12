package com.walkman.tv.data.sync

import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The data policy of one sync session: a first sync keeps the previous (server-driven) behaviour, a
 * daily sync merges instead of overwriting, and the agreement only advances when the session really
 * finished (spec §4.1/§4.2/§6).
 */
class LxSyncSessionTest {

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

    /** Mirrors the real wiring: the owner of the session persists what was agreed. */
    private fun session(onAgreed: suspend (SyncBaseline) -> Unit = {}): LxSyncSession =
        LxSyncSession(library, repository, baselines, { server }, { view -> baselines.save(view); onAgreed(view) })

    private fun seed(love: List<String>) = runBlocking {
        library.overwriteSyncLists(
            LibraryStore.SyncLists(emptyList(), love.map { track(songmid = it) }, emptyList()),
        )
    }

    /** Records the state both sides last agreed on - the current library, as after a real session. */
    private fun agree() = runBlocking {
        baselines.save(SyncBaselines.of(library.syncLists(), serverId = server))
    }

    private fun loveIds() = library.syncLists().love.map { it.id }

    private fun payload(vararg loveMids: String) = listData(loveList = loveMids.map { musicInfo(songmid = it) })

    @Test
    fun `a first sync applies the server payload verbatim`() = runBlocking {
        seed(listOf("local"))
        session().applyRemote(payload("srv"))
        assertEquals(listOf("kw_srv"), loveIds())
    }

    @Test
    fun `a daily sync keeps a song this device added`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        session().applyRemote(payload("a"))

        assertEquals(listOf("kw_a", "kw_local"), loveIds())
    }

    @Test
    fun `a daily sync accepts a deletion made elsewhere`() = runBlocking {
        seed(listOf("a", "b"))
        agree()

        session().applyRemote(payload("b"))

        assertEquals(listOf("kw_b"), loveIds())
    }

    @Test
    fun `the 同步方式 the user picked is still honoured`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))

        val session = session()
        session.onModeAnswer(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE)
        session.applyRemote(payload("a"))

        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `the uploaded lists are reported exactly, deletions included`() = runBlocking {
        seed(listOf("b"))
        agree()

        val uploaded = session().onGetListData()

        assertEquals(listOf("kw_b"), uploaded.loveList.map { it.id })
    }

    @Test
    fun `finishing records what the server holds and asks for a flush`() = runBlocking {
        seed(listOf("a"))
        var agreed: SyncBaseline? = null
        val session = session { agreed = it }
        session.onMd5()
        session.applyRemote(payload("a", "srv"))
        session.onFinished()

        assertEquals(listOf("kw_a", "kw_srv"), baselines.current()!!.loveList)
        assertEquals(baselines.current(), agreed)
    }

    @Test
    fun `without a server payload the uploaded state becomes the agreement`() = runBlocking {
        seed(listOf("a"))
        val session = session()
        session.onMd5()
        session.onFinished()

        assertEquals(listOf("kw_a"), baselines.current()!!.loveList)
    }

    @Test
    fun `cancel agrees on nothing`() = runBlocking {
        seed(listOf("a"))
        agree()
        val before = baselines.current()
        var agreed = false
        val session = session { agreed = true }

        session.onModeAnswer(LxSyncProtocol.SYNC_MODE_CANCEL)
        session.onFinished()

        assertEquals(before, baselines.current())
        assertTrue("a cancelled session must not flush anything", !agreed)
    }

    @Test
    fun `a payload that changes nothing does not touch the library`() = runBlocking {
        seed(listOf("a"))
        agree()
        val before = library.syncLists()

        session().applyRemote(payload("a"))

        assertEquals(before, library.syncLists())
    }

    @Test
    fun `a different account never merges against the other account's baseline`() = runBlocking {
        seed(listOf("a"))
        agree()
        seed(listOf("a", "local"))
        val otherAccount = LxSyncSession(library, repository, baselines, { "server-2" }, {})

        otherAccount.applyRemote(payload("srv"))

        // No baseline for this account -> the server view wins, nothing gets cross-merged.
        assertEquals(listOf("kw_srv"), loveIds())
    }

    @Test
    fun `reading the md5 never writes`() = runBlocking {
        seed(listOf("a"))
        val session = session()
        val md5 = session.onMd5()
        assertTrue(md5.isNotEmpty())
        assertNull(baselines.current())
        assertEquals(listOf("kw_a"), loveIds())
    }
}
