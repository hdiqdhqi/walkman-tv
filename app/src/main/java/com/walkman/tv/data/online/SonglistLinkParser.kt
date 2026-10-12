package com.walkman.tv.data.online

import com.walkman.tv.data.model.SonglistRef
import com.walkman.tv.data.model.SourceID

/**
 * Share-link parser for the online-playlist platforms.
 *
 * Rules are anchored on the platform's *own* domain/host so a link can never be matched against
 * the wrong platform (a bare `?id=123` is not enough to claim a playlist belongs to NetEase).
 * Every rule is tried in order; the first captured group is the normalized playlist id.
 *
 * Three platforms hand out short links that carry no id at all; for those the whole short URL is
 * kept as the id and [isShortLink] tells the fetch layer to follow the redirect first.
 */
object SonglistLinkParser {

    /** Short-link hosts whose URL must be followed to recover a real playlist id. */
    private val SHORT_LINK_HOSTS: Map<SourceID, Set<String>> = mapOf(
        SourceID.WY to setOf("163cn.tv"),
        SourceID.TX to setOf("c6.y.qq.com"),
        SourceID.KG to setOf("t1.kugou.com"),
    )

    private val rules: List<Pair<SourceID, List<Regex>>> = listOf(
        SourceID.WY to listOf(
            // https://music.163.com/playlist?id=123 , .../#/playlist?id=123 , ?id=123&userid=1
            Regex("""music\.163\.com[^\s]*?[?#&]id=(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""music\.163\.com/playlist/(\d+)""", RegexOption.IGNORE_CASE),
            // 手机分享短链：https://163cn.tv/xxxxxxx
            Regex("""(https?://163cn\.tv/[0-9A-Za-z_-]+)""", RegexOption.IGNORE_CASE),
        ),
        SourceID.TX to listOf(
            Regex("""y\.qq\.com/n/ryqq/playlist/(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""y\.qq\.com[^\s]*?/playsquare/(\d+)""", RegexOption.IGNORE_CASE),
            // 老分享链接：.../taoge.html?id=123
            Regex("""qq\.com[^\s]*?taoge\.html[^\s]*?[?#&]id=(\d+)""", RegexOption.IGNORE_CASE),
            // iPhone 微信分享的新格式：i2.y.qq.com/n3/other/pages/details/playlist.html?...&id=123
            Regex("""qq\.com[^\s]*?playlist\.html[^\s]*?[?#&]id=(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""[?&#]disstid=(\d+)""", RegexOption.IGNORE_CASE),
            // 短链：https://c6.y.qq.com/base/fcgi-bin/u?__=xxxx
            Regex("""(https?://c6\.y\.qq\.com/[^\s]+)""", RegexOption.IGNORE_CASE),
        ),
        SourceID.KG to listOf(
            Regex("""kugou\.com/songlist/(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""kugou\.com[^\s]*?[?&]listid=(\d+)""", RegexOption.IGNORE_CASE),
            // 概念版：global_specialid / global_collection_id=collection_..._2_0
            Regex("""[?&]global_(?:specialid|collection_id)=(collection_[0-9_]+)""", RegexOption.IGNORE_CASE),
            Regex("""kugou\.com[^\s]*?[?&]global_collection_id=([0-9A-Fa-f]+)""", RegexOption.IGNORE_CASE),
            // 概念版短链 t1.kugou.com/xxxx —— 存整条短链，fetch 时联网解析成 collection_ id
            Regex("""(https?://t1\.kugou\.com/[0-9A-Za-z]+)""", RegexOption.IGNORE_CASE),
        ),
        SourceID.KW to listOf(
            Regex("""kuwo\.cn[^\s]*?/playlist_detail/(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""kuwo\.cn[^\s]*?[?&]pid=(\d+)""", RegexOption.IGNORE_CASE),
        ),
    )

    /**
     * Parse a pasted link (or a bare id) into a ref, or null when nothing matches.
     * [SonglistRef.rawUrl] always keeps the trimmed input so the origin record can show what the
     * user actually pasted.
     */
    fun parse(raw: String): SonglistRef? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        for ((source, patterns) in rules) {
            for (pattern in patterns) {
                val match = pattern.find(s) ?: continue
                val id = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: continue
                return SonglistRef(source, id, s)
            }
        }
        return null
    }

    /** A bare numeric id together with a platform the user picked by hand. */
    fun bareId(raw: String, source: SourceID): SonglistRef? {
        val s = raw.trim()
        return if (s.isNotEmpty() && s.all { it.isDigit() }) SonglistRef(source, s, s) else null
    }

    /** True when [id] is a short link that has to be followed before the platform API can run. */
    fun isShortLink(ref: SonglistRef): Boolean {
        if (!ref.id.startsWith("http", ignoreCase = true)) return false
        val host = hostOf(ref.id) ?: return false
        return SHORT_LINK_HOSTS[ref.source]?.contains(host) == true
    }

    fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()
}
