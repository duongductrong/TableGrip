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

/** A group header the way SchemaCompareView/ImportConnections use it: the state comes from `selected` of `total` children and a click selects all unless all are selected. Reports `state` (on/off/indeterminate) and `selected`. */
object TriStateCheckboxSubject : ConformanceSubject {
    override val component = "tri-state-checkbox"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val total = setup["total"]?.jsonPrimitive?.int ?: 3
        var selected by remember { mutableStateOf(setup["selected"]?.jsonPrimitive?.int ?: 0) }
        val state = when (selected) { 0 -> ToggleableState.Off; total -> ToggleableState.On; else -> ToggleableState.Indeterminate }
        probe.readers["state"] = { state.name.lowercase() }
        probe.readers["selected"] = { selected.toString() }
        TriStateCheckbox(state, onClick = { selected = if (state != ToggleableState.On) total else 0 }, modifier = Modifier.testTag(SubjectTag))
    }
}
