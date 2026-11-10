package dev.tablegrip.app.ui

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A searchable list of every shortcut, grouped by where it applies. Esc closes it. */
@Composable
internal fun ShortcutsDialog(app: AppState) {
    val query = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    AppDialog(
        onCloseRequest = { app.shortcutsOpen = false },
        size = DpSize(560.dp, 620.dp),
        title = tr("Keyboard Shortcuts"),
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                query,
                Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(tr("Search actions or keys")) },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
            )
            val needle = query.text.toString().trim()
            val groups =
                Shortcuts.groups.mapNotNull { group ->
                    val entries = group.entries.filter { needle.isEmpty() || it.description.contains(needle, true) || it.keys.contains(needle, true) }
                    if (entries.isEmpty()) null else group.title to entries
                }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (groups.isEmpty()) {
                    Text(tr("No matching shortcuts"), Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEachIndexed { index, (title, entries) ->
                        item(key = title) {
                            Text(title, Modifier.padding(top = if (index == 0) 0.dp else 14.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
                            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        }
                        items(entries, key = { "$title/${it.description}" }) { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.description, Modifier.weight(1f))
                                KeyCap(entry.keys)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DefaultButton(onClick = { app.shortcutsOpen = false }) { Text(tr("Close")) }
            }
        }
    }
}

/** Keys drawn as a small chip, like the hints in IntelliJ's empty editor. */
@Composable
internal fun KeyCap(keys: String) {
    val islands = LocalIslands.current
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(islands.chipHover).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(keys, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
    }
}

/** Centered "action — keys" hints for an empty panel. */
@Composable
internal fun ShortcutHints(hints: List<Pair<String, Shortcut>>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hints.forEach { (action, shortcut) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(action, Modifier.width(150.dp), color = JewelTheme.globalColors.text.info)
                    KeyCap(shortcut.label)
                }
            }
        }
    }
}
