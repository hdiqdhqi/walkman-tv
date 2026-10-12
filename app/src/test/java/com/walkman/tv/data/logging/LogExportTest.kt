package com.walkman.tv.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class LogExportTest {

    private val facts = DeviceFacts(
        appVersion = "1.3.7",
        buildType = "debug",
        deviceModel = "TCL T7L Ultra",
        androidVersion = "12",
        sdkInt = 31,
        abis = listOf("arm64-v8a"),
        screenResolution = "1920x1080",
        lanIpv4 = "192.168.1.20",
        appStartMs = 1_700_000_000_000L,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                out[entry.name] = zin.readBytes().toString(Charsets.UTF_8)
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out
    }

    @Test
    fun zipContainsLogsAndDeviceTxt() {
        val now = 1_759_000_000_000L
        val slices = listOf(
            RunLog.LogSlice("2026-10-01.log", "2026-10-01T10:00:00.000 INFO A B\n"),
            RunLog.LogSlice("2026-10-02.log", "2026-10-02T10:00:00.000 INFO A B\n"),
        )
        val entries = unzip(LogExport.buildZip(slices, facts, now))
        assertEquals(setOf("logs/2026-10-01.log", "logs/2026-10-02.log", "device.txt"), entries.keys)
        val device = entries["device.txt"]!!
        assertTrue(device.contains("Device model: TCL T7L Ultra"))
        assertTrue(device.contains("LAN IPv4: 192.168.1.20"))
        assertTrue(device.contains("Log time range: "))
        assertFalse(device.contains("IMEI"))
    }

    @Test
    fun zipStillHasDeviceTxtWhenNoLogs() {
        val entries = unzip(LogExport.buildZip(emptyList(), facts, 1_759_000_000_000L))
        assertTrue(entries.containsKey("device.txt"))
        assertTrue(entries.containsKey("logs/empty.txt"))
    }

    @Test
    fun zipFileNameIsTimestamped() {
        val name = LogExport.zipFileName(1_759_000_000_000L)
        assertTrue(name.startsWith("walkman-tv-logs-"))
        assertTrue(name.endsWith(".zip"))
        assertTrue(name.contains("-"))
    }

    @Test
    fun deviceTxtNeverMentionsForbiddenIdentifiers() {
        val txt = LogExport.deviceTxt(facts, emptyList(), 0L).lowercase()
        listOf("imei", "serial", "mac address", "advertiser", "location", "wifi", "account")
            .forEach { assertFalse("device.txt leaked " + it, txt.contains(it)) }
    }
}
