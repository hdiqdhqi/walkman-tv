package com.walkman.tv.data.songlist

/**
 * Every cadence of the imported-playlist auto-update, in one place.
 *
 * The feature has three time scales and they are deliberately *not* the same number:
 *  - the **foreground check** ([FOREGROUND_CHECK_INTERVAL_MS]) — the app is on screen, so a list is
 *    re-read from its platform on a short cycle to notice changes quickly;
 *  - the **background work period** ([BACKGROUND_WORK_PERIOD_MINUTES]) — how often WorkManager is
 *    even allowed to wake the process; [SonglistUpdateWork] can never go below 15 minutes;
 *  - the **user's 更新间隔** ([MIN_INTERVAL_HOURS]..[MAX_INTERVAL_HOURS], 设置 → 歌单自动更新) — how
 *    old a list must be before a *background* wake actually re-fetches it.
 */
object SonglistAutoUpdateConfig {

    /**
     * Foreground cadence: while the app is in the foreground an auto-updating list is re-read at
     * most once every 3 minutes (spec §2 前台定时检查). This is also the floor that a resume must
     * clear before it checks again, so a quick app switch, a rotation or a second Activity start
     * never turns into a second sweep.
     */
    const val FOREGROUND_CHECK_INTERVAL_MS: Long = 3 * 60 * 1000L

    /**
     * Background wake period (`PeriodicWorkRequest`'s floor is 15 minutes, so this is the lowest
     * legal value). The wake is only a *timer*: whether it fetches anything is still decided by the
     * user's interval below plus the per-list failure backoff, so a 6-hour setting does not become
     * 96 requests a day.
     */
    const val BACKGROUND_WORK_PERIOD_MINUTES: Long = 15L

    /** Bounds of the user-facing 更新间隔, shared by the updater and the settings screen. */
    const val MIN_INTERVAL_HOURS: Int = 1
    const val MAX_INTERVAL_HOURS: Int = 168
}
