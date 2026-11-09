package dev.tablegrip.app.conformance.subjects

import androidx.compose.runtime.Composable
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import kotlinx.serialization.json.JsonObject

/** A multi-line TextArea over a TextFieldState, as the app uses it. Same setup and reports as [TextFieldSubject]. */
object TextAreaSubject : ConformanceSubject {
    override val component = "text-area"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) = TextSubject(setup, probe, multiLine = true)
}
