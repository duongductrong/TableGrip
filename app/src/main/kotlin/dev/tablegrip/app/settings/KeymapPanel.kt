package dev.tablegrip.app.settings

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.KeymapPreset
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcut
import dev.tablegrip.app.ui.ShortcutAction
import dev.tablegrip.app.ui.Shortcuts
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Every action with its shortcut. Clicking a shortcut records the next key combination for it (Esc cancels); a changed
 * one can be put back to the preset's. Clashes within the same place (e.g. two SQL editor actions) are flagged.
 */
@Composable
internal fun KeymapPanel(app: AppState) {
    val query = remember { TextFieldState() }
    var recording by remember { mutableStateOf<ShortcutAction?>(null) }
    val preset = KeymapPreset.of(app.settings.keymap)
    val needle = query.text.toString().trim()
    val actions = Shortcuts.actions.filter { needle.isEmpty() || it.description.contains(needle, ignoreCase = true) || Shortcuts.current(it).label.contains(needle, ignoreCase = true) }
    val customized = app.settings.keyBindings.keys

    fun bind(action: ShortcutAction, shortcut: Shortcut?) {
        app.updateSettings { s ->
            val sameAsPreset = shortcut == null || Shortcuts.presetBinding(preset, action).sameKeys(shortcut)
            s.copy(keyBindings = if (sameAsPreset) s.keyBindings - action.id else s.keyBindings + (action.id to shortcut!!.toBinding()))
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(query, Modifier.width(280.dp), placeholder = { Text(tr("Search actions or keys")) }, leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) })
            if (customized.isNotEmpty()) Text(tr("{0} changed", customized.size), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (actions.isEmpty()) Text(tr("No actions match \"{0}\"", needle), color = JewelTheme.globalColors.text.info)
        actions.groupBy { it.groupLabel }.forEach { (group, groupActions) ->
            Text(group, Modifier.padding(top = 10.dp, bottom = 2.dp), fontWeight = FontWeight.SemiBold)
            groupActions.forEach { action ->
                ActionRow(
                    action = action,
                    shortcut = Shortcuts.current(action),
                    customized = action.id in customized,
                    recording = recording == action,
                    clashes = clashes(action),
                    onRecord = { recording = action },
                    onRecorded = { shortcut ->
                        recording = null
                        if (shortcut != null) bind(action, shortcut)
                    },
                    onReset = { bind(action, null) },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    action: ShortcutAction,
    shortcut: Shortcut,
    customized: Boolean,
    recording: Boolean,
    clashes: Clashes,
    onRecord: () -> Unit,
    onRecorded: (Shortcut?) -> Unit,
    onReset: () -> Unit,
) {
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action.description, Modifier.weight(1f), maxLines = 1)
            if (customized) Text(tr("changed"), Modifier.padding(end = 8.dp), color = info, fontSize = 11.sp)
            if (recording) KeyRecorder(onRecorded) else KeyChip(shortcut.label, conflict = clashes.sameContext.isNotEmpty(), onClick = onRecord)
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (customized) {
                    Tooltip({ Text(tr("Back to the preset's shortcut")) }) { IconButton(onClick = onReset) { Icon(AllIconsKeys.General.Reset, tr("Reset")) } }
                }
            }
        }
        if (clashes.sameContext.isNotEmpty()) {
            Text(tr("Also bound to: {0}", clashes.sameContext.joinToString { it.description }), color = JewelTheme.globalColors.text.error, fontSize = 12.sp)
        } else if (clashes.global.isNotEmpty()) {
            Text(tr("Shared with {0}; the focused place takes it first.", clashes.global.joinToString { "${it.description} (${it.groupLabel})" }), color = info, fontSize = 12.sp)
        }
    }
}

@Composable
private fun KeyChip(label: String, conflict: Boolean, onClick: () -> Unit) {
    val islands = LocalIslands.current
    val border = if (conflict) JewelTheme.globalColors.text.error else JewelTheme.globalColors.borders.normal
    Tooltip({ Text(tr("Click, then press the new shortcut")) }) {
        Box(
            Modifier.widthIn(min = 90.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(islands.chipHover)
                .border(1.dp, border, RoundedCornerShape(5.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}

/** Takes the focus and turns the next key combination into a shortcut; Esc or leaving it cancels. */
@Composable
private fun KeyRecorder(onRecorded: (Shortcut?) -> Unit) {
    val focus = remember { FocusRequester() }
    val islands = LocalIslands.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Window-wide shortcuts (tab switching, double ⇧) stand aside while recording.
    DisposableEffect(Unit) {
        Shortcuts.recording = true
        onDispose { Shortcuts.recording = false }
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.widthIn(min = 160.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(islands.accent.copy(alpha = 0.18f))
            .border(1.dp, islands.accent, RoundedCornerShape(5.dp))
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) onRecorded(null)
                focused = it.isFocused
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) {
                    onRecorded(null)
                    return@onPreviewKeyEvent true
                }
                Shortcut.of(event)?.let(onRecorded)
                true
            }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tr("Press keys… (Esc cancels)"), fontSize = 12.sp)
    }
}

/** Other actions on the same keys: [sameContext] fight over them, [global] (window-wide ones) are shadowed by design. */
private class Clashes(val sameContext: List<ShortcutAction>, val global: List<ShortcutAction>)

/** Groups whose shortcuts are live at the same time, so a shared key in them is a real conflict. */
private val Contexts = listOf(setOf("Console", "SQL Editor"), setOf("Data Grid", "Table Filter"), setOf("Filter Builder"), setOf("General"))

private fun contextOf(group: String): Set<String> = Contexts.firstOrNull { group in it } ?: setOf(group)

private fun clashes(action: ShortcutAction): Clashes {
    val shortcut = Shortcuts.current(action)
    val same = Shortcuts.actions.filter { it != action && Shortcuts.current(it).sameKeys(shortcut) }
    val context = contextOf(action.group)
    return Clashes(
        sameContext = same.filter { it.group in context },
        global = same.filter { it.group !in context && (it.group == "General" || action.group == "General") },
    )
}

/** Same key and modifiers, whatever the labels say. */
private fun Shortcut.sameKeys(other: Shortcut): Boolean =
    key == other.key && primary == other.primary && shift == other.shift && alt == other.alt && ctrl == other.ctrl
