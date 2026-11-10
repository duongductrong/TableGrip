package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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

/** A tooltip over its target (`target`: `icon-button` or `text`). The tooltip text is tagged `tip`. */
object TooltipSubject : ConformanceSubject {
    override val component = "tooltip"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        Row {
            Tooltip({ Text("Hint", Modifier.testTag("tip")) }) {
                when (setup.string("target", "icon-button")) {
                    "text" -> Text("Target", Modifier.testTag(SubjectTag))
                    else -> IconButton(onClick = {}, modifier = Modifier.size(24.dp).testTag(SubjectTag)) { Icon(AllIconsKeys.Actions.Execute, "Run", Modifier.size(14.dp)) }
                }
            }
            Text("Elsewhere", Modifier.padding(start = 80.dp).testTag("away"))
        }
    }
}
