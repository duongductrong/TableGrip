package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.TrackClickBehavior
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A PopupMenu opened from a clickable trigger, the way StatusBar uses it: `open` is the app's own boolean, an item
 * click closes it, and `onDismissRequest` closes it. Items are tagged `item:0`…; `disabled` lists indexes shown with
 * `enabled = false` and `separatorsAfter` the indexes followed by a separator. Reports `chosen` (last chosen index,
 * -1 none) and `open`.
 */
object PopupMenuSubject : ConformanceSubject {
    override val component = "popup-menu"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val labels = setup["items"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("Auto-commit", "Manual commit", "Commit", "Roll Back")
        val disabled = setup["disabled"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
        val separatorsAfter = setup["separatorsAfter"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()
        var open by remember { mutableStateOf(setup.bool("open")) }
        var chosen by remember { mutableIntStateOf(-1) }
        probe.readers["chosen"] = { chosen.toString() }
        probe.readers["open"] = { open.toString() }
        Column {
            Row(Modifier.testTag(SubjectTag).pointerInput(Unit) { detectTapGestures { open = true } }.padding(4.dp)) { Text("Menu") }
            Text("Elsewhere", Modifier.padding(top = 120.dp, start = 300.dp).testTag("away"))
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End, modifier = Modifier.testTag("menu")) {
                labels.forEachIndexed { i, label ->
                    selectableItem(selected = i == (setup["selected"]?.jsonPrimitive?.int ?: 0), enabled = i !in disabled, onClick = { open = false; chosen = i }) { Text(label, Modifier) }
                    if (i in separatorsAfter) separator()
                }
            }
        }
    }
}
