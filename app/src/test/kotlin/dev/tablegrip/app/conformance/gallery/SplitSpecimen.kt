package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle

/** Two islands in a split as `MainScreen` lays out the sidebar: an invisible divider, the 6dp gap is the handle. */
object SplitSpecimen : GallerySpecimen {
    override val name = "split"
    override val size = DpSize(300.dp, 120.dp)

    @Composable
    override fun Content() {
        Box(Modifier.size(300.dp, 120.dp).padding(6.dp)) {
            HorizontalSplitLayout(
                first = { Island(Modifier.fillMaxSize().padding(end = 3.dp)) {} },
                second = { Island(Modifier.fillMaxSize().padding(start = 3.dp)) {} },
                modifier = Modifier.fillMaxSize(),
                dividerStyle = remember { DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)) },
                draggableWidth = 6.dp,
                state = rememberSplitLayoutState(0.4f),
            )
        }
    }
}
