package com.walkman.tv.data.sync

import android.os.Build
import android.util.Base64
import com.walkman.tv.crypto.AES
import com.walkman.tv.crypto.RSA
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest

/** Internal failure of a protocol crypto/encoding step. The message is diagnostics-only. */
internal class LxSyncCryptoException(message: String) : Exception(message)

/** A freshly generated RSA key pair, base64 (no PEM headers, no newlines) - official wire form. */
internal data class RsaKeyPair(val publicKeyBase64: String, val privateKeyBase64: String)

/**
 * Pure constants and side-effect-free helpers of the LX Music Sync protocol.
 *
 * Every literal here is copied from the official implementations shipped in
 * `.lxsync-research` (lx-music-sync-server `src/constants.ts`, lx-music-mobile
 * `plugins/sync/constants.ts`) - do not "improve" them.
 *
 * Crypto is deliberately delegated to the project's existing [AES] / [RSA] helpers rather
 * than re-implemented here.
 */
object LxSyncProtocol {
    const val HELLO_MSG = "Hello~::^-^::~v4~"
    const val HELLO_PREFIX = "Hello~::^-^::"
    const val ID_PREFIX = "OjppZDo6"
    const val AUTH_MSG = "lx-music auth::"
    const val MSG_AUTH_FAILED = "Auth failed"
    const val MSG_BLOCKED_IP = "Blocked IP"
    const val MSG_CONNECT = "lx-music connect"

    /** Protocol version this client speaks (the `v4` in [HELLO_MSG]). */
    const val LOCAL_VERSION = 4

    /**
     * Client-type token the server expects as the 4th line of the first auth payload. It is
     * NOT the device name - it only tells the server to send text `ping` heartbeats.
     */
    const val CLIENT_TYPE = "lx_music_mobile"

    /** Close codes that mean "this session is over" - the official client does not reconnect. */
    const val CLOSE_NORMAL = 1000
    const val CLOSE_FAILED = 4100

    /** Java maps the bare `AES` transform to AES/ECB/PKCS5Padding == aes-128-ecb + PKCS7. */
    const val AES_MODE = "AES"

    /** Node's `RSA_PKCS1_OAEP_PADDING` == OAEP with SHA-1 + MGF1(SHA-1). */
    const val RSA_PADDING = "RSA/ECB/OAEPWithSHA1AndMGF1Padding"

    /**
     * The 7 official list-sync modes. Stage 3A never sends these - kept here so the mode
     * picker (next stage) has a single source of truth.
     */
    /** Official `LX.Sync.List.SyncMode` values (server side maps them through `TRANS_MODE`). */
    const val SYNC_MODE_MERGE_LOCAL_REMOTE = "merge_local_remote"
    const val SYNC_MODE_MERGE_REMOTE_LOCAL = "merge_remote_local"
    const val SYNC_MODE_OVERWRITE_LOCAL_REMOTE = "overwrite_local_remote"
    const val SYNC_MODE_OVERWRITE_REMOTE_LOCAL = "overwrite_remote_local"
    const val SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL = "overwrite_local_remote_full"
    const val SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL = "overwrite_remote_local_full"

    /** The one official mode that means "do not touch anything". */
    const val SYNC_MODE_CANCEL = "cancel"

    val SYNC_MODES: List<String> = listOf(
        SYNC_MODE_MERGE_LOCAL_REMOTE,
        SYNC_MODE_MERGE_REMOTE_LOCAL,
        SYNC_MODE_OVERWRITE_LOCAL_REMOTE,
        SYNC_MODE_OVERWRITE_REMOTE_LOCAL,
        SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL,
        SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL,
        SYNC_MODE_CANCEL,
    )

    /**
     * Modes that may be remembered as the user's default. `cancel` is deliberately excluded:
     * it means "abort this sync", not "the policy for next time".
     */
    val SELECTABLE_SYNC_MODES: List<String> = SYNC_MODES.filter { it != SYNC_MODE_CANCEL }

    fun isSelectableSyncMode(mode: String?): Boolean = mode != null && mode in SELECTABLE_SYNC_MODES

    private const val HEX = "0123456789abcdef"
    private val VERSION_REGEX = Regex("v(\\d+)")

    /**
     * Device name shown in the server's device list. The user must not have to type this, and
     * it must be stable across restarts so the server can reuse the stored clientId/key.
     */
    fun deviceName(): String = (Build.MODEL ?: "").trim().ifEmpty { "Android TV" }

    /** Lowercase hex MD5, byte-for-byte what the official `toMD5` produces. */
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * `key = base64(md5(connectionCode).substring(0, 16))` - the AES-128 key used for the
     * FIRST (code based) `GET /ah` round trip, in the base64 form [AES] expects.
     */
    fun connectionCodeKey(connectionCode: String): String =
        base64NoWrap(md5Hex(connectionCode).substring(0, 16).toByteArray(StandardCharsets.UTF_8))

    /** First-time payload: `lx-music auth::\n{publicKey}\n{deviceName}\nlx_music_mobile`. */
    fun firstAuthPayload(publicKeyBase64: String, deviceName: String): String =
        "$AUTH_MSG\n$publicKeyBase64\n$deviceName\n$CLIENT_TYPE"

    /** Subsequent payload: `lx-music auth::` + deviceName (no separators - official quirk). */
    fun keyAuthPayload(deviceName: String): String = AUTH_MSG + deviceName

    internal fun newRsaKeyPair(): RsaKeyPair {
        val kp: KeyPair = RSA.getKeyPair() ?: throw LxSyncCryptoException("RSA key pair generation failed")
        return RsaKeyPair(
            publicKeyBase64 = base64NoWrap(kp.public.encoded),
            privateKeyBase64 = base64NoWrap(kp.private.encoded),
        )
    }

    /** AES-128-ECB encrypt, returns base64. Throws when the platform cipher refuses. */
    fun aesEncrypt(plainText: String, keyBase64: String): String {
        val out = AES.encrypt(
            plainText.toByteArray(StandardCharsets.UTF_8),
            Base64.decode(keyBase64, Base64.DEFAULT),
            AES_MODE,
        )
        if (out.isEmpty()) throw LxSyncCryptoException("AES encrypt failed")
        return out
    }

    /** AES-128-ECB decrypt. [AES] swallows cipher errors and returns "", so treat that as failure. */
    fun aesDecrypt(cipherBase64: String, keyBase64: String): String {
        val out = AES.decrypt(cipherBase64, keyBase64, "", AES_MODE)
        if (out.isEmpty()) throw LxSyncCryptoException("AES decrypt failed")
        return out
    }

    /** Decrypts the RSA-OAEP base64 blob the server returns from the first `/ah`. */
    fun rsaDecrypt(cipherBase64: String, privateKeyBase64: String): String {
        val out = RSA.decryptRSAToString(cipherBase64, privateKeyBase64, RSA_PADDING)
        if (out.isEmpty()) throw LxSyncCryptoException("RSA decrypt failed")
        return out
    }

    fun isHelloOk(text: String): Boolean = text == HELLO_MSG

    /** `Hello~::^-^::~v4~` -> 4. Server-major/minor drift is detected in [SyncClient]. */
    fun serverVersionFromHello(text: String): Int? =
        VERSION_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** `OjppZDo6<serverId>` -> `<serverId>`; anything else means "not an LX sync server". */
    fun parseServerId(text: String): String? =
        text.removePrefix(ID_PREFIX).takeIf { text.startsWith(ID_PREFIX) && it.isNotBlank() }

    private fun base64NoWrap(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
