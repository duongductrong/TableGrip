package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import org.jetbrains.jewel.ui.component.*

/**
 * An open PopupMenu: a selected item, a plain one, a separator, a hovered item and a disabled one. The popup layer
 * would be a second root the capture cannot take, so the menu's own content is drawn in place (`MenuContent`: border,
 * shadow and items are the same as in the popup). Item texts are text regions; the items themselves lose their tags.
 */
@OptIn(ExperimentalTestApi::class)
object PopupMenuSpecimen : GallerySpecimen {
    override val name = "popup-menu"
    override val size = DpSize(240.dp, 160.dp)

    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalMenuController provides DefaultMenuController(onDismissRequest = { true })) {
            Box(Modifier.offset(16.dp, 8.dp)) {
                MenuContent(modifier = Modifier.testTag("menu")) {
                    selectableItem(selected = true, onClick = {}) { Text("Auto-commit", Modifier.testTag("text:popup-menu.0")) }
                    selectableItem(selected = false, onClick = {}) { Text("Manual commit", Modifier.testTag("text:popup-menu.1")) }
                    separator()
                    selectableItem(selected = false, onClick = {}) { Text("Commit", Modifier.testTag("text:popup-menu.2")) }
                    selectableItem(selected = false, enabled = false, onClick = {}) { Text("Roll Back", Modifier.testTag("text:popup-menu.3")) }
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        // Over the third item (its centre is 83dp down, 40dp across).
        onRoot().performMouseInput { moveTo(Offset(80f * 2, 83f * 2)) }
    }

    override fun measure(m: Measure) = m.size("menu")
}
