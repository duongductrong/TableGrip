package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.treeStyle

/** Hand-drawn popup list rows as the model picker draws them: row 0 is the keyboard highlight, row 2 is hovered. */
@OptIn(ExperimentalTestApi::class)
object PopupListSpecimen : GallerySpecimen {
    override val name = "popup-list"
    override val size = DpSize(176.dp, 144.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(176.dp, 144.dp)) {
            Column(Modifier.offset(16.dp, 12.dp).width(144.dp)) {
                repeat(4) { i ->
                    val interaction = remember { MutableInteractionSource() }
                    val isHovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier.fillMaxWidth().height(28.dp).testTag("row:$i")
                            .background(if (i == 0 || isHovered) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
                            .hoverable(interaction)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("Row $i", Modifier.testTag("text:popup-list.$i")) }
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("row:2").performMouseInput { moveTo(center) }
    }

    override fun measure(m: Measure) = m.size("row:0")
}
