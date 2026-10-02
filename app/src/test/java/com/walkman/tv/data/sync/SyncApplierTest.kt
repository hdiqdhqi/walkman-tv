package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "What does the local library look like after a server action is applied" - the core of the
 * offline verification. Every case drives a real [LibraryStore] on disk.
 */
class SyncApplierTest {

    private lateinit var store: LibraryStore
    private lateinit var applier: SyncApplier

    private fun setup(initial: LibraryStore.SyncLists = LibraryStore.SyncLists(emptyList(), emptyList(), emptyList())) {
        store = LibraryStore(tempDir())
        applier = SyncApplier(store, SyncRepository(store))
        runBlocking { store.overwriteSyncLists(initial) }
    }

    private fun apply(action: LxListAction) = runBlocking { applier.apply(action) }

    private fun loveIds() = store.syncLists().love.map { it.id }
    private fun userIds() = store.syncLists().userLists.map { it.id }

    private fun list(id: String, name: String, vararg mids: String) =
        Playlist(id = id, name = name, tracks = mids.map { track(songmid = it) })

    @Test
    fun `list_create inserts at the given position and ignores duplicate ids`() {
        setup(LibraryStore.SyncLists(emptyList(), emptyList(), listOf(list("b", "乙"))))
        apply(
            LxListAction.CreateLists(
                0,
                listOf(
                    LxUserListInfo(id = "a", name = "甲"),
                    LxUserListInfo(id = "b", name = "重复"),
                ),
            ),
        )
        assertEquals(listOf("a", "b"), userIds())
        assertEquals(listOf("甲", "乙"), store.syncLists().userLists.map { it.name })
        apply(LxListAction.CreateLists(99, listOf(LxUserListInfo(id = "c", name = "丙"))))
        assertEquals(listOf("a", "b", "c"), userIds())
    }

    @Test
    fun `list_remove deletes only the listed ids`() {
        setup(
            LibraryStore.SyncLists(
                emptyList(),
                emptyList(),
                listOf(list("a", "甲"), list("b", "乙"), list("c", "丙")),
            ),
        )
        apply(LxListAction.RemoveLists(listOf("b")))
        assertEquals(listOf("a", "c"), userIds())
    }

    @Test
    fun `list_update renames and keeps tracks`() {
        setup(LibraryStore.SyncLists(emptyList(), emptyList(), listOf(list("a", "旧名", "x"))))
        apply(LxListAction.UpdateLists(listOf(LxUserListInfo(id = "a", name = "新名", source = "kg", sourceListId = "s1"))))
        val updated = store.syncLists().userLists[0]
        assertEquals("新名", updated.name)
        assertEquals("kg", updated.source)
        assertEquals("s1", updated.sourceListId)
        assertEquals(listOf("kw_x"), updated.tracks.map { it.id })
    }

    @Test
    fun `list_update_position reorders only the listed ids`() {
        setup(
            LibraryStore.SyncLists(emptyList(), emptyList(), listOf(list("a", "甲"), list("b", "乙"), list("c", "丙"))),
        )
        apply(LxListAction.UpdateListPosition(0, listOf("c")))
        assertEquals(listOf("c", "a", "b"), userIds())
    }

    @Test
    fun `music_add honours top and bottom and is idempotent`() {
        setup(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "old")), emptyList()))
        val fresh = listOf(musicInfo(songmid = "new", name = "新"))
        apply(LxListAction.AddMusic(Playlist.LOVE, fresh, AddMusicLocation.TOP))
        assertEquals(listOf("kw_new", "kw_old"), loveIds())
        // The same broadcast arriving twice must not duplicate the song.
        apply(LxListAction.AddMusic(Playlist.LOVE, fresh, AddMusicLocation.TOP))
        assertEquals(listOf("kw_new", "kw_old"), loveIds())

        apply(LxListAction.AddMusic(Playlist.LOVE, listOf(musicInfo(songmid = "tail")), AddMusicLocation.BOTTOM))
        assertEquals(listOf("kw_new", "kw_old", "kw_tail"), loveIds())
    }

    @Test
    fun `music_add to an unknown list is a no-op`() {
        setup(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "keep")), emptyList()))
        apply(LxListAction.AddMusic("nope", listOf(musicInfo(songmid = "x")), AddMusicLocation.BOTTOM))
        assertEquals(listOf("kw_keep"), loveIds())
        assertEquals(emptyList<Playlist>(), store.syncLists().userLists)
    }

    @Test
    fun `music_move removes from the source and inserts into the target`() {
        setup(
            LibraryStore.SyncLists(
                emptyList(),
                listOf(track(songmid = "m")),
                listOf(list("to", "目标", "keep")),
            ),
        )
        val moved = musicInfo(songmid = "m")
        apply(LxListAction.MoveMusic(Playlist.LOVE, "to", listOf(moved), AddMusicLocation.TOP))
        assertEquals(emptyList<String>(), loveIds())
        assertEquals(listOf("kw_m", "kw_keep"), store.syncLists().userLists[0].tracks.map { it.id })
    }

    @Test
    fun `music_remove and music_clear`() {
        setup(
            LibraryStore.SyncLists(
                defaultList = emptyList(),
                love = listOf(track(songmid = "a"), track(songmid = "b")),
                userLists = listOf(list("l1", "甲", "x")),
            ),
        )
        apply(LxListAction.RemoveMusic(Playlist.LOVE, listOf("kw_a")))
        assertEquals(listOf("kw_b"), loveIds())
        apply(LxListAction.ClearMusic(listOf(Playlist.LOVE, "l1")))
        assertEquals(emptyList<String>(), loveIds())
        assertEquals(emptyList<String>(), store.syncLists().userLists[0].tracks.map { it.id })
    }

    @Test
    fun `music_update replaces the metadata of a matching entry`() {
        setup(LibraryStore.SyncLists(defaultList = emptyList(), love = listOf(track(songmid = "a", name = "旧")), userLists = emptyList()))
        apply(
            LxListAction.UpdateMusic(
                listOf(LxListAction.UpdateMusic.Entry(Playlist.LOVE, musicInfo(songmid = "a", name = "新", singer = "新歌手"))),
            ),
        )
        val updated = store.syncLists().love[0]
        assertEquals("新", updated.name)
        assertEquals("新歌手", updated.singer)
        // Unknown ids are ignored rather than throwing.
        apply(LxListAction.UpdateMusic(listOf(LxListAction.UpdateMusic.Entry(Playlist.LOVE, musicInfo(songmid = "zz")))))
        assertEquals(listOf("kw_a"), loveIds())
    }

    @Test
    fun `music_update_position moves a song to the given index`() {
        setup(LibraryStore.SyncLists(defaultList = emptyList(), love = listOf(track(songmid = "a"), track(songmid = "b"), track(songmid = "c")), userLists = emptyList()))
        apply(LxListAction.UpdateMusicPosition(Playlist.LOVE, 0, listOf("kw_c")))
        assertEquals(listOf("kw_c", "kw_a", "kw_b"), loveIds())
    }

    @Test
    fun `music_overwrite replaces the whole list`() {
        setup(LibraryStore.SyncLists(defaultList = emptyList(), love = listOf(track(songmid = "a"), track(songmid = "b")), userLists = emptyList()))
        apply(LxListAction.OverwriteMusic(Playlist.LOVE, listOf(musicInfo(songmid = "c"))))
        assertEquals(listOf("kw_c"), loveIds())
    }

    @Test
    fun `data_overwrite replaces every list at once - deletions included`() {
        setup(
            LibraryStore.SyncLists(
                defaultList = listOf(track(songmid = "d")),
                love = listOf(track(songmid = "a"), track(songmid = "b")),
                userLists = listOf(list("gone", "将被删除", "x")),
            ),
        )
        apply(
            LxListAction.DataOverwrite(
                listData(
                    defaultList = listOf(musicInfo(songmid = "d2")),
                    loveList = listOf(musicInfo(songmid = "a")),
                    userList = listOf(userList(id = "kept", name = "保留")),
                ),
            ),
        )
        val lists = store.syncLists()
        assertEquals(listOf("kw_d2"), lists.defaultList.map { it.id })
        assertEquals(listOf("kw_a"), lists.love.map { it.id })
        assertEquals(listOf("kept"), lists.userLists.map { it.id })
    }

    @Test
    fun `history is never touched by any list action`() {
        setup(LibraryStore.SyncLists(emptyList(), listOf(track(songmid = "a")), emptyList()))
        runBlocking { store.recordHistory(track(songmid = "hist")) }
        apply(LxListAction.DataOverwrite(listData(loveList = listOf(musicInfo(songmid = "a")))))
        apply(LxListAction.ClearMusic(listOf(Playlist.LOVE, Playlist.HISTORY, Playlist.DEFAULT)))
        apply(LxListAction.RemoveLists(listOf(Playlist.HISTORY, Playlist.LOVE)))
        assertEquals(listOf("kw_hist"), store.history.value.tracks.map { it.id })
    }

    @Test
    fun `duplicate broadcast of the same action stays idempotent`() {
        setup(LibraryStore.SyncLists(emptyList(), emptyList(), emptyList()))
        val create = LxListAction.CreateLists(0, listOf(LxUserListInfo(id = "l1", name = "歌单")))
        val add = LxListAction.AddMusic("l1", listOf(musicInfo(songmid = "a")), AddMusicLocation.BOTTOM)
        repeat(3) {
            apply(create)
            apply(add)
        }
        assertEquals(listOf("l1"), userIds())
        assertEquals(listOf("kw_a"), store.syncLists().userLists[0].tracks.map { it.id })
    }
}