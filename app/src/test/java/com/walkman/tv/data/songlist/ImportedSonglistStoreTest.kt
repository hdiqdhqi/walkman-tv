package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.sync.tempDir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Persistence of the origin records — spec §5 (关联信息、重启恢复、删除处理). */
class ImportedSonglistStoreTest {

    private lateinit var dir: File
    private lateinit var store: ImportedSonglistStore

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir = tempDir()
        store = ImportedSonglistStore(dir)
    }

    @Test
    fun `upsert is keyed by list id and readable back`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.WY, "123", lastTrackCount = 5))
        store.upsert(ImportedSonglist("l2", SourceID.TX, "456"))

        assertEquals(2, store.all().size)
        assertEquals(5, store.get("l1")?.lastTrackCount)
        assertEquals(SourceID.TX, store.get("l2")?.source)
    }

    @Test
    fun `ensure never clobbers an existing record`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.WY, "123", autoUpdate = false, rawUrl = "https://a"))

        store.ensure(ImportedSonglist("l1", SourceID.TX, "999", autoUpdate = true, rawUrl = "https://b"))

        val record = store.get("l1")!!
        assertEquals(SourceID.WY, record.source)
        assertEquals("123", record.sourceListId)
        assertEquals("https://a", record.rawUrl)
        assertFalse(record.autoUpdate)
    }

    @Test
    fun `ensure creates the record when absent`() = runBlocking {
        store.ensure(ImportedSonglist("l9", SourceID.KG, "abc"))
        assertEquals("abc", store.get("l9")?.sourceListId)
    }

    @Test
    fun `markApplied stores the successful snapshot and clears the failure state`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.KW, "1", lastError = "旧的失败", consecutiveFailures = 3))

        store.markApplied(
            listId = "l1",
            onlineTrackIds = setOf("kw_a", "kw_b"),
            trackCount = 2,
            declaredTotal = 2,
            intervalHours = 6,
            now = now,
        )

        val record = store.get("l1")!!
        assertEquals(UpdateStatus.OK, record.lastStatus)
        assertEquals(now, record.lastUpdatedAt)
        assertEquals(now, record.lastFetchedAt)
        assertEquals(now, record.lastAttemptAt)
        assertEquals(2, record.lastTrackCount)
        assertEquals(2, record.lastDeclaredTotal)
        assertEquals(setOf("kw_a", "kw_b"), record.onlineTrackIds)
        assertEquals(0, record.consecutiveFailures)
        assertEquals(6, record.lastIntervalHours)
        assertNull(record.lastError)
    }

    @Test
    fun `markSkipped records the refusal without touching the last success`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.WY, "1"))
        store.markApplied("l1", setOf("wy_a"), 1, 1, 6, now = now)

        store.markSkipped("l1", "数量异常下降", 1, 1, 6, now = now + 60_000)

        val record = store.get("l1")!!
        assertEquals(UpdateStatus.SKIPPED, record.lastStatus)
        // The last *successful* update time is what the UI shows as 上次更新.
        assertEquals(now, record.lastUpdatedAt)
        assertEquals(now + 60_000, record.lastAttemptAt)
        assertEquals("数量异常下降", record.lastError)
    }

    @Test
    fun `markFailed grows the backoff counter and keeps the song list untouched`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.WY, "1", lastUpdatedAt = now, lastTrackCount = 9))

        store.markFailed("l1", "网络不可用", 6, now = now + 1000)

        val record = store.get("l1")!!
        assertEquals(UpdateStatus.FAILED, record.lastStatus)
        assertEquals(1, record.consecutiveFailures)
        assertEquals("网络不可用", record.lastError)
        assertEquals(9, record.lastTrackCount)
        assertEquals(now, record.lastUpdatedAt)
    }

    @Test
    fun `markCheckedUnchanged moves the check time but never the content time`() = runBlocking {
        store.upsert(
            ImportedSonglist(
                "l1",
                SourceID.WY,
                "1",
                lastUpdatedAt = now - 3_600_000L,
                lastAttemptAt = now - 3_600_000L,
                lastError = "旧的失败",
                consecutiveFailures = 2,
            ),
        )

        store.markCheckedUnchanged(
            listId = "l1",
            onlineTrackIds = setOf("wy_a", "wy_b"),
            trackCount = 2,
            declaredTotal = 2,
            intervalHours = 6,
            now = now,
        )

        val record = store.get("l1")!!
        // 上次检查 (检查时间) advanced so neither schedule re-reads the list before its gap...
        assertEquals(now, record.lastAttemptAt)
        assertEquals(now, record.lastFetchedAt)
        assertEquals(UpdateStatus.OK, record.lastStatus)
        // ... while 上次更新 (内容更新时间) stays put: the content did not change.
        assertEquals(now - 3_600_000L, record.lastUpdatedAt)
        // A successful read proves the platform is reachable again, so the backoff resets.
        assertEquals(0, record.consecutiveFailures)
        assertNull(record.lastError)
        assertEquals(setOf("wy_a", "wy_b"), record.onlineTrackIds)
        assertEquals(6, record.lastIntervalHours)
    }

    @Test
    fun `setAutoUpdate and remove only touch the addressed list`() = runBlocking {
        store.upsert(ImportedSonglist("l1", SourceID.WY, "1"))
        store.upsert(ImportedSonglist("l2", SourceID.WY, "2"))

        store.setAutoUpdate("l1", false)
        assertFalse(store.get("l1")!!.autoUpdate)
        assertTrue(store.get("l2")!!.autoUpdate)

        store.remove("l1")
        assertNull(store.get("l1"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `prune drops records whose list is gone`() = runBlocking {
        store.upsert(ImportedSonglist("keep", SourceID.WY, "1"))
        store.upsert(ImportedSonglist("gone", SourceID.WY, "2"))

        store.prune(setOf("keep"))

        assertEquals(listOf("keep"), store.all().map { it.listId })
    }

    @Test
    fun `records survive a reload from disk`() = runBlocking {
        store.upsert(
            ImportedSonglist(
                "l1", SourceID.TX, "123",
                rawUrl = "https://y.qq.com/n/ryqq/playlist/123",
                autoUpdate = false,
                lastTrackCount = 7,
                onlineTrackIds = setOf("tx_a"),
            ),
        )

        val reloaded = ImportedSonglistStore(dir)
        reloaded.loadAll()

        assertEquals(store.all(), reloaded.all())
    }

    @Test
    fun `a record written without the newer fields still loads`() = runBlocking {
        // Simulates a file written by an older build: only listId/source/sourceListId are known.
        File(dir, "importedSonglists.json").writeText(
            """{"l1":{"listId":"l1","source":"wy","sourceListId":"9"}}""",
        )

        val reloaded = ImportedSonglistStore(dir)
        reloaded.loadAll()

        val record = reloaded.get("l1")!!
        assertEquals(SourceID.WY, record.source)
        assertEquals(0L, record.lastUpdatedAt)
        assertEquals(UpdateStatus.NEVER, record.lastStatus)
        assertTrue(record.autoUpdate)
    }
}
