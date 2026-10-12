package com.walkman.tv.data.logging

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Non-identifying device facts that go into `device.txt` (spec §17). There is deliberately no
 * field for IMEI / serial / MAC / accounts / advertiser id / location / Wi-Fi credentials - the
 * data class simply cannot carry them.
 */
data class DeviceFacts(
    val appVersion: String,
    val buildType: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abis: List<String>,
    val screenResolution: String,
    val lanIpv4: String?,
    val appStartMs: Long,
)

/**
 * Pure ZIP assembly for the phone download (spec §16/§17). Kept free of Android APIs so it can be
 * exercised by a plain JVM test; the Android side only supplies [DeviceFacts].
 */
object LogExport {

    const val ZIP_PREFIX = "walkman-tv-logs-"

    /** e.g. `walkman-tv-logs-20261003-125500.zip`. */
    fun zipFileName(nowMs: Long): String = ZIP_PREFIX + LogFormat.fileStamp(nowMs) + ".zip"

    /**
     * Build the archive: one `logs/<name>.log` entry per slice plus `device.txt`. An empty
     * archive still gets a marker file so the phone never downloads a zero-content ZIP.
     */
    fun buildZip(slices: List<RunLog.LogSlice>, device: DeviceFacts, nowMs: Long): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(out).use { zip ->
            if (slices.isEmpty()) {
                zip.putNextEntry(ZipEntry("logs/empty.txt"))
                zip.write("暂无日志记录\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            } else {
                for (slice in slices) {
                    zip.putNextEntry(ZipEntry("logs/" + slice.name))
                    zip.write(slice.text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(deviceTxt(device, slices, nowMs).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** Human-readable, secret-free device report (spec §17). */
    fun deviceTxt(device: DeviceFacts, slices: List<RunLog.LogSlice>, nowMs: Long): String {
        val oldest = slices.firstOrNull()?.let { firstTimestamp(it.text) }
        val newest = slices.lastOrNull()?.let { lastTimestamp(it.text) }
        return buildString {
            append("App version: ").append(device.appVersion).append('\n')
            append("Build type: ").append(device.buildType).append('\n')
            append("Device model: ").append(device.deviceModel).append('\n')
            append("Android version: ").append(device.androidVersion)
                .append(" (SDK ").append(device.sdkInt).append(")\n")
            append("ABI: ").append(device.abis.joinToString(", ").ifEmpty { "unknown" }).append('\n')
            append("Screen resolution: ").append(device.screenResolution).append('\n')
            append("LAN IPv4: ").append(device.lanIpv4 ?: "unknown").append('\n')
            append("Log generation time: ").append(LogFormat.timestamp(nowMs)).append('\n')
            append("Log time range: ")
                .append(oldest?.let { LogFormat.timestamp(it) } ?: "无")
                .append(" - ")
                .append(newest?.let { LogFormat.timestamp(it) } ?: "无")
                .append('\n')
            append("App start time: ").append(LogFormat.timestamp(device.appStartMs)).append('\n')
        }
    }

    private fun firstTimestamp(text: String): Long? =
        text.lineSequence().firstOrNull { it.isNotEmpty() }?.let { LogFormat.parseTimestamp(it) }

    private fun lastTimestamp(text: String): Long? =
        text.lineSequence().filter { it.isNotEmpty() }.lastOrNull()?.let { LogFormat.parseTimestamp(it) }
}
