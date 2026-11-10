package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.ConnectedColor
import dev.tablegrip.app.ui.Dot
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.IslandGap
import dev.tablegrip.app.ui.PendingColor
import dev.tablegrip.app.ui.StatusDivider
import dev.tablegrip.app.ui.StatusWidget
import dev.tablegrip.app.ui.StripeButton
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * `MainScreen`'s empty frame: the tool stripe and status bar on the frame, a tool window island and the workspace
 * island between them, split 30/70 with the 6dp gap as the handle, and the 6dp margin where no right stripe is shown.
 */
object MainScreenSpecimen : GallerySpecimen {
    override val name = "main-screen"
    override val size = DpSize(480.dp, 320.dp)

    @Composable
    override fun Content() {
        val info = JewelTheme.globalColors.text.info
        Column(Modifier.size(480.dp, 320.dp).background(dev.tablegrip.app.ui.LocalIslands.current.frame)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StripeButton(AllIconsKeys.Nodes.DataSchema, "Connections", selected = true) {}
                    StripeButton(AllIconsKeys.Vcs.History, "Query History", selected = false) {}
                    StripeButton(AllIconsKeys.Nodes.Bookmark, "Saved Queries", selected = false) {}
                    Divider(Orientation.Horizontal, Modifier.width(20.dp).padding(vertical = 2.dp))
                    StripeButton(AllIconsKeys.Actions.Search, "Go to Anything", selected = false) {}
                    StripeButton(AllIconsKeys.General.Add, "New Connection", selected = false) {}
                    Spacer(Modifier.weight(1f))
                    StripeButton(AllIconsKeys.General.Keyboard, "Keyboard Shortcuts", selected = false) {}
                    StripeButton(AllIconsKeys.General.Settings, "Settings", selected = false) {}
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    HorizontalSplitLayout(
                        first = { Island(Modifier.fillMaxSize().padding(end = IslandGap / 2)) {} },
                        second = { Island(Modifier.fillMaxSize().padding(start = IslandGap / 2)) {} },
                        modifier = Modifier.fillMaxSize(),
                        dividerStyle = remember { DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)) },
                        draggableWidth = IslandGap,
                        state = rememberSplitLayoutState(0.3f),
                    )
                }
                Box(Modifier.width(IslandGap))
            }
            Row(
                Modifier.fillMaxWidth().height(26.dp).padding(start = 10.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val crumbs = listOf("prod-db", "public", "users")
                    crumbs.forEachIndexed { index, crumb ->
                        if (index > 0) Icon(AllIconsKeys.General.ChevronRight, null, Modifier.size(12.dp), tint = info)
                        Text(
                            crumb,
                            Modifier.testTag("text:main-screen.crumb$index"),
                            color = if (index == crumbs.lastIndex) JewelTheme.globalColors.text.normal else info,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusDivider()
                Box(Modifier.weight(1f)) {
                    Text("Ready", Modifier.padding(horizontal = 4.dp).testTag("text:main-screen.message"), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusWidget(tooltip = "3 change(s) not submitted yet") {
                    Dot(PendingColor)
                    Text("3 pending", Modifier.testTag("text:main-screen.pending"), color = PendingColor, maxLines = 1)
                }
                StatusDivider()
                StatusWidget(tooltip = "prod-db") {
                    Dot(ConnectedColor)
                    Text("prod-db", Modifier.testTag("text:main-screen.connection"), maxLines = 1)
                }
            }
        }
    }
}
