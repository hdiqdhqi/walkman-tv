package com.walkman.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Makes Back leave the text field instead of only closing the IME. By default the IME eats Back
 * and hides itself while focus stays in the field — on some TV IMEs that re-opens the keyboard
 * right away, trapping the remote in a show/hide loop. We intercept Back *before* the IME sees
 * it, hide the keyboard ourselves and move focus to the next control (the dialog's buttons).
 * A second Back then reaches the dialog normally and dismisses it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.leaveFieldOnBack(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return onInterceptKeyBeforeSoftKeyboard { event ->
        if (event.key != Key.Back) return@onInterceptKeyBeforeSoftKeyboard false
        // Consume both down and up so the dialog doesn't also dismiss on this press.
        if (event.type == KeyEventType.KeyUp) leaveField(focusManager, keyboard)
        true
    }
}

/** Hide the IME and move focus off the current text field (down, else just clear it). */
fun leaveField(focusManager: FocusManager, keyboard: SoftwareKeyboardController?) {
    runCatching { keyboard?.hide() }
    if (!focusManager.moveFocus(FocusDirection.Down)) focusManager.clearFocus(force = true)
}

/**
 * Same Back interception as [leaveFieldOnBack], but for fields that must *stay* where they are:
 * it hides the IME and runs [onExit], leaving D-pad focus on the same control. Used by
 * [TvFocusGatedField] with `keepFocusOnExit = true`, where a field is a settings row and walking
 * focus off it on Back would silently move the user to a neighbouring setting.
 *
 * Back is consumed on both down and up so the press cannot also reach the screen and close it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exitEditOnBack(onExit: () -> Unit): Modifier =
    onInterceptKeyBeforeSoftKeyboard { event ->
        if (event.key != Key.Back) return@onInterceptKeyBeforeSoftKeyboard false
        if (event.type == KeyEventType.KeyUp) onExit()
        true
    }
