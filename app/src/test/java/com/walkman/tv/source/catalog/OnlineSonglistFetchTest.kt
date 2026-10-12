package com.walkman.tv.source.catalog

import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SonglistFetchResult
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four platform fetchers, driven by canned bodies instead of the network.
 *
 * Every shape replayed here was captured from the live platform API on 2026-10-12 (the findings are
 * written up next to the code in `SonglistCatalog.kt`), so this file is the regression net for the
 * things that made "更新失败：平台返回的数据无法识别" happen:
 *
 *  - QQ's `musicu.fcg` is **POSTed** (the transport LX uses, pinned here by a regression test); a
 *    browse-endpoint refusal (`subcode:4000`, no `cdlist`) must fall back to the pager rather than
 *    read as an empty playlist.
 *  - 网易云 answers HTTP 200 with a *business* code and no `playlist` when it refuses an id
 *    (`404 歌单不存在`, `400 请求参数错误`), which used to be reported as an unrecognisable payload.
 *  - 网易云 ships the full `trackIds` but caps the `tracks` array, so the song objects have to come
 *    from batched `/api/song/detail` calls.
 *  - 酷我 stops at `rn` songs per `pl.svc` page and always reports the true `total`.
 *  - 酷狗 answers a deleted playlist with a generic HTML page (no `global.data`).
 *
 * Nothing here asserts on a *live* response; the fixtures are fixed and repeatable.
 */
class OnlineSonglistFetchTest {

    // ---------------------------------------------------------------- 网易云 NetEase

    @Test
    fun `netease reads trackIds then fetches the whole song list in one batch`() {
        val http = FakeHttp { call ->
            when {
                call.url.contains(NE_DETAIL) -> neV6Body(listOf(1, 2, 3))
                call.url.contains(NE_SONGS) -> neSongBody(idsIn(call.url))
                else -> ""
            }
        }

        val result = fetch(NetEaseSonglist(http), "24381616")

        assertEquals(listOf("wy_1", "wy_2", "wy_3"), result.tracks.map { it.id })
        assertEquals("歌 1", result.tracks.first().name)
        assertEquals("歌手 1", result.tracks.first().singer)
        assertEquals("测试歌单", result.info.name)
        assertEquals(3, result.declaredTotal)
        assertTrue(result.complete)
        // The detail call is a POST form carrying the id (`n=100000` asks for everything).
        val detail = http.callsTo(NE_DETAIL).single()
        assertEquals("POST", detail.method)
        assertTrue(detail.body!!.contains("id=24381616"))
        assertEquals(1, http.callsTo(NE_SONGS).size)
    }

    @Test
    fun `netease splits a long playlist into 100-id batches and keeps the order`() {
        val ids = (1..250).toList()
        val http = FakeHttp { call ->
            when {
                call.url.contains(NE_DETAIL) -> neV6Body(ids)
                call.url.contains(NE_SONGS) -> neSongBody(idsIn(call.url))
                else -> ""
            }
        }

        val result = fetch(NetEaseSonglist(http), "24381616")

        assertEquals(250, result.tracks.size)
        assertEquals("wy_1", result.tracks.first().id)
        assertEquals("wy_250", result.tracks.last().id)
        assertEquals(listOf(100, 100, 50), http.callsTo(NE_SONGS).map { idsIn(it.url).size })
        assertEquals(250, result.declaredTotal)
        assertTrue(result.complete)
    }

    @Test
    fun `netease business codes keep a category of their own`() {
        assertEquals(OnlinePlaylistException.Kind.NOT_FOUND, neKind("""{"code":404,"msg":"歌单不存在"}"""))
        assertEquals(OnlinePlaylistException.Kind.BAD_ID, neKind("""{"code":400,"msg":"请求参数错误"}"""))
        assertEquals(OnlinePlaylistException.Kind.RATE_LIMIT, neKind("""{"code":-460,"msg":"cheating"}"""))
        assertEquals(OnlinePlaylistException.Kind.EMPTY, neKind(neV6Body(emptyList(), trackCount = 0)))
    }

    @Test
    fun `netease a payload that is not JSON is a content failure, not a parse failure`() {
        assertEquals(OnlinePlaylistException.Kind.CONTENT, neKind("<html><body>验证</body></html>"))
        assertEquals(OnlinePlaylistException.Kind.CONTENT, neKind(""))
    }

    @Test
    fun `netease a renamed trackIds array is a shape change`() {
        assertEquals(
            OnlinePlaylistException.Kind.PARSE,
            neKind("""{"code":200,"playlist":{"name":"x","trackCount":3,"songIds":[{"id":1}]}}"""),
        )
    }

    @Test
    fun `netease a failed detail batch leaves the read incomplete`() {
        val ids = (1..150).toList()
        val http = FakeHttp { call ->
            when {
                call.url.contains(NE_DETAIL) -> neV6Body(ids)
                call.url.contains(NE_SONGS) && idsIn(call.url).size == 50 -> throw CatalogException("HTTP 500 boom")
                else -> neSongBody(idsIn(call.url))
            }
        }

        val result = fetch(NetEaseSonglist(http), "24381616")

        assertEquals(100, result.tracks.size)
        assertEquals(150, result.declaredTotal)
        assertFalse(result.complete)
    }

    // ---------------------------------------------------------------- 酷我 Kuwo

    @Test
    fun `kuwo pages until the platform total and then stops asking`() {
        val http = FakeHttp { call ->
            if (call.url.contains("pl.svc")) kuwoPage(pnIn(call.url)) else ""
        }

        val result = fetch(KuwoSonglist(http), "2692001108")

        assertEquals(444, result.tracks.size)
        assertEquals(444, result.declaredTotal)
        assertEquals("测试歌单", result.info.name)
        assertEquals("歌手", result.tracks.first().singer)
        assertTrue(result.complete)
        // 300 songs on pn=0, the remaining 144 on pn=1 - pn=2 is never requested.
        assertEquals(listOf(0, 1), http.callsTo("pl.svc").map { pnIn(it.url) })
    }

    @Test
    fun `kuwo a failed later page leaves the read incomplete`() {
        val http = FakeHttp { call ->
            when {
                !call.url.contains("pl.svc") -> ""
                pnIn(call.url) == 1 -> throw CatalogException("HTTP 500 boom")
                else -> kuwoPage(0)
            }
        }

        val result = fetch(KuwoSonglist(http), "2692001108")

        assertEquals(300, result.tracks.size)
        assertEquals(444, result.declaredTotal)
        assertFalse(result.complete)
    }

    @Test
    fun `kuwo under-delivering against its own total is incomplete as well`() {
        val http = FakeHttp { call ->
            if (call.url.contains("pl.svc")) kuwoPage(if (pnIn(call.url) == 0) 0 else 99) else ""
        }

        val result = fetch(KuwoSonglist(http), "2692001108")

        assertEquals(300, result.tracks.size)
        assertEquals(444, result.declaredTotal)
        assertFalse(result.complete)
    }

    @Test
    fun `kuwo empty and refused answers are failures, never an empty mirror`() {
        assertEquals(OnlinePlaylistException.Kind.EMPTY, kwKind("""{"result":"ok","total":0,"musiclist":[]}"""))
        assertEquals(OnlinePlaylistException.Kind.NOT_FOUND, kwKind("""{"result":"fail","msg":"no such playlist"}"""))
        assertEquals(OnlinePlaylistException.Kind.CONTENT, kwKind("<html>busy</html>"))
        // Songs were delivered but the field names moved - an interface change, not an empty list.
        assertEquals(OnlinePlaylistException.Kind.PARSE, kwKind("""{"result":"ok","musiclist":[{"name":"no id"}]}"""))
    }

    // ---------------------------------------------------------------- QQ 音乐

    @Test
    fun `qq pages through a posted json body until the declared total is reached`() {
        val total = 1669
        val http = FakeHttp { call ->
            val begin = beginIn(call.body!!)
            val count = minOf(1000, total - begin)
            qqPage(qqSongs(begin + 1, count), total, if (begin + count >= total) 0 else 1)
        }

        val result = fetch(QQSonglist(http), "9744256455")

        assertEquals(total, result.tracks.size)
        assertEquals("歌 1", result.tracks.first().name)
        assertEquals("歌 1669", result.tracks.last().name)
        assertEquals("测试歌单", result.info.name)
        assertEquals(total, result.declaredTotal)
        assertTrue(result.complete)
        // Every page is a POSTed JSON body: the transport LX uses for this call and the one this
        // regression test pins. (Re-checked live on 2026-10-12, a GET `?data=` with the same params
        // answers code 0 too, so the verb is a consistency choice, not a workaround.)
        assertEquals(listOf(0, 1000), http.calls.map { beginIn(it.body!!) })
        assertTrue(http.calls.all { it.method == "POST" })
        assertTrue(http.calls.all { it.url.contains(QQ_FCG) })
        assertTrue(http.calls.all { !it.url.contains("data=") })
    }

    /**
     * The 2026-10-12 TV log, replayed: 更新「我喜欢」 ended with
     * `stage=playlist page begin=900 fetched=1199 declared=1967 firstPage=300 reason=short`,
     * `SONGLIST_FETCH_OK ... complete=false` and then `SONGLIST_UPDATE_FAIL kind=INCOMPLETE`. The
     * platform drops songs it cannot serve, so a page comes back short *in the middle* of the list;
     * ending the read there threw away everything after it.
     */
    @Test
    fun `qq keeps paging through the short pages a filtered playlist serves`() {
        val http = FakeHttp { call ->
            val begin = beginIn(call.body!!)
            when (begin) {
                0 -> qqPage(qqSongs(1, 1000), 1967, 1)
                // Short page in the middle of the list: 700 songs for a 1000-song window.
                1000 -> qqPage(qqSongs(1001, 700), 1960, 1)
                else -> {
                    assertEquals(2000, begin)
                    qqPage(qqSongs(1701, 258), 1958, 0)
                }
            }
        }

        val result = fetch(QQSonglist(http), "1113832590")

        assertEquals(listOf(0, 1000, 2000), http.calls.map { beginIn(it.body!!) })
        assertEquals(1958, result.tracks.size)
        assertEquals("歌 1958", result.tracks.last().name)
        // The reachable total is the *last* page's, not the 1967 the first page counted (which
        // included songs the platform will not serve).
        assertEquals(1958, result.declaredTotal)
        assertTrue(result.complete)
    }

    @Test
    fun `qq without a hasmore flag a short page is not trusted as the end`() {
        val http = FakeHttp { call ->
            if (beginIn(call.body!!) == 0) qqPageNoHasmore(qqSongs(1, 1000), 1967)
            else qqPageNoHasmore(qqSongs(1001, 300), 1967)
        }

        val result = fetch(QQSonglist(http), "1113832590")

        // The full page guesses "there may be more" and is followed; the short one guesses "the end"
        // and is followed too, but a guess must never be reported as a complete read.
        assertEquals(listOf(0, 1000), http.calls.map { beginIn(it.body!!) })
        assertEquals(1300, result.tracks.size)
        assertFalse(result.complete)
    }

    @Test
    fun `qq an unknown playlist id is not found`() {
        assertEquals(
            OnlinePlaylistException.Kind.NOT_FOUND,
            qqKind("""{"code":0,"req_1":{"code":0,"data":{"code":-100006,"msg":"get diss info from tmem error"}}}"""),
        )
    }

    @Test
    fun `qq a rejected request shape is a parse failure after the legacy module is tried`() {
        val http = FakeHttp { """{"req_1":{"code":10004,"data":{"code":-100008,"msg":"param error"}}}""" }

        assertEquals(OnlinePlaylistException.Kind.PARSE, kindOf(QQSonglist(http), "9744256455"))

        // Today's module first, then the older one LX falls back to.
        assertEquals(2, http.calls.size)
        assertTrue(http.calls[0].body!!.contains("music.srfDissInfo.aiDissInfo"))
        assertTrue(http.calls[1].body!!.contains("music.srfDissInfo.DissInfo"))
    }

    @Test
    fun `qq an unrecognised business code is not reported as a deleted playlist`() {
        val http = FakeHttp { """{"code":0,"req_1":{"code":0,"data":{"code":-100010,"msg":"who knows"}}}""" }

        assertEquals(OnlinePlaylistException.Kind.CONTENT, kindOf(QQSonglist(http), "9744256455"))
        // A business refusal is not a *shape* problem, so the legacy module is not retried.
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `qq a missing songlist array is a shape change`() {
        assertEquals(
            OnlinePlaylistException.Kind.PARSE,
            qqKind("""{"code":0,"req_1":{"code":0,"data":{"code":0,"total_song_num":3,"dirinfo":{"songnum":3}}}}"""),
        )
    }

    @Test
    fun `qq a page that declares songs but maps none is a shape change`() {
        assertEquals(OnlinePlaylistException.Kind.PARSE, qqKind(qqPage("", total = 3, hasmore = 0)))
    }

    @Test
    fun `qq a later page failing leaves the read incomplete`() {
        val http = FakeHttp { call ->
            if (beginIn(call.body!!) == 0) qqPage(qqSongs(1, 1000), 1669, 1)
            else throw CatalogException("HTTP 500 boom")
        }

        val result = fetch(QQSonglist(http), "9744256455")

        assertEquals(1000, result.tracks.size)
        assertEquals(1669, result.declaredTotal)
        assertFalse(result.complete)
    }

    @Test
    fun `qq a playlist the browse endpoint declines is read through the pager instead`() {
        val http = FakeHttp { call ->
            if (call.url.contains(QQ_BROWSE)) """{"subcode":4000,"msg":"check privacy error!"}"""
            else qqPage(qqSongs(1, 5), 5, 0)
        }

        val detail = runBlocking {
            QQSonglist(http).fetchDetail(SonglistInfo(id = "8591179133", source = SourceID.TX, name = ""))
        }

        // Verified live on 2026-10-12: `fcg_ucc_getcdinfo_byids_cp` answers disstid 8591179133 with
        // `subcode:4000 "check privacy error!"` and no `cdlist`, while `musicu.fcg` serves the songs.
        // Browsing used to come back empty for a playlist the browse endpoint declined.
        assertEquals(5, detail.tracks.size)
        assertEquals("歌 1", detail.tracks.first().name)
        assertEquals(1, http.callsTo(QQ_BROWSE).size)
        assertTrue(http.calls.any { it.url.contains(QQ_FCG) })
    }

    // ---------------------------------------------------------------- 酷狗 Kugou

    @Test
    fun `kugou reads the embedded hash array and resolves it in one batch`() {
        val http = FakeHttp { call ->
            when {
                call.url.contains(KG_SINGLE) -> kgPage(listOf("aaa", "bbb"))
                call.url.contains(KG_AUDIO) -> kgAudioBody(hashesIn(call.body!!))
                else -> ""
            }
        }

        val result = fetch(KugouSonglist(http), "1234567890")

        // Hashes arrive lower-case on the page and are upper-cased before the detail call.
        assertEquals(listOf("kg_AAA", "kg_BBB"), result.tracks.map { it.id })
        assertEquals("歌 AAA", result.tracks.first().name)
        assertEquals("歌手", result.tracks.first().singer)
        assertTrue(result.tracks.first().qualities.contains(com.walkman.tv.data.model.Quality.K320))
        // 酷狗 reports no trustworthy total; what we could read is the only number there is.
        assertNull(result.declaredTotal)
        assertTrue(result.complete)
        val audio = http.callsTo(KG_AUDIO).single()
        assertEquals("POST", audio.method)
        assertTrue(audio.body!!.contains("\"AAA\""))
    }

    @Test
    fun `kugou a deleted playlist page is not found rather than an empty playlist`() {
        assertEquals(OnlinePlaylistException.Kind.NOT_FOUND, kgKind("<!DOCTYPE html><html><body>歌单不存在</body></html>"))
    }

    @Test
    fun `kugou an unreadable page that is not html is a content failure`() {
        assertEquals(OnlinePlaylistException.Kind.CONTENT, kgKind("   "))
        assertEquals(OnlinePlaylistException.Kind.CONTENT, kgKind("""{"error":"risk control"}"""))
    }

    @Test
    fun `kugou an embedded but empty song array is an empty playlist`() {
        assertEquals(OnlinePlaylistException.Kind.EMPTY, kgKind(kgPage(emptyList())))
    }

    @Test
    fun `kugou a detail response without a data array is a shape change`() {
        val http = FakeHttp { call ->
            if (call.url.contains(KG_SINGLE)) kgPage(listOf("aaa")) else """{"status":1}"""
        }
        assertEquals(OnlinePlaylistException.Kind.PARSE, kindOf(KugouSonglist(http), "1234567890"))
    }

    @Test
    fun `kugou a failed detail batch leaves the read incomplete`() {
        val hashes = (0 until 150).map { "C$it" }
        val http = FakeHttp { call ->
            when {
                call.url.contains(KG_SINGLE) -> kgPage(hashes)
                call.body.orEmpty().contains("\"C100\"") -> throw CatalogException("HTTP 500 boom")
                else -> kgAudioBody(hashesIn(call.body.orEmpty()))
            }
        }

        val result = fetch(KugouSonglist(http), "1234567890")

        assertEquals(100, result.tracks.size)
        assertFalse(result.complete)
    }

    @Test
    fun `kugou a fully failing detail call surfaces the transport failure untouched`() {
        val http = FakeHttp { call ->
            if (call.url.contains(KG_SINGLE)) kgPage(listOf("aaa", "bbb")) else throw CatalogException("HTTP 500 boom")
        }
        // A plain transport exception is handed back as-is on purpose: turning it into a Kind is the
        // repository's job (`OnlinePlaylistRepository.classify` maps `HTTP 500` to Kind.HTTP).
        val e = assertThrows(CatalogException::class.java) { fetch(KugouSonglist(http), "1234567890") }
        assertEquals("HTTP 500 boom", e.message)
    }

    // ---------------------------------------------------------------- helpers

    private fun fetch(service: SonglistService, id: String): SonglistFetchResult =
        runBlocking { service.fetchAll(SonglistInfo(id = id, source = service.source, name = "")) }

    private fun kindOf(service: SonglistService, id: String): OnlinePlaylistException.Kind =
        assertThrows(OnlinePlaylistException::class.java) { fetch(service, id) }.kind

    private fun neKind(body: String): OnlinePlaylistException.Kind =
        kindOf(NetEaseSonglist(FakeHttp { body }), "24381616")

    private fun kwKind(body: String): OnlinePlaylistException.Kind =
        kindOf(KuwoSonglist(FakeHttp { body }), "2692001108")

    private fun qqKind(body: String): OnlinePlaylistException.Kind =
        kindOf(QQSonglist(FakeHttp { body }), "9744256455")

    private fun kgKind(html: String): OnlinePlaylistException.Kind =
        kindOf(KugouSonglist(FakeHttp { html }), "1234567890")
}

/** One request a fetcher made, so a test can assert *which* endpoint, verb and body was used. */
private data class HttpCall(val method: String, val url: String, val body: String?)

/** A [CatalogHttpLike] that answers from canned bodies. No socket is ever opened. */
private class FakeHttp(private val handler: (HttpCall) -> String) : CatalogHttpLike {
    val calls = mutableListOf<HttpCall>()

    fun callsTo(fragment: String) = calls.filter { it.url.contains(fragment) }

    override suspend fun getText(url: String, headers: Map<String, String>): String = respond(HttpCall("GET", url, null))

    override suspend fun postForm(url: String, form: String, headers: Map<String, String>): String =
        respond(HttpCall("POST", url, form))

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): String =
        respond(HttpCall("POST", url, json))

    override suspend fun resolveFinalUrl(url: String, headers: Map<String, String>): String? = null

    private fun respond(call: HttpCall): String {
        calls += call
        return handler(call)
    }
}

private const val NE_DETAIL = "api/v6/playlist/detail"
private const val NE_SONGS = "api/song/detail"
private const val KG_SINGLE = "kugou.kugou.com/yueku/v9/special/single/"
private const val KG_AUDIO = "gateway.kugou.com/v2/album_audio/audio"
private const val QQ_FCG = "u.y.qq.com/cgi-bin/musicu.fcg"
private const val QQ_BROWSE = "fcg_ucc_getcdinfo_byids_cp"

/** The ids a batched `/api/song/detail` GET asked for. */
private fun idsIn(url: String): List<Int> {
    val raw = java.net.URLDecoder.decode(url.substringAfter("ids=", ""), "UTF-8")
    return raw.trim('[', ']').split(',').mapNotNull { it.trim().toIntOrNull() }
}

/** The `pn` a `pl.svc` page asked for. */
private fun pnIn(url: String): Int = url.substringAfter("pn=", "0").substringBefore("&").toIntOrNull() ?: 0

/** The `song_begin` a `musicu.fcg` page asked for. */
private fun beginIn(body: String): Int =
    JSONObject(body).getJSONObject("req_1").getJSONObject("param").getInt("song_begin")

private fun neV6Body(trackIds: List<Int>, trackCount: Int = trackIds.size, name: String = "测试歌单"): String {
    val ids = trackIds.joinToString(",", "[", "]") { "{\"id\":$it}" }
    return "{\"code\":200,\"playlist\":{\"id\":24381616,\"name\":\"$name\"," +
        "\"coverImgUrl\":\"https://p1.music.126.net/x.jpg\",\"trackCount\":$trackCount," +
        "\"creator\":{\"nickname\":\"某人\"},\"trackIds\":$ids}}"
}

private fun neSong(id: Int): String =
    "{\"id\":$id,\"name\":\"歌 $id\",\"artists\":[{\"name\":\"歌手 $id\"}]," +
        "\"album\":{\"id\":10,\"name\":\"专辑\",\"picUrl\":\"https://p1.music.126.net/a.jpg\"}," +
        "\"duration\":180000,\"mvid\":0}"

private fun neSongBody(ids: List<Int>): String = "{\"songs\":[${ids.joinToString(",") { neSong(it) }}]}"

/** 300 songs on `pn=0`, the remaining 144 on `pn=1`, and a true total of 444 on every page. */
private fun kuwoPage(pn: Int): String {
    val (from, count) = when (pn) {
        0 -> 1 to 300
        1 -> 301 to 144
        else -> 445 to 0
    }
    val items = (from until from + count).joinToString(",") { i ->
        "{\"id\":$i,\"name\":\"歌 $i\",\"artist\":\"歌手\",\"album\":\"专辑\",\"albumid\":1," +
            "\"duration\":200,\"formats\":\"MP3128|MP3H\"}"
    }
    return "{\"result\":\"ok\",\"total\":444,\"title\":\"测试歌单\",\"uname\":\"某人\",\"pic\":\"\"," +
        "\"musiclist\":[$items]}"
}

private fun qqSongs(from: Int, count: Int): String = (from until from + count).joinToString(",") { i ->
    "{\"mid\":\"m$i\",\"id\":$i,\"name\":\"歌 $i\",\"singer\":[{\"name\":\"歌手 $i\"}]," +
        "\"album\":{\"id\":1,\"mid\":\"alb\",\"name\":\"专辑\"}," +
        "\"file\":{\"size_128mp3\":100,\"media_mid\":\"mm\"},\"interval\":200}"
}

private fun qqPage(songs: String, total: Int, hasmore: Int): String =
    "{\"code\":0,\"req_1\":{\"code\":0,\"data\":{\"code\":0,\"total_song_num\":$total,\"hasmore\":$hasmore," +
        "\"dirinfo\":{\"title\":\"测试歌单\",\"host_nick\":\"某人\",\"songnum\":$total,\"picurl\":\"\"}," +
        "\"songlist\":[$songs]}}}"

/** [qqPage] without the `hasmore` field: the payload shape the reader has to *guess* the end from. */
private fun qqPageNoHasmore(songs: String, total: Int): String =
    "{\"code\":0,\"req_1\":{\"code\":0,\"data\":{\"code\":0,\"total_song_num\":$total," +
        "\"dirinfo\":{\"title\":\"测试歌单\",\"host_nick\":\"某人\",\"songnum\":$total,\"picurl\":\"\"}," +
        "\"songlist\":[$songs]}}}"

private fun kgPage(hashes: List<String>): String =
    "<!DOCTYPE html><html><body><script>global.data = [${hashes.joinToString(",") { "{\"hash\":\"$it\"}" }}];</script></body></html>"

/** The `album_audio` answer: a two-level array, one single-element array per requested hash. */
private fun kgAudioBody(hashes: List<String>): String =
    "{\"status\":1,\"data\":[${hashes.joinToString(",") { "[${kgAudioTrack(it)}]" }}]}"

private fun kgAudioTrack(hash: String): String =
    "{\"songname\":\"歌 $hash\",\"author_name\":\"歌手\"," +
        "\"audio_info\":{\"hash\":\"$hash\",\"audio_id\":\"$hash\",\"timelength\":200000," +
        "\"filesize\":100,\"filesize_320\":200}," +
        "\"album_info\":{\"album_id\":10,\"album_name\":\"专辑\",\"sizable_cover\":\"http://x/{size}\"}}"

private fun hashesIn(body: String): List<String> =
    Regex("\"hash\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
