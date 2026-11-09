package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinux
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.component.styling.TrackClickBehavior
import org.jetbrains.jewel.ui.theme.scrollbarStyle

/**
 * Scroll containers with the always-visible scrollbar KeyBrowserView uses (8dp track), 160dp tall over 20 rows of
 * text: one at the top, one scrolled to the middle with the pointer over its thumb, one scrolled to the end.
 */
@OptIn(ExperimentalTestApi::class)
object ScrollbarSpecimen : GallerySpecimen {
    override val name = "scrollbar"
    override val size = DpSize(240.dp, 184.dp)

    private val Positions = listOf("top", "middle", "end")

    @Composable
    override fun Content() {
        val base = JewelTheme.scrollbarStyle
        val style =
            ScrollbarStyle(
                colors = base.colors,
                metrics = base.metrics,
                trackClickBehavior = TrackClickBehavior.JumpToSpot,
                scrollbarVisibility = ScrollbarVisibility.AlwaysVisible.windowsAndLinux(trackThickness = 8.dp, trackPadding = PaddingValues(horizontal = 1.dp)),
            )
        Island(Modifier.size(240.dp, 184.dp)) {
            Positions.forEachIndexed { i, p ->
                val state = rememberScrollState()
                LaunchedEffect(state.maxValue) {
                    when (p) {
                        "middle" -> state.scrollTo(state.maxValue / 2)
                        "end" -> state.scrollTo(state.maxValue)
                    }
                }
                Box(Modifier.offset((12 + 76 * i).dp, 12.dp).size(64.dp, 160.dp).testTag("scroll.$p")) {
                    Column(Modifier.fillMaxSize().verticalScroll(state).padding(end = 10.dp)) {
                        repeat(20) { Text("Row $it", Modifier.height(20.dp).testTag("text:scroll.$p.$it")) }
                    }
                    VerticalScrollbar(state, Modifier.align(Alignment.TopEnd).fillMaxHeight().testTag("track.$p"), style = style)
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        // The thumb of the middle one, about 40% of the way down its 160dp track.
        onNodeWithTag("scroll.middle").performMouseInput { moveTo(Offset(width - 4f * 2, 80f * 2)) }
    }

    override fun measure(m: Measure) = m.size("scroll.top")
}
