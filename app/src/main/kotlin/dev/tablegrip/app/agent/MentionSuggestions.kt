package dev.tablegrip.app.agent

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/**
 * What `@` can mention, above the composer: grouped by kind, [highlighted] picked by Enter. Doesn't take focus, so typing
 * goes on narrowing the list.
 */
@Composable
internal fun MentionSuggestions(query: String, candidates: List<MentionCandidate>, highlighted: Int, onPick: (MentionCandidate) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val listState = rememberLazyListState()
    // Headings sit between rows, so a candidate's list index is its own plus the headings before it.
    val rows = buildList {
        candidates.forEachIndexed { index, candidate ->
            if (index == 0 || candidates[index - 1].kind != candidate.kind) add(candidate.kind)
            add(index to candidate)
        }
    }
    LaunchedEffect(highlighted, candidates) {
        val row = rows.indexOfFirst { it is Pair<*, *> && it.first == highlighted }
        if (row >= 0) listState.animateScrollToItem((row - 2).coerceAtLeast(0))
    }
    Popup(popupPositionProvider = AboveAnchor, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier.width(340.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(4.dp)
        ) {
            if (candidates.isEmpty()) {
                Text(
                    if (query.isEmpty()) tr("Nothing to mention yet") else tr("Nothing matches “{0}”", query),
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = JewelTheme.globalColors.text.info,
                )
                return@Box
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = listState) {
                items(rows.size) { row ->
                    when (val item = rows[row]) {
                        is MentionCandidate.Kind ->
                            Text(item.label, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
                        is Pair<*, *> -> {
                            val index = item.first as Int
                            val candidate = item.second as MentionCandidate
                            SuggestionRow(candidate, index == highlighted) { onPick(candidate) }
                        }
                    }
                }
            }
        }
    }
}

private fun MentionCandidate.Kind.icon(): IconKey =
    when (this) {
        MentionCandidate.Kind.Tab -> AllIconsKeys.Nodes.Console
        MentionCandidate.Kind.Table -> AllIconsKeys.Nodes.DataTables
        MentionCandidate.Kind.Column -> AllIconsKeys.Nodes.DataColumn
        MentionCandidate.Kind.Query -> AllIconsKeys.FileTypes.Text
    }

@Composable
private fun SuggestionRow(candidate: MentionCandidate, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Icon(candidate.kind.icon(), null) }
        Text(candidate.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        candidate.detail?.let { Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
    }
}
