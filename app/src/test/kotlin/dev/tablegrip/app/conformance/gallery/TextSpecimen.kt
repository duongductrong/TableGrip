package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.conformance.Measure
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Text the way the app writes it: the default style, the four colours it passes (info, error, warning, disabled),
 * the editor style, a smaller and a bold label, and an ellipsised one. Each sits at a fixed spot, tagged as a text
 * region, so the image diff compares its ink.
 */
object TextSpecimen : GallerySpecimen {
    override val name = "text"
    override val size = DpSize(320.dp, 200.dp)

    @Composable
    override fun Content() {
        val c = JewelTheme.globalColors.text
        Island(Modifier.size(320.dp, 200.dp)) {
            Box {
                Row(16.dp, 12.dp, "text:normal") { Text("Connections") }
                Row(16.dp, 36.dp, "text:info") { Text("12 rows · 4 ms", color = c.info) }
                Row(16.dp, 60.dp, "text:error") { Text("Connection refused", color = c.error) }
                Row(16.dp, 84.dp, "text:warning") { Text("Unsaved changes", color = c.warning) }
                Row(16.dp, 108.dp, "text:disabled") { Text("Read-only", color = c.disabled) }
                Row(16.dp, 132.dp, "text:editor") { Text("SELECT * FROM users", style = JewelTheme.editorTextStyle) }
                Row(16.dp, 160.dp, "text:small") { Text("PRIMARY KEY", fontSize = 11.sp, color = c.info) }
                Row(176.dp, 12.dp, "text:bold") { Text("Tables", fontWeight = FontWeight.Bold) }
                Box(Modifier.offset(176.dp, 36.dp).size(96.dp, 20.dp)) {
                    Text("customer_addresses_archive", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("text:ellipsis"))
                }
            }
        }
    }

    @Composable
    private fun Row(x: Dp, y: Dp, tag: String, content: @Composable () -> Unit) {
        Box(Modifier.offset(x, y).testTag(tag)) { content() }
    }

    override fun measure(m: Measure) {
        for (tag in listOf("text:normal", "text:editor", "text:small")) m.size(tag)
    }
}
