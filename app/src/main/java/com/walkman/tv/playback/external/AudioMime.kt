package com.walkman.tv.playback.external

/**
 * Best-effort MIME for the external-player hand-off (spec 3 / 17).
 *
 * Walkman never learns a MIME from the source: [com.walkman.tv.source.ResolvedTrack] carries only
 * a URL + quality, and ExoPlayer normally infers the format itself from the bytes. So before we
 * hand a URL to another app we infer a type from the strongest evidence available, in order:
 *
 *  1. the probed file header ([com.walkman.tv.playback.AudioSpec] codec) — real codec info;
 *  2. the URL path extension — a hint, not proof;
 *  3. [DEFAULT] — the honest "we do not know" fallback.
 *
 * We never claim a Dolby/Atmos bitstream from metadata alone (spec 17): only a real E-AC3 / AC-4
 * codec or extension maps to `audio/eac3` / `audio/ac4`.
 */
object AudioMime {

    /** Universal fallback when we cannot name the format. */
    const val DEFAULT = "audio/*"

    /** True for the two Dolby bitstream containers we explicitly recognise. */
    val DOLBY_TYPES = setOf("audio/eac3", "audio/ac4")

    /** Codec first (real header), then URL extension, then [DEFAULT]. */
    fun resolve(url: String?, codec: String? = null): String =
        normalizeCodec(codec) ?: fromUrl(url)

    /** Map a probed codec name (e.g. `FLAC`, `MP3`) to a MIME type; null when unknown. */
    fun normalizeCodec(codec: String?): String? = when (codec?.trim()?.uppercase()) {
        "FLAC" -> "audio/flac"
        "MP3", "MPEG", "MPEG3" -> "audio/mpeg"
        "AAC" -> "audio/aac"
        "M4A", "MP4" -> "audio/mp4"
        "EAC3", "EC3", "E-AC3" -> "audio/eac3"
        "AC4", "AC-4" -> "audio/ac4"
        "OPUS" -> "audio/opus"
        "VORBIS", "OGG" -> "audio/ogg"
        "WAV", "PCM" -> "audio/x-wav"
        "APE" -> "audio/x-ape"
        "WMA" -> "audio/x-ms-wma"
        else -> null
    }

    /** Map a URL's path extension to a MIME type; [DEFAULT] when unknown. */
    fun fromUrl(url: String?): String = when (extension(url)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/x-wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "eac3", "ec3" -> "audio/eac3"
        "ac4", "ac-4" -> "audio/ac4"
        "ape" -> "audio/x-ape"
        "wma" -> "audio/x-ms-wma"
        "dsf" -> "audio/dsf"
        else -> DEFAULT
    }

    /** True when [mime] names a Dolby bitstream (E-AC3 / AC-4) — used to avoid false Atmos claims. */
    fun isDolbyBitstream(mime: String?): Boolean = mime?.lowercase() in DOLBY_TYPES

    /** Lower-cased path extension, ignoring query / fragment. Null when there is not one. */
    fun extension(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val path = raw.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
