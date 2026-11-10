package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
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
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A checkbox row pressed. */
@OptIn(ExperimentalTestApi::class)
object CheckboxPressedSpecimen : GallerySpecimen {
    override val name = "checkbox-pressed"
    override val size = DpSize(144.dp, 44.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(144.dp, 44.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { CheckboxRow(false, {}, Modifier.testTag("checkbox.pressed")) { Text("Read-only", Modifier.testTag("text:checkbox.pressed")) } }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("checkbox.pressed").performMouseInput { moveTo(center); press() }
    }
}
