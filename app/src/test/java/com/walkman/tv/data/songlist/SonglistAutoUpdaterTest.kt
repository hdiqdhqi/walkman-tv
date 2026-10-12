package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.OnlinePlaylist
import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistRef
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.online.OnlinePlaylistFetcher
import com.walkman.tv.data.online.OnlinePlaylistSupport
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.SettingsStore
import com.walkman.tv.data.sync.tempDir
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The auto-update policy — spec §6 (更新规则 / 安全保护) and §7 (调度 / 退避 / 防重 / 与 LX Sync 解耦).
 *
 * Everything here runs on the JVM: the schedule (WorkManager) and the network are both behind
 * seams, so only the real policy — due-ness, mirror rules, safety refusals, backoff — is exercised.
 */
class SonglistAutoUpdaterTest {

    private lateinit var dir: File
    private lateinit var settingsStore: SettingsStore
    private lateinit var libraryStore: LibraryStore
    private lateinit var originStore: ImportedSonglistStore
    private lateinit var scope: CoroutineScope
    private lateinit var fetch: FakeFetcher
    private lateinit var updater: SonglistAutoUpdater

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val sixHours = 6 * hour
    private val minute = 60_000L

    /** Movable clock: the backoff tests need the sweep to observe time passing. */
    private var clockNow = now

    @Before
    fun setUp() {
        dir = tempDir()
        settingsStore = SettingsStore(dir)
        libraryStore = LibraryStore(dir)
        originStore = ImportedSonglistStore(dir)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        fetch = FakeFetcher()
        clockNow = now
        updater = SonglistAutoUpdater(
            settingsStore = settingsStore,
            libraryStore = libraryStore,
            originStore = originStore,
            fetcher = fetch,
            support = fetch,
            clock = { clockNow },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------- the scheduled sweep

    @Test
    fun `a due list is mirrored and its bookkeeping advances`() = runBlocking {
        val id = importedList(local = listOf(wy("old")), snapshot = setOf("wy_old"))
        fetch.tracks = listOf(wy("a"), wy("b"), wy("c"))

        val sweep = updater.runDueSweep()

        assertEquals(1, sweep.attempted)
        assertEquals(1, sweep.applied)
        assertEquals(0, sweep.failed)
        assertEquals(0, sweep.retryable)
        assertEquals(listOf("wy_a", "wy_b", "wy_c"), tracks())
        val record = originStore.get(id)!!
        assertEquals(UpdateStatus.OK, record.lastStatus)
        assertEquals(now, record.lastUpdatedAt)
        assertEquals(now, record.lastFetchedAt)
        assertEquals(3, record.lastTrackCount)
        assertEquals(setOf("wy_a", "wy_b", "wy_c"), record.onlineTrackIds)
        assertNull(record.lastError)
        assertEquals(listOf(SourceID.WY to "123"), fetch.calls)
    }

    @Test
    fun `a freshly imported list is inside its interval and is skipped`() = runBlocking {
        importedList(lastUpdatedAt = now - 60_000L, lastAttemptAt = now - 60_000L)

        assertEquals(0, updater.runDueSweep().attempted)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `the configured interval is what re-queues a list`() = runBlocking {
        importedList(lastUpdatedAt = now - 4 * hour, lastAttemptAt = now - 4 * hour)
        settingsStore.update { it.copy(songlistAutoUpdateIntervalHours = 3) }

        assertEquals(1, updater.runDueSweep().attempted)
    }

    @Test
    fun `the per-list opt-out wins over the schedule`() = runBlocking {
        importedList(autoUpdate = false)

        assertEquals(0, updater.runDueSweep().attempted)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `the global switch turns the whole sweep off`() = runBlocking {
        importedList()
        settingsStore.update { it.copy(songlistAutoUpdateEnabled = false) }

        assertEquals(0, updater.runDueSweep().attempted)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `playlists of a platform we cannot fetch are never queued`() = runBlocking {
        val id = libraryStore.createList("咪咕歌单", "mg", "9").id
        originStore.upsert(ImportedSonglist(id, SourceID.MG, "9", lastUpdatedAt = 0L, lastAttemptAt = 0L))

        assertEquals(0, updater.runDueSweep().attempted)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `records of deleted lists are pruned by the sweep`() = runBlocking {
        originStore.upsert(ImportedSonglist("ghost", SourceID.WY, "123"))

        updater.runDueSweep()

        assertNull(originStore.get("ghost"))
    }

    // ---------------------------------------------------------------- failure & backoff

    @Test
    fun `a failed refresh keeps the local list and backs off exponentially`() = runBlocking {
        val id = importedList(local = listOf(wy("keep")), snapshot = setOf("wy_keep"))
        fetch.error = OnlinePlaylistException(OnlinePlaylistException.Kind.NETWORK, "boom")

        val first = updater.runDueSweep()

        assertEquals(1, first.attempted)
        assertEquals(1, first.failed)
        assertEquals(1, first.retryable)
        assertEquals(listOf("wy_keep"), tracks())
        assertEquals(UpdateStatus.FAILED, originStore.get(id)!!.lastStatus)
        assertEquals(1, originStore.get(id)!!.consecutiveFailures)

        // One failure doubles the wait: nothing due at 6h, due again at 12h.
        clockNow = now + sixHours
        assertEquals(0, updater.runDueSweep().attempted)
        clockNow = now + 12 * hour

        assertEquals(1, updater.runDueSweep().attempted)
        assertEquals(2, originStore.get(id)!!.consecutiveFailures)
    }

    @Test
    fun `an empty platform answer is a failure, never an empty mirror`() = runBlocking {
        val id = importedList(local = listOf(wy("keep")), snapshot = setOf("wy_keep"))
        fetch.tracks = emptyList()

        val outcome = updater.updateNow(id)

        assertEquals(OnlinePlaylistException.Kind.EMPTY, (outcome as SonglistUpdateOutcome.Failed).kind)
        assertEquals(listOf("wy_keep"), tracks())
        assertEquals(UpdateStatus.FAILED, originStore.get(id)!!.lastStatus)
    }

    @Test
    fun `a partially fetched list is never written over the local copy`() = runBlocking {
        val id = importedList(local = listOf(wy("a"), wy("b")), snapshot = setOf("wy_a", "wy_b"))
        fetch.complete = false

        val outcome = updater.updateNow(id)

        assertEquals(OnlinePlaylistException.Kind.INCOMPLETE, (outcome as SonglistUpdateOutcome.Failed).kind)
        assertEquals(listOf("wy_a", "wy_b"), tracks())
    }

    // ---------------------------------------------------------------- safety: manual adds & anomalies

    @Test
    fun `a song added by hand survives the automatic mirror`() = runBlocking {
        val id = importedList(local = listOf(wy("a"), wy("mine")), snapshot = setOf("wy_a"))
        fetch.tracks = listOf(wy("a"), wy("b"))

        val outcome = updater.updateNow(id) as SonglistUpdateOutcome.Applied

        assertEquals(1, outcome.kept)
        assertEquals(0, outcome.removed)
        // Mirrored block first, then the hand-picked song: it is never dropped silently.
        assertEquals(listOf("wy_a", "wy_b", "wy_mine"), tracks())
    }

    @Test
    fun `a suspicious collapse needs confirmation and only applies when confirmed`() = runBlocking {
        val snapshot = (1..10).mapTo(LinkedHashSet()) { "wy_$it" }
        val id = importedList(local = snapshot.map { wy(it.removePrefix("wy_")) }, snapshot = snapshot)
        fetch.tracks = listOf(wy("1"), wy("2"))

        val refused = updater.updateNow(id)

        assertTrue(refused is SonglistUpdateOutcome.NeedsConfirmation)
        assertEquals(10, tracks().size)
        assertEquals(UpdateStatus.SKIPPED, originStore.get(id)!!.lastStatus)

        val confirmed = updater.updateNow(id, confirmAnomaly = true)

        assertTrue(confirmed is SonglistUpdateOutcome.Applied)
        assertEquals(listOf("wy_1", "wy_2"), tracks())
        assertEquals(UpdateStatus.OK, originStore.get(id)!!.lastStatus)
    }

    // ---------------------------------------------------------------- manual update

    @Test
    fun `manual update ignores the per-list flag, the interval and the backoff`() = runBlocking {
        val id = importedList(
            autoUpdate = false,
            lastUpdatedAt = now,
            lastAttemptAt = now,
            failures = 3,
        )

        val outcome = updater.updateNow(id)

        assertTrue(outcome is SonglistUpdateOutcome.Applied)
        assertEquals(1, fetch.calls.size)
    }

    @Test
    fun `manual update of a hand-built list reports that it cannot be linked`() = runBlocking {
        val id = libraryStore.createList("手动歌单").id

        val outcome = updater.updateNow(id)

        assertEquals(null, (outcome as SonglistUpdateOutcome.Failed).kind)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `updateAllNow reports attempted applied skipped and failed`() = runBlocking {
        val a = libraryStore.createList("A", "wy", "1").id
        val b = libraryStore.createList("B", "wy", "2").id
        originStore.upsert(ImportedSonglist(a, SourceID.WY, "1"))
        originStore.upsert(ImportedSonglist(b, SourceID.WY, "2"))
        fetch.failIds = setOf("2")

        val result = updater.updateAllNow()

        assertEquals(2, result.attempted)
        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
    }

    // ---------------------------------------------------------------- origins & scheduling state

    @Test
    fun `switching the per-list flag off creates the record on first use`() = runBlocking {
        val id = libraryStore.createList("来自其他设备", "wy", "123").id
        assertNull(originStore.get(id))

        updater.setAutoUpdate(id, SourceID.WY, "123", on = false)

        assertFalse(originStore.get(id)!!.autoUpdate)
    }

    @Test
    fun `originFor synthesises a refreshable record from a list that arrived through LX sync`() = runBlocking {
        val imported = libraryStore.createList("来自其他设备", "wy", "123")
        val handBuilt = libraryStore.createList("手动歌单")
        val migu = libraryStore.createList("咪咕歌单", "mg", "9")

        val origin = updater.originFor(imported)!!

        assertEquals(SourceID.WY, origin.source)
        assertEquals("123", origin.sourceListId)
        assertTrue(origin.autoUpdate)
        // An adopted list has an unknown snapshot -> the first update preserves everything local.
        assertTrue(origin.onlineTrackIds.isEmpty())
        assertNull(updater.originFor(handBuilt))
        assertNull(updater.originFor(migu))
        assertNull(updater.originFor("no-such-list"))
        assertNotNull(updater.originFor(imported.id))
    }

    @Test
    fun `nextDueAt is the earliest wait among the enabled lists`() = runBlocking {
        val id = importedList(lastUpdatedAt = now, lastAttemptAt = now)

        assertEquals(now + sixHours, updater.nextDueAt())

        settingsStore.update { it.copy(songlistAutoUpdateEnabled = false) }
        assertNull(updater.nextDueAt())

        settingsStore.update { it.copy(songlistAutoUpdateEnabled = true) }
        originStore.setAutoUpdate(id, false)
        assertNull(updater.nextDueAt())
    }

    // ---------------------------------------------------------------- concurrency & LX sync

    @Test
    fun `the startup check reads a list that is still inside its hour interval`() = runBlocking {
        val id = importedList(
            local = listOf(wy("a")),
            snapshot = setOf("wy_a"),
            lastUpdatedAt = now - 10 * minute,
            lastAttemptAt = now - 10 * minute,
        )
        fetch.tracks = listOf(wy("a"))

        val check = updater.runDueCheck(SonglistCheckTrigger.APP_START)

        // The hour-level 更新间隔 would not have queued this list for another ~6 hours.
        assertEquals(1, check.attempted)
        assertEquals(1, check.unchanged)
        assertEquals(0, check.applied)
        assertEquals(listOf(SourceID.WY to "123"), fetch.calls)
        val record = originStore.get(id)!!
        // 检查时间 advanced, 内容更新时间 did not: the platform was read, nothing had to change.
        assertEquals(now, record.lastAttemptAt)
        assertEquals(now - 10 * minute, record.lastUpdatedAt)
        assertEquals(UpdateStatus.OK, record.lastStatus)
    }

    @Test
    fun `a foreground check that finds a real change updates the list`() = runBlocking {
        val id = importedList(
            local = listOf(wy("a")),
            snapshot = setOf("wy_a"),
            lastUpdatedAt = now - 10 * minute,
            lastAttemptAt = now - 10 * minute,
        )
        fetch.tracks = listOf(wy("a"), wy("b"))

        val check = updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)

        assertEquals(1, check.applied)
        assertEquals(0, check.unchanged)
        assertEquals(listOf("wy_a", "wy_b"), tracks())
        assertEquals(now, originStore.get(id)!!.lastUpdatedAt)
        assertEquals(setOf("wy_a", "wy_b"), originStore.get(id)!!.onlineTrackIds)
    }

    @Test
    fun `a check right after a read is not due`() = runBlocking {
        importedList(lastUpdatedAt = now - minute, lastAttemptAt = now - minute)

        assertEquals(0, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_RESUME).attempted)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun `an unchanged check records the check without rewriting the list or notifying LX sync`() = runBlocking {
        val id = importedList(
            local = listOf(wy("a"), wy("b")),
            snapshot = setOf("wy_a", "wy_b"),
            lastUpdatedAt = now - 10 * minute,
            lastAttemptAt = now - 10 * minute,
        )
        fetch.tracks = listOf(wy("a"), wy("b"))
        val before = libraryStore.userLists.value.first()
        var changes = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            libraryStore.changes.collect { changes++ }
        }

        val check = updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)
        repeat(3) { yield() }

        assertEquals(1, check.unchanged)
        // Nothing was written: identical data is never re-replaced (the whole Playlist object is
        // untouched), so replaceListTracks never ran and `changes` never fired — LX Sync therefore
        // has nothing to push, however often the foreground checks.
        assertSame(before, libraryStore.userLists.value.first())
        assertEquals(0, changes)
        assertEquals(now - 10 * minute, originStore.get(id)!!.lastUpdatedAt)
        assertEquals(now, originStore.get(id)!!.lastAttemptAt)

        // Control: the very same plumbing does report a real write, so the assertion above is
        // about the write not happening rather than about the collector never working.
        fetch.tracks = listOf(wy("a"), wy("b"), wy("c"))
        clockNow = now + 4 * minute
        updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)
        repeat(3) { yield() }
        assertEquals(1, changes)
        collector.cancel()
    }

    @Test
    fun `a hand-added song survives an automatic foreground check`() = runBlocking {
        importedList(
            local = listOf(wy("a"), wy("mine")),
            snapshot = setOf("wy_a"),
            lastUpdatedAt = 0L,
            lastAttemptAt = 0L,
        )
        fetch.tracks = listOf(wy("a"))

        val check = updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)

        assertEquals(1, check.unchanged)
        assertEquals(listOf("wy_a", "wy_mine"), tracks())
    }

    @Test
    fun `a failed foreground check keeps the list and ramps its retry gap`() = runBlocking {
        val id = importedList(
            local = listOf(wy("keep")),
            snapshot = setOf("wy_keep"),
            lastUpdatedAt = 0L,
            lastAttemptAt = 0L,
        )
        fetch.error = OnlinePlaylistException(OnlinePlaylistException.Kind.NETWORK, "boom")

        assertEquals(1, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).failed)
        assertEquals(listOf("wy_keep"), tracks())
        assertEquals(UpdateStatus.FAILED, originStore.get(id)!!.lastStatus)

        // One failure ramps the foreground gap to 6 minutes, so a Wi-Fi drop is not re-requested
        // every 3 minutes; the list is not stuck either (spec §5 退避重试).
        clockNow = now + 4 * minute
        assertEquals(0, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).attempted)
        clockNow = now + 7 * minute
        assertEquals(1, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).attempted)
        assertEquals(2, originStore.get(id)!!.consecutiveFailures)
    }

    @Test
    fun `an automatic check does not fight LX sync over the list order`() = runBlocking {
        // Same songs, different order: LX Sync applies the server's order wholesale
        // (`overwriteSyncLists`, no `changes`), so an automatic check that re-imposed the platform
        // order would loop with it: write → changes → push → the merge hands its order back → the
        // next check sees a "reorder" again.
        val id = importedList(
            local = listOf(wy("a"), wy("b"), wy("c")),
            snapshot = setOf("wy_a", "wy_b", "wy_c"),
            lastUpdatedAt = now - 10 * minute,
            lastAttemptAt = now - 10 * minute,
        )
        fetch.tracks = listOf(wy("c"), wy("a"), wy("b"))
        val before = libraryStore.userLists.value.first()
        var changes = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            libraryStore.changes.collect { changes++ }
        }

        val check = updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)
        repeat(3) { yield() }

        assertEquals(1, check.unchanged)
        assertSame(before, libraryStore.userLists.value.first())
        assertEquals(0, changes)
        assertEquals(listOf("wy_a", "wy_b", "wy_c"), tracks())
        assertEquals(now, originStore.get(id)!!.lastAttemptAt)
        collector.cancel()
    }

    @Test
    fun `a manual update still applies the platform order`() = runBlocking {
        importedList(
            local = listOf(wy("a"), wy("b"), wy("c")),
            snapshot = setOf("wy_a", "wy_b", "wy_c"),
        )
        fetch.tracks = listOf(wy("c"), wy("a"), wy("b"))

        val outcome = updater.updateNow(firstListId()) as SonglistUpdateOutcome.Applied

        assertTrue(outcome.reordered)
        assertEquals(listOf("wy_c", "wy_a", "wy_b"), tracks())
    }

    @Test
    fun `a partial or anomalous answer is never written by a foreground check`() = runBlocking {
        val snapshot = (1..10).mapTo(LinkedHashSet()) { "wy_$it" }
        val id = importedList(
            local = snapshot.map { wy(it.removePrefix("wy_")) },
            snapshot = snapshot,
            lastUpdatedAt = 0L,
            lastAttemptAt = 0L,
        )
        fetch.tracks = listOf(wy("1"), wy("2"))

        assertEquals(1, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).skipped)
        assertEquals(10, tracks().size)
        assertEquals(UpdateStatus.SKIPPED, originStore.get(id)!!.lastStatus)

        fetch.complete = false
        clockNow = now + 4 * minute
        assertEquals(1, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).failed)
        assertEquals(10, tracks().size)
    }

    @Test
    fun `the foreground check obeys the global switch, the per-list flag and the platform`() = runBlocking {
        val id = importedList(lastUpdatedAt = 0L, lastAttemptAt = 0L)

        settingsStore.update { it.copy(songlistAutoUpdateEnabled = false) }
        assertEquals(0, updater.runDueCheck(SonglistCheckTrigger.APP_START).attempted)

        settingsStore.update { it.copy(songlistAutoUpdateEnabled = true) }
        originStore.setAutoUpdate(id, false)
        assertEquals(0, updater.runDueCheck(SonglistCheckTrigger.APP_START).attempted)

        originStore.setAutoUpdate(id, true)
        val migu = libraryStore.createList("咪咕歌单", "mg", "9").id
        originStore.upsert(ImportedSonglist(migu, SourceID.MG, "9"))
        assertEquals(1, updater.runDueCheck(SonglistCheckTrigger.APP_START).attempted)
        assertTrue(fetch.calls.none { it.first == SourceID.MG })
    }

    @Test
    fun `a foreground check never overlaps a manual update on the same list`() = runBlocking {
        importedList(lastUpdatedAt = 0L, lastAttemptAt = 0L)
        fetch.gate = CompletableDeferred()

        val running = async(Dispatchers.Default) { updater.updateNow(firstListId()) }
        withTimeout(5_000L) { fetch.entered.await() }

        val check = updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER)

        // 并发锁 (spec §4): the tap owns the updater, the tick defers instead of queueing a duplicate
        // request, and exactly one fetch happens.
        assertEquals(0, check.attempted)
        assertEquals(1, fetch.calls.size)
        fetch.gate!!.complete(Unit)
        assertTrue(running.await() is SonglistUpdateOutcome.Applied)
    }

    @Test
    fun `a sweep never overlaps a refresh that is already running`() = runBlocking {
        importedList(lastUpdatedAt = 0L, lastAttemptAt = 0L)
        fetch.gate = CompletableDeferred()

        val running = async(Dispatchers.Default) { updater.updateNow(firstListId()) }
        withTimeout(5_000L) { fetch.entered.await() }

        val sweep = updater.runDueSweep()

        assertEquals(0, sweep.attempted)
        assertEquals(1, fetch.calls.size)
        fetch.gate!!.complete(Unit)
        assertTrue(running.await() is SonglistUpdateOutcome.Applied)
    }

    @Test
    fun `a successful refresh shows up as a library change so LX sync can push it`() = runBlocking {
        val id = importedList(local = listOf(wy("old")), snapshot = setOf("wy_old"))
        val changed = CompletableDeferred<Unit>()
        // UNDISPATCHED guarantees the collector is subscribed before the refresh writes.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            libraryStore.changes.collect { changed.complete(Unit) }
        }

        updater.updateNow(id)

        withTimeout(5_000L) { changed.await() }
        job.cancel()
    }

    // ---------------------------------------------------------------- helpers

    /** Create list "123" of 网易云 with an origin record; returns its local id. */
    private suspend fun importedList(
        local: List<Track> = emptyList(),
        snapshot: Set<String> = emptySet(),
        autoUpdate: Boolean = true,
        lastUpdatedAt: Long = 0L,
        lastAttemptAt: Long = 0L,
        failures: Int = 0,
    ): String {
        val id = libraryStore.createList("导入的歌单", "wy", "123").id
        if (local.isNotEmpty()) libraryStore.addAllToList(id, local)
        originStore.upsert(
            ImportedSonglist(
                listId = id,
                source = SourceID.WY,
                sourceListId = "123",
                autoUpdate = autoUpdate,
                lastUpdatedAt = lastUpdatedAt,
                lastAttemptAt = lastAttemptAt,
                onlineTrackIds = snapshot,
                consecutiveFailures = failures,
            ),
        )
        return id
    }

    private fun firstListId(): String = libraryStore.userLists.value.first().id

    private fun tracks(): List<String> = libraryStore.userLists.value.first().tracks.map { it.id }
}

/** Records every fetch; the answer comes from [tracks] / [complete] / [error] / [failIds]. */
private class FakeFetcher : OnlinePlaylistFetcher, OnlinePlaylistSupport {
    var tracks: List<Track> = listOf(wy("a"), wy("b"), wy("c"))
    var complete: Boolean = true
    var error: OnlinePlaylistException? = null
    var failIds: Set<String> = emptySet()

    /** When set, every fetch blocks on it, so a second caller can observe the single-flight gate. */
    var gate: CompletableDeferred<Unit>? = null

    /** Completed as soon as a fetch is inside the fetcher. */
    val entered = CompletableDeferred<Unit>()

    val calls = mutableListOf<Pair<SourceID, String>>()

    override fun supports(source: SourceID): Boolean = source != SourceID.MG && source != SourceID.LOCAL

    override suspend fun fetch(ref: SonglistRef, onProgress: (fetched: Int, total: Int?) -> Unit): OnlinePlaylist {
        calls += ref.source to ref.id
        entered.complete(Unit)
        gate?.await()
        if (ref.id in failIds) throw OnlinePlaylistException(OnlinePlaylistException.Kind.NETWORK, "boom")
        error?.let { throw it }
        onProgress(tracks.size, tracks.size)
        return OnlinePlaylist(
            ref = ref,
            info = SonglistInfo(id = ref.id, source = ref.source, name = "远程歌单", trackCount = tracks.size),
            tracks = tracks,
            declaredTotal = tracks.size,
            complete = complete,
        )
    }
}

private fun wy(mid: String): Track = Track(
    id = Track.makeID(SourceID.WY, mid),
    name = "歌 $mid",
    singer = "歌手",
    source = SourceID.WY,
    songmid = mid,
)
