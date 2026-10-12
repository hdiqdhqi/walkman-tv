package com.walkman.tv.playback.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The remembered default player is a full component (`pkg/activity`), never a bare package. */
class ExternalPlayerRefTest {

    @Test
    fun componentStringRoundTripsThroughParse() {
        val ref = ExternalPlayerRef("com.tcl.media", "com.tcl.media.MediaCenter", "媒体中心")
        assertEquals("com.tcl.media/com.tcl.media.MediaCenter", ref.componentString)
        assertEquals(ref, ExternalPlayerRef.parse(ref.componentString, "媒体中心"))
    }

    @Test
    fun bareOrMalformedValuesAreRejected() {
        assertNull(ExternalPlayerRef.parse("com.tcl.media"))
        assertNull(ExternalPlayerRef.parse(""))
        assertNull(ExternalPlayerRef.parse(null))
        assertNull(ExternalPlayerRef.parse("/activity"))
        assertNull(ExternalPlayerRef.parse("pkg/"))
    }

    @Test
    fun nestedActivityNamesKeepDotsAndDollarSigns() {
        val ref = ExternalPlayerRef.parse("pkg/pkg.Player\$Inner")
        assertEquals("pkg", ref!!.packageName)
        assertEquals("pkg.Player\$Inner", ref.activityName)
    }

    @Test
    fun displayNamePrefersTheLabelButNeverFallsBackToThePackage() {
        assertEquals("媒体中心", ExternalPlayerRef("com.tcl.media", "com.tcl.media.MediaCenter", "媒体中心").displayName)
        assertEquals("MediaCenter", ExternalPlayerRef("com.tcl.media", "com.tcl.media.MediaCenter", null).displayName)
        assertEquals("MediaCenter", ExternalPlayerRef("com.tcl.media", "com.tcl.media.MediaCenter", "  ").displayName)
    }
}
