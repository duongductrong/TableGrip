package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.string
import dev.tablegrip.app.workspace.restoredAgentSplit
import dev.tablegrip.app.workspace.restoredSidebarSplit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.styling.DividerStyle

/**
 * The main window's HorizontalSplitLayout with an invisible divider, in a box `width` dp wide (default 800).
 * `ratio` is the divider position it starts at (default 0.5), `firstMin`/`secondMin` the panes' minimum widths in dp;
 * `restore` (`sidebar` or `agent`) first passes `saved` through the clamp SessionKeeper applies when it puts a saved
 * position back. Reports `first` and `second` (pane widths in dp) and `ratio` (the divider position as a percentage,
 * rounded).
 */
object SplitSubject : ConformanceSubject {
    override val component = "split"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        fun num(name: String, default: Double) = (setup[name] as? JsonPrimitive)?.content?.toDouble() ?: default
        val width = num("width", 800.0).toInt()
        var ratio = num("ratio", 0.5).toFloat()
        val saved = num("saved", -1.0).toFloat()
        when (setup.string("restore")) {
            "sidebar" -> ratio = restoredSidebarSplit(saved)
            "agent" -> ratio = restoredAgentSplit(saved)
        }
        val state = remember { SplitLayoutState(ratio) }
        var first by remember { mutableIntStateOf(-1) }
        var second by remember { mutableIntStateOf(-1) }
        probe.readers["first"] = { first.toString() }
        probe.readers["second"] = { second.toString() }
        probe.readers["ratio"] = { Math.round(state.dividerPosition * 100f).toString() }
        Box(Modifier.width(width.dp).height(100.dp).testTag(SubjectTag)) {
            HorizontalSplitLayout(
                first = { Box(Modifier.fillMaxSize().onSizeChanged { first = it.width }) },
                second = { Box(Modifier.fillMaxSize().onSizeChanged { second = it.width }) },
                modifier = Modifier.fillMaxSize(),
                dividerStyle = DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)),
                draggableWidth = num("drag", 6.0).dp,
                firstPaneMinWidth = num("firstMin", 0.0).dp,
                secondPaneMinWidth = num("secondMin", 0.0).dp,
                state = state,
            )
        }
    }
}
