package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.ui.UiZoom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.ui.component.Text

/**
 * The interface zoom with its keyboard shortcuts replaced by three tagged targets that do what the shortcuts do:
 * `in` (zoom in a step), `out` and `reset`. `zoom` is the stored percentage the app starts from (default 100,
 * possibly a value typed into settings.json by hand). Reports `zoom` (the stored percentage), `level` (the level the
 * interface is drawn at, the stored value snapped to the nearest level), and `controlShown` (whether the status bar
 * shows its zoom control, which it does only away from 100%).
 */
object ZoomSubject : ConformanceSubject {
    override val component = "zoom"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        var zoom by remember { mutableStateOf((setup["zoom"] as? JsonPrimitive)?.content?.toInt() ?: UiZoom.Default) }
        probe.readers["zoom"] = { zoom.toString() }
        probe.readers["level"] = { UiZoom.snap(zoom).toString() }
        probe.readers["controlShown"] = { (UiZoom.snap(zoom) != UiZoom.Default).toString() }
        Column(Modifier.testTag(SubjectTag)) {
            // The shortcuts step from the stored value as it is, as `handleZoomShortcut` does.
            Text("in", Modifier.testTag("in").clickable { zoom = UiZoom.step(zoom, 1) }.padding(8.dp))
            Text("out", Modifier.testTag("out").clickable { zoom = UiZoom.step(zoom, -1) }.padding(8.dp))
            Text("reset", Modifier.testTag("reset").clickable { zoom = UiZoom.Default }.padding(8.dp))
        }
    }
}
