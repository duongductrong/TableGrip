package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.TextField

/** A single-line TextField over a TextFieldState. Reports `text`. */
object TextFieldSubject : ConformanceSubject {
    override val component = "text-field"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val state = remember { TextFieldState(setup.string("text")) }
        probe.readers["text"] = { state.text.toString() }
        TextField(state, Modifier.width(220.dp).testTag(SubjectTag), enabled = setup.bool("enabled", true))
    }
}
