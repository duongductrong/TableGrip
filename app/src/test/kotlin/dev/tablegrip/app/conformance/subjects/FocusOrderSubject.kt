package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A button, with a disabled button before (`disabledBefore`) and/or after (`disabledAfter`) it, in the focus chain. */
object FocusOrderSubject : ConformanceSubject {
    override val component = "focus-order"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        Row(Modifier.testTag(SubjectTag)) {
            if (setup.bool("disabledBefore")) OutlinedButton(onClick = {}, enabled = false) { Text("Off") }
            OutlinedButton(onClick = {}) { Text("On") }
            if (setup.bool("disabledAfter")) OutlinedButton(onClick = {}, enabled = false) { Text("Off") }
        }
    }
}
