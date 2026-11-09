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

/** DefaultButton and OutlinedButton at rest, hovered, focused and disabled (one pointer and one focus, so each shown once). */
@OptIn(ExperimentalTestApi::class)
object ButtonSpecimen : GallerySpecimen {
    override val name = "button"
    override val size = DpSize(344.dp, 92.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(344.dp, 92.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { DefaultButton({}, Modifier.testTag("button.default.rest")) { Text("Save", Modifier.testTag("text:button.default.rest")) } }
                Box(Modifier.offset(96.dp, 12.dp)) { DefaultButton({}, Modifier.testTag("button.default.hovered")) { Text("Save", Modifier.testTag("text:button.default.hovered")) } }
                Box(Modifier.offset(176.dp, 12.dp)) { DefaultButton({}, Modifier.testTag("button.default.disabled"), enabled = false) { Text("Save", Modifier.testTag("text:button.default.disabled")) } }
                Box(Modifier.offset(16.dp, 52.dp)) { OutlinedButton({}, Modifier.testTag("button.outlined.rest")) { Text("Cancel", Modifier.testTag("text:button.outlined.rest")) } }
                Box(Modifier.offset(96.dp, 52.dp)) { OutlinedButton({}, Modifier.testTag("button.outlined.focused")) { Text("Cancel", Modifier.testTag("text:button.outlined.focused")) } }
                Box(Modifier.offset(176.dp, 52.dp)) { OutlinedButton({}, Modifier.testTag("button.outlined.disabled"), enabled = false) { Text("Cancel", Modifier.testTag("text:button.outlined.disabled")) } }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("button.default.hovered").performMouseInput { moveTo(center) }
        onNodeWithTag("button.outlined.focused").requestFocus()
    }
}
