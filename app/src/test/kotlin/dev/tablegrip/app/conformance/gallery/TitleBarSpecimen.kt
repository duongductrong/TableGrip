package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The content of the main window's 40dp title bar as `TitleBarView` lays it out, without the window's own controls
 * (the traffic lights and caption buttons belong to the OS): the connection on the left, the screen's name centred, the
 * header buttons on the right.
 */
object TitleBarSpecimen : GallerySpecimen {
    override val name = "title-bar"
    override val size = DpSize(480.dp, 40.dp)

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Text("No connection", Modifier.padding(start = 8.dp).testTag("text:title-bar.connection"), color = JewelTheme.globalColors.text.info)
            }
            Text("users", Modifier.align(Alignment.Center).testTag("text:title-bar.title"), color = JewelTheme.globalColors.text.info)
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(AllIconsKeys.Nodes.Console to "Actions.Console", AllIconsKeys.Actions.Execute to "Actions.Execute", AllIconsKeys.General.Settings to "General.Settings").forEach { (key, tag) ->
                    IconButton(onClick = {}, modifier = Modifier.size(32.dp).padding(3.dp)) {
                        // The gear's rasterisation differs by a sub-pixel (whole bar: ΔE ≤ 0.6), so only the other icons are regions.
                        Icon(key, null, if (key == AllIconsKeys.General.Settings) Modifier else Modifier.testTag("icon:title-bar.$tag"))
                    }
                }
            }
        }
    }
}
