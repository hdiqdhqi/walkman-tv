package com.walkman.tv.data.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two settings merged from upstream `feat/vinyl-refresh-autohide` (黑胶颜色 / 播放控件自动隐藏).
 *
 * Two things must not silently regress:
 *  1. the *official* default (翠绿, controls visible) - the upstream colour behaviour and default are
 *     part of the feature being merged, not ours to re-pick;
 *  2. old settings.json files keep loading - these fields were added to a model that already had
 *     users, so a file written before the option existed must still read and get the default.
 */
class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the merged defaults match the upstream branch`() {
        val settings = Settings()
        assertEquals("翠绿 is the upstream default vinyl colour", VinylColor.GREEN, settings.vinylColor)
        assertFalse("auto-hide player controls is off by default", settings.autoHidePlayerControls)
    }

    @Test
    fun `a settings file written before the new options still loads`() = runBlocking {
        // Exactly the shape this app persisted before the merge: no vinylColor / autoHide* keys.
        File(tmp.root, "settings.json").writeText(
            """{"preferredQuality":"flac24bit","fallbackEnabled":true,"audioOffloadEnabled":true,""" +
                """"showLyricTranslation":true,"lyricSize":"STANDARD","maxConcurrentDownloads":3,""" +
                """"redownloadOnQualityChange":true,"songlistAutoUpdateEnabled":true}""",
        )

        val store = SettingsStore(tmp.root)
        store.loadAll()

        assertEquals(VinylColor.GREEN, store.settings.value.vinylColor)
        assertFalse(store.settings.value.autoHidePlayerControls)
        assertEquals(LyricSize.STANDARD, store.settings.value.lyricSize)
    }

    @Test
    fun `every vinyl colour survives a save and reload`() = runBlocking {
        val store = SettingsStore(tmp.root)
        store.loadAll()
        VinylColor.entries.forEach { color ->
            store.update { it.copy(vinylColor = color) }
            val reloaded = SettingsStore(tmp.root).apply { loadAll() }
            assertEquals(color, reloaded.settings.value.vinylColor)
        }
    }

    @Test
    fun `auto hide is persisted and an unknown future key does not break loading`() = runBlocking {
        val store = SettingsStore(tmp.root)
        store.loadAll()
        store.update { it.copy(autoHidePlayerControls = true) }

        val file = File(tmp.root, "settings.json")
        val raw = file.readText()
        assertTrue("the new key is persisted", raw.contains("\"autoHidePlayerControls\":true"))
        assertTrue("the vinyl colour is persisted", raw.contains("\"vinylColor\":\"GREEN\""))

        // A newer build writing a field this one has never heard of must not wipe the settings.
        file.writeText(raw.dropLast(1) + ",\"someFutureFlag\":true}")
        val reloaded = SettingsStore(tmp.root).apply { loadAll() }
        assertTrue(reloaded.settings.value.autoHidePlayerControls)
        assertEquals(VinylColor.GREEN, reloaded.settings.value.vinylColor)
    }
}