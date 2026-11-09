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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** An IconButton pressed. */
@OptIn(ExperimentalTestApi::class)
object IconButtonPressedSpecimen : GallerySpecimen {
    override val name = "icon-button-pressed"
    override val size = DpSize(56.dp, 48.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(56.dp, 48.dp)) {
            Box(Modifier.offset(16.dp, 12.dp)) {
                IconButton({}, Modifier.size(24.dp).testTag("icon-button.pressed")) {
                    Icon(AllIconsKeys.Actions.Execute, "Run", Modifier.size(14.dp).testTag("icon:icon-button.pressed"))
                }
            }
        }
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("icon-button.pressed").performMouseInput { moveTo(center); press() }
    }
}
