package com.walkman.tv.data.logging

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-shot, high-entropy access token for the temporary log download server (spec §14).
 *
 * 32 random bytes (256 bit, well above the required 128 bit) rendered as 64 hex chars. It lives
 * in memory only: a new scan regenerates it, which immediately invalidates the previous one, and
 * [invalidate] kills it when the server stops or expires.
 */
class LogAccessToken(private val random: SecureRandom = SecureRandom()) {

    @Volatile
    private var value: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    /** Generate a fresh token, invalidating any previous one. */
    fun issue(nowMs: Long, ttlMs: Long): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val hex = StringBuilder(TOKEN_BYTES * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        val token = hex.toString()
        value = token
        expiresAtMs = nowMs + ttlMs
        return token
    }

    /** Missing, wrong, expired or already-invalidated tokens are all rejected. */
    fun isValid(candidate: String?, nowMs: Long): Boolean {
        val expected = value ?: return false
        if (candidate == null || candidate.isEmpty()) return false
        if (nowMs >= expiresAtMs) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    fun invalidate() {
        value = null
        expiresAtMs = 0L
    }

    fun current(): String? = value

    fun expiresAt(): Long = expiresAtMs

    companion object {
        const val TOKEN_BYTES = 32
        const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2
        private const val HEX = "0123456789abcdef"
    }
}
