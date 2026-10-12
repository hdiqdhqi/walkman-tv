package com.walkman.tv.playback.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIME fallback for the external hand-off (spec 3 / 17). Covers the three-step chain the plan
 * describes: codec (real evidence) -> URL extension (hint) -> [AudioMime.DEFAULT] (honest unknown).
 */
class AudioMimeTest {

    @Test
    fun probedCodecWinsOverUrlExtension() {
        // A script may claim .flac but the bytes are MP3; the probe is the real evidence.
        assertEquals("audio/mpeg", AudioMime.resolve("https://x/a.flac", codec = "MP3"))
        assertEquals("audio/flac", AudioMime.resolve("https://x/a.mp3", codec = "FLAC"))
    }

    @Test
    fun fallsBackToUrlExtensionWhenCodecUnknown() {
        assertEquals("audio/mpeg", AudioMime.resolve("https://x/a.mp3", codec = null))
        assertEquals("audio/flac", AudioMime.resolve("https://x/a.flac"))
        assertEquals("audio/mp4", AudioMime.resolve("https://x/a.m4a"))
        assertEquals("audio/aac", AudioMime.resolve("https://x/a.aac"))
        assertEquals("audio/ogg", AudioMime.resolve("https://x/a.ogg"))
    }

    @Test
    fun extensionIgnoresQueryAndFragment() {
        assertEquals("audio/flac", AudioMime.resolve("https://x/a.flac?token=SECRET&sign=z"))
        assertEquals("audio/mpeg", AudioMime.resolve("https://x/song.mp3#t=10"))
    }

    @Test
    fun unknownApiReturnsAudioWildcard() {
        assertEquals(AudioMime.DEFAULT, AudioMime.fromUrl(null))
        assertEquals(AudioMime.DEFAULT, AudioMime.fromUrl(""))
        assertEquals(AudioMime.DEFAULT, AudioMime.fromUrl("https://x/stream"))
        assertEquals(AudioMime.DEFAULT, AudioMime.fromUrl("https://x/stream?only=query"))
    }

    @Test
    fun dolbyBitstreamsAreNamedOnlyWhenReal() {
        assertEquals("audio/eac3", AudioMime.resolve("https://x/a.eac3"))
        assertEquals("audio/ac4", AudioMime.resolve("https://x/a.ac4", codec = "AC-4"))
        assertTrue(AudioMime.isDolbyBitstream("audio/eac3"))
        assertTrue(AudioMime.isDolbyBitstream("audio/ac4"))
    }

    @Test
    fun atmosMetadataAloneNeverClaimsADolbyBitstream() {
        // A track tagged Atmos whose URL is really MP3/FLAC must NOT be reported as Dolby (spec 17).
        val mp3 = AudioMime.resolve("https://x/atmos-song.mp3")
        val flac = AudioMime.resolve("https://x/atmos-song.flac")
        assertEquals("audio/mpeg", mp3)
        assertEquals("audio/flac", flac)
        assertFalse(AudioMime.isDolbyBitstream(mp3))
        assertFalse(AudioMime.isDolbyBitstream(flac))
    }

    @Test
    fun extensionParsing() {
        assertEquals("mp3", AudioMime.extension("https://x/a.MP3"))
        assertEquals("flac", AudioMime.extension("/sdcard/music/a.flac"))
        assertEquals(null, AudioMime.extension("https://x/folder.dots/a"))
        assertEquals(null, AudioMime.extension(null))
    }
}
