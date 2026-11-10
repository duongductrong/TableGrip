package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.component.ListComboBox

/**
 * Closed non-editable ListComboBoxes at rest, hovered, focused and disabled. The open list is a popup layer, which the
 * capture cannot take; it is drawn like a PopupMenu's list in the Rust twin.
 */
@OptIn(ExperimentalTestApi::class)
object ListComboBoxSpecimen : GallerySpecimen {
    override val name = "list-combo-box"
    override val size = DpSize(240.dp, 144.dp)

    private val States = listOf("rest", "hovered", "focused", "disabled")

    @Composable
    override fun Content() {
        Island(Modifier.size(240.dp, 144.dp)) {
            Box {
                States.forEachIndexed { i, s ->
                    // The item inside the box loses its tag, so a marker over the selected text stands in for it. A disabled box's
                    // text is too faint for the ink comparison, so that one is skipped.
                    Box(Modifier.offset((16 + 9 + 1).dp, (12 + 32 * i + 6).dp).size(72.dp, 16.dp).testTag(if (s == "disabled") "skip:combo.$s" else "text:combo.$s")) {}
                    Box(Modifier.offset(16.dp, (12 + 32 * i).dp)) {
                        ListComboBox(
                            items = listOf("PostgreSQL", "MySQL", "SQLite"),
                            selectedIndex = 0,
                            onSelectedItemChange = {},
                            enabled = s != "disabled",
                            modifier = Modifier.width(200.dp).testTag("combo.$s"),
                        )
                    }
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("combo.hovered").performMouseInput { moveTo(center) }
        onNodeWithTag("combo.focused").requestFocus()
    }

    override fun measure(m: Measure) = m.size("combo.rest")
}
