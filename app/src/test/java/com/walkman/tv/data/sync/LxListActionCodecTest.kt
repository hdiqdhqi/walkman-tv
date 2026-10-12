package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push direction: every action this client sends back as an official
 * `onListSyncAction({action, data})` must be parsed back into the very same action - the wire shape
 * only ever exists in one place ([LxListAction.encode] reuses the parsing DTOs).
 */
class LxListActionCodecTest {

    private val info = LxUserListInfo("p", "歌单", "tx", "123", 1_700_000_000_000)
    private val music = musicInfo(songmid = "a")

    private fun roundTrip(action: LxListAction): LxListAction {
        val encoded = LxListAction.encode(action)
        val obj = encoded as JsonObject
        assertTrue(obj.containsKey("action"))
        assertTrue(obj.containsKey("data"))
        return LxListAction.parse(encoded)
    }

    private fun cases(): List<LxListAction> = listOf(
        LxListAction.DataOverwrite(listData(loveList = listOf(music))),
        LxListAction.CreateLists(2, listOf(info)),
        LxListAction.RemoveLists(listOf("p", "q")),
        LxListAction.UpdateLists(listOf(info)),
        LxListAction.UpdateListPosition(1, listOf("b", "a")),
        LxListAction.AddMusic(Playlist.LOVE, listOf(music), AddMusicLocation.TOP),
        LxListAction.AddMusic(Playlist.LOVE, listOf(music), AddMusicLocation.BOTTOM),
        LxListAction.MoveMusic("from", "to", listOf(music), AddMusicLocation.TOP),
        LxListAction.RemoveMusic("p", listOf("kw_a")),
        LxListAction.UpdateMusic(listOf(LxListAction.UpdateMusic.Entry("p", music))),
        LxListAction.UpdateMusicPosition("p", 3, listOf("kw_a")),
        LxListAction.OverwriteMusic("p", listOf(music)),
        LxListAction.ClearMusic(listOf("p")),
    )

    @Test
    fun `every action survives encode then parse`() {
        for (action in cases()) {
            assertEquals("round trip of $action", action, roundTrip(action))
        }
    }

    @Test
    fun `the push method is the official remoteQueueList call`() {
        assertEquals("onListSyncAction", LxListAction.PUSH_METHOD)
    }

    @Test
    fun `the wire name is the official one for every action`() {
        val names = cases().map { action ->
            ((LxListAction.encode(action) as JsonObject)["action"] as JsonPrimitive).content
        }
        assertEquals(
            listOf(
                "list_data_overwrite",
                "list_create",
                "list_remove",
                "list_update",
                "list_update_position",
                "list_music_add",
                "list_music_add",
                "list_music_move",
                "list_music_remove",
                "list_music_update",
                "list_music_update_position",
                "list_music_overwrite",
                "list_music_clear",
            ),
            names,
        )
    }

    @Test
    fun `add music carries the official location key`() {
        for ((location, key) in listOf(AddMusicLocation.TOP to "top", AddMusicLocation.BOTTOM to "bottom")) {
            val data = (LxListAction.encode(LxListAction.AddMusic(Playlist.LOVE, listOf(music), location)) as JsonObject)["data"] as JsonObject
            assertEquals(JsonPrimitive(key), data["addMusicLocationType"])
        }
    }
}
