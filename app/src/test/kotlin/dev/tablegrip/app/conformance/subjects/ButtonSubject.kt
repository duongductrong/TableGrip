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
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** DefaultButton (`variant: default`) or OutlinedButton (`variant: outlined`). Reports `clicks`. */
object ButtonSubject : ConformanceSubject {
    override val component = "button"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        var clicks by remember { mutableStateOf(0) }
        probe.readers["clicks"] = { clicks.toString() }
        val enabled = setup.bool("enabled", true)
        val label = setup.string("label", "OK")
        when (setup.string("variant", "default")) {
            "outlined" -> OutlinedButton(onClick = { clicks++ }, enabled = enabled, modifier = Modifier.testTag(SubjectTag)) { Text(label) }
            else -> DefaultButton(onClick = { clicks++ }, enabled = enabled, modifier = Modifier.testTag(SubjectTag)) { Text(label) }
        }
    }
}
