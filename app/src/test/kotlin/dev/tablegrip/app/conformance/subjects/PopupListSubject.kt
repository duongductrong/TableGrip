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
import org.jetbrains.jewel.ui.theme.treeStyle
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinux

/**
 * Hand-drawn popup list rows as the app draws them (ModelPicker, ChatHistoryList): the owner takes Up/Down (wrapping)
 * and Enter, a row is painted when highlighted or hovered, a click picks. Rows are tagged `row:0`….
 * Reports `highlighted`, `hovered` (-1 none), `painted` (the rows drawn in the highlight colour, comma separated) and `picked`.
 */
object PopupListSubject : ConformanceSubject {
    override val component = "popup-list"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val count = setup["rows"]?.jsonPrimitive?.int ?: 4
        var highlighted by remember { mutableIntStateOf(0) }
        var picked by remember { mutableIntStateOf(-1) }
        var hovered by remember { mutableIntStateOf(-1) }
        probe.readers["highlighted"] = { highlighted.toString() }
        probe.readers["picked"] = { picked.toString() }
        probe.readers["hovered"] = { hovered.toString() }
        probe.readers["painted"] = { (0 until count).filter { it == highlighted || it == hovered }.joinToString(",") }
        val last = count - 1
        Column(
            Modifier.width(240.dp).testTag(SubjectTag).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> true.also { highlighted = if (highlighted >= last) 0 else highlighted + 1 }
                    Key.DirectionUp -> true.also { highlighted = if (highlighted <= 0) last else highlighted - 1 }
                    Key.Enter -> true.also { picked = highlighted }
                    else -> false
                }
            }.focusable()
        ) {
            repeat(count) { i ->
                val interaction = remember { MutableInteractionSource() }
                val isHovered by interaction.collectIsHoveredAsState()
                if (isHovered) hovered = i else if (hovered == i) hovered = -1
                Row(
                    Modifier.fillMaxWidth().height(28.dp).testTag("row:$i")
                        .background(if (i == highlighted || isHovered) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
                        .hoverable(interaction)
                        .clickable { picked = i; highlighted = i }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("Row $i") }
            }
        }
    }
}
