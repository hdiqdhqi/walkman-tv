package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this device owes the server (the official `onListSyncAction` push). The differ turns the
 * distance between the last agreement and the current library into actions - and deliberately never
 * into order-only actions (spec §2/§7: a reorder must not be re-imposed on the other devices).
 */
class LxListDifferTest {

    private fun state(
        love: List<String> = emptyList(),
        user: List<Playlist> = emptyList(),
    ) = LibraryStore.SyncLists(
        defaultList = emptyList(),
        love = love.map { track(songmid = it) },
        userLists = user,
    )

    private fun baselineOf(state: LibraryStore.SyncLists) = SyncBaselines.of(state, "server-1")

    private fun diff(baseline: LibraryStore.SyncLists, current: LibraryStore.SyncLists) =
        LxListDiffer.diff(baselineOf(baseline), current)

    @Test
    fun `a song added here is pushed as list_music_add`() {
        val actions = diff(state(love = listOf("a")), state(love = listOf("a", "new")))
        assertEquals(1, actions.size)
        val add = actions[0] as LxListAction.AddMusic
        assertEquals(Playlist.LOVE, add.listId)
        assertEquals(listOf("kw_new"), add.musicInfos.map { it.id })
        assertEquals(AddMusicLocation.BOTTOM, add.location)
    }

    @Test
    fun `a favourite added in front is pushed with top insertion`() {
        val actions = diff(state(love = listOf("a")), state(love = listOf("new", "a")))
        assertEquals(1, actions.size)
        assertEquals(AddMusicLocation.TOP, (actions[0] as LxListAction.AddMusic).location)
    }

    @Test
    fun `a song deleted here is pushed as list_music_remove`() {
        val actions = diff(state(love = listOf("a", "b")), state(love = listOf("b")))
        assertEquals(1, actions.size)
        val remove = actions[0] as LxListAction.RemoveMusic
        assertEquals(Playlist.LOVE, remove.listId)
        assertEquals(listOf("kw_a"), remove.ids)
    }

    @Test
    fun `nothing changed means nothing to push`() {
        val agreed = state(love = listOf("a", "b"))
        assertTrue(diff(agreed, agreed).isEmpty())
    }

    @Test
    fun `an order-only change is never pushed`() {
        val actions = diff(state(love = listOf("a", "b", "c")), state(love = listOf("c", "b", "a")))
        assertTrue("order must not travel as a sync action: $actions", actions.isEmpty())
    }

    @Test
    fun `reordering while adding pushes only the membership change`() {
        val actions = diff(state(love = listOf("a", "b")), state(love = listOf("b", "a", "new")))
        assertEquals(1, actions.size)
        assertEquals(listOf("kw_new"), (actions[0] as LxListAction.AddMusic).musicInfos.map { it.id })
    }

    @Test
    fun `a playlist created here is pushed as list_create plus its songs`() {
        val current = state(user = listOf(Playlist("mine", "我的歌单", listOf(track(songmid = "a")))))
        val actions = diff(state(), current)
        val create = actions.filterIsInstance<LxListAction.CreateLists>().single()
        assertEquals(listOf("mine"), create.listInfos.map { it.id })
        assertEquals("我的歌单", create.listInfos[0].name)
        val add = actions.filterIsInstance<LxListAction.AddMusic>().single()
        assertEquals("mine", add.listId)
        assertEquals(listOf("kw_a"), add.musicInfos.map { it.id })
    }

    @Test
    fun `a playlist deleted here is pushed as list_remove`() {
        val agreed = state(user = listOf(Playlist("old", "旧歌单")))
        val actions = diff(agreed, state())
        assertEquals(listOf(LxListAction.RemoveLists(listOf("old"))), actions)
    }

    @Test
    fun `a playlist renamed here is pushed as list_update`() {
        val agreed = state(user = listOf(Playlist("p", "旧名")))
        val actions = diff(agreed, state(user = listOf(Playlist("p", "新名"))))
        val update = actions.filterIsInstance<LxListAction.UpdateLists>().single()
        assertEquals(listOf("p"), update.lists.map { it.id })
        assertEquals("新名", update.lists[0].name)
    }

    @Test
    fun `an online-songlist origin is carried with a created playlist`() {
        val current = state(
            user = listOf(Playlist("p", "导入歌单", emptyList(), source = "tx", sourceListId = "123")),
        )
        val create = diff(state(), current).filterIsInstance<LxListAction.CreateLists>().single()
        assertEquals("tx", create.listInfos[0].source)
        assertEquals("123", create.listInfos[0].sourceListId)
    }

    @Test
    fun `a playlist with old tracks does not re-push them`() {
        val agreed = state(user = listOf(Playlist("p", "p", listOf(track(songmid = "a"), track(songmid = "b")))))
        val current = state(user = listOf(Playlist("p", "p", listOf(track(songmid = "b"), track(songmid = "a")))))
        assertTrue(diff(agreed, current).isEmpty())
    }

    @Test
    fun `the temp list is never pushed`() {
        val current = state(user = listOf(Playlist("temp", "临时", listOf(track(songmid = "a")))))
        assertTrue(diff(state(), current).isEmpty())
    }

    @Test
    fun `without a baseline there is nothing to diff against`() {
        // An empty baseline must still be able to say "these are additions", which is what a first
        // push after a reinstall looks like once the sync has already agreed on the state.
        val actions = LxListDiffer.diff(SyncBaseline(serverId = "server-1"), state(love = listOf("a")))
        assertEquals(listOf("kw_a"), (actions[0] as LxListAction.AddMusic).musicInfos.map { it.id })
    }
}
