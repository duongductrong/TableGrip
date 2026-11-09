package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Nested layers of key handlers around one focused control: `layers` lists them from the outermost (the window) to the
 * innermost, which holds focus. Each layer is `{"preview": [keys], "bubble": [keys]}`: the keys its preview handler
 * (runs on the way in, parents first) and its bubble handler (on the way out, children first) consume. Scenarios
 * `focus` first. Reports `trace` (every handler a key reached, `<layer>.<phase>` with layers numbered from 0 at the
 * outermost, in order, for the last key pressed, comma separated) and `consumedBy` (the handler that took it, or empty).
 */
object KeyDispatchSubject : ConformanceSubject {
    override val component = "key-dispatch"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val layers = (setup["layers"] as? JsonArray).orEmpty().map { it.jsonObject }
        var trace by remember { mutableStateOf("") }
        var consumedBy by remember { mutableStateOf("") }
        probe.readers["trace"] = { trace }
        probe.readers["consumedBy"] = { consumedBy }

        // A new key press starts a new trace: the outermost preview handler is always first to see it.
        fun visit(event: KeyEvent, index: Int, phase: String, keys: Set<androidx.compose.ui.input.key.Key>): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val name = "$index.$phase"
            trace = if (index == 0 && phase == "preview") name else "$trace,$name"
            val taken = event.key in keys
            if (taken) consumedBy = name else if (index == 0 && phase == "preview") consumedBy = ""
            return taken
        }

        @Composable
        fun Layer(index: Int, content: @Composable () -> Unit) {
            val config = layers[index]
            val preview = config.keys("preview")
            val bubble = config.keys("bubble")
            Box(
                Modifier.onPreviewKeyEvent { visit(it, index, "preview", preview) }.onKeyEvent { visit(it, index, "bubble", bubble) },
            ) { content() }
        }

        @Composable
        fun Nest(index: Int) {
            if (index == layers.size) {
                Box(Modifier.size(40.dp).testTag("inner").focusable())
            } else {
                Layer(index) { Nest(index + 1) }
            }
        }
        Box(Modifier.testTag(SubjectTag)) { Nest(0) }
    }
}
