package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/** Dividers inside an island: a full-width rule, a full-height one and the 18dp toolbar separator. */
object DividerSpecimen : GallerySpecimen {
    override val name = "divider"
    override val size = DpSize(240.dp, 96.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(240.dp, 96.dp)) {
            Box {
                Divider(Orientation.Horizontal, Modifier.offset(0.dp, 24.dp).width(240.dp).testTag("divider.horizontal"))
                Divider(Orientation.Vertical, Modifier.offset(120.dp, 25.dp).height(71.dp).testTag("divider.vertical"))
                Divider(Orientation.Vertical, Modifier.offset(180.dp, 51.dp).height(18.dp).testTag("divider.toolbar"))
            }
        }
    }

    override fun measure(m: Measure) {
        for (tag in listOf("divider.horizontal", "divider.vertical", "divider.toolbar")) {
            m.size(tag)
            val b = m.bounds(tag)
            m.color(tag, b.center.x, b.center.y)
        }
    }
}
