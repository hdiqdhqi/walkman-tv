package com.walkman.tv.data.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxListJsonTest {

    @Test
    fun `encoded list data uses the official field order and omits undefined`() {
        val data = listData(
            loveList = listOf(musicInfo(songmid = "a")),
            userList = listOf(userList(id = "list-1", name = "歌单")),
        )
        val encoded = LxListJson.encode(data)
        assertTrue(encoded, encoded.startsWith("""{"defaultList":[],"loveList":["""))
        assertTrue(encoded, encoded.contains("\"userList\":[{\"id\":\"list-1\",\"name\":\"歌单\""))
        assertTrue(encoded, encoded.endsWith("\"list\":[]}]}"))
        assertFalse("null values must not be written: $encoded", encoded.contains("null"))
        // Compact output - the MD5 input has to look like JSON.stringify's.
        assertFalse(encoded.contains("\n"))
        assertFalse(encoded.contains(": "))
    }

    @Test
    fun `music info keys follow the official order`() {
        val encoded = LxListJson.encode(listData(loveList = listOf(musicInfo(songmid = "a"))))
        val expected = """{"id":"kw_a","name":"歌名","singer":"歌手","source":"kw","interval":"03:55","meta":{"songId":"a"}}"""
        assertTrue(encoded, encoded.contains(expected))
    }

    @Test
    fun `decode tolerates missing fields and a null interval`() {
        val decoded = LxListJson.decode(
            """{"loveList":[{"id":"kw_a","name":"歌名","source":"kw","interval":null,"meta":{}}]}""",
        )
        assertEquals(1, decoded.loveList.size)
        assertEquals("", decoded.loveList[0].interval)
        assertEquals("", decoded.loveList[0].singer)
        assertEquals(emptyList<LxUserList>(), decoded.userList)
    }

    @Test
    fun `unknown fields are ignored`() {
        val decoded = LxListJson.decode("""{"defaultList":[],"loveList":[],"userList":[],"tempList":[]}""")
        assertEquals(emptyList<LxUserList>(), decoded.userList)
    }

    @Test
    fun `md5 is the md5 of the protocol serialization`() {
        val data = listData(loveList = listOf(musicInfo(songmid = "a")))
        assertEquals(LxSyncProtocol.md5Hex(LxListJson.encode(data)), LxListJson.md5(data))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", LxSyncProtocol.md5Hex("abc"))
    }

    @Test
    fun `md5 changes when the lists change`() {
        val a = listData(loveList = listOf(musicInfo(songmid = "a")))
        val b = listData(loveList = listOf(musicInfo(songmid = "b")))
        assertFalse(LxListJson.md5(a) == LxListJson.md5(b))
    }

    @Test
    fun `round trip through encode and decode is stable`() {
        val data = listData(
            defaultList = listOf(musicInfo(songmid = "d")),
            loveList = listOf(musicInfo(songmid = "l")),
            userList = listOf(
                userList(
                    id = "list-1",
                    name = "歌单",
                    tracks = listOf(musicInfo(songmid = "u")),
                    source = "kg",
                    sourceListId = "sl-1",
                    locationUpdateTime = 1700000000000L,
                ),
            ),
        )
        val encoded = LxListJson.encode(data)
        assertEquals(encoded, LxListJson.encode(LxListJson.decode(encoded)))
    }

    @Test
    fun `music info with nested meta survives a json round trip`() {
        val nested = buildJsonArray {
            add(buildJsonObject { put("type", JsonPrimitive("flac")) })
        }
        val info = MusicInfo(
            id = "kg_a",
            name = "n",
            singer = "s",
            source = "kg",
            interval = "01:00",
            meta = buildJsonObject {
                put("songId", JsonPrimitive("a"))
                put("qualitys", nested)
            },
        )
        val decoded = LxListJson.decode(LxListJson.encode(listData(loveList = listOf(info))))
        assertEquals(nested, decoded.loveList[0].meta["qualitys"])
    }
}