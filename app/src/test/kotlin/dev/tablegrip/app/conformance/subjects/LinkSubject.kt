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
import org.jetbrains.jewel.ui.component.Link

/** `Link(text, onClick)`, the only way the app uses it, optionally disabled. Reports `clicks`. */
object LinkSubject : ConformanceSubject {
    override val component = "link"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        var clicks by remember { mutableStateOf(0) }
        probe.readers["clicks"] = { clicks.toString() }
        Link(setup.string("label", "Edit as SQL"), onClick = { clicks++ }, modifier = Modifier.testTag(SubjectTag), enabled = setup.bool("enabled", true))
    }
}
