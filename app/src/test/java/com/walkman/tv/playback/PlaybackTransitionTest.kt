package com.walkman.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision core of the auto-next fix. The ExoPlayer side ("did audio actually
 * start") cannot be asserted here - these tests pin the single-flight and queue-boundary rules
 * that the controller relies on; see the on-device verification steps in the report.
 */
class PlaybackTransitionGuardTest {

    @Test
    fun `begin issues increasing ids and only the newest is current`() {
        val guard = PlaybackTransitionGuard()
        assertFalse(guard.isCurrent(0L))

        val first = guard.begin()
        assertTrue(guard.isCurrent(first))
        assertEquals(first, guard.current)

        val second = guard.begin()
        assertEquals(second, guard.current)
        assertTrue(guard.isCurrent(second))
        assertFalse("superseded transition must be stale", guard.isCurrent(first))
        assertTrue(second > first)
    }

    @Test
    fun `stale resolve result is dropped instead of applied`() {
        val guard = PlaybackTransitionGuard()
        val resolveB = guard.begin() // user picks B, resolve starts
        val resolveC = guard.begin() // user immediately picks C
        // B's resolve lands late: it must not be allowed to touch the player.
        assertFalse(guard.isCurrent(resolveB))
        assertTrue(guard.isCurrent(resolveC))
    }

    @Test
    fun `auto next and manual next - latest transition wins`() {
        val guard = PlaybackTransitionGuard()
        val autoNext = guard.begin()    // STATE_ENDED -> auto next
        val userNext = guard.begin()    // user presses next during the resolve
        assertFalse(guard.isCurrent(autoNext))
        assertTrue(guard.isCurrent(userNext))
    }

    @Test
    fun `duplicate ended for the same media item cannot start a second transition`() {
        val guard = PlaybackTransitionGuard()
        val mediaA = guard.begin()
        // first ENDED consumes mediaA and starts the next transition
        val mediaB = guard.begin()
        // a duplicate ENDED still carrying mediaA's id is stale
        assertFalse(guard.isCurrent(mediaA))
        assertNotEquals(mediaA, guard.current)
        assertTrue(guard.isCurrent(mediaB))
    }

    @Test
    fun `invalidate kills everything in flight without starting a transition`() {
        val guard = PlaybackTransitionGuard()
        val inFlight = guard.begin()
        guard.invalidate()
        assertFalse(guard.isCurrent(inFlight))
        assertFalse(guard.isCurrent(0L))
        assertEquals("nothing is active after an invalidate", 0L, guard.current)
    }

    @Test
    fun `stop then a fresh begin is current again`() {
        val guard = PlaybackTransitionGuard()
        val beforeStop = guard.begin()
        guard.invalidate()
        val afterStop = guard.begin()
        assertFalse(guard.isCurrent(beforeStop))
        assertTrue(guard.isCurrent(afterStop))
    }
}

class PlaybackQueueTest {

    private fun alwaysFirst() = { _: Int -> 0 }

    @Test
    fun `middle of the queue advances by one`() {
        assertEquals(2, PlaybackQueue.nextIndex(5, 1, RepeatMode.ALL, shuffle = false))
        assertEquals(4, PlaybackQueue.nextIndex(5, 3, RepeatMode.OFF, shuffle = false))
    }

    @Test
    fun `repeat off at the last track stops instead of wrapping`() {
        assertNull(PlaybackQueue.nextIndex(3, 2, RepeatMode.OFF, shuffle = false))
        assertNull("single track + repeat off has nowhere to go", PlaybackQueue.nextIndex(1, 0, RepeatMode.OFF, shuffle = false))
    }

    @Test
    fun `repeat all wraps to the first track`() {
        assertEquals(0, PlaybackQueue.nextIndex(3, 2, RepeatMode.ALL, shuffle = false))
        assertEquals(0, PlaybackQueue.nextIndex(1, 0, RepeatMode.ALL, shuffle = false))
    }

    @Test
    fun `manual next with repeat one keeps the pre-existing wrap-to-first behaviour`() {
        assertEquals(0, PlaybackQueue.nextIndex(3, 2, RepeatMode.ONE, shuffle = false))
        assertEquals(3, PlaybackQueue.nextIndex(5, 2, RepeatMode.ONE, shuffle = false))
    }

    @Test
    fun `shuffle retries when the random pick equals the current index`() {
        // First pick equals the current index -> must retry and land on the other track.
        var calls = 0
        val retryOnce = { _: Int -> if (calls++ == 0) 0 else 1 }
        assertEquals(1, PlaybackQueue.nextIndex(2, 0, RepeatMode.ALL, shuffle = true, random = retryOnce))

        calls = 0
        assertEquals(0, PlaybackQueue.nextIndex(2, 1, RepeatMode.ALL, shuffle = true, random = retryOnce))
    }

    @Test
    fun `shuffle with a degenerate random terminates instead of looping`() {
        // A random source that only ever returns the current index must fall back to that index
        // (bounded retry) rather than spinning forever, which the old while-loop would have done.
        assertEquals(0, PlaybackQueue.nextIndex(2, 0, RepeatMode.ALL, shuffle = true, random = alwaysFirst()))
    }

    @Test
    fun `shuffle with a single track stays on that track`() {
        assertEquals(0, PlaybackQueue.nextIndex(1, 0, RepeatMode.ALL, shuffle = true))
        assertEquals(0, PlaybackQueue.prevIndex(1, 0, shuffle = true))
    }

    @Test
    fun `shuffle result always stays inside the queue`() {
        repeat(50) {
            val next = PlaybackQueue.nextIndex(4, 2, RepeatMode.ALL, shuffle = true)
            assertTrue("index $next out of range", next != null && next in 0 until 4)
            assertNotEquals(2, next)
        }
    }

    @Test
    fun `prev wraps from the first track to the last`() {
        assertEquals(1, PlaybackQueue.prevIndex(5, 2, shuffle = false))
        assertEquals(4, PlaybackQueue.prevIndex(5, 0, shuffle = false))
        assertEquals(0, PlaybackQueue.prevIndex(1, 0, shuffle = false))
    }

    @Test
    fun `empty queue or bogus index has no next or prev`() {
        assertNull(PlaybackQueue.nextIndex(0, 0, RepeatMode.ALL, shuffle = false))
        assertNull(PlaybackQueue.prevIndex(0, 0, shuffle = false))
        assertNull(PlaybackQueue.nextIndex(3, 7, RepeatMode.ALL, shuffle = false))
        assertNull(PlaybackQueue.nextIndex(3, -1, RepeatMode.ALL, shuffle = false))
        assertNull(PlaybackQueue.prevIndex(3, 7, shuffle = false))
    }

    @Test
    fun `natural end repeats only in repeat one`() {
        assertEquals(EndedAction.REPEAT_CURRENT, PlaybackQueue.endedAction(RepeatMode.ONE))
        assertEquals(EndedAction.ADVANCE, PlaybackQueue.endedAction(RepeatMode.ALL))
        assertEquals(EndedAction.ADVANCE, PlaybackQueue.endedAction(RepeatMode.OFF))
    }

    @Test
    fun `two track queue advances and then stops with repeat off`() {
        assertEquals(1, PlaybackQueue.nextIndex(2, 0, RepeatMode.OFF, shuffle = false))
        assertNull(PlaybackQueue.nextIndex(2, 1, RepeatMode.OFF, shuffle = false))
    }
}
