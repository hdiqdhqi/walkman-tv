package com.walkman.tv.playback.download

import com.walkman.tv.data.model.DownloadRecord
import java.io.File

/**
 * Where a downloaded record's bytes physically live.
 *
 * Resolving this is deliberately Android-free so the File-vs-SAF decision and the exact File paths
 * can be asserted in a plain JVM test — the store only maps the target onto `File.delete()` /
 * `DocumentFile.delete()`.
 */
sealed interface DownloadFileTarget {

    /** A plain file on disk (app-scoped dir, or a raw-storage dir the user picked). */
    data class PlainFile(val file: File, val partFile: File) : DownloadFileTarget

    /** A SAF document (`content://…`) exported into a user-picked tree folder. */
    data class SafDocument(val uri: String) : DownloadFileTarget

    /** Record carries neither a file name nor a Uri — nothing to delete. */
    data object None : DownloadFileTarget
}

/**
 * Maps a [DownloadRecord] to its [DownloadFileTarget].
 *
 * A record with a `safUri` lives behind the ContentResolver even if it also carries a `fileName`,
 * so SAF always wins — callers must never assume `File.delete()` works for it.
 */
object DownloadFileLocator {

    fun locate(
        record: DownloadRecord,
        resolveRoot: (DownloadRecord) -> File,
    ): DownloadFileTarget {
        val saf = record.safUri
        if (!saf.isNullOrBlank()) return DownloadFileTarget.SafDocument(saf)
        if (record.fileName.isBlank()) return DownloadFileTarget.None
        val file = File(resolveRoot(record), record.fileName)
        return DownloadFileTarget.PlainFile(
            file = file,
            partFile = File(file.parentFile, file.name + ".part"),
        )
    }
}

/** Outcome of deleting a record's backing file. */
sealed interface DownloadFileDeleteResult {

    /** File removed, or already absent — the space is (now) free. */
    data object Deleted : DownloadFileDeleteResult

    /** Record carries no file info, so there is nothing to delete. */
    data object NothingToDelete : DownloadFileDeleteResult

    /** A real failure. [message] is safe to show the user. */
    data class Failed(val message: String) : DownloadFileDeleteResult
}
