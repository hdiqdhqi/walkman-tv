package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mirror rules — spec §6 (新增 / 删除 / 顺序 / 重复 / 手动添加保留 / 异常下降保护). */
class SonglistUpdatePlannerTest {

    @Test
    fun `songs the platform added are added locally`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("a")),
            online = listOf(wy("a"), wy("b")),
            previousOnlineIds = setOf("wy_a"),
        )

        assertEquals(listOf("wy_b"), plan.added)
        assertEquals(emptyList<String>(), plan.removed)
        assertEquals(listOf("wy_a", "wy_b"), plan.finalTracks.map { it.id })
        assertFalse(plan.needsConfirmation)
    }

    @Test
    fun `songs the platform deleted are removed locally`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("a"), wy("b")),
            online = listOf(wy("a")),
            previousOnlineIds = setOf("wy_a", "wy_b"),
        )

        assertEquals(listOf("wy_b"), plan.removed)
        assertEquals(listOf("wy_a"), plan.finalTracks.map { it.id })
    }

    @Test
    fun `a platform reorder reorders the local list`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("a"), wy("b")),
            online = listOf(wy("b"), wy("a")),
            previousOnlineIds = setOf("wy_a", "wy_b"),
        )

        assertTrue(plan.reordered)
        assertEquals(listOf("wy_b", "wy_a"), plan.finalTracks.map { it.id })
    }

    @Test
    fun `duplicate platform entries collapse to one local track`() {
        val plan = SonglistUpdatePlanner.plan(
            current = emptyList(),
            online = listOf(wy("a"), wy("a"), wy("b")),
            previousOnlineIds = emptySet(),
        )

        assertEquals(listOf("wy_a", "wy_b"), plan.onlineIds)
        assertEquals(listOf("wy_a", "wy_b"), plan.finalTracks.map { it.id })
    }

    @Test
    fun `tracks added by hand survive the mirror instead of being deleted`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("a"), wy("mine")),
            online = listOf(wy("a"), wy("b")),
            previousOnlineIds = setOf("wy_a"),
        )

        assertEquals(listOf("wy_mine"), plan.kept.map { it.id })
        // Mirrored block first, hand-picked songs appended after it.
        assertEquals(listOf("wy_a", "wy_b", "wy_mine"), plan.finalTracks.map { it.id })
        assertEquals(emptyList<String>(), plan.removed)
    }

    @Test
    fun `an unknown previous snapshot preserves everything that is not online`() {
        // This is the state of a list adopted through LX sync: it carries source/sourceListId but
        // this device has never fetched it, so it cannot know what is "hand added" vs "platform".
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("x"), wy("y")),
            online = listOf(wy("x")),
            previousOnlineIds = emptySet(),
        )

        assertEquals(emptyList<String>(), plan.removed)
        assertEquals(listOf("wy_x", "wy_y"), plan.finalTracks.map { it.id })
    }

    @Test
    fun `a suspicious collapse asks for confirmation instead of deleting most of the list`() {
        val previous = (1..10).mapTo(LinkedHashSet()) { "wy_$it" }
        val plan = SonglistUpdatePlanner.plan(
            current = previous.map { wy(it.removePrefix("wy_")) },
            online = listOf(wy("1"), wy("2")),
            previousOnlineIds = previous,
        )

        assertTrue(plan.needsConfirmation)
        assertNotNull(plan.confirmReason)
        assertTrue(plan.confirmReason!!.contains("异常下降"))
        // The plan still describes the change, so a confirmed run can apply it.
        assertEquals(8, plan.removed.size)
        assertEquals(2, plan.finalTracks.size)
    }

    @Test
    fun `a small list shrinking is not treated as suspicious`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("1"), wy("2"), wy("3")),
            online = listOf(wy("1")),
            previousOnlineIds = setOf("wy_1", "wy_2", "wy_3"),
        )

        assertFalse(plan.needsConfirmation)
    }

    @Test
    fun `turning manual-preservation off mirrors the platform exactly`() {
        val plan = SonglistUpdatePlanner.plan(
            current = listOf(wy("a"), wy("mine")),
            online = listOf(wy("a"), wy("b")),
            previousOnlineIds = setOf("wy_a"),
            keepLocalTracks = false,
        )

        assertEquals(emptyList<Track>(), plan.kept)
        assertEquals(listOf("wy_a", "wy_b"), plan.finalTracks.map { it.id })
    }
}

private fun wy(mid: String): Track = Track(
    id = Track.makeID(SourceID.WY, mid),
    name = "歌 $mid",
    singer = "歌手",
    source = SourceID.WY,
    songmid = mid,
)
