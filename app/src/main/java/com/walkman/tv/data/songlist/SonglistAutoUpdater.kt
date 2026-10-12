package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.OnlinePlaylist
import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.SonglistRef
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.online.OnlinePlaylistErrors
import com.walkman.tv.data.online.OnlinePlaylistFetcher
import com.walkman.tv.data.online.OnlinePlaylistLog
import com.walkman.tv.data.online.OnlinePlaylistSupport
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of one refresh attempt, in the shape the UI needs. */
sealed interface SonglistUpdateOutcome {
    data class Applied(
        val added: Int,
        val removed: Int,
        val kept: Int,
        val total: Int,
        val reordered: Boolean,
    ) : SonglistUpdateOutcome

    /**
     * The platform was read and holds the same songs as the local mirror: the check succeeded, but
     * nothing had to be written — so `changes` is not raised and LX Sync is not asked to push an
     * unchanged playlist. Only the automatic paths produce this; a manual 更新 always writes (and
     * mirrors the platform order).
     */
    data class Unchanged(val total: Int) : SonglistUpdateOutcome

    /** The platform was read, but the drop was suspicious — the user must confirm first. */
    data class NeedsConfirmation(val reason: String, val online: Int, val previous: Int) : SonglistUpdateOutcome

    /** The platform could not be read, or the write was refused. Nothing changed locally. */
    data class Skipped(val reason: String) : SonglistUpdateOutcome

    data class Failed(val kind: OnlinePlaylistException.Kind?, val message: String) : SonglistUpdateOutcome
}

/** Outcome of one background sweep (a single WorkManager run). */
data class SweepResult(
    val attempted: Int = 0,
    val applied: Int = 0,
    /** The platform was read, but the songs matched the local mirror: nothing was written. */
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** Failures worth retrying with backoff (network / HTTP / throttling / partial fetch). */
    val retryable: Int = 0,
) {
    val ranSomething: Boolean get() = attempted > 0
}

/** Outcome of a manual "立即更新全部". */
data class BulkUpdateResult(val attempted: Int, val applied: Int, val skipped: Int, val failed: Int)

/**
 * Which of the app's five entry points started a check — the `trigger` field of every update log
 * line, so 启动检查 / 恢复前台 / 前台定时 / WorkManager 后台 / 手动更新 are distinguishable in the
 * running log the user can share (spec §6).
 */
enum class SonglistCheckTrigger(val key: String) {
    /** First foreground of the process: the 应用启动检查. */
    APP_START("app_start"),

    /** Back to the foreground after being backgrounded. */
    FOREGROUND_RESUME("foreground_resume"),

    /** The foreground poll loop (every [SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS]). */
    FOREGROUND_TIMER("foreground_timer"),

    /** The WorkManager sweep — the app is not on screen. */
    BACKGROUND("background_work"),

    /** The user tapped 更新歌单 / 立即更新全部. */
    MANUAL("manual"),
}

/**
 * Keeps imported (网易云 / QQ / 酷狗 / 酷我) playlists in step with their platform source.
 *
 * This is the app-side analogue of LX Music's per-list `isAutoUpdate`, and it is deliberately a
 * *pure* suspend API: the schedule lives in [SonglistUpdateScheduler] (WorkManager), the UI calls
 * [updateNow] / [updateAllNow], and the worker calls [runDueSweep]. That keeps the whole policy —
 * due-ness, exponential backoff, mirror rules, safety refusals — testable on the JVM.
 *
 * Propagation is free: a successful refresh writes through [LibraryStore.replaceListTracks], which
 * emits [LibraryStore.changes] and therefore rolls into the LX sync auto-push, so a refresh made
 * here lands on other devices exactly like a manual edit would.
 */
class SonglistAutoUpdater(
    private val settingsStore: SettingsStore,
    private val libraryStore: LibraryStore,
    private val originStore: ImportedSonglistStore,
    private val fetcher: OnlinePlaylistFetcher,
    private val support: OnlinePlaylistSupport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Single-flight: a background sweep and a manual tap can never run at the same time. */
    private val gate = Mutex()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    /** List ids whose refresh is in flight right now (drives the "更新中…" state in the UI). */
    val updating: StateFlow<Set<String>> = _updating.asStateFlow()

    /** Human-readable result of the most recent refresh; never contains secrets. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun supports(source: SourceID): Boolean = support.supports(source)

    /**
     * The origin record for [playlist], synthesised from its LX source metadata when this device
     * has none yet — a list imported on another LX client arrives through sync carrying
     * `source`/`sourceListId` but no local auto-update state, and it should still be refreshable.
     * Returns null for hand-built lists and for platforms we cannot re-fetch (咪咕).
     */
    fun originFor(playlist: Playlist): ImportedSonglist? {
        originStore.get(playlist.id)?.let { return it }
        val source = SourceID.fromKey(playlist.source) ?: return null
        if (!support.supports(source)) return null
        val sourceListId = playlist.sourceListId?.takeIf { it.isNotBlank() } ?: return null
        return ImportedSonglist(
            listId = playlist.id,
            source = source,
            sourceListId = sourceListId,
            // Unknown snapshot: the planner then preserves every local track that is not online.
            onlineTrackIds = emptySet(),
            lastUpdatedAt = playlist.updatedAt,
            lastAttemptAt = playlist.updatedAt,
        )
    }

    /** The origin record for a local list id — the form the UI and the background worker call with. */
    fun originFor(listId: String): ImportedSonglist? =
        libraryStore.userLists.value.firstOrNull { it.id == listId }?.let { originFor(it) }

    /**
     * Manual refresh of one list — ignores the auto-update flag, the interval and the backoff.
     *
     * Shares [refreshOne] *and* the single-flight [gate] with the automatic paths, so a tap can
     * never run beside a foreground tick or a WorkManager sweep on the same list (spec §4 并发控制).
     * A manual tap still mirrors even when the platform returned exactly what is already local: the
     * user explicitly asked for it, and that write is the visible feedback they expect.
     */
    suspend fun updateNow(listId: String, confirmAnomaly: Boolean = false): SonglistUpdateOutcome {
        val origin = originFor(listId) ?: return SonglistUpdateOutcome.Failed(null, NOT_LINKED)
        return gate.withLock {
            refreshOne(
                origin = origin,
                confirmAnomaly = confirmAnomaly,
                intervalHours = intervalHours(),
                trigger = SonglistCheckTrigger.MANUAL,
                writeWhenUnchanged = true,
            )
        }
    }

    /** Manual "立即更新全部" — every imported list, ignoring flags/intervals. */
    suspend fun updateAllNow(): BulkUpdateResult = gate.withLock {
        val origins = libraryStore.userLists.value.mapNotNull { originFor(it) }
        val hours = intervalHours()
        var applied = 0
        var skipped = 0
        var failed = 0
        for (origin in origins) {
            val outcome = refreshOne(
                origin = origin,
                confirmAnomaly = false,
                intervalHours = hours,
                trigger = SonglistCheckTrigger.MANUAL,
                writeWhenUnchanged = true,
            )
            when (outcome) {
                is SonglistUpdateOutcome.Applied -> applied++
                is SonglistUpdateOutcome.Unchanged -> applied++
                is SonglistUpdateOutcome.NeedsConfirmation -> skipped++
                is SonglistUpdateOutcome.Skipped -> skipped++
                is SonglistUpdateOutcome.Failed -> failed++
            }
        }
        BulkUpdateResult(origins.size, applied, skipped, failed)
    }

    /** Turn the per-list auto-update flag on/off, creating the origin record on first use. */
    suspend fun setAutoUpdate(listId: String, source: SourceID, sourceListId: String, on: Boolean) {
        originStore.ensure(ImportedSonglist(listId, source, sourceListId, autoUpdate = on))
        originStore.setAutoUpdate(listId, on)
    }

    /**
     * One background sweep (a single WorkManager run): refresh every list whose origin wants it and
     * whose wait — the user's 更新间隔, lengthened by the exponential backoff after failures — has
     * elapsed.
     *
     * The WorkManager period only decides how often the process is *woken*; this is what decides
     * whether a wake actually talks to a platform.
     */
    suspend fun runDueSweep(): SweepResult = sweep(
        trigger = SonglistCheckTrigger.BACKGROUND,
        gapFor = { origin, intervalMs -> waitMs(origin, intervalMs) },
    )

    /**
     * One *foreground* check — 启动检查 / 恢复前台检查 / 前台定时检查 all land here.
     *
     * The app is on screen, so a list does not have to wait for the hour-level 更新间隔: it is
     * re-read once every [SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS], which is also what
     * makes a quick app switch, a rotation or a second Activity start a no-op instead of a re-sweep.
     *
     * Everything else is deliberately the same policy as [runDueSweep], from the same code path: the
     * global switch, the per-list flag, the platform capability, the single-flight gate, the
     * exponential backoff after a failure (a broken platform is not polled every 3 minutes) and the
     * "read but unchanged ⇒ write nothing" rule.
     */
    suspend fun runDueCheck(trigger: SonglistCheckTrigger): SweepResult = sweep(
        trigger = trigger,
        gapFor = { origin, _ -> foregroundGapMs(origin) },
    )

    /**
     * The gap a list must clear before either schedule reads it again. `gapFor` is the only
     * difference between the foreground and the background policy, so they cannot drift apart.
     */
    private suspend fun sweep(
        trigger: SonglistCheckTrigger,
        gapFor: (ImportedSonglist, Long) -> Long,
    ): SweepResult {
        val lists = libraryStore.userLists.value
        originStore.prune(lists.mapTo(HashSet()) { it.id })

        if (!settingsStore.settings.value.songlistAutoUpdateEnabled) {
            // 自动更新关闭: no foreground polling, no background fetch, and the manual entry points
            // are untouched. Logged once per attempt so a report can tell it apart from "nothing due".
            OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_CHECK_SKIP,
                OnlinePlaylistLog.trigger(trigger.key) + ("reason" to "disabled"),
            )
            return SweepResult()
        }
        val hours = intervalHours()
        val intervalMs = hours * HOUR_MS
        val now = clock()
        val due = lists.mapNotNull { originFor(it) }.filter { origin ->
            origin.autoUpdate &&
                support.supports(origin.source) &&
                now - lastReadAt(origin) >= gapFor(origin, intervalMs)
        }
        if (due.isEmpty()) {
            // Either every list was read recently enough or there is nothing refreshable. Not a
            // failure: the next tick (foreground) / next period (background) looks again.
            OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_CHECK_SKIP,
                OnlinePlaylistLog.trigger(trigger.key) +
                    listOf("reason" to "not_due", "lists" to lists.size),
            )
            return SweepResult()
        }
        if (!gate.tryLock()) {
            // 并发锁: a foreground tick, a manual tap or a WorkManager run already owns the updater.
            // Deferred, never queued — the run that holds the lock is fetching the same fresh answer
            // and will write it, so a second pass would only duplicate the request.
            OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_CHECK_SKIP,
                OnlinePlaylistLog.trigger(trigger.key) +
                    listOf("reason" to "busy", "due" to due.size),
            )
            return SweepResult()
        }
        try {
            var applied = 0
            var unchanged = 0
            var skipped = 0
            var failed = 0
            var retryable = 0
            for (origin in due) {
                val outcome = refreshOne(
                    origin = origin,
                    confirmAnomaly = false,
                    intervalHours = hours,
                    trigger = trigger,
                    writeWhenUnchanged = false,
                )
                when (outcome) {
                    is SonglistUpdateOutcome.Applied -> applied++
                    is SonglistUpdateOutcome.Unchanged -> unchanged++
                    is SonglistUpdateOutcome.NeedsConfirmation -> skipped++
                    is SonglistUpdateOutcome.Skipped -> skipped++
                    is SonglistUpdateOutcome.Failed -> {
                        failed++
                        if (outcome.kind == null || outcome.kind.isTransient()) retryable++
                    }
                }
            }
            return SweepResult(due.size, applied, unchanged, skipped, failed, retryable)
        } finally {
            gate.unlock()
        }
    }

    /**
     * When the next automatic sweep is expected to do *something*, or null when auto-update is off
     * / nothing is scheduled. Android may run the real job later than this — the UI must say so.
     */
    fun nextDueAt(): Long? {
        val settings = settingsStore.settings.value
        if (!settings.songlistAutoUpdateEnabled) return null
        val intervalMs = intervalHours() * HOUR_MS
        return libraryStore.userLists.value
            .mapNotNull { originFor(it) }
            .filter { it.autoUpdate && support.supports(it.source) }
            .map { lastReadAt(it) + waitMs(it, intervalMs) }
            .minOrNull()
    }

    /**
     * The single refresh path: automatic checks, the background sweep and a manual tap all end up
     * here, so they cannot disagree about request order, failure classification or the write rules.
     */
    private suspend fun refreshOne(
        origin: ImportedSonglist,
        confirmAnomaly: Boolean,
        intervalHours: Int,
        trigger: SonglistCheckTrigger,
        writeWhenUnchanged: Boolean,
    ): SonglistUpdateOutcome {
        // A synthesised record (list adopted through LX sync) becomes real on first refresh.
        originStore.ensure(origin)
        _updating.value = _updating.value + origin.listId
        val started = clock()
        OnlinePlaylistLog.emit(
            OnlinePlaylistLog.EVENT_UPDATE_START,
            OnlinePlaylistLog.ref(origin.source, origin.sourceListId) +
                listOf("listId" to origin.listId, "confirm" to confirmAnomaly) +
                OnlinePlaylistLog.trigger(trigger.key),
        )
        val outcome = try {
            val online = fetcher.fetch(SonglistRef(origin.source, origin.sourceListId, origin.rawUrl)) { _, _ -> }
            when {
                // Defence in depth: the repository already refuses an empty answer, but "the
                // platform returned nothing" must never be read as "the playlist is now empty"
                // and wipe the local copy (spec §6 安全保护).
                online.tracks.isEmpty() -> fail(origin, OnlinePlaylistException.Kind.EMPTY, intervalHours)
                // A half-fetched list must never be written over a complete local one.
                !online.complete -> fail(origin, OnlinePlaylistException.Kind.INCOMPLETE, intervalHours)
                else -> applyPlan(origin, online, confirmAnomaly, intervalHours, writeWhenUnchanged)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnlinePlaylistException) {
            fail(origin, e.kind, intervalHours)
        } catch (e: Exception) {
            fail(origin, null, intervalHours, e.localizedMessage)
        } finally {
            _updating.value = _updating.value - origin.listId
        }
        // A quiet "nothing changed" must not overwrite the last interesting result: the settings page
        // would otherwise be rewritten with 已是最新 several hundred times a day.
        if (outcome !is SonglistUpdateOutcome.Unchanged) _message.value = describe(origin.listId, outcome)
        logOutcome(origin, outcome, clock() - started, trigger)
        return outcome
    }

    private suspend fun applyPlan(
        origin: ImportedSonglist,
        online: OnlinePlaylist,
        confirmAnomaly: Boolean,
        intervalHours: Int,
        writeWhenUnchanged: Boolean,
    ): SonglistUpdateOutcome {
        val playlist = libraryStore.userLists.value.firstOrNull { it.id == origin.listId }
            ?: return SonglistUpdateOutcome.Failed(null, MISSING)
        val current = playlist.tracks
        val plan = SonglistUpdatePlanner.plan(
            current = current,
            online = online.tracks,
            previousOnlineIds = origin.onlineTrackIds,
            keepLocalTracks = settingsStore.settings.value.songlistKeepManualOnUpdate,
        )
        if (plan.needsConfirmation && !confirmAnomaly) {
            val reason = plan.confirmReason ?: "数量异常下降"
            // Record the fetch (so the UI can show what came back) but do NOT touch the songs.
            originStore.markSkipped(
                listId = origin.listId,
                reason = reason,
                trackCount = current.size,
                declaredTotal = online.declaredTotal,
                intervalHours = intervalHours,
                now = clock(),
            )
            return SonglistUpdateOutcome.NeedsConfirmation(reason, plan.onlineIds.size, origin.onlineTrackIds.size)
        }
        // 变化判定只看歌曲集合（新增 / 删除 / 手加歌曲的去留），既不看顺序，也不比对整个 payload：
        //  - several platforms hand back a fresh cover URL or duration for the same song, so comparing
        //    the whole payload would rewrite (and push) forever;
        //  - LX Sync keeps the *server's* list order for songs both sides already have, so a refresh
        //    that insisted on the platform order would fight the sync forever: write → changes → push
        //    → the merge hands back its own order → the next check sees a "reorder" → write … at a
        //    3-minute foreground cadence that is a full-library push every few minutes until the sync
        //    server gives up (seen in a device log as 同步超时).
        // A manual 更新歌单 still mirrors the platform order locally, and so does any automatic check
        // whose song set actually changed; the write is announced as ONLINE_REFRESH so only the added
        // and removed songs travel to the other devices, never the platform's running order.
        val unchangedSongSet = plan.added.isEmpty() && plan.removed.isEmpty()
        if (!writeWhenUnchanged && unchangedSongSet) {
            // 优先检查变化: the platform was read and the local mirror already matches, so nothing is
            // written — no `updatedAt` churn, no `changes` event, no LX Sync push every few minutes.
            // The check itself is still recorded as a success (spec §3.3 / §5): lastAttemptAt moves so
            // neither schedule re-reads this list before its gap elapses, while lastUpdatedAt (内容
            // 更新时间) stays put because the content did not change.
            originStore.markCheckedUnchanged(
                listId = origin.listId,
                onlineTrackIds = plan.onlineIds.toSet(),
                trackCount = current.size,
                declaredTotal = online.declaredTotal,
                intervalHours = intervalHours,
                now = clock(),
            )
            return SonglistUpdateOutcome.Unchanged(current.size)
        }
        if (!libraryStore.replaceListTracks(origin.listId, plan.finalTracks, LibraryStore.ListChangeSource.ONLINE_REFRESH)) {
            return SonglistUpdateOutcome.Failed(null, MISSING)
        }
        originStore.markApplied(
            listId = origin.listId,
            onlineTrackIds = plan.onlineIds.toSet(),
            trackCount = plan.finalTracks.size,
            declaredTotal = online.declaredTotal,
            intervalHours = intervalHours,
            now = clock(),
        )
        return SonglistUpdateOutcome.Applied(
            added = plan.added.size,
            removed = plan.removed.size,
            kept = plan.kept.size,
            total = plan.finalTracks.size,
            reordered = plan.reordered,
        )
    }

    private suspend fun fail(
        origin: ImportedSonglist,
        kind: OnlinePlaylistException.Kind?,
        intervalHours: Int,
        detail: String? = null,
    ): SonglistUpdateOutcome {
        // Name the platform in the user-facing text: "更新失败：歌单不存在" is not actionable, and the
        // failure hook is the only place that knows which platform the list came from.
        val message = if (kind != null) OnlinePlaylistErrors.message(kind, origin.source) else (detail ?: "更新失败")
        originStore.markFailed(origin.listId, message, intervalHours, now = clock())
        return SonglistUpdateOutcome.Failed(kind, message)
    }

    private fun logOutcome(
        origin: ImportedSonglist,
        outcome: SonglistUpdateOutcome,
        elapsedMs: Long,
        trigger: SonglistCheckTrigger,
    ) {
        val base = OnlinePlaylistLog.ref(origin.source, origin.sourceListId) +
            ("listId" to origin.listId) +
            OnlinePlaylistLog.trigger(trigger.key)
        val ms = "ms" to elapsedMs
        when (outcome) {
            is SonglistUpdateOutcome.Applied -> OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_UPDATE_OK,
                base + listOf(
                    "added" to outcome.added,
                    "removed" to outcome.removed,
                    "kept" to outcome.kept,
                    "total" to outcome.total,
                    "reordered" to outcome.reordered,
                    ms,
                ),
            )
            is SonglistUpdateOutcome.Unchanged -> OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_CHECK_NO_CHANGE,
                base + listOf("total" to outcome.total, ms),
            )
            is SonglistUpdateOutcome.NeedsConfirmation -> OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_UPDATE_SKIP,
                base + listOf("reason" to "anomaly", "online" to outcome.online, "ms" to elapsedMs),
            )
            is SonglistUpdateOutcome.Skipped -> OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_UPDATE_SKIP,
                base + listOf("reason" to outcome.reason, ms),
            )
            is SonglistUpdateOutcome.Failed -> OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_UPDATE_FAIL,
                base + listOf(
                    "kind" to (outcome.kind?.name ?: "UNKNOWN"),
                    // The message can embed a platform string; the log sink does not redact on its own.
                    "message" to OnlinePlaylistLog.safe(outcome.message),
                    ms,
                ),
            )
        }
    }

    private fun describe(listId: String, outcome: SonglistUpdateOutcome): String {
        val name = libraryStore.userLists.value.firstOrNull { it.id == listId }?.name
        val prefix = if (name != null) "「$name」" else ""
        return when (outcome) {
            is SonglistUpdateOutcome.Applied -> buildString {
                append(prefix).append("已更新：新增 ").append(outcome.added)
                append("、删除 ").append(outcome.removed)
                if (outcome.kept > 0) append("、保留本地 ").append(outcome.kept)
                append("，共 ").append(outcome.total).append(" 首")
            }
            is SonglistUpdateOutcome.Unchanged -> "${prefix}已是最新（${outcome.total} 首）"
            is SonglistUpdateOutcome.NeedsConfirmation -> "${prefix}需确认：${outcome.reason}"
            is SonglistUpdateOutcome.Skipped -> "${prefix}已跳过：${outcome.reason}"
            is SonglistUpdateOutcome.Failed -> "${prefix}更新失败：${outcome.message}"
        }
    }

    private fun intervalHours(): Int =
        settingsStore.settings.value.songlistAutoUpdateIntervalHours.coerceIn(
            SonglistAutoUpdateConfig.MIN_INTERVAL_HOURS,
            SonglistAutoUpdateConfig.MAX_INTERVAL_HOURS,
        )

    /**
     * When this list's platform was last read, successfully or not — the reference both schedules
     * measure their gap from. All three fields sit next to each other in [ImportedSonglist]; the
     * maximum keeps an older record (written before one of them existed) working.
     */
    private fun lastReadAt(origin: ImportedSonglist): Long =
        maxOf(origin.lastUpdatedAt, origin.lastAttemptAt, origin.lastFetchedAt)

    /**
     * The foreground gap: the 3-minute cadence normally, ramped 3 → 6 → 12 → 24 → 48 minutes after
     * consecutive failures. Deliberately tighter than the background backoff — the user is watching
     * the screen — but still ramped, so a platform that is down (or a Wi-Fi drop) is not polled
     * every three minutes (spec §5 失败重试与请求控制).
     */
    private fun foregroundGapMs(origin: ImportedSonglist): Long {
        if (origin.consecutiveFailures <= 0) return SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS
        val shift = origin.consecutiveFailures.coerceAtMost(MAX_BACKOFF_SHIFT)
        return SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS shl shift
    }

    /** The per-list wait: the configured interval, doubled for each consecutive failure. */
    private fun waitMs(origin: ImportedSonglist, intervalMs: Long): Long {
        if (origin.consecutiveFailures <= 0) return intervalMs
        val shift = origin.consecutiveFailures.coerceAtMost(MAX_BACKOFF_SHIFT)
        return intervalMs * (1L shl shift)
    }

    private fun OnlinePlaylistException.Kind.isTransient(): Boolean = when (this) {
        OnlinePlaylistException.Kind.NETWORK,
        OnlinePlaylistException.Kind.HTTP,
        OnlinePlaylistException.Kind.RATE_LIMIT,
        OnlinePlaylistException.Kind.INCOMPLETE,
        -> true

        else -> false
    }

    private companion object {
        const val HOUR_MS = 3_600_000L

        /** 2^n doubling capped at 16x the interval. */
        const val MAX_BACKOFF_SHIFT = 4

        const val NOT_LINKED = "这个歌单不是从平台导入的，无法更新"
        const val MISSING = "本地歌单已不存在"
    }
}
