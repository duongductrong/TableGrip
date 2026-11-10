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

/** The circles are drawn curves, so each has an `icon:` region (compared loosely) over it, as the Phase 1 icons do.
 * RadioButtonRow at rest, selected, hovered, focused, disabled and in error. */
@OptIn(ExperimentalTestApi::class)
object RadioSpecimen : GallerySpecimen {
    override val name = "radio"
    override val size = DpSize(464.dp, 76.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(464.dp, 76.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { RadioButtonRow(false, {}, Modifier.testTag("radio.rest")) { Text("Read-only", Modifier.testTag("text:radio.rest")) } }
                Box(Modifier.offset(16.dp, 12.dp).size(24.dp).testTag("icon:radio.rest"))
                Box(Modifier.offset(128.dp, 12.dp)) { RadioButtonRow(true, {}, Modifier.testTag("radio.selected")) { Text("Read-only", Modifier.testTag("text:radio.selected")) } }
                Box(Modifier.offset(128.dp, 12.dp).size(24.dp).testTag("icon:radio.selected"))
                Box(Modifier.offset(240.dp, 12.dp)) { RadioButtonRow(false, {}, Modifier.testTag("radio.hovered")) { Text("Read-only", Modifier.testTag("text:radio.hovered")) } }
                Box(Modifier.offset(240.dp, 12.dp).size(24.dp).testTag("icon:radio.hovered"))
                Box(Modifier.offset(352.dp, 12.dp)) { RadioButtonRow(true, {}, Modifier.testTag("radio.focused")) { Text("Read-only", Modifier.testTag("text:radio.focused")) } }
                Box(Modifier.offset(352.dp, 12.dp).size(24.dp).testTag("icon:radio.focused"))
                Box(Modifier.offset(16.dp, 44.dp)) { RadioButtonRow(false, {}, Modifier.testTag("radio.disabled"), enabled = false) { Text("Read-only", Modifier.testTag("text:radio.disabled")) } }
                Box(Modifier.offset(16.dp, 44.dp).size(24.dp).testTag("icon:radio.disabled"))
                Box(Modifier.offset(128.dp, 44.dp)) { RadioButtonRow(true, {}, Modifier.testTag("radio.disabled.selected"), enabled = false) { Text("Read-only", Modifier.testTag("text:radio.disabled.selected")) } }
                Box(Modifier.offset(128.dp, 44.dp).size(24.dp).testTag("icon:radio.disabled.selected"))
                Box(Modifier.offset(240.dp, 44.dp)) { RadioButtonRow(false, {}, Modifier.testTag("radio.error"), outline = Outline.Error) { Text("Read-only", Modifier.testTag("text:radio.error")) } }
                Box(Modifier.offset(240.dp, 44.dp).size(24.dp).testTag("icon:radio.error"))
                Box(Modifier.offset(352.dp, 44.dp)) { RadioButtonRow(true, {}, Modifier.testTag("radio.error.selected"), outline = Outline.Error) { Text("Read-only", Modifier.testTag("text:radio.error.selected")) } }
                Box(Modifier.offset(352.dp, 44.dp).size(24.dp).testTag("icon:radio.error.selected"))
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("radio.hovered").performMouseInput { moveTo(center) }
        onNodeWithTag("radio.focused").requestFocus()
    }
}
