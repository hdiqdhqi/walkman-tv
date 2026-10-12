package com.walkman.tv.playback.external

import com.walkman.tv.data.store.Settings
import com.walkman.tv.data.store.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The remembered default hand-off player is only ever a *preference*: the default is null, which
 * means Walkman keeps playing everything and the first tap shows Android's chooser.
 */
class ExternalPlayerSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun defaultIsWalkmanWithNoHandoffPlayer() {
        val settings = Settings()
        assertNull(settings.defaultHandoffPlayer)
        assertNull(settings.defaultHandoffPlayerLabel)
    }

    @Test
    fun chosenPlayerPersistsAcrossRestart() = runBlocking {
        val dir = folder.newFolder("store")
        val store = SettingsStore(dir)
        store.loadAll()
        store.update {
            it.copy(
                defaultHandoffPlayer = "org.videolan.vlc/org.videolan.vlc.MainActivity",
                defaultHandoffPlayerLabel = "VLC",
            )
        }

        val reopened = SettingsStore(dir)
        reopened.loadAll()
        assertEquals(
            "org.videolan.vlc/org.videolan.vlc.MainActivity",
            reopened.settings.value.defaultHandoffPlayer,
        )
        assertEquals("VLC", reopened.settings.value.defaultHandoffPlayerLabel)
    }

    @Test
    fun clearingThePlayerFallsBackToTheDefault() = runBlocking {
        val dir = folder.newFolder("store2")
        val store = SettingsStore(dir)
        store.loadAll()
        store.update {
            it.copy(
                defaultHandoffPlayer = "org.videolan.vlc/org.videolan.vlc.MainActivity",
                defaultHandoffPlayerLabel = "VLC",
            )
        }
        store.update {
            it.copy(defaultHandoffPlayer = null, defaultHandoffPlayerLabel = null)
        }

        val reopened = SettingsStore(dir)
        reopened.loadAll()
        assertNull(reopened.settings.value.defaultHandoffPlayer)
    }

    @Test
    fun clearingWhenNothingWasEverSetIsANoOpAndDoesNotCrash() = runBlocking {
        val dir = folder.newFolder("store3")
        val store = SettingsStore(dir)
        store.loadAll()

        // "清除默认播放器" is always clickable; with no saved player it is simply a no-op.
        store.update { it.copy(defaultHandoffPlayer = null, defaultHandoffPlayerLabel = null) }

        assertNull(store.settings.value.defaultHandoffPlayer)
        assertNull(store.settings.value.defaultHandoffPlayerLabel)
    }

    @Test
    fun savedPickerSelectionIsWhatSettingsRendersAsTheDefaultName() = runBlocking {
        val dir = folder.newFolder("store4")
        val store = SettingsStore(dir)
        store.loadAll()

        // This is exactly what the chooser callback persists (EXTRA_CHOSEN_COMPONENT → ref).
        val ref = ExternalPlayerRef("com.tcl.media", "com.tcl.media.MediaCenter", "媒体中心")
        store.update {
            it.copy(defaultHandoffPlayer = ref.componentString, defaultHandoffPlayerLabel = ref.label)
        }

        val rendered = ExternalPlayerRef.parse(
            store.settings.value.defaultHandoffPlayer,
            store.settings.value.defaultHandoffPlayerLabel,
        )
        assertEquals("媒体中心", rendered?.displayName)
    }
}
