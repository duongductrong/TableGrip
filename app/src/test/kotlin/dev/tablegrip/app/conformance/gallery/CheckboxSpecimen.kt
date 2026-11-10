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

/** Checkbox and TriStateCheckbox rows at rest, checked, indeterminate, hovered, focused, disabled and in error. */
@OptIn(ExperimentalTestApi::class)
object CheckboxSpecimen : GallerySpecimen {
    override val name = "checkbox"
    override val size = DpSize(464.dp, 108.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(464.dp, 108.dp)) {
            Box {
                Box(Modifier.offset(16.dp, 12.dp)) { CheckboxRow(false, {}, Modifier.testTag("checkbox.rest")) { Text("Read-only", Modifier.testTag("text:checkbox.rest")) } }
                Box(Modifier.offset(128.dp, 12.dp)) { CheckboxRow(true, {}, Modifier.testTag("checkbox.checked")) { Text("Read-only", Modifier.testTag("text:checkbox.checked")) } }
                Box(Modifier.offset(240.dp, 12.dp)) { CheckboxRow(false, {}, Modifier.testTag("checkbox.hovered")) { Text("Read-only", Modifier.testTag("text:checkbox.hovered")) } }
                Box(Modifier.offset(352.dp, 12.dp)) { CheckboxRow(true, {}, Modifier.testTag("checkbox.focused")) { Text("Read-only", Modifier.testTag("text:checkbox.focused")) } }
                Box(Modifier.offset(16.dp, 44.dp)) { CheckboxRow(false, {}, Modifier.testTag("checkbox.disabled"), enabled = false) { Text("Read-only", Modifier.testTag("text:checkbox.disabled")) } }
                Box(Modifier.offset(128.dp, 44.dp)) { CheckboxRow(true, {}, Modifier.testTag("checkbox.disabled.checked"), enabled = false) { Text("Read-only", Modifier.testTag("text:checkbox.disabled.checked")) } }
                Box(Modifier.offset(240.dp, 44.dp)) { CheckboxRow(false, {}, Modifier.testTag("checkbox.error"), outline = Outline.Error) { Text("Read-only", Modifier.testTag("text:checkbox.error")) } }
                Box(Modifier.offset(352.dp, 44.dp)) { CheckboxRow(true, {}, Modifier.testTag("checkbox.error.checked"), outline = Outline.Error) { Text("Read-only", Modifier.testTag("text:checkbox.error.checked")) } }
                Box(Modifier.offset(16.dp, 76.dp)) { TriStateCheckboxRow(ToggleableState.Off, {}, Modifier.testTag("tri.off")) { Text("Read-only", Modifier.testTag("text:tri.off")) } }
                Box(Modifier.offset(128.dp, 76.dp)) { TriStateCheckboxRow(ToggleableState.On, {}, Modifier.testTag("tri.on")) { Text("Read-only", Modifier.testTag("text:tri.on")) } }
                Box(Modifier.offset(240.dp, 76.dp)) { TriStateCheckboxRow(ToggleableState.Indeterminate, {}, Modifier.testTag("tri.indeterminate")) { Text("Read-only", Modifier.testTag("text:tri.indeterminate")) } }
                Box(Modifier.offset(352.dp, 76.dp)) { TriStateCheckboxRow(ToggleableState.Indeterminate, {}, Modifier.testTag("tri.indeterminate.disabled"), enabled = false) { Text("Read-only", Modifier.testTag("text:tri.indeterminate.disabled")) } }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("checkbox.hovered").performMouseInput { moveTo(center) }
        onNodeWithTag("checkbox.focused").requestFocus()
    }
}
