package dev.tablegrip.app.conformance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

const val SubjectTag = "subject"
const val NextTag = "next"

/** What a subject exposes to the runner after the steps: values keyed like a scenario's `expect`. */
class Probe {
    val readers = mutableMapOf<String, () -> String>()
}

/**
 * Hosts the component a scenario file is about, the way the app uses it, with its state in [probe]. `setup.next`
 * adds a second focusable control after it, for focus traversal.
 */
@Composable
fun Subject(component: String, setup: JsonObject, probe: Probe) {
    Column(Modifier.padding(16.dp)) {
        when (component) {
            "checkbox" -> {
                var checked by remember { mutableStateOf(setup.bool("checked")) }
                probe.readers["checked"] = { checked.toString() }
                CheckboxRow(
                    text = setup.string("label", "Option"),
                    checked = checked,
                    onCheckedChange = { checked = it },
                    enabled = setup.bool("enabled", true),
                    modifier = Modifier.testTag(SubjectTag),
                )
            }
            "button" -> {
                var clicks by remember { mutableStateOf(0) }
                probe.readers["clicks"] = { clicks.toString() }
                val enabled = setup.bool("enabled", true)
                val label = setup.string("label", "OK")
                when (setup.string("variant", "default")) {
                    "outlined" -> OutlinedButton(onClick = { clicks++ }, enabled = enabled, modifier = Modifier.testTag(SubjectTag)) { Text(label) }
                    else -> DefaultButton(onClick = { clicks++ }, enabled = enabled, modifier = Modifier.testTag(SubjectTag)) { Text(label) }
                }
            }
            "text-field" -> {
                val state = remember { TextFieldState(setup.string("text")) }
                probe.readers["text"] = { state.text.toString() }
                TextField(state, Modifier.width(220.dp).testTag(SubjectTag), enabled = setup.bool("enabled", true))
            }
            else -> error("No Kotlin subject for component '$component'")
        }
        if (setup.bool("next")) OutlinedButton(onClick = {}, modifier = Modifier.testTag(NextTag)) { Text("Next") }
    }
}
