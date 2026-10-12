package com.walkman.tv.ui.player

import androidx.compose.ui.graphics.Color
import com.walkman.tv.data.store.VinylColor
import com.walkman.tv.ui.theme.AppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 黑胶 colour feature merged from upstream `feat/vinyl-refresh-autohide`.
 *
 * Only the palette mapping is testable on the JVM (the disc itself is Canvas drawing), and that is
 * exactly the part with a hard requirement attached: all of upstream's options must exist and 翠绿
 * must keep the upstream look and stay the default, while 经典黑 must keep reproducing the fixed
 * black record this app rendered before the option existed.
 */
class VinylPaletteTest {

    @Test
    fun `upstream's five colours are all available`() {
        assertEquals(5, VinylColor.entries.size)
        assertEquals(
            listOf("翠绿", "经典黑", "酒红", "深蓝", "琥珀金"),
            VinylColor.entries.map { it.label },
        )
    }

    @Test
    fun `green keeps the upstream look`() {
        val green = vinylPalette(VinylColor.GREEN)
        assertEquals(Color(0xFF0E2818), green.base)
        assertEquals(listOf(Color(0xFF0A1F12), Color(0xFF112F1C), Color(0xFF1B4528)), green.body)
        assertEquals("the rim ring is still the brand accent", AppColors.AccentGreen, green.rim)
        assertEquals("the halo is still the brand accent", AppColors.AccentGreen, green.glow)
        assertEquals(1f, green.glowStrength, 0f)
    }

    @Test
    fun `classic black reproduces the record this build used to render`() {
        val black = vinylPalette(VinylColor.BLACK)
        assertEquals(Color(0xFF0E0E0E), black.base)
        assertEquals(listOf(Color(0xFF0E0E0E), Color(0xFF161616), Color(0xFF1F1F1F)), black.body)
        assertEquals(Color(0xFF9E9E9E), black.groove)
        assertEquals(Color(0xFF3C3C3C), black.rim)
        assertEquals("a black record needs visible grooves, not black-on-black", Color(0xFF9E9E9E), black.groove)
    }

    @Test
    fun `every colour is a distinct disc`() {
        val bases = VinylColor.entries.map { vinylPalette(it).base }
        assertEquals("two colours must not render the same disc", bases.size, bases.distinct().size)
        VinylColor.entries.forEach { color ->
            val palette = vinylPalette(color)
            assertEquals("$color needs a 3-stop body gradient", 3, palette.body.size)
            assertNotEquals("$color's rim must differ from its base", palette.base, palette.rim)
            assertTrue("$color's glow strength must stay usable", palette.glowStrength in 0.05f..1f)
        }
    }
}