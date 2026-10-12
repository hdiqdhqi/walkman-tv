package com.walkman.tv.data.online

import com.walkman.tv.data.model.OnlinePlaylist
import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SonglistRef
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.source.catalog.CatalogException
import com.walkman.tv.source.catalog.SonglistService
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetch seam for the online-playlist feature. Implemented by [OnlinePlaylistRepository] in
 * production and by a fake in tests, so the import/update logic never needs the network.
 *
 * Implementations must throw [OnlinePlaylistException] for every failure mode.
 */
fun interface OnlinePlaylistFetcher {
    suspend fun fetch(
        ref: SonglistRef,
        onProgress: (fetched: Int, total: Int?) -> Unit,
    ): OnlinePlaylist
}

/** Whether this build can talk to a given platform's playlist API. */
interface OnlinePlaylistSupport {
    fun supports(source: SourceID): Boolean
}

/**
 * The single entry point for "read a playlist from its platform", shared by the import preview,
 * the import itself, the manual refresh and the background sweep - so all four can never disagree
 * about what a stored id means or how a failure is reported.
 *
 * Responsibilities, and nothing else:
 *  - repair the stored id ([SonglistIdNormalizer]) and resolve share short links
 *    (`163cn.tv` / `c6.y.qq.com` / `t1.kugou.com`) once, before any API call;
 *  - pick the platform service and run the all-pages [SonglistService.fetchAll];
 *  - classify every failure into an [OnlinePlaylistException.Kind] so the caller can choose
 *    between retry / ask the user / refuse to write;
 *  - guarantee a non-empty result (an empty answer is a failure, never a "the playlist is now
 *    empty" instruction to wipe the local copy);
 *  - record one SONGLIST_FETCH_* event per attempt with the platform, the id and the stage.
 */
class OnlinePlaylistRepository(
    private val serviceFor: (SourceID) -> SonglistService?,
    private val resolveFinalUrl: suspend (String) -> String?,
) : OnlinePlaylistFetcher, OnlinePlaylistSupport {

    override fun supports(source: SourceID): Boolean = serviceFor(source) != null

    override suspend fun fetch(
        ref: SonglistRef,
        onProgress: (fetched: Int, total: Int?) -> Unit,
    ): OnlinePlaylist {
        val service = serviceFor(ref.source)
            ?: throw fail(
                OnlinePlaylistException(
                    OnlinePlaylistException.Kind.UNSUPPORTED,
                    "no playlist provider for ${ref.source.key}",
                ),
                ref,
            )
        OnlinePlaylistLog.emit(
            OnlinePlaylistLog.EVENT_FETCH_START,
            OnlinePlaylistLog.ref(ref.source, ref.id) + listOf("stage" to "fetch"),
        )

        // Import parses the pasted link first; a *stored* record may still hold a link, an
        // LX-decorated id or an `id###credential` pair (see [SonglistIdNormalizer]). Repairing here
        // is what makes refresh behave like LX's getListId()-on-every-fetch instead of failing with
        // a platform business error that looks like a parse problem.
        val normalized = try {
            normalizeRef(ref)
        } catch (e: OnlinePlaylistException) {
            throw fail(e, ref)
        }

        // Stub only needs id + source: fetchAll reads those and returns the real metadata.
        val stub = SonglistInfo(
            id = normalized.id, source = ref.source,
            name = "", author = "", picURL = null, trackCount = null, playCount = null,
        )
        val result = try {
            service.fetchAll(stub, onProgress)
        } catch (e: OnlinePlaylistException) {
            throw fail(e, normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw fail(OnlinePlaylistException(classify(e), e.localizedMessage ?: e.toString(), e), normalized)
        }
        if (result.tracks.isEmpty()) {
            throw fail(OnlinePlaylistException(OnlinePlaylistException.Kind.EMPTY, "playlist has no songs"), normalized)
        }
        val playlist = OnlinePlaylist(
            ref = normalized,
            info = result.info.copy(id = normalized.id, source = ref.source),
            tracks = result.tracks,
            declaredTotal = result.declaredTotal,
            complete = result.complete,
        )
        OnlinePlaylistLog.emit(
            OnlinePlaylistLog.EVENT_FETCH_OK,
            OnlinePlaylistLog.ref(normalized.source, normalized.id) + listOf(
                "fetched" to playlist.tracks.size,
                "declared" to playlist.declaredTotal,
                "complete" to playlist.complete,
            ),
        )
        return playlist
    }

    /** Id repair + one-hop short-link resolution. Throws [OnlinePlaylistException] when stuck. */
    private suspend fun normalizeRef(ref: SonglistRef): SonglistRef {
        val id = SonglistIdNormalizer.normalize(ref.source, ref.id)
            ?: throw OnlinePlaylistException(
                OnlinePlaylistException.Kind.BAD_ID,
                "stored playlist id is not usable (${OnlinePlaylistLog.safe(ref.id)})",
                null,
                ref.source,
            )
        return resolveShortLink(if (id == ref.id) ref else ref.copy(id = id))
    }

    /**
     * Follow a platform short link to its real target and re-parse it. One hop only: a link that
     * redirects to another short link is reported as a parse failure instead of looping.
     */
    suspend fun resolveShortLink(ref: SonglistRef): SonglistRef {
        if (!SonglistLinkParser.isShortLink(ref)) return ref
        val finalUrl = resolveFinalUrl(ref.id)
            ?: throw OnlinePlaylistException(
                OnlinePlaylistException.Kind.NETWORK,
                "short link could not be resolved",
                null,
                ref.source,
            )
        val parsed = SonglistLinkParser.parse(finalUrl)
            ?: throw OnlinePlaylistException(
                OnlinePlaylistException.Kind.CONTENT,
                "short link target is not a recognized playlist URL",
                null,
                ref.source,
            )
        return parsed.copy(rawUrl = ref.rawUrl.ifEmpty { ref.id })
    }

    /**
     * Map a thrown error onto the category the UI and the scheduler branch on.
     *
     * The old code funnelled everything it did not recognise into PARSE, which is why a NetEase
     * business error (`{"code":400}`/`{"code":404}`, HTTP 200), an HTML risk-control page, a
     * truncated body and a real interface change were all reported the same way. Each shape now has
     * its own category so the message, the retry decision and the log entry can differ.
     */
    private fun classify(e: Throwable): OnlinePlaylistException.Kind = when (e) {
        // The body is not JSON at all: an HTML/verification/interstitial page, a truncated read or
        // an empty body. Risk control (风控) looks exactly like this, so retrying later is right.
        is org.json.JSONException -> OnlinePlaylistException.Kind.CONTENT

        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.io.InterruptedIOException,
        -> OnlinePlaylistException.Kind.NETWORK

        is CatalogException -> when (httpStatus(e)) {
            401, 403 -> OnlinePlaylistException.Kind.AUTH
            429 -> OnlinePlaylistException.Kind.RATE_LIMIT
            // A 2xx with no body at all ("空响应") - the platform answered with nothing to read.
            null -> OnlinePlaylistException.Kind.CONTENT
            else -> OnlinePlaylistException.Kind.HTTP
        }

        is java.io.IOException -> OnlinePlaylistException.Kind.NETWORK

        // A request that was rejected before it left (an id OkHttp refuses to build a URL from):
        // our shape problem, not a transport one.
        is IllegalArgumentException -> OnlinePlaylistException.Kind.PARSE

        else -> OnlinePlaylistException.Kind.PARSE
    }

    private fun httpStatus(e: CatalogException): Int? =
        Regex("""HTTP (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** Attach the platform, emit the stage-specific event and hand the exception back. */
    private fun fail(e: OnlinePlaylistException, ref: SonglistRef): OnlinePlaylistException {
        val withSource = if (e.source != null) e
        else OnlinePlaylistException(e.kind, e.message ?: "", e, ref.source)
        OnlinePlaylistLog.emit(
            OnlinePlaylistLog.failureEvent(withSource.kind),
            OnlinePlaylistLog.ref(ref.source, ref.id) + listOf(
                "kind" to withSource.kind.name,
                "stage" to "fetch",
                "detail" to OnlinePlaylistLog.safe(withSource.message),
            ),
        )
        return withSource
    }
}

/** User-facing Chinese text for a fetch failure. Kept next to [OnlinePlaylistException]. */
object OnlinePlaylistErrors {
    fun message(kind: OnlinePlaylistException.Kind, source: SourceID? = null): String {
        val text = when (kind) {
            OnlinePlaylistException.Kind.NETWORK -> "网络不可用或请求超时，请检查网络后重试"
            OnlinePlaylistException.Kind.HTTP -> "平台服务返回异常，请稍后重试"
            OnlinePlaylistException.Kind.AUTH -> "该歌单需要登录或访问受限，无法读取"
            OnlinePlaylistException.Kind.RATE_LIMIT -> "请求过于频繁已被平台限流，请稍后重试"
            OnlinePlaylistException.Kind.EMPTY -> "歌单为空、私密或暂时无法读取"
            OnlinePlaylistException.Kind.PARSE -> "平台返回的数据结构与预期不符（接口可能已变化），本次未更新"
            OnlinePlaylistException.Kind.CONTENT -> "平台未返回有效数据（可能触发了风控或验证页），请稍后重试"
            OnlinePlaylistException.Kind.INCOMPLETE -> "只获取到部分歌曲，已放弃本次写入"
            OnlinePlaylistException.Kind.NOT_FOUND -> "歌单不存在、已删除或已设为私密"
            OnlinePlaylistException.Kind.UNSUPPORTED -> "暂不支持该平台的歌单"
            OnlinePlaylistException.Kind.BAD_ID -> "歌单的来源标识无法识别，请在电视上重新导入一次"
        }
        return if (source == null) text else "${source.displayName}：$text"
    }

    /** Platform-labelled text. The caller's own prefix already names the action (导入 / 更新). */
    fun message(e: OnlinePlaylistException): String = message(e.kind, e.source)
}
