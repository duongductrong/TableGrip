package dev.tablegrip.app.conformance.subjects

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The key names scenario files use, as Compose keys; subjects that consume or watch keys read them from setup. */
internal fun composeKey(name: String): Key =
    when (name) {
        "Space" -> Key.Spacebar
        "Enter" -> Key.Enter
        "Tab" -> Key.Tab
        "Escape" -> Key.Escape
        "Backspace" -> Key.Backspace
        "Delete" -> Key.Delete
        "Home" -> Key.MoveHome
        "End" -> Key.MoveEnd
        "Left" -> Key.DirectionLeft
        "Right" -> Key.DirectionRight
        "Up" -> Key.DirectionUp
        "Down" -> Key.DirectionDown
        "PageUp" -> Key.PageUp
        "PageDown" -> Key.PageDown
        "F2" -> Key.F2
        "Equals" -> Key.Equals
        "Minus" -> Key.Minus
        "Slash" -> Key.Slash
        "Comma" -> Key.Comma
        "Period" -> Key.Period
        "LeftBracket" -> Key.LeftBracket
        "RightBracket" -> Key.RightBracket
        "NumpadEnter" -> Key.NumPadEnter
        "NumpadAdd" -> Key.NumPadAdd
        "NumpadSubtract" -> Key.NumPadSubtract
        else ->
            when {
                name.length == 1 && name[0] in 'A'..'Z' -> Key(java.awt.event.KeyEvent.VK_A + (name[0] - 'A'))
                name.length == 6 && name.startsWith("Digit") && name[5] in '0'..'9' -> Key(java.awt.event.KeyEvent.VK_0 + (name[5] - '0'))
                else -> error("Unknown key '$name'")
            }
    }

internal fun JsonObject.keys(name: String): Set<Key> = ((this[name] as? JsonArray).orEmpty().map { composeKey(it.jsonPrimitive.content) }).toSet()

internal fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
