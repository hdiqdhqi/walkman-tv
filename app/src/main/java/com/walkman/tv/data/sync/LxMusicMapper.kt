package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * LX Music `MusicInfo` as it travels inside `ListData`.
 *
 * Field order matches the official interface (`id, name, singer, source, interval, meta`) so the
 * JSON we emit looks like the official client's. `meta` is kept as an open [JsonObject] on
 * purpose: the official type is an open bag whose shape depends on the source (kg `hash`, tx
 * `strMediaMid`/`albumMid`, mg `copyrightId`, local `filePath`/`ext`, …).
 */
@Serializable
data class MusicInfo(
    val id: String,
    val name: String,
    val singer: String = "",
    val source: String,
    val interval: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
)

/**
 * Pure `Track <-> MusicInfo` conversion. No I/O, no store access - [com.walkman.tv.data.store.LibraryStore]
 * must never contain this logic, and no other sync class may hand-roll the conversion.
 *
 * Everything the protocol can carry survives a round trip:
 *  - typed fields (id/name/singer/source/albumName/albumId/picUrl/songmid/duration/qualities)
 *  - plain-string `meta` extras -> [Track.extras] (the JS bridge forwards them as musicInfo fields)
 *  - nested `meta` values (e.g. `qualitys` / `_qualitys`) -> [Track.syncMeta], verbatim
 *
 * Local-file tracks (`source: "local"`) are represented with the official `MusicInfoLocal` shape
 * (`meta.filePath` / `meta.ext`). They must round-trip rather than be dropped: the server's 3-way
 * merge treats "present in the snapshot, missing from a client" as a deletion, so omitting an
 * entry would delete it on every device.
 */
object LxMusicMapper {
    private const val KEY_SONG_ID = "songId"
    private const val KEY_ALBUM_NAME = "albumName"
    private const val KEY_ALBUM_ID = "albumId"
    private const val KEY_PIC_URL = "picUrl"
    private const val KEY_QUALITYS = "qualitys"
    private const val KEY_QUALITYS_ALT = "_qualitys"
    private const val KEY_FILE_PATH = "filePath"
    private const val KEY_EXT = "ext"

    /** `meta` keys the mapper owns; they are never copied into [Track.extras]. */
    private val TYPED_META_KEYS = setOf(
        KEY_SONG_ID, KEY_ALBUM_NAME, KEY_ALBUM_ID, KEY_PIC_URL, KEY_QUALITYS, KEY_QUALITYS_ALT,
        KEY_FILE_PATH, KEY_EXT,
    )

    /** `235` -> `"03:55"`; null/0 -> `""` (the official type allows null). */
    fun durationToInterval(seconds: Int?): String {
        val s = seconds ?: return ""
        if (s <= 0) return ""
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** `"03:55"` -> `235`; anything unparseable -> null. */
    fun intervalToDuration(interval: String?): Int? {
        val parts = interval?.trim()?.split(':') ?: return null
        if (parts.size != 2) return null
        val minutes = parts[0].toIntOrNull() ?: return null
        val seconds = parts[1].toIntOrNull() ?: return null
        if (minutes < 0 || seconds !in 0..59) return null
        return minutes * 60 + seconds
    }

    /** Returns null only for a track that cannot be addressed at all (blank source/songmid). */
    fun toMusicInfo(track: Track): MusicInfo? {
        val sourceKey = track.source.key
        if (sourceKey.isBlank() || track.songmid.isBlank()) return null

        val isLocal = track.source == SourceID.LOCAL
        // LinkedHashMap: official key order, no duplicates, and plain Map semantics.
        val meta = LinkedHashMap<String, JsonElement>()
        meta[KEY_SONG_ID] = JsonPrimitive(track.songmid)
        track.albumName?.takeIf { it.isNotEmpty() }?.let { meta[KEY_ALBUM_NAME] = JsonPrimitive(it) }
        track.albumId?.takeIf { it.isNotEmpty() }?.let { meta[KEY_ALBUM_ID] = JsonPrimitive(it) }
        track.picURL?.takeIf { it.isNotEmpty() }?.let { meta[KEY_PIC_URL] = JsonPrimitive(it) }
        if (isLocal) {
            meta[KEY_FILE_PATH] = JsonPrimitive(track.songmid)
            track.songmid.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                ?.let { meta[KEY_EXT] = JsonPrimitive(it.lowercase()) }
        } else {
            // Prefer exactly what the server sent us; regenerate a minimal one for tracks that
            // were built on this device.
            meta[KEY_QUALITYS] = track.syncMeta[KEY_QUALITYS] ?: qualitysJson(track.qualities)
            meta[KEY_QUALITYS_ALT] = track.syncMeta[KEY_QUALITYS_ALT] ?: JsonObject(emptyMap())
        }
        for ((k, v) in track.extras) {
            if (k.isEmpty() || k in TYPED_META_KEYS || meta.containsKey(k)) continue
            meta[k] = JsonPrimitive(v)
        }
        for ((k, v) in track.syncMeta) {
            if (k.isEmpty() || k in TYPED_META_KEYS || meta.containsKey(k)) continue
            meta[k] = v
        }
        return MusicInfo(
            id = track.id,
            name = track.name,
            singer = track.singer,
            source = sourceKey,
            interval = durationToInterval(track.duration),
            meta = JsonObject(meta),
        )
    }

    /** Returns null for entries this client cannot represent (unknown source, no usable id). */
    fun toTrack(info: MusicInfo): Track? {
        val source = SourceID.fromKey(info.source) ?: return null
        if (info.name.isEmpty()) return null

        val meta = info.meta
        val songmid = meta[KEY_SONG_ID].asTextOrNull()
            ?: meta[KEY_FILE_PATH].asTextOrNull()
            ?: info.id.substringAfter('_', "").takeIf { it.isNotEmpty() }
            ?: return null

        val extras = LinkedHashMap<String, String>()
        val syncMeta = LinkedHashMap<String, JsonElement>()
        val typedQualities = qualitiesOf(meta)
        val regeneratedQualitys = qualitysJson(typedQualities)
        for ((k, v) in meta) {
            when {
                // `qualitys` / `_qualitys` carry more than the quality key (size, hash, ...).
                // They are re-emitted verbatim whenever regenerating them from the typed list
                // would drop data - otherwise a sync round trip would rewrite the payload and
                // the protocol MD5 would never stabilise.
                k == KEY_QUALITYS -> if (v != regeneratedQualitys) syncMeta[k] = v
                k == KEY_QUALITYS_ALT -> if (v !is JsonObject || v.isNotEmpty()) syncMeta[k] = v
                k in TYPED_META_KEYS -> Unit
                v is JsonPrimitive && v !is JsonNull ->
                    v.content.takeIf { it.isNotEmpty() }?.let { extras[k] = it }
                else -> syncMeta[k] = v
            }
        }
        return Track(
            id = info.id.ifEmpty { Track.makeID(source, songmid) },
            name = info.name,
            singer = info.singer,
            albumName = meta[KEY_ALBUM_NAME].asTextOrNull(),
            albumId = meta[KEY_ALBUM_ID].asTextOrNull(),
            source = source,
            songmid = songmid,
            duration = intervalToDuration(info.interval),
            picURL = meta[KEY_PIC_URL].asTextOrNull(),
            qualities = typedQualities,
            extras = extras,
            syncMeta = syncMeta,
        )
    }

    /** `meta.qualitys` / `meta._qualitys` -> the typed (ordered) quality list. */
    private fun qualitiesOf(meta: JsonObject): List<Quality> {
        val out = LinkedHashSet<Quality>()
        (meta[KEY_QUALITYS] as? JsonArray)?.forEach { element ->
            val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.content
            Quality.fromKey(type)?.let { out += it }
        }
        (meta[KEY_QUALITYS_ALT] as? JsonObject)?.keys?.forEach { key ->
            Quality.fromKey(key)?.let { out += it }
        }
        return out.toList()
    }

    /** `[{type, size:null}, ...]` - the official `MusicQualityType` list, sizes unknown to us. */
    private fun qualitysJson(qualities: List<Quality>) = buildJsonArray {
        for (q in qualities) add(buildJsonObject {
            put("type", JsonPrimitive(q.key))
            put("size", JsonNull)
        })
    }

    private fun JsonElement?.asTextOrNull(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content.takeIf { it.isNotEmpty() }
        is JsonArray, is JsonObject -> null
        else -> null
    }
}