package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import dev.tablegrip.app.ui.KeymapPreset
import dev.tablegrip.app.ui.Shortcuts
import kotlinx.serialization.json.JsonObject

/**
 * The main window's key handling over the app's own [Shortcuts]: a window that tries shortcuts in two passes, like
 * `Main.kt` does. The actions named in `preview` run in the preview pass (before the focused control sees the key),
 * all the others in the bubble pass (only for keys the focused control declined). The focused control, tagged `inner`,
 * consumes the keys named in `inner`, as a text field consumes its characters. `preset` is the keymap preset
 * (TableGrip, IdeClassic or VsCode); with `recording`, Settings → Keymap is recording a key and the window stands
 * aside. Scenarios `focus` first. Reports `fired` (ids of the actions the last handled key ran, comma separated),
 * `fires` (how many keys ran an action) and `innerHits` (how many keys the inner control consumed).
 */
object KeymapSubject : ConformanceSubject {
    override val component = "keymap"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val recording = setup.bool("recording")
        val preset = KeymapPreset.of(setup.string("preset", "Default"))
        DisposableEffect(preset, recording) {
            Shortcuts.configure(preset, emptyMap())
            Shortcuts.recording = recording
            onDispose {
                Shortcuts.configure(KeymapPreset.Default, emptyMap())
                Shortcuts.recording = false
            }
        }
        val previewIds = setup.strings("preview").toSet()
        val innerKeys = setup.keys("inner")
        var fired by remember { mutableStateOf("") }
        var fires by remember { mutableStateOf(0) }
        var innerHits by remember { mutableStateOf(0) }
        probe.readers["fired"] = { fired }
        probe.readers["fires"] = { fires.toString() }
        probe.readers["innerHits"] = { innerHits.toString() }

        fun run(event: KeyEvent, preview: Boolean): Boolean {
            if (Shortcuts.recording) return false
            val ids = Shortcuts.actions.filter { (it.id in previewIds) == preview && Shortcuts.current(it).matches(event) }.map { it.id }
            if (ids.isEmpty()) return false
            fired = ids.joinToString(",")
            fires++
            return true
        }
        Box(
            Modifier.testTag(SubjectTag)
                .onPreviewKeyEvent { run(it, preview = true) }
                .onKeyEvent { run(it, preview = false) },
        ) {
            Box(
                Modifier.size(40.dp).testTag("inner")
                    .onKeyEvent { event ->
                        (event.type == KeyEventType.KeyDown && event.key in innerKeys).also { if (it) innerHits++ }
                    }
                    .focusable(),
            )
        }
    }
}
