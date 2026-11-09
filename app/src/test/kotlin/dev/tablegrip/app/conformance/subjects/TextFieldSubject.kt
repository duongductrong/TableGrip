package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.connections.MaskPassword
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.text.BreakIterator
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

/**
 * A single-line TextField over a TextFieldState, as the app uses it. Setup: `text`, `enabled`, `readOnly`,
 * `placeholder`, `password` (the app's `MaskPassword`), `clipboard` (what the
 * clipboard holds at the start). Reports what [textProbe] reports.
 */
object TextFieldSubject : ConformanceSubject {
    override val component = "text-field"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) = TextSubject(setup, probe, multiLine = false)
}

/**
 * The field inside a parent that records the keys the field lets through, on a clipboard the test owns. Reports `text`,
 * `selected` (the selected text), `placeholder` (whether it shows), `caret` (characters before the caret, counted as a user sees them), `clipboard`, and
 * `bubbled`: the key presses that reached the parent, as `mods+Key` joined by spaces.
 */
@Composable
internal fun TextSubject(setup: JsonObject, probe: Probe, multiLine: Boolean) {
    val state = remember { TextFieldState(setup.string("text")) }
    val clipboard = remember { FakeClipboard(setup.string("clipboard").takeIf { "clipboard" in setup }) }
    val bubbled = remember { mutableListOf<String>() }
    probe.readers["text"] = { state.text.toString() }
    probe.readers["selected"] = { state.text.substring(state.selection.min, state.selection.max) }
    probe.readers["caret"] = { graphemes(state.text.substring(0, state.selection.end)).toString() }
    probe.readers["clipboard"] = { clipboard.text.orEmpty() }
    probe.readers["bubbled"] = { bubbled.joinToString(" ") }
    val enabled = setup.bool("enabled", true)
    val readOnly = setup.bool("readOnly")
    // The placeholder sits inside the field's merged semantics, so it reports itself while it's in the composition.
    val placeholderShown = remember { mutableStateOf(false) }
    probe.readers["placeholder"] = { placeholderShown.value.toString() }
    val placeholder: (@Composable () -> Unit)? =
        setup.string("placeholder").takeIf { it.isNotEmpty() }?.let { label ->
            {
                DisposableEffect(Unit) {
                    placeholderShown.value = true
                    onDispose { placeholderShown.value = false }
                }
                Text(label)
            }
        }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown) it.describe()?.let(bubbled::add); false }) {
            if (multiLine) {
                TextArea(state, Modifier.width(220.dp).height(80.dp).testTag(SubjectTag), enabled = enabled, readOnly = readOnly, placeholder = placeholder)
            } else {
                TextField(
                    state,
                    Modifier.width(220.dp).testTag(SubjectTag),
                    enabled = enabled,
                    readOnly = readOnly,
                    placeholder = placeholder,
                    outputTransformation = if (setup.bool("password")) MaskPassword else null,
                )
            }
        }
    }
}

private fun graphemes(text: String): Int {
    val it = BreakIterator.getCharacterInstance().apply { setText(text) }
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

private fun KeyEvent.describe(): String? {
    if (key in Modifiers) return null
    val primary = if (hostOs == OS.MacOS) isMetaPressed else isCtrlPressed
    val mods = listOfNotNull("primary".takeIf { primary }, "shift".takeIf { isShiftPressed }, "alt".takeIf { isAltPressed })
    return (mods + keyName(key)).joinToString("+")
}

private fun keyName(key: Key): String =
    when (key) {
        Key.Spacebar -> "Space"
        Key.Enter -> "Enter"
        Key.Tab -> "Tab"
        Key.Escape -> "Escape"
        Key.Backspace -> "Backspace"
        Key.Delete -> "Delete"
        Key.MoveHome -> "Home"
        Key.MoveEnd -> "End"
        Key.DirectionLeft -> "Left"
        Key.DirectionRight -> "Right"
        Key.DirectionUp -> "Up"
        Key.DirectionDown -> "Down"
        Key.PageUp -> "PageUp"
        Key.PageDown -> "PageDown"
        Key.F2 -> "F2"
        else -> Letters.indexOf(key).takeIf { it >= 0 }?.let { ('A' + it).toString() } ?: key.toString()
    }

private val Letters =
    listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )

private val Modifiers = setOf(Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight, Key.AltLeft, Key.AltRight)

/** A clipboard held by the test, so scenarios never touch the system one. */
@OptIn(ExperimentalComposeUiApi::class)
private class FakeClipboard(var text: String?) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = text?.let { ClipEntry(StringSelection(it)) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? java.awt.datatransfer.Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
