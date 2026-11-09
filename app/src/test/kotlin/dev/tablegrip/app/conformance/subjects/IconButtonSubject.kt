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

/** `Tooltip({ Text(label) }) { IconButton(...) { Icon } }`, the app's dominant idiom. Reports `clicks`; the tooltip text is tagged `tip`. */
object IconButtonSubject : ConformanceSubject {
    override val component = "icon-button"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        var clicks by remember { mutableStateOf(0) }
        probe.readers["clicks"] = { clicks.toString() }
        val label = setup.string("label", "Run")
        Tooltip({ Text(label, Modifier.testTag("tip")) }) {
            IconButton(onClick = { clicks++ }, enabled = setup.bool("enabled", true), modifier = Modifier.size(24.dp).testTag(SubjectTag)) {
                Icon(AllIconsKeys.Actions.Execute, label, Modifier.size(14.dp))
            }
        }
    }
}
