package dev.tablegrip.app.completion

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.tablegrip.core.completion.CompletionKind
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/**
 * The suggestion list, anchored at [offset] within its parent. Doesn't take focus: the text field keeps it, and its
 * key handler drives the list through [CompletionController].
 */
@Composable
fun CompletionPopup(controller: CompletionController, offset: IntOffset, onAccept: (Int) -> Unit) {
    if (!controller.isOpen) return
    val items = controller.items
    val prefix = controller.result?.prefix?.trimStart('"', '`', '\'').orEmpty()
    Popup(offset = offset, properties = PopupProperties(focusable = false)) {
        val shape = RoundedCornerShape(8.dp)
        val listState = rememberLazyListState()
        LaunchedEffect(controller.selected, items) {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == controller.selected && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
                listState.scrollToItem((controller.selected - 3).coerceAtLeast(0))
            }
        }
        Box(
            Modifier.width(460.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
        ) {
            LazyColumn(Modifier.heightIn(max = 280.dp).padding(4.dp), state = listState) {
                itemsIndexed(items) { index, item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (index == controller.selected) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
                            .clickable { onAccept(index) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindIcon(item.kind)
                        Text(highlight(item.label, prefix), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = JewelTheme.editorTextStyle)
                        item.detail?.let { Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/** Bolds the part of [label] that matches the typed prefix. */
private fun highlight(label: String, prefix: String) = buildAnnotatedString {
    if (prefix.isNotEmpty() && label.startsWith(prefix, ignoreCase = true)) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label.substring(0, prefix.length)) }
        append(label.substring(prefix.length))
    } else {
        append(label)
    }
}

@Composable
private fun KindIcon(kind: CompletionKind) {
    when (kind) {
        CompletionKind.Table -> Icon(AllIconsKeys.Nodes.DataTables, null)
        CompletionKind.View -> Icon(AllIconsKeys.Nodes.DataTables, null)
        CompletionKind.Column -> Icon(AllIconsKeys.Nodes.DataColumn, null)
        CompletionKind.Schema -> Icon(AllIconsKeys.Nodes.Folder, null)
        else -> {
            val (letter, color) =
                when (kind) {
                    CompletionKind.Keyword -> "k" to Color(0xFFCF8E6D)
                    CompletionKind.Function -> "f" to Color(0xFF56A8F5)
                    CompletionKind.Join -> "j" to Color(0xFF5FB865)
                    CompletionKind.Value -> "v" to Color(0xFF6AAB73)
                    CompletionKind.Alias -> "a" to Color(0xFF9876D8)
                    else -> "o" to Color(0xFF8C8C8C)
                }
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Text(letter, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Shared key handling for a field with a completion popup; returns true when the key was used. */
fun handleCompletionKey(controller: CompletionController, key: Key, accept: () -> Unit): Boolean {
    if (!controller.isOpen) return false
    return when (key) {
        Key.DirectionDown -> controller.move(1).let { true }
        Key.DirectionUp -> controller.move(-1).let { true }
        Key.PageDown -> controller.move(8).let { true }
        Key.PageUp -> controller.move(-8).let { true }
        // Enter on a word that's already typed out in full just breaks the line.
        Key.Enter,
        Key.NumPadEnter -> if (controller.selectedIsTyped) controller.close().let { false } else accept().let { true }
        Key.Tab -> accept().let { true }
        Key.Escape -> controller.close().let { true }
        else -> false
    }
}

