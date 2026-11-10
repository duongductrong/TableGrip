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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/** Jewel's TabStrip as the structure view uses it: three sections, the second selected, the third hovered, none closable. */
@OptIn(ExperimentalTestApi::class)
object TabStripSpecimen : GallerySpecimen {
    override val name = "tab-strip"
    override val size = DpSize(300.dp, 56.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(300.dp, 56.dp)) {
            Box(Modifier.offset(8.dp, 8.dp).width(284.dp)) {
                TabStrip(
                    tabs =
                        listOf("Columns 4", "Indexes 2", "Keys").mapIndexed { i, label ->
                            TabData.Default(
                                selected = i == 1,
                                content = { state -> SimpleTabContent(label, state, Modifier.testTag("text:tab-strip.$i")) },
                                closable = false,
                                onClick = {},
                            )
                        },
                    style = JewelTheme.defaultTabStyle,
                )
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("text:tab-strip.2", useUnmergedTree = true).performMouseInput { moveTo(center) }
    }
}
