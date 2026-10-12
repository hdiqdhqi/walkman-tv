package com.walkman.tv.data.online

import com.walkman.tv.data.model.SourceID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stored-id repair, mirroring LX Music's "normalise on every fetch" behaviour
 * (`songList.getListId()` runs inside `getListDetail`/`getListDetailAll`).
 *
 * A list imported on another LX client — or by an older build of this app — can reach the refresh
 * path as a share link, an LX-decorated id or an `id###credential` pair. Handing that straight to
 * the platform API is what made NetEase answer `{"code":400}` ("请求参数错误") and the old code
 * report 「平台返回的数据无法识别」.
 */
class SonglistIdNormalizerTest {

    @Test
    fun `a plain numeric id is already usable`() {
        assertEquals("24381616", SonglistIdNormalizer.normalize(SourceID.WY, "24381616"))
        assertEquals("9744256455", SonglistIdNormalizer.normalize(SourceID.TX, "9744256455"))
        assertEquals("2692001108", SonglistIdNormalizer.normalize(SourceID.KW, " 2692001108 "))
    }

    @Test
    fun `a share link is reduced to its platform id`() {
        assertEquals(
            "24381616",
            SonglistIdNormalizer.normalize(SourceID.WY, "https://music.163.com/playlist?id=24381616&userid=1"),
        )
        assertEquals(
            "9744256455",
            SonglistIdNormalizer.normalize(SourceID.TX, "https://y.qq.com/n/ryqq/playlist/9744256455"),
        )
        assertEquals(
            "2692001108",
            SonglistIdNormalizer.normalize(SourceID.KW, "https://www.kuwo.cn/playlist_detail/2692001108"),
        )
        assertEquals(
            "1234567890",
            SonglistIdNormalizer.normalize(SourceID.KG, "https://www.kugou.com/songlist/1234567890"),
        )
    }

    @Test
    fun `an LX credential suffix is dropped, never carried into a request`() {
        assertEquals("24381616", SonglistIdNormalizer.normalize(SourceID.WY, "24381616###MUSIC_U=abc123"))
    }

    @Test
    fun `a javascript number artifact is repaired`() {
        // LX repairs this on the DB read path (`dbHelper.ts`: replace(/\.0$/, '')); a synced record
        // can therefore legitimately arrive as "9744256455.0".
        assertEquals("9744256455", SonglistIdNormalizer.normalize(SourceID.TX, "9744256455.0"))
        assertEquals("24381616", SonglistIdNormalizer.normalize(SourceID.WY, "24381616.0###MUSIC_U=abc"))
        // Only the exact `.<zeros-then-0>` integer form, never a real decimal or a version-ish id.
        assertNull(SonglistIdNormalizer.normalize(SourceID.WY, "24381616.5"))
    }

    @Test
    fun `LX id decorations are stripped`() {
        assertEquals("1067062", SonglistIdNormalizer.normalize(SourceID.WY, "id_1067062"))
        assertEquals("1234567", SonglistIdNormalizer.normalize(SourceID.KW, "kw__1234567"))
        assertEquals("1234567", SonglistIdNormalizer.normalize(SourceID.TX, "tx_1234567"))
        // The `<source>__<id>` form a synced playlist carries.
        assertEquals("93", SonglistIdNormalizer.normalize(SourceID.KW, "kw__93"))
    }

    @Test
    fun `kugou concept collections survive the prefix stripper`() {
        assertEquals("collection_3_2122406585_2_0", SonglistIdNormalizer.normalize(SourceID.KG, "collection_3_2122406585_2_0"))
    }

    @Test
    fun `a short link is kept whole so the fetch layer can follow it`() {
        assertEquals("https://163cn.tv/abcDEF", SonglistIdNormalizer.normalize(SourceID.WY, "https://163cn.tv/abcDEF"))
        assertEquals("https://c6.y.qq.com/base/fcgi-bin/u?__=xyz", SonglistIdNormalizer.normalize(SourceID.TX, "https://c6.y.qq.com/base/fcgi-bin/u?__=xyz"))
        assertEquals("https://t1.kugou.com/abcdef", SonglistIdNormalizer.normalize(SourceID.KG, "https://t1.kugou.com/abcdef"))
    }

    @Test
    fun `an id the platform cannot use is refused instead of sent`() {
        assertNull(SonglistIdNormalizer.normalize(SourceID.WY, ""))
        assertNull(SonglistIdNormalizer.normalize(SourceID.WY, "###MUSIC_U=abc"))
        assertNull(SonglistIdNormalizer.normalize(SourceID.WY, "https://example.com/playlist?id=1"))
        assertNull(SonglistIdNormalizer.normalize(SourceID.TX, "https://example.com/x"))
        assertNull(SonglistIdNormalizer.normalize(SourceID.KG, "not-a-real-id"))
        // 咪咕 has no playlist provider and a local list has no platform id at all.
        assertNull(SonglistIdNormalizer.normalize(SourceID.MG, "1"))
        assertNull(SonglistIdNormalizer.normalize(SourceID.LOCAL, "1"))
    }
}
