package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.StripeButton
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The 44dp tool stripe on the frame: tools, a rule, actions, then the bottom buttons; the first tool is selected.
 * Each button is an icon region: the icons' rasterisation differs by a sub-pixel, and they are most of the pixels.
 */
object ToolStripeSpecimen : GallerySpecimen {
    override val name = "tool-stripe"
    override val size = DpSize(44.dp, 320.dp)

    @Composable
    override fun Content() {
        Column(
            Modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.testTag("icon:tool-stripe.explorer")) { StripeButton(AllIconsKeys.Nodes.DataSchema, "Connections", selected = true) {} }
            Box(Modifier.testTag("icon:tool-stripe.history")) { StripeButton(AllIconsKeys.Vcs.History, "Query History", selected = false) {} }
            Box(Modifier.testTag("icon:tool-stripe.queries")) { StripeButton(AllIconsKeys.Nodes.Bookmark, "Saved Queries", selected = false) {} }
            Divider(Orientation.Horizontal, Modifier.width(20.dp).padding(vertical = 2.dp))
            Box(Modifier.testTag("icon:tool-stripe.goto")) { StripeButton(AllIconsKeys.Actions.Search, "Go to Anything", selected = false) {} }
            Box(Modifier.testTag("icon:tool-stripe.new")) { StripeButton(AllIconsKeys.General.Add, "New Connection", selected = false) {} }
            Spacer(Modifier.weight(1f))
            Box(Modifier.testTag("icon:tool-stripe.shortcuts")) { StripeButton(AllIconsKeys.General.Keyboard, "Keyboard Shortcuts", selected = false) {} }
            Box(Modifier.testTag("icon:tool-stripe.settings")) { StripeButton(AllIconsKeys.General.Settings, "Settings", selected = false) {} }
        }
    }
}
