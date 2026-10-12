package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.OnlinePlaylist
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The foreground driver — spec §2 (启动检查 / 恢复前台检查 / 前台定时检查) and §4.8 (开关).
 *
 * The checker is what decides *when* the app asks for a check; the Android lifecycle that feeds it
 * (`Application.registerActivityLifecycleCallbacks`) is the one part that cannot be exercised on the
 * JVM and is listed as 未真机验证 in the report.
 */
class SonglistForegroundCheckerTest {

    private lateinit var dir: File
    private lateinit var settingsStore: SettingsStore
    private lateinit var libraryStore: LibraryStore
    private lateinit var originStore: ImportedSonglistStore
    private lateinit var scope: CoroutineScope
    private lateinit var fetch: CountingFetcher
    private lateinit var updater: SonglistAutoUpdater
    private lateinit var checker: SonglistForegroundChecker

    private val now = 1_700_000_000_000L
    private val interval = SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS
    private var clockNow = now

    @Before
    fun setUp() {
        dir = tempDir()
        settingsStore = SettingsStore(dir)
        libraryStore = LibraryStore(dir)
        originStore = ImportedSonglistStore(dir)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        fetch = CountingFetcher()
        clockNow = now
        updater = SonglistAutoUpdater(
            settingsStore = settingsStore,
            libraryStore = libraryStore,
            originStore = originStore,
            fetcher = fetch,
            support = fetch,
            clock = { clockNow },
        )
        checker = SonglistForegroundChecker(updater = updater, scope = scope, clock = { clockNow })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the first foreground transition runs the startup check`() = runBlocking {
        seedList()

        assertTrue(checker.onForeground())

        awaitCheck(now)
        assertEquals(1, fetch.calls)
    }

    @Test
    fun `a quick resume right after a check is skipped`() = runBlocking {
        seedList()

        assertTrue(checker.onForeground())
        awaitCheck(now)
        assertEquals(1, fetch.calls)

        // An app switch / rotation one second later: 获得焦点 must not re-read the platforms.
        clockNow = now + 1_000L
        assertFalse(checker.onForeground())
        assertEquals(1, fetch.calls)

        // Once the interval has elapsed a resume does check again (spec §2.1).
        clockNow = now + interval
        assertTrue(checker.onForeground())
        awaitCheck(clockNow)
        assertEquals(2, fetch.calls)
    }

    @Test
    fun `the loop checks again after the interval and is cancelled on the background`() = runBlocking {
        seedList()
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val driven = SonglistForegroundChecker(
            updater = updater,
            scope = scope,
            clock = { clockNow },
            sleep = { ticks.receive() },
        )

        assertTrue(driven.onForeground())
        awaitCheck(now)
        assertEquals(1, fetch.calls)

        // One interval later the loop asks again — this is the 每 3 分钟检查, without a wall-clock wait.
        clockNow += interval
        ticks.trySend(Unit)
        awaitCheck(clockNow)
        assertEquals(2, fetch.calls)

        // Backgrounded: the loop is cancelled, so nothing happens while WorkManager owns the schedule.
        driven.onBackground()
        delay(100)
        assertEquals(2, fetch.calls)

        // Foreground again: a *fresh* loop is armed and keeps checking, so the background transition
        // did not leave the feature without a poller.
        clockNow += interval
        assertTrue(driven.onForeground())
        awaitCheck(clockNow)
        assertEquals(3, fetch.calls)
        clockNow += interval
        ticks.trySend(Unit)
        awaitCheck(clockNow)
        assertEquals(4, fetch.calls)
    }

    @Test
    fun `turning auto-update off stops the foreground polling but not the manual update`() = runBlocking {
        seedList()
        checker.setEnabled(false)

        assertFalse(checker.onForeground())
        assertFalse(checker.requestCheck(SonglistCheckTrigger.FOREGROUND_TIMER))
        assertEquals(0, fetch.calls)

        // The updater's own policy is off too (the WorkManager sweep returns without a request) ...
        settingsStore.update { it.copy(songlistAutoUpdateEnabled = false) }
        assertEquals(0, updater.runDueCheck(SonglistCheckTrigger.FOREGROUND_TIMER).attempted)

        // ... while a manual tap still refreshes: the switch never gates 更新歌单 (spec §4.8).
        assertTrue(updater.updateNow(firstListId()) is SonglistUpdateOutcome.Applied)
        assertEquals(1, fetch.calls)
    }

    @Test
    fun `a check waits for the stores to load before it runs`() = runBlocking {
        seedList()
        val ready = CompletableDeferred<Unit>()
        val gated = SonglistForegroundChecker(
            updater = updater,
            scope = scope,
            clock = { clockNow },
            awaitReady = { ready.await() },
        )

        assertTrue(gated.onForeground())
        delay(100)
        // The process can be started by WorkManager: checking an empty library would look like
        // "every playlist was deleted".
        assertEquals(0, fetch.calls)

        ready.complete(Unit)
        awaitCheck(now)
        assertEquals(1, fetch.calls)
    }

    @Test
    fun `an offline tick is skipped and does not consume the interval`() = runBlocking {
        seedList()
        var online = false
        val gated = SonglistForegroundChecker(
            updater = updater,
            scope = scope,
            clock = { clockNow },
            isOnline = { online },
        )

        assertFalse(gated.onForeground())
        assertEquals(0, fetch.calls)

        // The skip did not burn the interval, so a network that comes back is noticed on the very
        // next tick instead of up to three minutes later.
        online = true
        assertTrue(gated.requestCheck(SonglistCheckTrigger.FOREGROUND_TIMER))
        awaitCheck(now)
        assertEquals(1, fetch.calls)
    }

    // ---------------------------------------------------------------- helpers

    /** A list whose local songs already match the platform, so every check is a no-change read. */
    private suspend fun seedList(): String {
        val id = libraryStore.createList("导入的歌单", "wy", "123").id
        libraryStore.addAllToList(id, listOf(wyTrack("a")))
        originStore.upsert(
            ImportedSonglist(
                listId = id,
                source = SourceID.WY,
                sourceListId = "123",
                onlineTrackIds = setOf("wy_a"),
            ),
        )
        return id
    }

    private fun firstListId(): String = libraryStore.userLists.value.first().id

    /**
     * Wait until a check ran at [at] **and** the updater released its single-flight gate.
     *
     * The origin record is published while the gate is still held (the ledger save follows), and a
     * second check started in that window is legitimately refused by the 并发锁 — correct in
     * production, but not what these tests are about. The final pause covers the handful of
     * instructions between the record update and the unlock.
     */
    private suspend fun awaitCheck(at: Long) = withTimeout(5_000L) {
        originStore.records.first { records -> records.values.any { it.lastAttemptAt == at } }
        updater.updating.first { it.isEmpty() }
        delay(15)
    }
}

/** Counts the fetches the foreground loop causes; the answer is always "same as the local copy". */
private class CountingFetcher : OnlinePlaylistFetcher, OnlinePlaylistSupport {
    @Volatile
    var calls: Int = 0
        private set

    override fun supports(source: SourceID): Boolean = source != SourceID.MG && source != SourceID.LOCAL

    override suspend fun fetch(ref: SonglistRef, onProgress: (fetched: Int, total: Int?) -> Unit): OnlinePlaylist {
        calls++
        val tracks = listOf(wyTrack("a"))
        onProgress(tracks.size, tracks.size)
        return OnlinePlaylist(
            ref = ref,
            info = SonglistInfo(id = ref.id, source = ref.source, name = "远程歌单", trackCount = tracks.size),
            tracks = tracks,
            declaredTotal = tracks.size,
            complete = true,
        )
    }
}

private fun wyTrack(mid: String): Track = Track(
    id = Track.makeID(SourceID.WY, mid),
    name = "歌 $mid",
    singer = "歌手",
    source = SourceID.WY,
    songmid = mid,
)
