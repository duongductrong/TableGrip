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
        else -> if (name.length == 1 && name[0] in 'A'..'Z') Key(java.awt.event.KeyEvent.VK_A + (name[0] - 'A')) else error("Unknown key '$name'")
    }

internal fun JsonObject.keys(name: String): Set<Key> = ((this[name] as? JsonArray).orEmpty().map { composeKey(it.jsonPrimitive.content) }).toSet()

internal fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
