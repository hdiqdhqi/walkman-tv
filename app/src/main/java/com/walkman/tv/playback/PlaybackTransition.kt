package com.walkman.tv.playback

import kotlin.random.Random

/**
 * Monotonic identity for one "play transition" - a single logical request to make some track the
 * current one (user tap, next/prev, auto-next after STATE_ENDED, retry, session restore...).
 *
 * Every asynchronous step of a transition (URL resolve, retry delay, failure advance) carries the
 * id it started with and must call [isCurrent] before touching the player or the UI state. That is
 * the single-flight rule: a superseded transition can never overwrite a newer one, and a cancelled
 * resolve is never mistaken for a playback failure.
 */
class PlaybackTransitionGuard {
    private var sequence = 0L

    /** Id of the transition that currently owns the player. 0 means "none yet". */
    var current: Long = 0L
        private set

    /** Supersede everything in flight and start a new transition. Returns the new id. */
    fun begin(): Long {
        sequence += 1
        current = sequence
        return sequence
    }

    /** Supersede everything in flight without starting a new one (stop/release). */
    fun invalidate() {
        sequence += 1
        current = 0L
    }

    /** True when [id] is still the transition that owns the player. */
    fun isCurrent(id: Long): Boolean = id != 0L && id == current
}

/** What to do when the current track reaches Player.STATE_ENDED. */
enum class EndedAction { REPEAT_CURRENT, ADVANCE }

/**
 * Pure queue-navigation rules (repeat / shuffle / queue boundaries). Kept framework-free so the
 * end-of-queue, repeat-one, shuffle and single-track decisions can be unit-tested without an
 * ExoPlayer instance.
 */
object PlaybackQueue {
    /**
     * Index to move to when advancing, or `null` when playback must stop (repeat OFF with an
     * exhausted queue). Keeps the pre-existing behaviour exactly: with repeat ONE a *manual*
     * next still wraps to 0 - repeat ONE only repeats on a natural end, see [endedAction].
     */
    fun nextIndex(
        size: Int,
        index: Int,
        repeatMode: RepeatMode,
        shuffle: Boolean,
        random: (Int) -> Int = { Random.nextInt(it) },
    ): Int? {
        if (size <= 0 || index !in 0 until size) return null
        if (shuffle) return if (size <= 1) index else otherIndex(index, size, random)
        if (index + 1 < size) return index + 1
        return if (repeatMode != RepeatMode.OFF) 0 else null
    }

    /** Index to move to when going back; wraps to the last track (pre-existing behaviour). */
    fun prevIndex(
        size: Int,
        index: Int,
        shuffle: Boolean,
        random: (Int) -> Int = { Random.nextInt(it) },
    ): Int? {
        if (size <= 0 || index !in 0 until size) return null
        if (shuffle) return if (size <= 1) index else otherIndex(index, size, random)
        return if (index - 1 >= 0) index - 1 else size - 1
    }

    /** A natural end repeats the same track only in repeat ONE; otherwise it advances. */
    fun endedAction(repeatMode: RepeatMode): EndedAction =
        if (repeatMode == RepeatMode.ONE) EndedAction.REPEAT_CURRENT else EndedAction.ADVANCE

    private fun otherIndex(current: Int, size: Int, random: (Int) -> Int): Int {
        var candidate = current
        var attempts = 0
        while (candidate == current && attempts < size * 2) {
            candidate = random(size).coerceIn(0, size - 1)
            attempts += 1
        }
        return candidate
    }
}
