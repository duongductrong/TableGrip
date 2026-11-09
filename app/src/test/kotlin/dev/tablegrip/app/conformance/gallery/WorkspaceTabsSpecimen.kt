package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.LocalIslands
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The workspace's pill tabs as `WorkspaceView.PillTab` draws them, in its 42dp strip: the selected tab, a console with
 * unsaved work, and a hovered tab. The close button shows on the selected and the hovered tab only.
 */
@OptIn(ExperimentalTestApi::class)
object WorkspaceTabsSpecimen : GallerySpecimen {
    override val name = "workspace-tabs"
    override val size = DpSize(360.dp, 42.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(360.dp, 42.dp)) {
            Row(
                Modifier.height(42.dp).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(0, "users", console = false, selected = true)
                Pill(1, "Console 1 •", console = true, selected = false)
                Pill(2, "orders", console = false, selected = false)
            }
        }
    }

    @Composable
    private fun Pill(index: Int, title: String, console: Boolean, selected: Boolean) {
        val islands = LocalIslands.current
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.height(30.dp)
                .testTag("tab:$index")
                .clip(shape)
                .background(
                    when {
                        selected -> islands.accent.copy(alpha = if (JewelTheme.isDark) 0.22f else 0.12f)
                        hovered -> islands.chipHover
                        else -> Color.Transparent
                    }
                )
                .border(1.dp, if (selected) islands.accent.copy(alpha = 0.55f) else Color.Transparent, shape)
                .hoverable(interaction)
                .padding(start = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val key = if (console) AllIconsKeys.Nodes.Console else AllIconsKeys.Nodes.DataTables
            Icon(key, null, Modifier.testTag(if (console) "icon:Nodes.Console" else "icon:Nodes.DataTables"))
            Text(title, Modifier.testTag("text:workspace-tabs.$index"), maxLines = 1)
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                if (selected || hovered) Icon(AllIconsKeys.General.CloseSmall, "Close", Modifier.testTag("icon:General.CloseSmall"))
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("tab:2").performMouseInput { moveTo(center) }
    }
}
