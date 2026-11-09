package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island

/**
 * Islands on the frame: the default (radius from settings, 10dp), the 6dp gap the main screen leaves between
 * islands, and a dialog's explicit 4dp shape.
 */
object IslandSpecimen : GallerySpecimen {
    override val name = "island"
    override val size = DpSize(280.dp, 120.dp)

    @Composable
    override fun Content() {
        Box {
            Island(Modifier.offset(12.dp, 12.dp).size(120.dp, 96.dp).testTag("island")) {}
            Island(Modifier.offset(138.dp, 12.dp).size(60.dp, 96.dp).testTag("island.gap")) {}
            Island(Modifier.offset(212.dp, 12.dp).size(56.dp, 96.dp).testTag("island.dialog"), shape = RoundedCornerShape(4.dp)) {}
        }
    }

    override fun measure(m: Measure) {
        val s = m.scale
        m.size("island")
        val b = m.bounds("island")
        m.color("island.fill", b.center.x, b.center.y)
        m.color("island.edge", b.center.x, b.top + s / 2)
        m.color("frame.background", s * 2, s * 2)
    }
}
