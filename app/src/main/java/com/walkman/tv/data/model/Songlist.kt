package com.walkman.tv.data.model

/** A playlist/歌单 summary (iOS `SonglistInfo`). [id] is the platform-side playlist id. */
data class SonglistInfo(
    val id: String,
    val source: SourceID,
    val name: String,
    val author: String = "",
    val picURL: String? = null,
    val trackCount: Int? = null,
    val playCount: String? = null,
)

data class SonglistDetail(
    val info: SonglistInfo,
    val tracks: List<Track>,
)

/**
 * Where an online playlist lives: platform + platform-side id + the text the user pasted.
 *
 * [id] is the *normalized* platform id used by every API call. For the platforms that only hand
 * out a share link (酷狗概念版 `t1.kugou.com/xxx`, QQ `c6.y.qq.com/...?__=xxx`), [id] keeps the
 * short URL and the online layer resolves it to a real id on first fetch.
 */
data class SonglistRef(
    val source: SourceID,
    val id: String,
    /** The raw link/id the user typed. Kept for the origin record and diagnostics; "" when derived. */
    val rawUrl: String = "",
)

/**
 * Result of a *complete* (all-pages) fetch through `SonglistService.fetchAll`.
 *
 * [declaredTotal] is what the platform reports the list holds; [complete] is false as soon as one
 * page/chunk request failed - that is the signal the update path uses to refuse a destructive
 * mirror, because a half-fetched list must never be written over a full local one.
 */
data class SonglistFetchResult(
    val info: SonglistInfo,
    val tracks: List<Track>,
    val declaredTotal: Int? = null,
    val complete: Boolean = true,
)

/** A fully fetched online playlist plus the ref it was fetched from. */
data class OnlinePlaylist(
    val ref: SonglistRef,
    val info: SonglistInfo,
    val tracks: List<Track>,
    val declaredTotal: Int? = null,
    val complete: Boolean = true,
)

/**
 * Classified failure of an online-playlist fetch.
 *
 * [kind] lets the caller choose between "retry later" (NETWORK / CONTENT / HTTP / RATE_LIMIT),
 * "needs the user" (AUTH / NOT_FOUND / UNSUPPORTED) and "refuse to write anything" (INCOMPLETE /
 * PARSE / EMPTY).
 * [message] is the raw diagnostic detail (stage + platform code); the UI renders its own Chinese
 * text from [kind], prefixed with [source]'s display name.
 */
class OnlinePlaylistException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    /** Platform the failure came from, when it is known - lets the UI name it. */
    val source: SourceID? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** DNS / TCP / TLS / timeout - worth retrying later. */
        NETWORK,
        /** Non-2xx answer from the platform. */
        HTTP,
        /** The platform refused the request as unauthenticated / restricted. */
        AUTH,
        /** The platform throttled us. */
        RATE_LIMIT,
        /** The playlist is empty, or the platform returned nothing usable. */
        EMPTY,
        /**
         * The answer parsed as JSON but its *shape* changed: a required object/array is missing or
         * renamed, or a platform business code rejected the request. Retrying will not help until
         * the parser is updated - surfacing this instead of "empty" is what tells us an interface
         * moved.
         */
        PARSE,
        /**
         * The platform answered with something that is not data at all: an HTML / verification
         * page, a truncated body, an empty body or a JSON syntax error. In practice this is risk
         * control (风控) or a captive portal, so it is treated as worth retrying later.
         */
        CONTENT,
        /** Only part of the list came back - never write this over the local copy. */
        INCOMPLETE,
        /** Deleted / private / never existed. */
        NOT_FOUND,
        /** No provider for this platform in this build (咪咕). */
        UNSUPPORTED,
        /**
         * The stored playlist id cannot be turned into anything the platform accepts - typically a
         * record that arrived through LX Sync or an older build. The user has to re-import the
         * playlist once; nothing local is touched.
         */
        BAD_ID,
    }
}

/** A platform sort option (id = API value, name = label). */
data class SonglistOrder(val id: String, val name: String)

/** A single filter tag (歌单分类). id == "" ⇒ 全部. */
data class SonglistTag(val id: String, val name: String) {
    companion object {
        val ALL = SonglistTag("", "全部")
    }
}

/** A named group of tags (热门/语种/风格/场景…). */
data class SonglistTagGroup(val name: String, val tags: List<SonglistTag>)
