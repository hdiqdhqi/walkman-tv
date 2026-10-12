package com.walkman.tv.data.songlist

import com.walkman.tv.data.online.OnlinePlaylistLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app is on screen: re-check the imported playlists on a short cycle instead of waiting for the
 * hour-level background schedule (spec §2 前台定时检查).
 *
 * This is the *only* foreground poller in the process — one instance owned by AppContainer and fed
 * by `Application`'s Activity callbacks. An Activity rebuild, a screen rotation, a page switch or a
 * second Activity start therefore cannot start a second loop, and every path runs through
 * [SonglistAutoUpdater.runDueCheck] and its single-flight gate, so a tick can never race a manual
 * update or a WorkManager sweep.
 *
 * Deliberately Android-free: the scope, the clock and the sleep are injected, which is what lets the
 * throttle and the loop be unit-tested on the JVM.
 */
class SonglistForegroundChecker(
    private val updater: SonglistAutoUpdater,
    private val scope: CoroutineScope,
    private val intervalMs: Long = SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS,
    private val awaitReady: suspend () -> Unit = {},
    private val isOnline: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val monitor = Any()

    private var timer: Job? = null
    private var foregrounded = false
    private var enabled = true
    private var lastRequestAt = 0L
    private var everForegrounded = false

    /**
     * The process entered the foreground: the first launch (启动检查) or a resume (恢复前台检查).
     *
     * A check is requested only when the last one is at least [intervalMs] old — "如果刚刚检查过，
     * 则跳过" — so an app switch, a rotation or a second Activity start degrades to a no-op instead of
     * a fresh sweep. The loop is then (re)armed, which is what gives the 每 3 分钟检查 while the app
     * stays on screen.
     *
     * @return true when this transition actually asked for a check (false for a throttled resume or
     *   while 自动更新 is off).
     */
    fun onForeground(): Boolean {
        val trigger = synchronized(monitor) {
            val first = !everForegrounded
            everForegrounded = true
            foregrounded = true
            if (first) SonglistCheckTrigger.APP_START else SonglistCheckTrigger.FOREGROUND_RESUME
        }
        val requested = requestCheck(trigger)
        startTimer()
        return requested
    }

    /**
     * The app went to the background: the loop is cancelled and the WorkManager sweep is the 兜底
     * from here (spec §3 后台定时更新). Keeping a 3-minute poll alive in the background would be
     * exactly the "阻止设备休眠 / 常驻前台服务" behaviour the task forbids.
     */
    fun onBackground() {
        synchronized(monitor) {
            foregrounded = false
            timer?.cancel()
            timer = null
        }
    }

    /**
     * The 歌单自动更新 switch. Off ⇒ foreground polling stops: the loop is cancelled and nothing new
     * is requested, while the manual 更新歌单 / 立即更新全部 entry points keep working. On ⇒ the loop
     * resumes when the app is on screen.
     */
    fun setEnabled(on: Boolean) {
        val resume = synchronized(monitor) {
            enabled = on
            timer?.cancel()
            timer = null
            on && foregrounded
        }
        if (resume) startTimer()
    }

    /**
     * Ask for one check, used by the transitions above and by the loop.
     *
     * Returns false when the request was skipped — 自动更新关闭, or the previous request is younger
     * than [intervalMs] (the same-window de-duplication spec §5 asks for), or the device has no
     * network at all. The expensive part happens off this call: on [scope], and only after the stores
     * are loaded.
     *
     * An offline skip deliberately does *not* consume the interval, so the check happens on the first
     * tick after the network is back instead of up to [intervalMs] later.
     */
    fun requestCheck(trigger: SonglistCheckTrigger): Boolean {
        val now = clock()
        val skipReason = synchronized(monitor) {
            when {
                !enabled -> "disabled"
                !isOnline() -> "offline"
                lastRequestAt != 0L && now - lastRequestAt < intervalMs -> "throttled"
                else -> {
                    lastRequestAt = now
                    null
                }
            }
        }
        if (skipReason != null) {
            OnlinePlaylistLog.emit(
                OnlinePlaylistLog.EVENT_CHECK_SKIP,
                OnlinePlaylistLog.trigger(trigger.key) + ("reason" to skipReason),
            )
            return false
        }
        // Off the main thread on purpose: the platform services parse their JSON on the calling
        // dispatcher, and this now happens while the user is watching TV — a long playlist must not
        // be parsed on the UI thread every few minutes.
        scope.launch(Dispatchers.Default) {
            // The process can be started *by* WorkManager, so the stores may still be loading:
            // checking an empty library would look like "every list was deleted".
            awaitReady()
            updater.runDueCheck(trigger)
        }
        return true
    }

    private fun startTimer() {
        synchronized(monitor) {
            if (!enabled || !foregrounded || timer?.isActive == true) return
            timer = scope.launch {
                while (isActive) {
                    sleep(intervalMs)
                    requestCheck(SonglistCheckTrigger.FOREGROUND_TIMER)
                }
            }
        }
    }
}
