package com.walkman.tv.data.online

import com.walkman.tv.data.model.SourceID

/**
 * Repairs whatever is stored as a playlist id into the form the platform API actually wants.
 *
 * LX Music normalises on *every* fetch: `songList.getListId()` runs inside `getListDetail()` and
 * `getListDetailAll()`, so a list whose `sourceListId` is still the pasted share link (LX stores
 * exactly that - `views/songList/Detail/index.vue` keeps the raw query id, `addSongListDetail`
 * saves it as `sourceListId`) or carries one of LX's own id decorations keeps working forever.
 *
 * Walkman TV stores a *normalised* id at import time but used to hand the stored value straight to
 * the platform API on refresh, so a record that came from LX Sync - or from an older build - could
 * be a link, an LX-prefixed id or an `id###cookie` pair. NetEase answers such an id with HTTP 200
 * `{"code":400,"msg":"请求参数错误"}` and no `playlist`, which the old code reported as
 * 「平台返回的数据无法识别」.
 *
 * Pure and total: returns the id to call the platform with, or null when nothing usable can be
 * recovered. A credential is never part of the result - an LX `id###MUSIC_U=...` pair keeps only
 * the id half, so nothing here can leak a token into a request or the log.
 */
object SonglistIdNormalizer {

    /**
     * LX decorations seen on `sourceListId`: the leaderboard marker (`board__`), the plaza-item
     * prefix (`id_1067062`) and the `<source>__<id>` / `<source>_<id>` forms (`kw__123`,
     * `wy_123`). Two letters + underscores only, so `collection_3_...` is never touched.
     */
    private val LX_PREFIX = Regex("""^(?:board__|id_|[a-z]{2}_+)""", RegexOption.IGNORE_CASE)

    /** 酷狗概念版 collection id, e.g. `collection_3_2122406585_2_0`. */
    private val CONCEPT_ID = Regex("""^collection_[0-9_]+$""", RegexOption.IGNORE_CASE)

    /**
     * A numeric id that went through a JavaScript `Number` at some point and came back as
     * `9744256455.0`. LX repairs exactly this on the DB read path (`dbHelper.ts`:
     * `if (info.sourceListId?.endsWith?.('.0')) info.sourceListId = info.sourceListId.replace(/\.0$/, '')`),
     * so a record that travelled through another LX client must be repaired here too - a `.0` suffix
     * fails the "all digits" check and would otherwise be reported as a bad id.
     */
    private val JS_DECIMAL = Regex("""^(\d+)\.0$""")

    fun normalize(source: SourceID, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // LX's share flow can carry "id###MUSIC_U=..." - keep the id, drop the credential.
        val bare = stripJsDecimal(trimmed.substringBefore("###").trim())
        if (bare.isEmpty()) return null
        // A link (including the three share short links) is parsed by the same rules the importer
        // uses, so import and refresh can never disagree about what a link means.
        SonglistLinkParser.parse(bare)?.let { return it.id }
        val stripped = bare.replace(LX_PREFIX, "")
        if (stripped != bare) {
            SonglistLinkParser.parse(stripped)?.let { return it.id }
            accept(source, stripped)?.let { return it }
        }
        return accept(source, bare)
    }

    /** Platform-specific id shapes. Everything else means "we cannot refresh this". */
    private fun accept(source: SourceID, id: String): String? = when (source) {
        SourceID.WY, SourceID.TX, SourceID.KW -> id.takeIf(::isNumericId)
        SourceID.KG -> id.takeIf { isNumericId(it) || CONCEPT_ID.matches(it) }
        // 咪咕 has no playlist provider; local lists have no platform id.
        SourceID.MG, SourceID.LOCAL -> null
    }

    private fun isNumericId(id: String): Boolean = id.isNotEmpty() && id.all { it.isDigit() }

    private fun stripJsDecimal(id: String): String = JS_DECIMAL.find(id)?.groupValues?.get(1) ?: id
}
