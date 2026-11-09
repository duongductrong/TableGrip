package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.window.defaultTitleBarStyle
import org.jetbrains.jewel.ui.component.Text

/**
 * `AppDialog`'s frame as it draws inside the window: a 32dp title bar with the centred semi-bold title, then the
 * content as an island inset 4dp, corners concentric with the window's (14dp minus the inset).
 */
object AppDialogSpecimen : GallerySpecimen {
    override val name = "app-dialog"
    override val size = DpSize(320.dp, 200.dp)

    @Composable
    override fun Content() {
        val colors = JewelTheme.defaultTitleBarStyle.colors
        Column(Modifier.size(320.dp, 200.dp).background(dev.tablegrip.app.ui.LocalIslands.current.frame)) {
            Box(Modifier.fillMaxWidth().height(32.dp).background(colors.background)) {
                Text(
                    "Export data",
                    Modifier.align(Alignment.Center).padding(horizontal = 8.dp).testTag("text:app-dialog.title"),
                    color = colors.content,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val body = Modifier.weight(1f).fillMaxWidth()
            Island(body.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), RoundedCornerShape(10.dp)) {
                Box(Modifier.fillMaxSize().padding(12.dp)) { Text("Choose where to save the file.", Modifier.testTag("text:app-dialog.body")) }
            }
        }
    }
}
