package com.walkman.tv.playback.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge rules for the three-probe player discovery. All pure data, so the exact behaviour that
 * [com.walkman.tv.playback.external.ExternalPlayerLauncher.findPlayers] relies on is asserted here
 * without an Android runtime (the launcher only maps `ResolveInfo` onto these candidates).
 */
class ExternalPlayerDiscoveryTest {

    private val self = "com.walkman.tv"

    private fun candidate(pkg: String, activity: String, label: String? = null) =
        ExternalPlayerCandidate(packageName = pkg, activityName = activity, label = label)

    @Test
    fun theSamePlayerAnsweredBySeveralProbesIsDedupedOnItsComponent() {
        val players = ExternalPlayerDiscovery.collect(
            listOf(
                candidate("com.vendor.player", "com.vendor.player.MediaCenter", "媒体中心"),
                // Same activity found again by the no-MIME probe.
                candidate("com.vendor.player", "com.vendor.player.MediaCenter", "媒体中心"),
                // A second activity of the same app is a distinct entry.
                candidate("com.vendor.player", "com.vendor.player.PlayerActivity", "媒体中心"),
            ),
            selfPackage = self,
        )

        assertEquals(2, players.size)
        assertEquals(
            listOf(
                "com.vendor.player/com.vendor.player.MediaCenter",
                "com.vendor.player/com.vendor.player.PlayerActivity",
            ),
            players.map { it.componentString },
        )
    }

    @Test
    fun walkmanItselfIsNeverOffered() {
        val players = ExternalPlayerDiscovery.collect(
            listOf(
                candidate(self, "$self.MainActivity", "Walkman"),
                candidate("com.vendor.player", "com.vendor.player.MediaCenter", "媒体中心"),
            ),
            selfPackage = self,
        )

        assertEquals(listOf("com.vendor.player/com.vendor.player.MediaCenter"), players.map { it.componentString })
    }

    @Test
    fun theAndroidTvFrameworkStubIsNeverOffered() {
        val players = ExternalPlayerDiscovery.collect(
            listOf(
                candidate(ExternalPlayerDiscovery.FRAMEWORK_STUBS_PACKAGE, "com.android.tv.frameworkpackagestubs.StubActivity", null),
                candidate("com.vendor.player", "com.vendor.player.MediaCenter", "媒体中心"),
            ),
            selfPackage = self,
        )

        assertEquals(listOf("com.vendor.player/com.vendor.player.MediaCenter"), players.map { it.componentString })
        assertEquals("com.android.tv.frameworkpackagestubs", ExternalPlayerDiscovery.FRAMEWORK_STUBS_PACKAGE)
    }

    @Test
    fun blankComponentsAreIgnored() {
        val players = ExternalPlayerDiscovery.collect(
            listOf(
                candidate("", "com.vendor.player.MediaCenter", "媒体中心"),
                candidate("com.vendor.player", "", "媒体中心"),
                candidate("com.vendor.player", "com.vendor.player.MediaCenter", "媒体中心"),
            ),
            selfPackage = self,
        )

        assertEquals(1, players.size)
    }

    @Test
    fun playersAreSortedByVisibleNameAndKeepALabelFallback() {
        val players = ExternalPlayerDiscovery.collect(
            listOf(
                candidate("com.b", "com.b.Zed", null),
                candidate("com.a", "com.a.Alpha", "   "),
                candidate("com.c", "com.c.Charlie", "Bravo 播放器"),
            ),
            selfPackage = self,
        )

        assertEquals(
            listOf(
                "com.a/com.a.Alpha",      // no usable label -> falls back to the activity name
                "com.c/com.c.Charlie",    // "Bravo 播放器"
                "com.b/com.b.Zed",
            ),
            players.map { it.componentString },
        )
        assertEquals("Alpha", players[0].displayName)
        assertEquals("Bravo 播放器", players[1].displayName)
        assertTrue(players.all { it.label == null || it.label!!.isNotBlank() })
    }
}
