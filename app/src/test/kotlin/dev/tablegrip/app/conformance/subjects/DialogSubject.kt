package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.ui.isCloseKey
import kotlinx.serialization.json.JsonObject

/**
 * The key handling of `AppDialog`: the dialog's `onKeyEvent` runs the caller's handler first and closes on a bare Esc
 * only if the handler declined and `closeOnEscape` (default true) is set. The dialog's content is a focusable control
 * (`content`) that consumes the keys named in `inner` before the key reaches the dialog; the caller's own handler
 * (`handler`) consumes the keys named there. Scenarios `focus` first. Reports `closed` and `innerHits` / `handlerHits`
 * (how many keys each consumed).
 */
object DialogSubject : ConformanceSubject {
    override val component = "dialog"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val closeOnEscape = if ("closeOnEscape" in setup) setup.bool("closeOnEscape") else true
        val innerKeys = setup.keys("inner")
        val handlerKeys = setup.keys("handler")
        var closed by remember { mutableStateOf(false) }
        var innerHits by remember { mutableStateOf(0) }
        var handlerHits by remember { mutableStateOf(0) }
        probe.readers["closed"] = { closed.toString() }
        probe.readers["innerHits"] = { innerHits.toString() }
        probe.readers["handlerHits"] = { handlerHits.toString() }
        Box(
            Modifier.testTag(SubjectTag).onKeyEvent { event ->
                // AppDialog's own: the caller's handler first, then the close key.
                val taken = event.type == KeyEventType.KeyDown && event.key in handlerKeys
                if (taken) handlerHits++
                taken || (closeOnEscape && isCloseKey(event)).also { close -> if (close) closed = true }
            },
        ) {
            Box(
                Modifier.size(40.dp).testTag("content")
                    .onKeyEvent { event -> (event.type == KeyEventType.KeyDown && event.key in innerKeys).also { if (it) innerHits++ } }
                    .focusable(),
            )
        }
    }
}
