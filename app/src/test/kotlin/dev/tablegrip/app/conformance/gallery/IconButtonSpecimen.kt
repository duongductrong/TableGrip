package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** IconButtons (24dp with a 14dp icon) at rest, hovered, focused and disabled. The icon is an icon region. */
@OptIn(ExperimentalTestApi::class)
object IconButtonSpecimen : GallerySpecimen {
    override val name = "icon-button"
    override val size = DpSize(160.dp, 48.dp)

    private val States = listOf("rest", "hovered", "focused", "disabled")

    @Composable
    override fun Content() {
        Island(Modifier.size(160.dp, 48.dp)) {
            Box {
                States.forEachIndexed { i, s ->
                    Box(Modifier.offset((16 + 36 * i).dp, 12.dp)) {
                        IconButton({}, Modifier.size(24.dp).testTag("icon-button.$s"), enabled = s != "disabled") {
                            Icon(AllIconsKeys.Actions.Execute, "Run", Modifier.size(14.dp).testTag("icon:icon-button.$s"))
                        }
                    }
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("icon-button.hovered").performMouseInput { moveTo(center) }
        onNodeWithTag("icon-button.focused").requestFocus()
    }
}
