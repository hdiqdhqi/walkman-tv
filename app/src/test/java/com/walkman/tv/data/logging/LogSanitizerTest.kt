package com.walkman.tv.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun urlKeepsOnlySchemeHostAndPath() {
        assertEquals(
            "scheme=https host=a.com path=/music.mp3",
            LogSanitizer.sanitizeUrl("https://a.com/music.mp3?token=xxxx&sign=yyyy"),
        )
    }

    @Test
    fun urlNeverKeepsCredentialsOrQuery() {
        val out = LogSanitizer.sanitizeUrl("http://user:pass@a.com/live.m3u8?auth=zzz")
        assertFalse(out.contains("pass"))
        assertFalse(out.contains("zzz"))
        assertTrue(out.contains("host=a.com"))
    }

    @Test
    fun unparseableUrlDegradesSafely() {
        assertEquals("host=? path=?", LogSanitizer.sanitizeUrl("not a url"))
    }

    @Test
    fun masksSensitiveQueryParameters() {
        val out = LogSanitizer.sanitizeText("GET /x?token=abcdef123456&sign=zzz9 done")
        assertFalse(out.contains("abcdef123456"))
        assertFalse(out.contains("zzz9"))
    }

    @Test
    fun masksAuthorizationHeader() {
        val out = LogSanitizer.sanitizeText("Authorization: Bearer abcdef1234567890")
        assertFalse(out.contains("abcdef1234567890"))
    }

    @Test
    fun masksCookieHeader() {
        val out = LogSanitizer.sanitizeText("Cookie: SESSION=topsecretvalue; x=1")
        assertFalse(out.contains("topsecretvalue"))
    }

    @Test
    fun masksBearerCredential() {
        val out = LogSanitizer.sanitizeText("sent bearer eyJhbGciOiJIUzI1NiJ9payload")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9payload"))
    }

    @Test
    fun masksConnectionCode() {
        assertFalse(LogSanitizer.sanitizeText("connection code: SECRET12345").contains("SECRET12345"))
    }

    @Test
    fun masksAesAndRsaKeys() {
        assertFalse(LogSanitizer.sanitizeText("aesKey=0123456789abcdef").contains("0123456789abcdef"))
        assertFalse(LogSanitizer.sanitizeText("rsa_key: MIIBIjANBgkq").contains("MIIBIjANBgkq"))
    }

    @Test
    fun masksPassword() {
        assertFalse(LogSanitizer.sanitizeText("password=hunter2").contains("hunter2"))
    }

    @Test
    fun masksShareTokenInPath() {
        val out = LogSanitizer.sanitizeText("http://192.168.1.2:9/log/a1b2c3d4e5f60718")
        assertFalse(out.contains("a1b2c3d4e5f60718"))
        assertTrue(out.contains("/log/***"))
    }

    @Test
    fun sanitizeValueHandlesNullAndOtherTypes() {
        assertEquals("null", LogSanitizer.sanitizeValue(null))
        assertEquals("42", LogSanitizer.sanitizeValue(42))
    }
}
