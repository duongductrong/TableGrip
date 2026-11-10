package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.component.Link

/** Links at rest, disabled, hovered and focused. The text is a text region; underline and focus ring are compared. */
@OptIn(ExperimentalTestApi::class)
object LinkSpecimen : GallerySpecimen {
    override val name = "link"
    override val size = DpSize(320.dp, 72.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(320.dp, 72.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { Link("Edit as SQL", {}, Modifier.testTag("text:link")) }
                Box(Modifier.offset(168.dp, 12.dp)) { Link("Edit as SQL", {}, Modifier.testTag("text:link.disabled"), enabled = false) }
                Box(Modifier.offset(16.dp, 40.dp)) { Link("Edit as SQL", {}, Modifier.testTag("text:link.hovered")) }
                Box(Modifier.offset(168.dp, 40.dp)) { Link("Edit as SQL", {}, Modifier.testTag("text:link.focused")) }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("text:link.focused").requestFocus()
        onNodeWithTag("text:link.hovered").performMouseInput { moveTo(center) }
    }
}
