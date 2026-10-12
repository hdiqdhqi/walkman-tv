package com.walkman.tv.data.songlist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.walkman.tv.App
import com.walkman.tv.data.online.OnlinePlaylistLog
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Persistent schedule for the background refresh of imported online playlists.
 *
 * Android TV background work cannot be pinned to a wall-clock minute: the app may be stopped, the
 * device may sleep, and the system batches jobs. WorkManager is therefore used only as a *timer*
 * that wakes the process up roughly every [SonglistAutoUpdateConfig.BACKGROUND_WORK_PERIOD_MINUTES]
 * minutes; the real policy (which list is due, the user's interval, exponential backoff after a
 * failure, safety refusals) lives in the pure [SonglistAutoUpdater.runDueSweep], which is
 * unit-tested on the JVM. A wake that finds nothing due costs one log line and no request.
 *
 * One unique periodic job covers every imported playlist, so N lists can never produce N
 * schedules. A sweep not finding anything due is a no-op.
 */
object SonglistUpdateWork {

    /** Unique work name — the single schedule this feature owns. */
    const val UNIQUE = "songlist_auto_update"

    /** Tag applied to the request, so it can be inspected from `workmanager` dumps. */
    const val TAG = "songlist"

    /**
     * (Re)schedule the wake. Called whenever 歌单自动更新 changes, so the schedule exists exactly
     * while the switch is on. Uses UPDATE rather than KEEP, so the parameters are refreshed while an
     * already-running sweep is left alone.
     *
     * The period is the WorkManager floor ([SonglistAutoUpdateConfig.BACKGROUND_WORK_PERIOD_MINUTES]):
     * it decides how *promptly* a due list is noticed while the app is not on screen, while the
     * user's 更新间隔 (and the per-list failure backoff) still decides whether a wake re-fetches
     * anything — a 6-hour setting must not turn into 96 requests a day.
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SonglistUpdateWorker>(
            SonglistAutoUpdateConfig.BACKGROUND_WORK_PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    // No network ⇒ the system defers the job instead of letting it fail. This is
                    // the "网络不可用时合理延后" behaviour the spec asks for.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Global switch off: drop the schedule entirely (nothing keeps polling the platforms). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}

/**
 * The periodic job body: one [SonglistAutoUpdater.runDueSweep] and nothing else.
 *
 * Returns [Result.retry] only for failures that a later attempt could fix (network / HTTP /
 * throttling / partial fetch) and only for the first few attempts, so a permanently broken
 * playlist cannot spin forever. Everything else is [Result.success] — per-list bookkeeping
 * already recorded the failure and the next scheduled sweep will try again.
 */
class SonglistUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The process can be started *by* WorkManager, so bootstrap() may still be running. Wait
        // for it: an empty library would otherwise make the sweep prune every origin record.
        val container = runCatching { App.container }.getOrNull() ?: return Result.success()
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) { container.awaitBootstrap() } != null
        if (!ready) return Result.success()

        val started = System.currentTimeMillis()
        val sweep = container.songlistAutoUpdater.runDueSweep()
        OnlinePlaylistLog.emit(
            OnlinePlaylistLog.EVENT_SCHEDULE,
            listOf(
                "attempt" to runAttemptCount,
                "attempted" to sweep.attempted,
                "applied" to sweep.applied,
                "unchanged" to sweep.unchanged,
                "skipped" to sweep.skipped,
                "failed" to sweep.failed,
                "retryable" to sweep.retryable,
                "ms" to (System.currentTimeMillis() - started),
            ),
        )
        return if (sweep.retryable > 0 && runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private companion object {
        /** Retry at most this many times (WorkManager resets the counter for the next period). */
        const val MAX_ATTEMPTS = 3

        /** Give the app's startup IO this long before giving up on the run. */
        const val READY_TIMEOUT_MS = 60_000L
    }
}
