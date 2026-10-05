package com.walkman.tv.playback.download

import com.walkman.tv.data.model.DownloadFolder
import com.walkman.tv.data.model.DownloadRecord
import com.walkman.tv.data.model.DownloadStatus
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The File-vs-SAF decision behind 「删除下载」. Android-free on purpose: the store only maps the
 * resolved target onto `File.delete()` / `DocumentFile.delete()`, so what must never be wrong
 * (which mechanism a record uses, and the exact path) is asserted here.
 */
class DownloadFileLocatorTest {

    private val appScopedRoot =
        File("/storage/emulated/0/Android/data/com.walkman.tv/files/Music")

    private fun track(id: String = "t1") = Track(
        id = id,
        name = "测试歌曲",
        singer = "测试歌手",
        source = SourceID.KW,
        songmid = "mid-1",
    )

    private fun record(
        fileName: String = "",
        baseDir: String? = null,
        safUri: String? = null,
    ) = DownloadRecord(
        track = track(),
        quality = Quality.K320,
        fileName = fileName,
        status = DownloadStatus.COMPLETED,
        folderID = DownloadFolder.DEFAULT_ID,
        baseDir = baseDir,
        safUri = safUri,
    )

    @Test
    fun plainFileResolvesUnderItsOwnRootWithThePartSibling() {
        val rec = record(
            fileName = "歌手/专辑/01 - 歌名.flac",
            baseDir = appScopedRoot.path,
        )

        val target = DownloadFileLocator.locate(rec) { File(it.baseDir!!) }

        assertTrue(target is DownloadFileTarget.PlainFile)
        target as DownloadFileTarget.PlainFile
        val expected = File(appScopedRoot, "歌手/专辑/01 - 歌名.flac")
        assertEquals(expected, target.file)
        assertEquals(File(expected.parentFile, expected.name + ".part"), target.partFile)
    }

    @Test
    fun safUriWinsOverFileNameAndNeverTouchesTheFilesystemPath() {
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
            "document/primary%3AMusic%2F%E6%AD%8C%E5%90%8D.flac"
        val rec = record(fileName = "歌手/歌名.flac", safUri = saf)

        val target = DownloadFileLocator.locate(rec) {
            error("a SAF record must not resolve a File root")
        }

        assertEquals(DownloadFileTarget.SafDocument(saf), target)
    }

    @Test
    fun blankSafUriFallsBackToThePlainFile() {
        val rec = record(fileName = "a.mp3", baseDir = appScopedRoot.path, safUri = "   ")

        val target = DownloadFileLocator.locate(rec) { File(it.baseDir!!) }

        assertTrue(target is DownloadFileTarget.PlainFile)
    }

    @Test
    fun recordWithNeitherPathNorUriHasNothingToDelete() {
        val target = DownloadFileLocator.locate(record()) { appScopedRoot }

        assertEquals(DownloadFileTarget.None, target)
    }

    @Test
    fun blankFileNameIsNothingToDeleteEvenWithARoot() {
        val target = DownloadFileLocator.locate(record(fileName = "  ")) { appScopedRoot }

        assertEquals(DownloadFileTarget.None, target)
    }

    @Test
    fun rootResolutionReceivesTheRecordSoPerRecordBaseDirIsHonoured() {
        val rec = record(fileName = "a.mp3", baseDir = "/tmp/other")
        var seen: DownloadRecord? = null

        DownloadFileLocator.locate(rec) {
            seen = it
            File(it.baseDir!!)
        }

        assertEquals(rec, seen)
        assertEquals("baseDir must travel with the record", "/tmp/other", seen?.baseDir)
    }

    @Test
    fun aUserPickedSafFolderRecordIsAlsoSafEvenWithoutAFileName() {
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val target = DownloadFileLocator.locate(record(safUri = saf)) { appScopedRoot }

        assertEquals(DownloadFileTarget.SafDocument(saf), target)
    }
}
