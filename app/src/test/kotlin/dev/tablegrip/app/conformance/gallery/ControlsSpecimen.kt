package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** Phase 0's specimens: buttons, checkboxes and text fields in the states the golden file measures. */
object ControlsSpecimen : GallerySpecimen {
    override val name = "controls"
    override val size = DpSize(560.dp, 300.dp)

    private val Specimens =
        listOf(
            "button.default", "button.default.disabled", "button.outlined", "button.outlined.disabled",
            "checkbox.unchecked", "checkbox.checked", "checkbox.disabled",
            "textfield", "textfield.disabled", "textfield.error", "textfield.focused", "divider",
        )

    @Composable
    override fun Content() {
        val focus = remember { FocusRequester() }
        Island(Modifier.size(560.dp, 300.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DefaultButton({}, Modifier.testTag("button.default")) { Text("Save") }
                    DefaultButton({}, Modifier.testTag("button.default.disabled"), enabled = false) { Text("Save") }
                    OutlinedButton({}, Modifier.testTag("button.outlined")) { Text("Cancel") }
                    OutlinedButton({}, Modifier.testTag("button.outlined.disabled"), enabled = false) { Text("Cancel") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckboxRow("Read-only", false, {}, Modifier.testTag("checkbox.unchecked"))
                    CheckboxRow("Read-only", true, {}, Modifier.testTag("checkbox.checked"))
                    CheckboxRow("Read-only", false, {}, Modifier.testTag("checkbox.disabled"), enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield"))
                    TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.disabled"), enabled = false)
                    TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.error"), outline = Outline.Error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.focused").focusRequester(focus))
                    Box(Modifier.size(40.dp, 20.dp).testTag("blank"))
                }
                Divider(Orientation.Horizontal, Modifier.width(200.dp).testTag("divider"))
                Spacer(Modifier.size(1.dp))
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    override fun measure(m: Measure) {
        val s = m.scale
        for (tag in Specimens) m.size(tag)
        m.color("island.background", m.bounds("blank").center.x, m.bounds("blank").center.y)
        for (tag in Specimens.filter { it.startsWith("button") || it.startsWith("textfield") }) {
            val b = m.bounds(tag)
            m.color("$tag.fill", b.left + 6 * s, b.center.y)
            m.color("$tag.border", b.center.x, b.top + s / 2)
            // The focus ring and the error outline are drawn just outside the bounds.
            m.color("$tag.outline", b.center.x, b.top - s / 2)
        }
        for (tag in Specimens.filter { it.startsWith("checkbox") }) {
            // The box sits 4dp in from the row's left edge and 4dp down; sampled clear of the check mark.
            val b = m.bounds(tag)
            m.color("$tag.box.border", b.left + 4 * s + s / 2, b.top + 8 * s)
            m.color("$tag.box.fill", b.left + 6 * s, b.top + 6 * s)
        }
        m.color("divider", m.bounds("divider").center.x, m.bounds("divider").center.y)
    }
}
