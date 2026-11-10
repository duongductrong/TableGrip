package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.TableGripIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Icons as the app draws them: monochrome and coloured IntelliJ icons at 16dp, TableGrip's own agent icon, and a
 * 12dp and a 14dp icon tinted in the info colour (the status bar's breadcrumb).
 */
object IconSpecimen : GallerySpecimen {
    override val name = "icon"
    override val size = DpSize(240.dp, 56.dp)

    @Composable
    override fun Content() {
        val info = JewelTheme.globalColors.text.info
        Island(Modifier.size(240.dp, 56.dp)) {
            Box {
                Icon(AllIconsKeys.General.Add, null, Modifier.offset(16.dp, 20.dp).size(16.dp).testTag("icon:General.Add"))
                Icon(AllIconsKeys.General.Warning, null, Modifier.offset(44.dp, 20.dp).size(16.dp).testTag("icon:General.Warning"))
                Icon(AllIconsKeys.General.Error, null, Modifier.offset(72.dp, 20.dp).size(16.dp).testTag("icon:General.Error"))
                Icon(AllIconsKeys.Nodes.DataTables, null, Modifier.offset(100.dp, 20.dp).size(16.dp).testTag("icon:Nodes.DataTables"))
                Icon(AllIconsKeys.Actions.Refresh, null, Modifier.offset(128.dp, 20.dp).size(16.dp).testTag("icon:Actions.Refresh"))
                Icon(TableGripIcons.Agent, null, Modifier.offset(156.dp, 20.dp).size(16.dp).testTag("icon:TableGrip.Agent"))
                Icon(AllIconsKeys.General.ChevronRight, null, Modifier.offset(184.dp, 22.dp).size(12.dp).testTag("icon:General.ChevronRight"), tint = info)
                Icon(AllIconsKeys.Actions.Search, null, Modifier.offset(204.dp, 21.dp).size(14.dp).testTag("icon:Actions.Search"), tint = info)
            }
        }
    }
}
