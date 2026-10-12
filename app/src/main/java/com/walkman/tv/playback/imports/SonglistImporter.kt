package com.walkman.tv.playback.imports

import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.OnlinePlaylist
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.online.OnlinePlaylistErrors
import com.walkman.tv.data.online.OnlinePlaylistFetcher
import com.walkman.tv.data.online.SonglistLinkParser
import com.walkman.tv.data.songlist.ImportedSonglist
import com.walkman.tv.data.songlist.ImportedSonglistStore
import com.walkman.tv.data.songlist.UpdateStatus
import com.walkman.tv.data.store.LibraryStore

/** URL/ID parser result. Source + id uniquely identifies a platform playlist. */
data class SonglistRef(val source: SourceID, val id: String)

sealed class ImportError(message: String) : Throwable(message) {
    object UnrecognizedURL : ImportError("无法识别歌单链接，请粘贴酷我 / 酷狗 / QQ / 网易云的歌单分享链接")
    class FetchFailed(detail: String) : ImportError("获取歌单失败：$detail")
    object EmptyPlaylist : ImportError("歌单为空、私密或暂时无法读取（私密歌单需创建者设为公开后才能导入）")
}

data class ImportResult(val playlistId: String, val count: Int)

/**
 * URL → SonglistRef parser + import orchestrator. Spec docs/songlist-import-spec-android-tv.md.
 *
 * Parsing is delegated to [SonglistLinkParser] (domain-anchored rules, plus the share short links
 * `163cn.tv` / `c6.y.qq.com` / `t1.kugou.com`), so the dialog and the background updater can never
 * disagree about what a link means. UI layer calls [parse] reactively on every keystroke and
 * [pureIdOrNull] as a fallback when [parse] fails (lets the user pair a bare ID with a manual
 * source picker).
 */
object SonglistImporter {

    fun parse(raw: String): SonglistRef? =
        SonglistLinkParser.parse(raw)?.let { SonglistRef(it.source, it.id) }

    fun pureIdOrNull(raw: String): String? {
        val s = raw.trim()
        return if (s.isNotEmpty() && s.all { it.isDigit() }) s else null
    }

    /**
     * Orchestrator: fetch the **complete** playlist → create a user playlist → dump the tracks.
     *
     * The fetch goes through [OnlinePlaylistFetcher] (and therefore `SonglistService.fetchAll`)
     * instead of a single `fetchDetail`: NetEase's detail endpoint returns only 10 tracks to an
     * anonymous caller and QQ's `getcdinfo` truncates very long playlists, so a browse-shaped
     * request would import an incomplete list. The fetcher also resolves share short links, and the
     * *resolved* platform id is what gets stored — later updates then target the real playlist.
     *
     * The list is stamped with its platform origin (`source` + `sourceListId`, LX's official
     * `UserListInfo` fields) and registered in [origins] so it stays refreshable — the manual
     * "更新歌单" action and the background auto-update both key off that record. [UpdateStatus.OK]
     * plus the import time means a fresh import is not immediately re-fetched by the scheduler.
     */
    suspend fun importPlaylist(
        ref: SonglistRef,
        customName: String?,
        fetcher: OnlinePlaylistFetcher,
        library: LibraryStore,
        origins: ImportedSonglistStore,
    ): ImportResult = createFromFetched(fetchPlaylist(ref, fetcher), customName, library, origins)

    /**
     * Read the playlist without creating anything — the dialog's 预览 step (§4.1). Passing the
     * result to [createFromFetched] means previewing costs exactly the one fetch the import would
     * have done anyway.
     */
    suspend fun fetchPlaylist(ref: SonglistRef, fetcher: OnlinePlaylistFetcher): OnlinePlaylist = try {
        fetcher.fetch(com.walkman.tv.data.model.SonglistRef(ref.source, ref.id, ref.id)) { _, _ -> }
    } catch (e: OnlinePlaylistException) {
        throw when (e.kind) {
            OnlinePlaylistException.Kind.EMPTY -> ImportError.EmptyPlaylist
            OnlinePlaylistException.Kind.UNSUPPORTED -> ImportError.UnrecognizedURL
            else -> ImportError.FetchFailed(OnlinePlaylistErrors.message(e))
        }
    } catch (e: Exception) {
        throw ImportError.FetchFailed(e.localizedMessage ?: e.toString())
    }

    /** Create the local playlist + its origin record from an already fetched [online] playlist. */
    suspend fun createFromFetched(
        online: OnlinePlaylist,
        customName: String?,
        library: LibraryStore,
        origins: ImportedSonglistStore,
    ): ImportResult {
        if (online.tracks.isEmpty()) throw ImportError.EmptyPlaylist

        val resolvedId = online.ref.id
        val trimmed = customName?.trim().orEmpty()
        val name = when {
            trimmed.isNotEmpty() -> trimmed
            online.info.name.isNotEmpty() -> online.info.name
            else -> "导入的歌单"
        }
        val playlist = library.createList(name, source = online.ref.source.key, sourceListId = resolvedId)
        // Batch write: one StateFlow update + one library.json save for the whole songlist.
        library.addAllToList(playlist.id, online.tracks)
        val now = System.currentTimeMillis()
        origins.upsert(
            ImportedSonglist(
                listId = playlist.id,
                source = online.ref.source,
                sourceListId = resolvedId,
                rawUrl = online.ref.rawUrl.ifBlank { resolvedId },
                autoUpdate = true,
                lastFetchedAt = now,
                lastUpdatedAt = now,
                lastAttemptAt = now,
                lastStatus = UpdateStatus.OK,
                lastTrackCount = online.tracks.size,
                lastDeclaredTotal = online.declaredTotal,
                // Snapshot the online ids only when the fetch was complete: a partial snapshot
                // would make the next update treat the un-fetched songs as hand-added.
                onlineTrackIds = if (online.complete) online.tracks.mapTo(HashSet()) { it.id } else emptySet(),
            ),
        )
        return ImportResult(playlistId = playlist.id, count = online.tracks.size)
    }
}
