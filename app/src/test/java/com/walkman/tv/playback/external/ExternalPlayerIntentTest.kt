package com.walkman.tv.playback.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-off intent is a plain `ACTION_VIEW` with the resolved URL as data. Nothing here ever
 * enumerates packages (that would need a `<queries>` block, which breaks install on some OEMs).
 */
class ExternalPlayerIntentTest {

    @Test
    fun viewIntentCarriesActionUrlAndMime() {
        val spec = ExternalPlayerIntents.view(
            url = "https://cdn.example.com/a/song.flac?token=SECRET",
            mimeType = "audio/flac",
        )
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("https://cdn.example.com/a/song.flac?token=SECRET", spec.data)
        assertEquals("audio/flac", spec.mimeType)
        assertTrue(spec.flags and ExternalPlayerIntents.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(null, spec.component)
    }

    @Test
    fun chooserTargetDropsNewTaskBecauseItStartsFromAnActivity() {
        val spec = ExternalPlayerIntents.view("https://x/a.mp3", "audio/mpeg", newTask = false)
        assertEquals(0, spec.flags and ExternalPlayerIntents.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun blankMimeFallsBackToAudioWildcard() {
        val spec = ExternalPlayerIntents.view("https://x/a", "   ")
        assertEquals("audio/*", spec.mimeType)
    }

    @Test
    fun contentUriGrantsReadPermissionButHttpsDoesNot() {
        val content = ExternalPlayerIntents.view("content://com.walkman.tv.provider/music/a.flac", "audio/flac")
        assertTrue(content.flags and ExternalPlayerIntents.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val https = ExternalPlayerIntents.view("https://x/a.mp3", "audio/mpeg")
        assertEquals(0, https.flags and ExternalPlayerIntents.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun explicitHandOffAddressesTheRememberedComponent() {
        val spec = ExternalPlayerIntents.explicit(
            url = "https://x/a.flac",
            mimeType = "audio/flac",
            component = "com.tcl.media/com.tcl.media.PlayerActivity",
        )
        assertEquals("com.tcl.media/com.tcl.media.PlayerActivity", spec.component)
        assertEquals("https://x/a.flac", spec.data)
        assertEquals("audio/flac", spec.mimeType)
        assertTrue(spec.flags and ExternalPlayerIntents.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun enumerationProbesTheThreeIntentShapesWithTheUrlUntouched() {
        val url = "https://cdn.example.com/a/song.flac?token=SECRET&sign=x&expires=1"
        val probes = ExternalPlayerIntents.enumerationSpecs(url, "audio/flac")
        assertEquals(3, probes.size)

        // 1. ACTION_VIEW + url + resolved MIME.
        val withType = probes[0]
        assertEquals(HandoffProbe.ViewWithType, withType.probe)
        assertEquals("android.intent.action.VIEW", withType.spec.action)
        assertEquals(url, withType.spec.data)
        assertEquals("audio/flac", withType.spec.mimeType)
        assertTrue(withType.spec.defaultCategory)

        // 2. ACTION_VIEW + url, no MIME at all (a scheme-only filter never matches a typed intent).
        val withoutType = probes[1]
        assertEquals(HandoffProbe.ViewWithoutType, withoutType.probe)
        assertEquals("android.intent.action.VIEW", withoutType.spec.action)
        assertEquals(url, withoutType.spec.data)
        assertEquals("", withoutType.spec.mimeType)
        assertTrue(withoutType.spec.defaultCategory)

        // 3. Vendor extension action some TV players answer instead of ACTION_VIEW.
        val vendor = probes[2]
        assertEquals(HandoffProbe.VendorSchemePlay, vendor.probe)
        assertEquals("tv.intent.action.external.SCHEME_PLAY", vendor.spec.action)
        assertEquals(url, vendor.spec.data)
        assertEquals("", vendor.spec.mimeType)
        assertTrue(vendor.spec.defaultCategory)
    }

    @Test
    fun enumerationProbesAreNeverStartedSoTheyCarryNoFlagsOrComponent() {
        for (probe in ExternalPlayerIntents.enumerationSpecs("https://x/a.mp3", "audio/mpeg")) {
            assertEquals(0, probe.spec.flags)
            assertEquals(null, probe.spec.component)
        }
    }

    @Test
    fun enumerationFallsBackToTheWildcardAudioTypeWhenTheMimeIsUnknown() {
        val probes = ExternalPlayerIntents.enumerationSpecs("https://x/a", "   ")
        assertEquals("audio/*", probes[0].spec.mimeType)
        assertEquals("", probes[1].spec.mimeType)
        assertEquals("", probes[2].spec.mimeType)
    }

    @Test
    fun launchAttemptReportsSuccessAndFailure() {
        assertEquals(ExternalLaunchResult.STARTED, ExternalLaunchAttempt.run { })
        assertEquals(
            ExternalLaunchResult.START_FAILED,
            ExternalLaunchAttempt.run { throw RuntimeException("ActivityNotFoundException-like") },
        )
        assertEquals(
            ExternalLaunchResult.START_FAILED,
            ExternalLaunchAttempt.run { throw SecurityException("not exported") },
        )
    }
}
