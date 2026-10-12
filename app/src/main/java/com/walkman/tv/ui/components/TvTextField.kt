package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.walkman.tv.ui.theme.AppColors

/**
 * "Remote focus is not editing" for a TV text field.
 *
 * A Compose text field opens the system IME the moment it takes focus, which on a TV means simply
 * nudging the D-pad across a settings field pops a keyboard up. [TvFocusGatedField] splits the two
 * actions: D-pad focus lands on a gate that only draws the focus ring, and the wrapped field stays
 * unfocusable - and therefore silent - until the user presses OK / DPAD_CENTER (or clicks it).
 *
 * The rule itself lives in this plain state machine so it can be unit-tested on the JVM, where the
 * real IME cannot be observed:
 *
 *  - focus arrives            -> nothing (see [TvFocusGatedField]; only the gate is focused)
 *  - OK / DPAD_CENTER / click -> [onConfirm] starts the edit session ([innerFocusable] true)
 *  - IME dismissed (Back/Done)-> [onEditFinished] ends it again, the field keeps its focus ring
 *  - D-pad focus leaves       -> [onFocusLost] ends it too, so a stale session cannot linger
 *
 * The text itself is hoisted by the caller (`value` / `onValueChange`), so an edit session never
 * owns - and can never clear - what the user typed.
 */
class TvFieldEditState {
    /** True only inside an explicitly requested edit session. */
    var editing by mutableStateOf(false)
        private set

    /** The wrapped text field may take focus (and so open the IME) only while [editing]. */
    fun innerFocusable(enabled: Boolean): Boolean = enabled && editing

    /**
     * The explicit "start editing" action. Returns false for a disabled field, so a caller can tell
     * "nothing happened" from "the IME is coming". Pressing it again while already editing is a
     * no-op - it must never toggle the session off, or a repeated key event would close the IME the
     * user just opened.
     */
    fun onConfirm(enabled: Boolean = true): Boolean {
        if (!enabled) return false
        editing = true
        return true
    }

    /** The IME was dismissed: leave the edit session, keep the field where it is. */
    fun onEditFinished() {
        editing = false
    }

    /** D-pad focus left the field (or was handed to a sibling): the edit session ends with it. */
    fun onFocusLost() {
        editing = false
    }
}

/** The remote's OK button: DPAD_CENTER on a TV remote, Enter on a keyboard (incl. the numpad). */
fun isTvConfirmKey(key: Key): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * What the wrapped text field has to apply to itself: the focus gate plus the IME actions that end
 * the edit session. Pass [TvFieldSlot.modifier] to the field's own `modifier` (it can be chained
 * with the field's visual modifiers) and forward the keyboard options/actions unchanged.
 */
class TvFieldSlot internal constructor(
    val modifier: Modifier,
    val keyboardOptions: KeyboardOptions,
    val keyboardActions: KeyboardActions,
)

/**
 * Gate a text field behind an explicit OK press. See [TvFieldEditState] for the rule.
 *
 * [keepFocusOnExit] picks what Back does while editing:
 *  - true (default, settings lists): hide the IME, end the edit session and keep D-pad focus on the
 *    same field, so the remote never jumps to a neighbouring setting on its own;
 *  - false (dialogs): leave Back to the field's own `leaveFieldOnBack()`, which walks focus on to
 *    the dialog buttons - the behaviour the upstream TV-IME fix installs.
 *
 * [onImeDone] runs first and the edit session always ends afterwards, so the IME cannot be left
 * open over a field the user is finished with.
 */
@Composable
fun TvFocusGatedField(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: TvFieldEditState = remember { TvFieldEditState() },
    imeAction: ImeAction = ImeAction.Done,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = imeAction),
    keepFocusOnExit: Boolean = true,
    onImeDone: (() -> Unit)? = null,
    field: @Composable (TvFieldSlot) -> Unit,
) {
    val innerFocus = remember { FocusRequester() }
    val gateFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    var gateFocused by remember { mutableStateOf(false) }

    val exitEdit: () -> Unit = {
        runCatching { keyboard?.hide() }
        state.onEditFinished()
        if (keepFocusOnExit) runCatching { gateFocus.requestFocus() }
    }

    // The edit session is what opens the IME: the wrapped field only becomes focusable now, and
    // focusing it is the one thing that makes the platform show the keyboard.
    LaunchedEffect(state.editing) {
        if (state.editing) runCatching { innerFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .focusRequester(gateFocus)
            .onFocusChanged { gateFocused = it.isFocused }
            // OK / DPAD_CENTER (and a mouse click, so touch input keeps working) - Clickable also
            // makes the gate the D-pad focus target, which is the whole point of the split.
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = { state.onConfirm(enabled) },
            )
            .then(
                if (gateFocused && enabled) {
                    Modifier.border(2.dp, AppColors.AccentGreen, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        field(
            TvFieldSlot(
                modifier = Modifier
                    .focusRequester(innerFocus)
                    .focusProperties { canFocus = state.innerFocusable(enabled) }
                    .then(if (keepFocusOnExit) Modifier.exitEditOnBack(exitEdit) else Modifier)
                    .onFocusChanged { if (!it.isFocused) state.onFocusLost() },
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(
                    onDone = {
                        onImeDone?.invoke()
                        exitEdit()
                    },
                ),
            ),
        )
        if (!state.editing && enabled) {
            // Touch / mouse: a tap is the same intent as pressing OK. This overlay sits above the
            // field so the field's own pointer handling cannot swallow the tap.
            Box(
                Modifier
                    .matchParentSize()
                    .background(androidx.compose.ui.graphics.Color.Transparent)
                    .pointerInput(enabled) {
                        detectTapGestures { state.onConfirm(enabled) }
                    },
            )
        }
    }
}
