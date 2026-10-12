package com.walkman.tv.data.online

import com.walkman.tv.data.model.SourceID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link/id parsing rules — spec §4.2 (标准链接 / 带参数 / 短链 / 非法 / 裸 ID). */
class SonglistLinkParserTest {

    @Test
    fun `every netEase share shape resolves to the playlist id`() {
        val cases = listOf(
            "https://music.163.com/playlist?id=1234567",
            "https://music.163.com/#/playlist?id=1234567",
            "https://music.163.com/playlist/1234567",
            "https://music.163.com/playlist?id=1234567&userid=42",
            "  https://y.music.163.com/m/playlist?id=1234567&userid=1&creatorId=2  ",
        )
        for (raw in cases) {
            val ref = SonglistLinkParser.parse(raw)
            assertEquals("not parsed: $raw", SourceID.WY, ref?.source)
            assertEquals("wrong id: $raw", "1234567", ref?.id)
            assertEquals("raw url not kept: $raw", raw.trim(), ref?.rawUrl)
        }
    }

    @Test
    fun `every qq share shape resolves to the disstid`() {
        val cases = listOf(
            "https://y.qq.com/n/ryqq/playlist/7654321" to "7654321",
            "https://y.qq.com/n/yqq/playsquare/7654321.html" to "7654321",
            "https://i.y.qq.com/n3/other/pages/details/playlist.html?id=7654321&from=1" to "7654321",
            "https://y.qq.com/taoge.html?id=7654321#stat" to "7654321",
            "https://y.qq.com/n/ryqq/player?disstid=7654321&type=0" to "7654321",
        )
        for ((raw, id) in cases) {
            val ref = SonglistLinkParser.parse(raw)
            assertEquals("not parsed: $raw", SourceID.TX, ref?.source)
            assertEquals("wrong id: $raw", id, ref?.id)
        }
    }

    @Test
    fun `kugou and kuwo links are recognized on their own hosts`() {
        assertEquals(SourceID.KG, SonglistLinkParser.parse("https://www.kugou.com/songlist/12345/")?.source)
        assertEquals("collection_3_1_2", SonglistLinkParser.parse(
            "https://m.kugou.com/share/zlist.html?global_collection_id=collection_3_1_2",
        )?.id)
        assertEquals(SourceID.KW, SonglistLinkParser.parse("https://www.kuwo.cn/playlist_detail/8888")?.source)
        assertEquals("8888", SonglistLinkParser.parse("https://www.kuwo.cn/playlist_detail/8888")?.id)
    }

    @Test
    fun `the same numeric id under different platforms stays on its own platform`() {
        assertEquals(SourceID.WY, SonglistLinkParser.parse("https://music.163.com/playlist/999")?.source)
        assertEquals(SourceID.TX, SonglistLinkParser.parse("https://y.qq.com/n/ryqq/playlist/999")?.source)
    }

    @Test
    fun `a short link is kept whole and flagged for resolution`() {
        val wy = SonglistLinkParser.parse("https://163cn.tv/abcDEF")!!
        assertEquals(SourceID.WY, wy.source)
        assertEquals("https://163cn.tv/abcDEF", wy.id)
        assertTrue(SonglistLinkParser.isShortLink(wy))

        val tx = SonglistLinkParser.parse("https://c6.y.qq.com/base/fcgi-bin/u?__=xyz")!!
        assertEquals(SourceID.TX, tx.source)
        assertTrue(SonglistLinkParser.isShortLink(tx))

        val kg = SonglistLinkParser.parse("https://t1.kugou.com/abc123")!!
        assertEquals(SourceID.KG, kg.source)
        assertTrue(SonglistLinkParser.isShortLink(kg))
    }

    @Test
    fun `a normal link is not a short link`() {
        val direct = SonglistLinkParser.parse("https://music.163.com/playlist?id=1")!!
        assertFalse(SonglistLinkParser.isShortLink(direct))
        // Same host name, wrong platform -> never claimed.
        assertFalse(SonglistLinkParser.isShortLink(
            com.walkman.tv.data.model.SonglistRef(SourceID.TX, "https://163cn.tv/abc"),
        ))
    }

    @Test
    fun `unrecognized input parses to null instead of guessing`() {
        assertNull(SonglistLinkParser.parse(""))
        assertNull(SonglistLinkParser.parse("   "))
        assertNull(SonglistLinkParser.parse("1234567"))
        assertNull(SonglistLinkParser.parse("hello world"))
        // A playlist id on an unrelated host must not be claimed by any platform.
        assertNull(SonglistLinkParser.parse("https://example.com/playlist?id=1234567"))
        assertNull(SonglistLinkParser.parse("https://music.163.com/"))
    }

    @Test
    fun `a bare numeric id is accepted only with an explicit platform`() {
        assertEquals("1234567", SonglistLinkParser.bareId(" 1234567 ", SourceID.WY)?.id)
        assertEquals(SourceID.KW, SonglistLinkParser.bareId("1234567", SourceID.KW)?.source)
        assertNull(SonglistLinkParser.bareId("abc", SourceID.WY))
        assertNull(SonglistLinkParser.bareId("", SourceID.WY))
        assertNull(SonglistLinkParser.bareId("https://music.163.com/playlist?id=1", SourceID.WY))
    }

    @Test
    fun `hostOf lowercases and drops the www prefix`() {
        assertEquals("music.163.com", SonglistLinkParser.hostOf("https://WWW.Music.163.com/playlist?id=1"))
        assertEquals("c6.y.qq.com", SonglistLinkParser.hostOf("https://c6.y.qq.com/base/fcgi-bin/u?__=x"))
        assertNull(SonglistLinkParser.hostOf("not a url"))
    }
}
