package com.walkman.tv.data.songlist

import com.walkman.tv.data.model.OnlinePlaylist
import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.online.OnlinePlaylistFetcher
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.sync.tempDir
import com.walkman.tv.playback.imports.ImportError
import com.walkman.tv.playback.imports.SonglistImporter
import com.walkman.tv.playback.imports.SonglistRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import orchestration — spec §4.1 (确认后创建本地歌单), §4.3 (完整获取) and §5 (来源关联信息).
 */
class SonglistImporterTest {

    @Test
    fun `parsing delegates to the shared link parser`() {
        val wy = SonglistImporter.parse("https://music.163.com/playlist?id=123")!!
        assertEquals(SourceID.WY, wy.source)
        assertEquals("123", wy.id)
        assertEquals(SourceID.TX, SonglistImporter.parse("https://y.qq.com/n/ryqq/playlist/456")!!.source)
        // Short links are recognized here too, so the dialog can accept them.
        assertEquals(SourceID.WY, SonglistImporter.parse("https://163cn.tv/abc")!!.source)
        assertNull(SonglistImporter.parse("https://example.com/playlist?id=1"))
        assertEquals("789", SonglistImporter.pureIdOrNull(" 789 "))
        assertNull(SonglistImporter.pureIdOrNull("abc"))
    }

    @Test
    fun `import stores the resolved platform id and registers a refreshable origin`() = runBlocking {
        val dir = tempDir()
        val library = LibraryStore(dir)
        val origins = ImportedSonglistStore(dir)
        val fetcher = ImportFetcher(resolvedId = "999")

        val result = SonglistImporter.importPlaylist(
            ref = SonglistRef(SourceID.WY, "https://163cn.tv/x"),
            customName = null,
            fetcher = fetcher,
            library = library,
            origins = origins,
        )

        val list = library.userLists.value.single()
        assertEquals("远程歌单", list.name)
        assertEquals("wy", list.source)
        // The *resolved* id, not the short link: later updates must hit the real playlist.
        assertEquals("999", list.sourceListId)
        assertEquals(list.id, result.playlistId)
        assertEquals(1, result.count)
        assertEquals(listOf("wy_a"), list.tracks.map { it.id })

        val origin = origins.get(list.id)!!
        assertEquals(SourceID.WY, origin.source)
        assertEquals("999", origin.sourceListId)
        assertEquals("https://163cn.tv/x", origin.rawUrl)
        assertEquals(UpdateStatus.OK, origin.lastStatus)
        assertEquals(1, origin.lastTrackCount)
        assertEquals(setOf("wy_a"), origin.onlineTrackIds)
        assertTrue(origin.autoUpdate)
    }

    @Test
    fun `an empty platform answer is reported and no list is created`() = runBlocking {
        val dir = tempDir()
        val library = LibraryStore(dir)
        val origins = ImportedSonglistStore(dir)
        val fetcher = ImportFetcher(error = OnlinePlaylistException(OnlinePlaylistException.Kind.EMPTY, "empty"))

        var thrown: ImportError? = null
        try {
            SonglistImporter.importPlaylist(SonglistRef(SourceID.WY, "1"), null, fetcher, library, origins)
        } catch (e: ImportError) {
            thrown = e
        }

        assertTrue(thrown is ImportError.EmptyPlaylist)
        assertTrue(library.userLists.value.isEmpty())
        assertTrue(origins.all().isEmpty())
    }

    @Test
    fun `previewing and then importing costs a single fetch`() = runBlocking {
        val dir = tempDir()
        val library = LibraryStore(dir)
        val origins = ImportedSonglistStore(dir)
        val fetcher = ImportFetcher()
        val ref = SonglistRef(SourceID.WY, "1")

        // 预览 → the dialog's pre-import step.
        val preview = SonglistImporter.fetchPlaylist(ref, fetcher)
        assertEquals(1, fetcher.calls)
        assertEquals("远程歌单", preview.info.name)

        // 确定导入 → reuses the preview instead of downloading the playlist again.
        val result = SonglistImporter.createFromFetched(preview, null, library, origins)

        assertEquals(1, fetcher.calls)
        assertEquals(1, result.count)
        assertEquals("远程歌单", library.userLists.value.single().name)
    }

    @Test
    fun `a platform without a provider is reported as an unrecognized link`() = runBlocking {
        val dir = tempDir()
        val library = LibraryStore(dir)
        val origins = ImportedSonglistStore(dir)
        val fetcher = ImportFetcher(
            error = OnlinePlaylistException(OnlinePlaylistException.Kind.UNSUPPORTED, "no provider"),
        )

        var thrown: ImportError? = null
        try {
            SonglistImporter.importPlaylist(SonglistRef(SourceID.MG, "1"), null, fetcher, library, origins)
        } catch (e: ImportError) {
            thrown = e
        }

        assertTrue(thrown is ImportError.UnrecognizedURL)
        assertTrue(library.userLists.value.isEmpty())
    }
}

/** A fetcher that answers with a fixed, complete playlist (or throws [error]). */
private class ImportFetcher(
    private val resolvedId: String = "123",
    private val tracks: List<Track> = listOf(
        Track(Track.makeID(SourceID.WY, "a"), "歌 a", "歌手", source = SourceID.WY, songmid = "a"),
    ),
    private val error: Throwable? = null,
) : OnlinePlaylistFetcher {

    /** How many times the platform was actually read. */
    var calls: Int = 0

    override suspend fun fetch(
        ref: com.walkman.tv.data.model.SonglistRef,
        onProgress: (fetched: Int, total: Int?) -> Unit,
    ): OnlinePlaylist {
        calls++
        error?.let { throw it }
        onProgress(tracks.size, tracks.size)
        return OnlinePlaylist(
            ref = ref.copy(id = resolvedId),
            info = SonglistInfo(id = resolvedId, source = ref.source, name = "远程歌单", trackCount = tracks.size),
            tracks = tracks,
            declaredTotal = tracks.size,
            complete = true,
        )
    }
}
