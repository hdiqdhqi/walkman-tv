package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-way merge (spec §5.1-§5.5). Every case compares the *result*, not just "no exception":
 * list identity, membership and order of both sides.
 */
class LxListMergeTest {

    private val server = "server-1"

    private fun lists(
        love: List<String> = emptyList(),
        user: List<Playlist> = emptyList(),
    ) = LibraryStore.SyncLists(
        defaultList = emptyList(),
        love = love.map { track(songmid = it) },
        userLists = user,
    )

    private fun userList(id: String, vararg songmids: String) =
        Playlist(id = id, name = id, tracks = songmids.map { track(songmid = it) })

    /** The server payload that represents exactly [state]. */
    private fun remote(state: LibraryStore.SyncLists): LxListData = LxListMapper.toListData(state)

    private fun baselineOf(state: LibraryStore.SyncLists) = SyncBaselines.of(state, server)

    private fun loveIds(result: LxListMerge.Result) = result.lists.love.map { it.id }

    private fun merge(
        baseline: LibraryStore.SyncLists,
        local: LibraryStore.SyncLists,
        remote: LibraryStore.SyncLists,
    ) = LxListMerge.merge(baselineOf(baseline), local, remote(remote))

    // ------------------------------------------------------------------ membership: additions

    @Test
    fun `a song only this device added survives the server payload`() {
        val result = merge(
            baseline = lists(love = listOf("a")),
            local = lists(love = listOf("a", "new")),
            remote = lists(love = listOf("a")),
        )
        assertEquals(listOf("kw_a", "kw_new"), loveIds(result))
        // Nothing had to change locally: the local addition is already here.
        assertTrue(result.summary.isEmpty)
    }

    @Test
    fun `a song only the server added lands here`() {
        val result = merge(
            baseline = lists(love = listOf("a")),
            local = lists(love = listOf("a")),
            remote = lists(love = listOf("a", "srv")),
        )
        assertEquals(listOf("kw_a", "kw_srv"), loveIds(result))
        assertEquals(1, result.summary.addedTracks)
    }

    @Test
    fun `two different additions from both sides are both kept`() {
        val result = merge(
            baseline = lists(love = listOf("a")),
            local = lists(love = listOf("a", "local")),
            remote = lists(love = listOf("a", "srv")),
        )
        assertEquals(setOf("kw_a", "kw_local", "kw_srv"), loveIds(result).toSet())
        // Only the server's song is new to this device; the local one was already here.
        assertEquals(1, result.summary.addedTracks)
    }

    @Test
    fun `the same song added on both sides is not duplicated`() {
        val result = merge(
            baseline = lists(),
            local = lists(love = listOf("same")),
            remote = lists(love = listOf("same")),
        )
        assertEquals(listOf("kw_same"), loveIds(result))
    }

    // ------------------------------------------------------------------ membership: deletions

    @Test
    fun `a local deletion is not undone by the server still having the song`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b")),
            local = lists(love = listOf("b")),
            remote = lists(love = listOf("a", "b")),
        )
        assertEquals(listOf("kw_b"), loveIds(result))
        // The deletion happened here, so nothing was removed from this device by the merge.
        assertEquals(0, result.summary.removedTracks)
    }

    @Test
    fun `a server deletion removes the song here too`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b")),
            local = lists(love = listOf("a", "b")),
            remote = lists(love = listOf("b")),
        )
        assertEquals(listOf("kw_b"), loveIds(result))
    }

    @Test
    fun `a deleted song does not come back on the next merge`() {
        val agreed = lists(love = listOf("a", "b"))
        val afterLocalDelete = lists(love = listOf("b"))
        // The server applied the deletion too (its snapshot diff removed `a` from both sides).
        val first = merge(baseline = agreed, local = afterLocalDelete, remote = afterLocalDelete)
        assertEquals(listOf("kw_b"), loveIds(first))
        // Re-running against the new agreed state must be a no-op, never a revival.
        val second = merge(
            baseline = first.lists,
            local = first.lists,
            remote = first.lists,
        )
        assertEquals(listOf("kw_b"), loveIds(second))
        assertTrue(second.summary.isEmpty)
    }

    @Test
    fun `a song both sides deleted stays deleted`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b")),
            local = lists(love = listOf("b")),
            remote = lists(love = listOf("b")),
        )
        assertEquals(listOf("kw_b"), loveIds(result))
    }

    // ------------------------------------------------------------------ order (spec §5.4)

    @Test
    fun `a reorder made here is kept when the server did not reorder`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b", "c")),
            local = lists(love = listOf("b", "a", "c")),
            remote = lists(love = listOf("a", "b", "c")),
        )
        assertEquals(listOf("kw_b", "kw_a", "kw_c"), loveIds(result))
        // The local order is already the merged order: nothing to rewrite.
        assertTrue(result.summary.isEmpty)
    }

    @Test
    fun `a reorder made elsewhere is accepted here`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b", "c")),
            local = lists(love = listOf("a", "b", "c")),
            remote = lists(love = listOf("c", "b", "a")),
        )
        assertEquals(listOf("kw_c", "kw_b", "kw_a"), loveIds(result))
    }

    @Test
    fun `a single-side reorder travels while the memberships match`() {
        // Local reordered and the song set is identical: the merge must not fall back to server order.
        val result = merge(
            baseline = lists(love = listOf("a", "b", "c", "d")),
            local = lists(love = listOf("d", "c", "b", "a")),
            remote = lists(love = listOf("a", "b", "c", "d")),
        )
        assertEquals(listOf("kw_d", "kw_c", "kw_b", "kw_a"), loveIds(result))
    }

    @Test
    fun `two different reorders settle on the server order and stay there`() {
        val baseline = lists(love = listOf("a", "b", "c"))
        val local = lists(love = listOf("b", "c", "a"))
        val remote = lists(love = listOf("c", "a", "b"))
        val first = merge(baseline = baseline, local = local, remote = remote)
        assertEquals(listOf("kw_c", "kw_a", "kw_b"), loveIds(first))
        // Both sides now hold the merged state: re-merging must not swing back to the local order.
        val second = merge(baseline = first.lists, local = first.lists, remote = first.lists)
        assertEquals(loveIds(first), loveIds(second))
        assertTrue(second.summary.isEmpty)
    }

    @Test
    fun `reordering and adding at the same time keeps both`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b")),
            local = lists(love = listOf("b", "a", "new")),
            remote = lists(love = listOf("a", "b", "srv")),
        )
        assertEquals(listOf("kw_b", "kw_a", "kw_srv", "kw_new"), loveIds(result))
    }

    @Test
    fun `a song both sides added independently is not treated as a reorder`() {
        val result = merge(
            baseline = lists(love = listOf("a", "b")),
            local = lists(love = listOf("b", "a", "same")),
            remote = lists(love = listOf("a", "b", "same")),
        )
        // The local reorder still wins; the jointly-added song is appended once.
        assertEquals(listOf("kw_b", "kw_a", "kw_same"), loveIds(result))
    }

    // ------------------------------------------------------------------ playlists (spec §5.5)

    @Test
    fun `a playlist created here is added to the result`() {
        val result = merge(
            baseline = lists(),
            local = lists(user = listOf(userList("mine", "a"))),
            remote = lists(),
        )
        assertEquals(1, result.lists.userLists.size)
        assertEquals("mine", result.lists.userLists[0].id)
        assertEquals(listOf("kw_a"), result.lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `a playlist created elsewhere lands here with its songs`() {
        val result = merge(
            baseline = lists(),
            local = lists(),
            remote = lists(user = listOf(userList("theirs", "a", "b"))),
        )
        assertEquals(listOf("theirs"), result.lists.userLists.map { it.id })
        assertEquals(listOf("kw_a", "kw_b"), result.lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `a playlist deleted here is not recreated by the server snapshot`() {
        val agreed = lists(user = listOf(userList("gone", "a")))
        val result = merge(baseline = agreed, local = lists(), remote = agreed)
        assertTrue(result.lists.userLists.isEmpty())
        assertEquals(1, result.summary.removedLists)
    }

    @Test
    fun `a playlist deleted elsewhere disappears here`() {
        val agreed = lists(user = listOf(userList("gone", "a")))
        val result = merge(baseline = agreed, local = agreed, remote = lists())
        assertTrue(result.lists.userLists.isEmpty())
        assertEquals(1, result.summary.removedLists)
    }

    @Test
    fun `a playlist changed here is kept even when the server dropped it`() {
        val agreed = lists(user = listOf(userList("kept", "a")))
        val local = lists(user = listOf(userList("kept", "a", "b")))
        val result = merge(baseline = agreed, local = local, remote = lists())
        assertEquals(listOf("kept"), result.lists.userLists.map { it.id })
        assertEquals(listOf("kw_a", "kw_b"), result.lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `a rename made elsewhere is accepted`() {
        val agreed = lists(user = listOf(Playlist("p", "old", listOf(track(songmid = "a")))))
        val remote = lists(user = listOf(Playlist("p", "new", listOf(track(songmid = "a")))))
        val result = merge(baseline = agreed, local = agreed, remote = remote)
        assertEquals("new", result.lists.userLists[0].name)
        assertEquals(1, result.summary.renamedLists)
    }

    @Test
    fun `a rename made here is not clobbered by the untouched server name`() {
        val agreed = lists(user = listOf(Playlist("p", "old", listOf(track(songmid = "a")))))
        val local = lists(user = listOf(Playlist("p", "mine", listOf(track(songmid = "a")))))
        val result = merge(baseline = agreed, local = local, remote = agreed)
        assertEquals("mine", result.lists.userLists[0].name)
    }

    @Test
    fun `a reorder inside a user list is resolved like the built-in lists`() {
        val agreed = lists(user = listOf(userList("p", "a", "b", "c")))
        val local = lists(user = listOf(userList("p", "c", "a", "b")))
        val result = merge(baseline = agreed, local = local, remote = agreed)
        assertEquals(listOf("kw_c", "kw_a", "kw_b"), result.lists.userLists[0].tracks.map { it.id })
    }

    // ------------------------------------------------------------------ safety / idempotence

    @Test
    fun `a local edit made while the session ran survives an older server snapshot`() {
        // `local` is read fresh at apply time: it already contains the offline edit.
        val result = merge(
            baseline = lists(love = listOf("a")),
            local = lists(love = listOf("a", "offline")),
            remote = lists(love = listOf("a")),
        )
        assertEquals(listOf("kw_a", "kw_offline"), loveIds(result))
    }

    @Test
    fun `metrics attached to the same song are not read as an add or remove`() {
        val agreed = lists(love = listOf("a"))
        val local = LibraryStore.SyncLists(
            defaultList = emptyList(),
            love = listOf(track(songmid = "a", name = "本地歌名", duration = 300)),
            userLists = emptyList(),
        )
        val result = merge(baseline = agreed, local = local, remote = agreed)
        assertEquals(listOf("kw_a"), loveIds(result))
        assertTrue(result.summary.isEmpty)
        // This device's own entry wins for playback metadata.
        assertEquals("本地歌名", result.lists.love[0].name)
    }

    @Test
    fun `a first sync without a baseline never drops local data`() {
        val local = lists(love = listOf("local"))
        val result = LxListMerge.merge(SyncBaseline(serverId = server), local, remote(lists()))
        assertEquals(listOf("kw_local"), loveIds(result))
    }

    @Test
    fun `repeating an identical merge is idempotent`() {
        val baseline = lists(love = listOf("a", "b"), user = listOf(userList("p", "x", "y")))
        val local = lists(love = listOf("b", "a", "c"), user = listOf(userList("p", "y", "x", "z")))
        val remote = lists(love = listOf("a", "b", "c"), user = listOf(userList("p", "x", "y")))
        val first = merge(baseline = baseline, local = local, remote = remote)
        val second = merge(baseline = baseline, local = local, remote = remote)
        assertEquals(loveIds(first), loveIds(second))
        assertEquals(first.lists.userLists[0].tracks.map { it.id }, second.lists.userLists[0].tracks.map { it.id })
        // A later session compares the *same agreed state* (the server keeps its own order) against
        // the merged local state: the result must not swing back to the server order.
        val next = merge(baseline = baseline, local = first.lists, remote = remote)
        assertEquals(loveIds(first), loveIds(next))
        assertEquals(first.lists.userLists[0].tracks.map { it.id }, next.lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `songs in different lists are never merged together`() {
        val agreed = lists(love = listOf("same"), user = listOf(userList("p", "other")))
        val result = merge(baseline = agreed, local = agreed, remote = agreed)
        assertEquals(listOf("kw_same"), loveIds(result))
        assertEquals(listOf("kw_other"), result.lists.userLists[0].tracks.map { it.id })
    }

    @Test
    fun `a device that adopted the merged state does not change again on the next round`() {
        // Round 1: the server already merged the other device's addition; this device contributed
        // its own addition and a reorder.
        val agreed = lists(love = listOf("a", "b"), user = listOf(userList("p", "x")))
        val server = lists(love = listOf("a", "b", "srv"), user = listOf(userList("p", "x")))
        val local = lists(love = listOf("b", "a", "local"), user = listOf(userList("p", "x", "y")))
        val round1 = merge(baseline = agreed, local = local, remote = server)
        assertEquals(
            listOf("kw_b", "kw_a", "kw_srv", "kw_local"),
            loveIds(round1),
        )

        // Round 2: this device now holds the merged state and the agreement is the server's view.
        // Nothing may change again - neither the membership nor the order this device curated.
        val round2 = merge(baseline = server, local = round1.lists, remote = server)
        assertEquals(loveIds(round1), loveIds(round2))
        assertEquals(
            round1.lists.userLists.map { it.tracks.map { track -> track.id } },
            round2.lists.userLists.map { it.tracks.map { track -> track.id } },
        )
        assertTrue(round2.summary.isEmpty)
    }
}
