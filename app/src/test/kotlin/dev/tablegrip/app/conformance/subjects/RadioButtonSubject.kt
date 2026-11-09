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

/** Radio rows the way the app uses them: the app keeps the exclusive state. Rows are tagged `row:0`…; reports `selected` (-1 = none). */
object RadioButtonSubject : ConformanceSubject {
    override val component = "radio-button"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val labels = setup["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("True", "False", "NULL")
        var selected by remember { mutableStateOf(setup["selected"]?.jsonPrimitive?.int ?: -1) }
        probe.readers["selected"] = { selected.toString() }
        Column(Modifier.testTag(SubjectTag)) {
            labels.forEachIndexed { i, label ->
                RadioButtonRow(text = label, selected = selected == i, onClick = { selected = i }, modifier = Modifier.testTag("row:$i"))
            }
        }
    }
}
