package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LxMusicMapperTest {

    @Test
    fun `interval and duration convert both ways`() {
        assertEquals("03:55", LxMusicMapper.durationToInterval(235))
        assertEquals("", LxMusicMapper.durationToInterval(null))
        assertEquals("", LxMusicMapper.durationToInterval(0))
        assertEquals(235, LxMusicMapper.intervalToDuration("03:55"))
        assertNull(LxMusicMapper.intervalToDuration(""))
        assertNull(LxMusicMapper.intervalToDuration("abc"))
    }

    @Test
    fun `online track round trips through MusicInfo`() {
        val original = track(
            source = SourceID.KG,
            songmid = "hash-1",
            name = "夜曲",
            singer = "周杰伦",
            albumName = "十一月的萧邦",
            albumId = "album-9",
            duration = 227,
            picURL = "https://img/x.jpg",
            qualities = listOf(Quality.FLAC, Quality.K320),
            extras = mapOf("hash" to "hash-1", "mvId" to "123"),
        )

        val info = LxMusicMapper.toMusicInfo(original)
        assertNotNull(info)
        info!!
        assertEquals("kg_hash-1", info.id)
        assertEquals("kg", info.source)
        assertEquals("03:47", info.interval)
        assertEquals("hash-1", info.meta["songId"]!!.let { (it as JsonPrimitive).content })
        assertEquals("十一月的萧邦", (info.meta["albumName"] as JsonPrimitive).content)
        assertEquals("album-9", (info.meta["albumId"] as JsonPrimitive).content)
        assertEquals("https://img/x.jpg", (info.meta["picUrl"] as JsonPrimitive).content)
        assertEquals("hash-1", (info.meta["hash"] as JsonPrimitive).content)
        // qualitys is regenerated from the typed list when we originate the data.
        assertEquals(2, (info.meta["qualitys"] as JsonArray).size)

        assertEquals(original, LxMusicMapper.toTrack(info))
    }

    @Test
    fun `nested meta from the server round trips verbatim`() {
        val qualitys = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("flac"))
                put("size", JsonPrimitive("28.1M"))
                put("hash", JsonPrimitive("kg-flac-hash"))
            })
        }
        val qualitysAlt = buildJsonObject {
            put("flac", buildJsonObject { put("size", JsonPrimitive("28.1M")) })
        }
        val info = MusicInfo(
            id = "kg_hash-1",
            name = "夜曲",
            singer = "周杰伦",
            source = "kg",
            interval = "03:47",
            meta = buildJsonObject {
                put("songId", JsonPrimitive("hash-1"))
                put("qualitys", qualitys)
                put("_qualitys", qualitysAlt)
                put("hash", JsonPrimitive("hash-1"))
            },
        )

        val parsed = LxMusicMapper.toTrack(info)
        assertNotNull(parsed)
        parsed!!
        assertEquals(listOf(Quality.FLAC), parsed.qualities)
        // The nested arrays/objects survive untouched (needed so a later upload doesn't lose them).
        assertEquals(qualitys, parsed.syncMeta["qualitys"])
        assertEquals(qualitysAlt, parsed.syncMeta["_qualitys"])

        val reencoded = LxMusicMapper.toMusicInfo(parsed)!!
        assertEquals(qualitys, reencoded.meta["qualitys"])
        assertEquals(qualitysAlt, reencoded.meta["_qualitys"])
        assertEquals("hash-1", (reencoded.meta["hash"] as JsonPrimitive).content)
    }

    @Test
    fun `local file track keeps filePath and ext`() {
        val original = track(
            source = SourceID.LOCAL,
            songmid = "lf://folder/song.flac",
            name = "本地歌曲",
            singer = "未知歌手",
            albumName = "本地专辑",
            duration = 100,
            qualities = listOf(Quality.FLAC),
        )
        val info = LxMusicMapper.toMusicInfo(original)!!
        assertEquals("local", info.source)
        assertEquals("lf://folder/song.flac", (info.meta["filePath"] as JsonPrimitive).content)
        assertEquals("flac", (info.meta["ext"] as JsonPrimitive).content)
        // The official MusicInfoMeta_local has no qualitys member, so the typed qualities are
        // intentionally not part of the wire payload.
        assertNull(info.meta["qualitys"])
        assertNull(info.meta["_qualitys"])
        assertEquals(original.copy(qualities = emptyList()), LxMusicMapper.toTrack(info))
    }

    @Test
    fun `unusable entries are rejected instead of invented`() {
        assertNull(LxMusicMapper.toMusicInfo(track(songmid = "")))
        assertNull(LxMusicMapper.toTrack(MusicInfo(id = "x", name = "n", singer = "", source = "nope")))
        assertNull(LxMusicMapper.toTrack(MusicInfo(id = "x", name = "", singer = "", source = "kw")))
    }

    @Test
    fun `id is never regenerated from album or title`() {
        val info = MusicInfo(
            id = "wy_custom-id",
            name = "别的名字",
            singer = "别人",
            source = "wy",
            meta = buildJsonObject { put("albumName", JsonPrimitive("某专辑")) },
        )
        val parsed = LxMusicMapper.toTrack(info)!!
        // The id is carried through as-is; album/title must never regenerate it.
        assertEquals("wy_custom-id", parsed.id)
        assertEquals("custom-id", parsed.songmid)
    }

    @Test
    fun `meta object without songId falls back to the id suffix`() {
        val info = MusicInfo(id = "tx_mid-7", name = "歌", singer = "手", source = "tx", meta = JsonObject(emptyMap()))
        assertEquals("mid-7", LxMusicMapper.toTrack(info)!!.songmid)
    }
}