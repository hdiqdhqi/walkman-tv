package com.walkman.tv.data.sync

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One message2call envelope.
 *
 * Request (client -> server): `{"name":"a__<random>","path":["a"],"data":[...args]}`
 * Response (server -> client): `{"name":"a__<random>","error":null|"msg","data":result}`
 * Call (server -> client): `{"name":"x__<random>","path":["x"],"data":[...args]}`
 *
 * So: a non-empty [path] means "someone is calling you"; otherwise [name] correlates with a
 * call we made. Raw JSON must never be sent without going through [LxSyncCodec].
 */
@Serializable
data class LxSyncMessage(
    val name: String? = null,
    val path: List<String>? = null,
    val error: String? = null,
    val data: JsonElement? = null,
) {
    val isIncomingCall: Boolean get() = !path.isNullOrEmpty()
}

/**
 * Wire framing of a message2call payload: anything longer than 1024 characters is gzipped,
 * base64-encoded and prefixed with `cg_`; shorter payloads go out verbatim.
 */
object LxSyncCodec {
    const val GZIP_PREFIX = "cg_"
    const val COMPRESS_THRESHOLD = 1024

    /** Decompression guard - a hostile server must not be able to OOM us with a zip bomb. */
    private const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024

    /**
     * NOTE: the official implementations compare `msg.length`, i.e. UTF-16 code units rather
     * than bytes. Keeping that behaviour makes our framing decision identical to the server's.
     */
    fun encode(json: String): String =
        if (json.length > COMPRESS_THRESHOLD) GZIP_PREFIX + gzipToBase64(json) else json

    fun decode(text: String): String =
        if (text.startsWith(GZIP_PREFIX)) gunzipFromBase64(text.removePrefix(GZIP_PREFIX)) else text

    fun gzipToBase64(text: String): String {
        val buf = ByteArrayOutputStream()
        GZIPOutputStream(buf).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
    }

    fun gunzipFromBase64(base64: String): String {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(Base64.decode(base64, Base64.DEFAULT))).use { gz ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = gz.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_DECOMPRESSED_BYTES) {
                    throw LxSyncCryptoException("sync message too large")
                }
                out.write(chunk, 0, n)
            }
        }
        return out.toString("UTF-8")
    }
}
