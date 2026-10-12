package com.walkman.tv.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV text-field rule, verified where it can be: "D-pad focus is not editing".
 *
 * The real IME cannot be observed from a JVM test, so the gate is written as this plain state
 * machine and these tests pin the rule the UI relies on:
 *   focus arrives          -> [TvFieldEditState.editing] stays false, nothing may open the IME
 *   OK / DPAD_CENTER       -> the only thing that starts a session
 *   one session == one IME -> a repeated OK must never toggle the session back off
 *   IME closed / focus out -> session ends, and the *same* state can start a new one
 *
 * The text itself is hoisted by the caller, so nothing here can clear what the user typed; the
 * tests instead pin that ending a session leaves the machine ready to edit the same field again.
 */
class TvTextFieldStateTest {

    @Test
    fun `focus alone is not an edit session`() {
        val state = TvFieldEditState()
        assertFalse("arriving at a field must not start editing", state.editing)
        assertFalse(
            "the wrapped field must stay unfocusable, or Compose would open the IME on focus",
            state.innerFocusable(enabled = true),
        )
    }

    @Test
    fun `only an explicit confirm starts editing`() {
        val state = TvFieldEditState()
        assertTrue(state.onConfirm(enabled = true))
        assertTrue(state.editing)
        assertTrue(state.innerFocusable(enabled = true))
    }

    @Test
    fun `a disabled field ignores confirm`() {
        val state = TvFieldEditState()
        assertFalse(state.onConfirm(enabled = false))
        assertFalse(state.editing)
        assertFalse(state.innerFocusable(enabled = false))
    }

    @Test
    fun `a repeated confirm does not close the session`() {
        val state = TvFieldEditState()
        state.onConfirm(enabled = true)
        repeat(3) { assertTrue(state.onConfirm(enabled = true)) }
        assertTrue("a duplicated OK key event must not toggle the IME the user just opened", state.editing)
        assertTrue(state.innerFocusable(enabled = true))
    }

    @Test
    fun `closing the ime ends the session and the field can be edited again`() {
        val state = TvFieldEditState()
        state.onConfirm(enabled = true)
        state.onEditFinished()
        assertFalse(state.editing)
        assertFalse(state.innerFocusable(enabled = true))

        // The remote stayed on the field: pressing OK again re-opens the session.
        assertTrue(state.onConfirm(enabled = true))
        assertTrue(state.editing)
        assertTrue(state.innerFocusable(enabled = true))
    }

    @Test
    fun `focus leaving ends the session`() {
        val state = TvFieldEditState()
        state.onConfirm(enabled = true)
        state.onFocusLost()
        assertFalse("a stale session would re-open the IME on the way back", state.editing)
        assertFalse(state.innerFocusable(enabled = true))
    }

    @Test
    fun `the remote ok button and keyboard enter are confirm keys`() {
        assertTrue(isTvConfirmKey(Key.DirectionCenter))
        assertTrue(isTvConfirmKey(Key.Enter))
        assertTrue(isTvConfirmKey(Key.NumPadEnter))
    }

    @Test
    fun `navigation keys are not confirm keys`() {
        listOf(
            Key.DirectionUp,
            Key.DirectionDown,
            Key.DirectionLeft,
            Key.DirectionRight,
            Key.Back,
            Key.Escape,
            Key.Spacebar,
        ).forEach { key ->
            assertFalse("$key must not start an edit session", isTvConfirmKey(key))
        }
    }
}