package com.walkman.tv.playback

import android.util.Log

/**
 * Opt-in structured trace for the play-transition chain
 * (TRANSITION_BEGIN -> RESOLVE -> SET_MEDIA_ITEM -> PREPARE -> READY -> PLAYING), used to pin down
 * the intermittent "auto-next does not start" stall on real devices.
 *
 * It never logs play URLs, tokens, cookies or connection codes - only ids, indices, qualities and
 * player flags, so a log can be shared safely.
 *
 * Enable on a debug build (default OFF):
 *     adb shell setprop log.tag.WalkmanPlayback DEBUG
 *     adb logcat -s WalkmanPlayback
 * Turn it off again with:
 *     adb shell setprop log.tag.WalkmanPlayback ""
 */
internal class PlaybackTrace(debuggable: Boolean) {
    /** Master switch; false on non-debuggable builds so release stays silent. */
    @Volatile
    var enabled: Boolean = debuggable

    fun step(transitionId: Long, step: String, vararg fields: Pair<String, Any?>) {
        // The long-term running log always records these play-chain events (spec §5) so an
        // intermittent stall can be investigated after it happened. The sink only enqueues.
        sink?.invoke(transitionId, step, fields.toList())
        if (!enabled || !Log.isLoggable(TAG, Log.DEBUG)) return
        val detail = if (fields.isEmpty()) "" else
            " " + fields.joinToString(" ") { "${it.first}=${it.second}" }
        Log.i(TAG, "t$transitionId $step$detail")
    }

    companion object {
        const val TAG = "WalkmanPlayback"

        /**
         * Bridge into the always-on running log; set once by AppContainer. Never blocks - it
         * only enqueues onto RunLog's writer thread. Null when unwired (e.g. unit tests).
         */
        @Volatile
        var sink: ((transitionId: Long, step: String, fields: List<Pair<String, Any?>>) -> Unit)? = null
    }
}