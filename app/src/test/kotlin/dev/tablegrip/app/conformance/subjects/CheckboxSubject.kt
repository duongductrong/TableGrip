package dev.tablegrip.app.conformance.subjects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.CheckboxRow

/** A labelled checkbox (CheckboxRow). Reports `checked`. */
object CheckboxSubject : ConformanceSubject {
    override val component = "checkbox"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
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
}
