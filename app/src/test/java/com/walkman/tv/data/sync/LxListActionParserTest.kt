package com.walkman.tv.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parses every official `onListSyncAction` payload and rejects malformed input. */
class LxListActionParserTest {

    private fun action(name: String, data: JsonElement?): JsonElement = buildJsonObject {
        put("action", JsonPrimitive(name))
        if (data != null) put("data", data)
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>) = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun str(value: String) = JsonPrimitive(value)

    private fun music(id: String) = obj(
        "id" to str(id),
        "name" to str("歌"),
        "singer" to str("手"),
        "source" to str("kw"),
        "interval" to str("03:00"),
    )

    @Test
    fun `list_data_overwrite carries all three lists`() {
        val parsed = LxListAction.parse(
            action(
                "list_data_overwrite",
                obj(
                    "defaultList" to buildJsonArray { add(music("default_a")) },
                    "loveList" to buildJsonArray { add(music("love_a")) },
                    "userList" to buildJsonArray {
                        add(obj("id" to str("l1"), "name" to str("歌单"), "list" to buildJsonArray { add(music("u_a")) }))
                    },
                ),
            ),
        ) as LxListAction.DataOverwrite
        assertEquals(listOf("default_a"), parsed.data.defaultList.map { it.id })
        assertEquals(listOf("love_a"), parsed.data.loveList.map { it.id })
        assertEquals("l1", parsed.data.userList[0].id)
        assertEquals(listOf("u_a"), parsed.data.userList[0].list.map { it.id })
    }

    @Test
    fun `list_create parses position and infos`() {
        val parsed = LxListAction.parse(
            action(
                "list_create",
                obj(
                    "position" to JsonPrimitive(1),
                    "listInfos" to buildJsonArray {
                        add(obj("id" to str("l1"), "name" to str("甲"), "locationUpdateTime" to JsonPrimitive(42)))
                    },
                ),
            ),
        ) as LxListAction.CreateLists
        assertEquals(1, parsed.position)
        assertEquals("l1", parsed.listInfos[0].id)
        assertEquals(42L, parsed.listInfos[0].locationUpdateTime)
    }

    @Test
    fun `list_remove and list_music_clear are id arrays`() {
        val remove = LxListAction.parse(action("list_remove", buildJsonArray { add(str("a")); add(str("b")) }))
        assertEquals(listOf("a", "b"), (remove as LxListAction.RemoveLists).ids)
        val clear = LxListAction.parse(action("list_music_clear", buildJsonArray { add(str("love")) }))
        assertEquals(listOf("love"), (clear as LxListAction.ClearMusic).listIds)
    }

    @Test
    fun `list_update parses metadata`() {
        val parsed = LxListAction.parse(
            action(
                "list_update",
                buildJsonArray {
                    add(obj("id" to str("l1"), "name" to str("新名字"), "source" to str("kg"), "sourceListId" to str("sl")))
                },
            ),
        ) as LxListAction.UpdateLists
        assertEquals("新名字", parsed.lists[0].name)
        assertEquals("kg", parsed.lists[0].source)
        assertEquals("sl", parsed.lists[0].sourceListId)
    }

    @Test
    fun `list_update_position parses position and ids`() {
        val parsed = LxListAction.parse(
            action("list_update_position", obj("position" to JsonPrimitive(0), "ids" to buildJsonArray { add(str("l2")) })),
        ) as LxListAction.UpdateListPosition
        assertEquals(0, parsed.position)
        assertEquals(listOf("l2"), parsed.ids)
    }

    @Test
    fun `list_music_add parses insertion location`() {
        val top = LxListAction.parse(
            action(
                "list_music_add",
                obj(
                    "id" to str("love"),
                    "musicInfos" to buildJsonArray { add(music("a")) },
                    "addMusicLocationType" to str("top"),
                ),
            ),
        ) as LxListAction.AddMusic
        assertEquals("love", top.listId)
        assertEquals(AddMusicLocation.TOP, top.location)
        assertEquals(listOf("a"), top.musicInfos.map { it.id })

        val fallback = LxListAction.parse(
            action("list_music_add", obj("id" to str("love"), "musicInfos" to buildJsonArray { add(music("a")) })),
        ) as LxListAction.AddMusic
        assertEquals(AddMusicLocation.BOTTOM, fallback.location)
    }

    @Test
    fun `list_music_move parses from target location`() {
        val parsed = LxListAction.parse(
            action(
                "list_music_move",
                obj(
                    "fromId" to str("l1"),
                    "toId" to str("l2"),
                    "musicInfos" to buildJsonArray { add(music("a")) },
                    "addMusicLocationType" to str("top"),
                ),
            ),
        ) as LxListAction.MoveMusic
        assertEquals("l1", parsed.fromId)
        assertEquals("l2", parsed.toId)
        assertEquals(AddMusicLocation.TOP, parsed.location)
    }

    @Test
    fun `list_music_remove and update_position parse`() {
        val remove = LxListAction.parse(
            action("list_music_remove", obj("listId" to str("l1"), "ids" to buildJsonArray { add(str("a")) })),
        ) as LxListAction.RemoveMusic
        assertEquals("l1", remove.listId)
        assertEquals(listOf("a"), remove.ids)

        val position = LxListAction.parse(
            action(
                "list_music_update_position",
                obj("listId" to str("l1"), "position" to JsonPrimitive(2), "ids" to buildJsonArray { add(str("a")) }),
            ),
        ) as LxListAction.UpdateMusicPosition
        assertEquals(2, position.position)
    }

    @Test
    fun `list_music_update parses entries`() {
        val parsed = LxListAction.parse(
            action(
                "list_music_update",
                buildJsonArray { add(obj("id" to str("l1"), "musicInfo" to music("a"))) },
            ),
        ) as LxListAction.UpdateMusic
        assertEquals("l1", parsed.entries[0].listId)
        assertEquals("a", parsed.entries[0].musicInfo.id)
    }

    @Test
    fun `list_music_update without musicInfo is rejected`() {
        try {
            LxListAction.parse(action("list_music_update", buildJsonArray { add(obj("id" to str("l1"))) }))
            fail("expected LxSyncRpcException")
        } catch (expected: LxSyncRpcException) {
            assertTrue(expected.message!!.contains("musicInfo"))
        }
    }

    @Test
    fun `list_music_overwrite parses target and songs`() {
        val parsed = LxListAction.parse(
            action("list_music_overwrite", obj("listId" to str("love"), "musicInfos" to buildJsonArray { add(music("a")) })),
        ) as LxListAction.OverwriteMusic
        assertEquals("love", parsed.listId)
        assertEquals(listOf("a"), parsed.musicInfos.map { it.id })
    }

    @Test
    fun `unknown action is rejected, not ignored`() {
        try {
            LxListAction.parse(action("list_does_not_exist", obj()))
            fail("expected LxSyncRpcException")
        } catch (expected: LxSyncRpcException) {
            assertTrue(expected.message!!.contains("unknown list sync action"))
        }
    }

    @Test
    fun `non-object, missing name and bad payloads are rejected`() {
        for (element in listOf<JsonElement?>(
            JsonPrimitive("nope"),
            null,
            buildJsonObject { put("data", obj()) },
            action("list_create", JsonPrimitive("not-an-object")),
            action("list_remove", JsonPrimitive("not-an-array")),
            action("list_update", str("not-an-array")),
            action("list_data_overwrite", null),
        )) {
            try {
                LxListAction.parse(element)
                fail("expected LxSyncRpcException for $element")
            } catch (expected: LxSyncRpcException) {
                // expected
            }
        }
    }
}