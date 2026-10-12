package com.walkman.tv.data.logging

import com.walkman.tv.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEventMappingTest {

    @Test
    fun everyStateMapsToAStableEventName() {
        assertEquals("SYNC_CONNECT_START", SyncEventMapping.event(SyncState.Connecting))
        assertEquals("SYNC_AUTH_START", SyncEventMapping.event(SyncState.Authenticating))
        assertEquals("SYNC_AUTH_OK", SyncEventMapping.event(SyncState.Connected))
        assertEquals("SYNC_START", SyncEventMapping.event(SyncState.Syncing))
        assertEquals("SYNC_SUCCESS", SyncEventMapping.event(SyncState.Success))
        assertEquals("SYNC_MODE", SyncEventMapping.event(SyncState.NeedSyncMode))
        assertEquals("SYNC_AUTH_FAIL", SyncEventMapping.event(SyncState.AuthFailed))
        assertEquals("SYNC_FAIL", SyncEventMapping.event(SyncState.BlockedIp))
        assertEquals("SYNC_FAIL", SyncEventMapping.event(SyncState.ServiceUnavailable))
        assertEquals("SYNC_FAIL", SyncEventMapping.event(SyncState.VersionIncompatible))
        assertEquals("SYNC_FAIL", SyncEventMapping.event(SyncState.Error("x")))
        assertEquals("SYNC_DISABLED", SyncEventMapping.event(SyncState.Disabled))
        assertEquals("SYNC_WS_CLOSE", SyncEventMapping.event(SyncState.Disconnected))
    }

    @Test
    fun failuresAreLoggedAtWarnLevel() {
        assertEquals(LogLevel.WARN, SyncEventMapping.level(SyncState.AuthFailed))
        assertEquals(LogLevel.WARN, SyncEventMapping.level(SyncState.BlockedIp))
        assertEquals(LogLevel.WARN, SyncEventMapping.level(SyncState.ServiceUnavailable))
        assertEquals(LogLevel.WARN, SyncEventMapping.level(SyncState.VersionIncompatible))
        assertEquals(LogLevel.WARN, SyncEventMapping.level(SyncState.Error("x")))
        assertEquals(LogLevel.INFO, SyncEventMapping.level(SyncState.Connected))
        assertEquals(LogLevel.INFO, SyncEventMapping.level(SyncState.Syncing))
    }

    @Test
    fun fieldsCarryNoSecrets() {
        val fields = SyncEventMapping.fields(SyncState.AuthFailed)
        assertEquals("认证失败", fields["state"])
        assertEquals(false, fields["busy"])
        assertEquals("AuthFailed", fields["category"])
    }
}
