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
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** The states ButtonSpecimen cannot show at the same time: DefaultButton focused, OutlinedButton hovered. */
@OptIn(ExperimentalTestApi::class)
object ButtonAltSpecimen : GallerySpecimen {
    override val name = "button-alt"
    override val size = DpSize(184.dp, 92.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(184.dp, 92.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { DefaultButton({}, Modifier.testTag("button.default.focused")) { Text("Save", Modifier.testTag("text:button.default.focused")) } }
                Box(Modifier.offset(16.dp, 52.dp)) { OutlinedButton({}, Modifier.testTag("button.outlined.hovered")) { Text("Cancel", Modifier.testTag("text:button.outlined.hovered")) } }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("button.default.focused").requestFocus()
        onNodeWithTag("button.outlined.hovered").performMouseInput { moveTo(center) }
    }
}
