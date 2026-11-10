package dev.tablegrip.app.settings

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.StripeGroup
import dev.tablegrip.app.ui.StripeItem
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The tool stripes' buttons with a check box to show each and arrows to move it within its group. */
@Composable
internal fun StripePanel(app: AppState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            tr("Settings stays on the stripe. A button can also be hidden by right-clicking it."),
            color = JewelTheme.globalColors.text.info,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        StripeGroup.entries.forEach { group ->
            val items = StripeItem.ordered(group, app.settings)
            items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(item.name !in app.settings.stripeHidden, onCheckedChange = { shown ->
                        app.updateSettings { s -> s.copy(stripeHidden = if (shown) s.stripeHidden - item.name else s.stripeHidden + item.name) }
                    })
                    Text(item.label, Modifier.width(180.dp))
                    Text(groupLabel(group), Modifier.width(90.dp), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
                    Tooltip({ Text(tr("Move up")) }) {
                        IconButton(onClick = { move(app, items, index, -1) }, enabled = index > 0) { Icon(AllIconsKeys.General.ArrowUp, tr("Move up")) }
                    }
                    Tooltip({ Text(tr("Move down")) }) {
                        IconButton(onClick = { move(app, items, index, 1) }, enabled = index < items.lastIndex) { Icon(AllIconsKeys.General.ArrowDown, tr("Move down")) }
                    }
                }
            }
        }
    }
}

private fun groupLabel(group: StripeGroup): String =
    when (group) {
        StripeGroup.Tools -> tr("Tool windows")
        StripeGroup.Actions -> tr("Actions")
        StripeGroup.Bottom -> tr("Bottom left")
        StripeGroup.Right -> tr("Right edge")
    }

/** Swaps [items]`[index]` with its neighbour [delta] away, writing the whole order so it no longer depends on defaults. */
private fun move(app: AppState, items: List<StripeItem>, index: Int, delta: Int) {
    val target = index + delta
    if (target !in items.indices) return
    val reordered = items.toMutableList().apply { add(target, removeAt(index)) }
    app.updateSettings { s ->
        val others = StripeGroup.entries.filter { it != items.first().group }.flatMap { StripeItem.ordered(it, s) }
        s.copy(stripeOrder = (reordered + others).map { it.name })
    }
}
