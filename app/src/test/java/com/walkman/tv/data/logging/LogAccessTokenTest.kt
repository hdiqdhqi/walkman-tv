package com.walkman.tv.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAccessTokenTest {

    @Test
    fun issuedTokenIsLongRandomHex() {
        val token = LogAccessToken()
        val value = token.issue(1_000L, 60_000L)
        assertEquals(LogAccessToken.TOKEN_HEX_LENGTH, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
    }

    @Test
    fun eachIssueRegeneratesAndInvalidatesPrevious() {
        val token = LogAccessToken()
        val first = token.issue(1_000L, 60_000L)
        val second = token.issue(2_000L, 60_000L)
        assertNotEquals(first, second)
        assertFalse(token.isValid(first, 2_000L))
        assertTrue(token.isValid(second, 2_000L))
    }

    @Test
    fun acceptsCorrectAndRejectsWrong() {
        val token = LogAccessToken()
        val value = token.issue(0L, 60_000L)
        assertTrue(token.isValid(value, 1L))
        assertFalse(token.isValid(value + "0", 1L))
        assertFalse(token.isValid(null, 1L))
        assertFalse(token.isValid("", 1L))
    }

    @Test
    fun expiresExactlyAtTtl() {
        val token = LogAccessToken()
        val value = token.issue(0L, 1_000L)
        assertTrue(token.isValid(value, 999L))
        assertFalse(token.isValid(value, 1_000L))
    }

    @Test
    fun invalidateKillsToken() {
        val token = LogAccessToken()
        val value = token.issue(0L, 60_000L)
        token.invalidate()
        assertFalse(token.isValid(value, 1L))
        assertNull(token.current())
    }
}
