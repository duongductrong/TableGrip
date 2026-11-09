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
 * A VerticalScrollbar over a ScrollState, as in KeyBrowserView (`behavior: jump` is its `TrackClickBehavior.JumpToSpot`
 * style, otherwise Jewel's default). The track is tagged `track`; viewport is 200dp, content `content` dp (default 1000),
 * scrolled to `scroll` percent. Reports `scroll` (percent of the way to the end, rounded) and `max` (px / density).
 */
object ScrollbarSubject : ConformanceSubject {
    override val component = "scrollbar"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val content = setup["content"]?.jsonPrimitive?.int ?: 1000
        val state = rememberScrollState()
        val initial = setup["scroll"]?.jsonPrimitive?.int ?: 0
        androidx.compose.runtime.LaunchedEffect(state.maxValue) { if (initial > 0 && state.maxValue > 0) state.scrollTo(state.maxValue * initial / 100) }
        probe.readers["scroll"] = { if (state.maxValue == 0) "0" else Math.round(state.value * 100f / state.maxValue).toString() }
        probe.readers["max"] = { state.maxValue.toString() }
        val base = JewelTheme.scrollbarStyle
        val style =
            if (setup.string("behavior") == "jump")
                ScrollbarStyle(colors = base.colors, metrics = base.metrics, trackClickBehavior = TrackClickBehavior.JumpToSpot, scrollbarVisibility = ScrollbarVisibility.AlwaysVisible.windowsAndLinux(trackThickness = 8.dp, trackPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 1.dp)))
            else base
        Box(Modifier.height(200.dp).width(120.dp).testTag(SubjectTag)) {
            Column(Modifier.verticalScroll(state)) { Box(Modifier.height(content.dp).fillMaxWidth()) }
            VerticalScrollbar(state, Modifier.align(Alignment.TopEnd).fillMaxHeight().testTag("track"), style = style)
        }
    }
}
