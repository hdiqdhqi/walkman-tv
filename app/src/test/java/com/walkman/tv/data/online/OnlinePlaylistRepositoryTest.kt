package com.walkman.tv.data.online

import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SonglistDetail
import com.walkman.tv.data.model.SonglistFetchResult
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistOrder
import com.walkman.tv.data.model.SonglistRef
import com.walkman.tv.data.model.SonglistTag
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.CatalogException
import com.walkman.tv.source.catalog.SonglistService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/**
 * The fetch seam — spec §4.3 (分页完整性 / 空歌单 / 超时 / HTTP 错误 / 认证失败 / 短链).
 *
 * The repository is driven through a fake [SonglistService], so no test touches the network.
 */
class OnlinePlaylistRepositoryTest {

    private val wy = SourceID.WY
    private val direct = SonglistRef(wy, "123", "https://music.163.com/playlist?id=123")

    @Test
    fun `a complete fetch is returned with its progress and normalized metadata`() = runBlocking {
        val service = FakeService(wy, listOf(wyTrack("a"), wyTrack("b")))
        val progress = mutableListOf<Pair<Int, Int?>>()

        val playlist = repository(service).fetch(direct) { fetched, total -> progress += fetched to total }

        assertEquals(listOf("wy_a", "wy_b"), playlist.tracks.map { it.id })
        assertEquals(direct.id, playlist.info.id)
        assertEquals(wy, playlist.info.source)
        assertEquals(2, playlist.declaredTotal)
        assertTrue(playlist.complete)
        assertEquals(listOf(2 to 2), progress)
        assertEquals(listOf("123"), service.asked)
    }

    @Test
    fun `an empty playlist is a failure, never an empty mirror instruction`() {
        val repo = repository(FakeService(wy, emptyList()))
        val e = assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repo.fetch(direct) { _, _ -> } }
        }
        assertEquals(OnlinePlaylistException.Kind.EMPTY, e.kind)
    }

    @Test
    fun `a partial page is passed through as incomplete`() = runBlocking {
        val service = FakeService(wy, listOf(wyTrack("a")), complete = false)
        val playlist = repository(service).fetch(direct) { _, _ -> }
        assertFalse(playlist.complete)
    }

    @Test
    fun `an unsupported platform is refused before any request`() {
        val service = FakeService(wy, listOf(wyTrack("a")))
        val repo = repository(service)
        val e = assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repo.fetch(SonglistRef(SourceID.MG, "1", "mg://1")) { _, _ -> } }
        }
        assertEquals(OnlinePlaylistException.Kind.UNSUPPORTED, e.kind)
        assertTrue(service.asked.isEmpty())
        // supports() mirrors the same lookup the fetch uses.
        assertFalse(repo.supports(SourceID.MG))
        assertTrue(repo.supports(wy))
    }

    @Test
    fun `an http failure is classified by status code`() {
        assertEquals(OnlinePlaylistException.Kind.AUTH, kindOf(CatalogException("HTTP 403 forbidden")))
        assertEquals(OnlinePlaylistException.Kind.RATE_LIMIT, kindOf(CatalogException("HTTP 429 too many")))
        assertEquals(OnlinePlaylistException.Kind.HTTP, kindOf(CatalogException("HTTP 500 boom")))
    }

    @Test
    fun `a transport failure is classified as a network error`() {
        assertEquals(OnlinePlaylistException.Kind.NETWORK, kindOf(UnknownHostException("dns")))
        assertEquals(OnlinePlaylistException.Kind.NETWORK, kindOf(java.io.IOException("reset")))
        assertEquals(OnlinePlaylistException.Kind.PARSE, kindOf(IllegalStateException("unexpected json")))
    }

    @Test
    fun `a body that is not json is a content failure, not a parser bug`() {
        // An HTML risk-control page, a truncated read or an empty body is worth retrying later, and
        // must not be filed under "the interface changed" (which is what the user used to see).
        assertEquals(
            OnlinePlaylistException.Kind.CONTENT,
            kindOf(org.json.JSONException("unexpected character at line 1, column 1")),
        )
        assertEquals(OnlinePlaylistException.Kind.CONTENT, kindOf(CatalogException("空响应")))
    }

    @Test
    fun `a stored id the platform cannot use is refused before any request`() {
        val service = FakeService(wy, listOf(wyTrack("a")))
        val unusable = SonglistRef(wy, "https://example.com/not-a-playlist", "https://example.com/not-a-playlist")

        val e = assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repository(service).fetch(unusable) { _, _ -> } }
        }

        assertEquals(OnlinePlaylistException.Kind.BAD_ID, e.kind)
        assertTrue(service.asked.isEmpty())
    }

    @Test
    fun `an LX-decorated stored id is repaired before the request`() = runBlocking {
        val service = FakeService(wy, listOf(wyTrack("a")))
        val synced = SonglistRef(wy, "id_123456###MUSIC_U=secret", "id_123456###MUSIC_U=secret")

        val playlist = repository(service).fetch(synced) { _, _ -> }

        // The credential half is dropped, so it can reach neither the request nor the log.
        assertEquals(listOf("123456"), service.asked)
        assertEquals("123456", playlist.ref.id)
    }

    @Test
    fun `a share short link is followed once and its raw url is kept`() = runBlocking {
        val service = FakeService(wy, listOf(wyTrack("a")))
        val resolved = mutableListOf<String>()
        val repo = OnlinePlaylistRepository(
            serviceFor = { if (it == wy) service else null },
            resolveFinalUrl = { url ->
                resolved += url
                "https://music.163.com/playlist?id=999"
            },
        )
        val short = SonglistRef(wy, "https://163cn.tv/abcDEF", "https://163cn.tv/abcDEF")

        val playlist = repo.fetch(short) { _, _ -> }

        assertEquals(listOf("https://163cn.tv/abcDEF"), resolved)
        assertEquals(listOf("999"), service.asked)
        assertEquals("999", playlist.ref.id)
        // What the user pasted stays available for the origin record / diagnostics.
        assertEquals("https://163cn.tv/abcDEF", playlist.ref.rawUrl)
    }

    @Test
    fun `a direct link never triggers a redirect lookup`() = runBlocking {
        var lookups = 0
        val repo = OnlinePlaylistRepository(
            serviceFor = { if (it == wy) FakeService(wy, listOf(wyTrack("a"))) else null },
            resolveFinalUrl = { lookups++; null },
        )
        repo.fetch(direct) { _, _ -> }
        assertEquals(0, lookups)
    }

    @Test
    fun `an unresolvable short link is a network error`() {
        val repo = OnlinePlaylistRepository(
            serviceFor = { FakeService(wy, listOf(wyTrack("a"))) },
            resolveFinalUrl = { null },
        )
        val e = assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repo.fetch(SonglistRef(wy, "https://163cn.tv/abc", "https://163cn.tv/abc")) { _, _ -> } }
        }
        assertEquals(OnlinePlaylistException.Kind.NETWORK, e.kind)
    }

    @Test
    fun `a short link that lands somewhere else is a content failure`() {
        val repo = OnlinePlaylistRepository(
            serviceFor = { FakeService(wy, listOf(wyTrack("a"))) },
            resolveFinalUrl = { "https://example.com/landing" },
        )
        val e = assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repo.fetch(SonglistRef(wy, "https://163cn.tv/abc", "https://163cn.tv/abc")) { _, _ -> } }
        }
        // Retargeted with the failure taxonomy: an expired link redirects to the platform's home
        // page (verified live - a dead `163cn.tv` link 302s to `https://music.163.com/`), which is
        // "we could not read a playlist out of what came back", not "the response shape moved".
        // PARSE is reserved for a body that parsed but no longer carries the fields we read, so
        // calling this PARSE is what used to turn a dead link into 「平台返回的数据无法识别」.
        assertEquals(OnlinePlaylistException.Kind.CONTENT, e.kind)
    }

    private fun repository(service: SonglistService) = OnlinePlaylistRepository(
        serviceFor = { if (it == service.source) service else null },
        resolveFinalUrl = { null },
    )

    /** Run a fetch against a service whose `fetchAll` throws [error] and read the classified kind. */
    private fun kindOf(error: Throwable): OnlinePlaylistException.Kind {
        val repo = repository(FakeService(wy, emptyList(), error = error))
        return assertThrows(OnlinePlaylistException::class.java) {
            runBlocking { repo.fetch(direct) { _, _ -> } }
        }.kind
    }
}

/** Minimal [SonglistService] whose `fetchAll` returns a fixed result (or throws [error]). */
private class FakeService(
    override val source: SourceID,
    private val tracks: List<Track>,
    private val complete: Boolean = true,
    private val error: Throwable? = null,
) : SonglistService {
    override val orders: List<SonglistOrder> = emptyList()

    /** Platform list ids this fake was asked for, in order. */
    val asked = mutableListOf<String>()

    override suspend fun fetchRecommended(order: SonglistOrder, tag: SonglistTag, page: Int): List<SonglistInfo> =
        emptyList()

    override suspend fun fetchDetail(list: SonglistInfo): SonglistDetail =
        throw UnsupportedOperationException("fetchDetail is not used by the online-playlist path")

    override suspend fun fetchAll(
        list: SonglistInfo,
        onProgress: (fetched: Int, total: Int?) -> Unit,
    ): SonglistFetchResult {
        asked += list.id
        error?.let { throw it }
        onProgress(tracks.size, tracks.size)
        return SonglistFetchResult(
            info = SonglistInfo(id = list.id, source = source, name = "远程歌单", trackCount = tracks.size),
            tracks = tracks,
            declaredTotal = tracks.size,
            complete = complete,
        )
    }
}

private fun wyTrack(mid: String): Track = Track(
    id = Track.makeID(SourceID.WY, mid),
    name = "歌 $mid",
    singer = "歌手",
    source = SourceID.WY,
    songmid = mid,
)
