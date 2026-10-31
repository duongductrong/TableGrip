package dev.datagrok.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.datagrok.core.agent.ChatSummary
import java.time.LocalDate
import java.time.ZoneId
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** Puts a popup just below its anchor, its right edge on the anchor's, as a header button's menu sits. */
private object BelowAnchorEnd : PopupPositionProvider {
    private const val Gap = 4

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x, anchorBounds.bottom + Gap)
    }
}

/** The chats whose title has every word of [query], newest first. */
internal fun filterChats(chats: List<ChatSummary>, query: String): List<ChatSummary> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return chats.filter { chat -> words.all { chat.title.lowercase().contains(it) } }
}

/**
 * Earlier chats, from the header's History button: searched by title, grouped by when they were last used, newest first.
 * Picking one reopens it as it was left; each row can be renamed or deleted on hover.
 */
@Composable
internal fun ChatHistoryPopup(agent: AgentViewModel, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val chats = filterChats(agent.chats, query.text.toString())
    var highlighted by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    // Headings sit between rows, so a chat's list index is its own plus the headings before it.
    val rows = buildList {
        chats.forEachIndexed { index, chat ->
            val age = ChatAge.of(chat.updatedAt, today, zone)
            if (index == 0 || ChatAge.of(chats[index - 1].updatedAt, today, zone) != age) add(age)
            add(index to chat)
        }
    }
    LaunchedEffect(Unit) {
        agent.refreshChats()
        focus.requestFocus()
    }
    LaunchedEffect(query.text) { highlighted = 0 }
    LaunchedEffect(highlighted) {
        val row = rows.indexOfFirst { it is Pair<*, *> && it.first == highlighted }
        if (row >= 0) listState.animateScrollToItem((row - 2).coerceAtLeast(0))
    }
    fun open(chat: ChatSummary) {
        agent.openChat(chat.id)
        onDismiss()
    }

    Popup(popupPositionProvider = BelowAnchorEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(340.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextField(
                query,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || editing != null) return@onPreviewKeyEvent false
                    val last = chats.lastIndex
                    when (event.key) {
                        Key.Escape -> true.also { onDismiss() }
                        Key.DirectionDown -> true.also { if (last >= 0) highlighted = if (highlighted >= last) 0 else highlighted + 1 }
                        Key.DirectionUp -> true.also { if (last >= 0) highlighted = if (highlighted <= 0) last else highlighted - 1 }
                        Key.Enter, Key.NumPadEnter -> true.also { chats.getOrNull(highlighted.coerceIn(0, maxOf(last, 0)))?.let(::open) }
                        else -> false
                    }
                },
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
            )
            if (chats.isEmpty()) {
                Text(
                    if (agent.chats.isEmpty()) "No chats yet. Chats are kept here once you send a message." else "No chat matches.",
                    Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    color = JewelTheme.globalColors.text.info,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), state = listState) {
                items(rows.size) { row ->
                    when (val item = rows[row]) {
                        is ChatAge ->
                            Text(
                                item.label,
                                Modifier.padding(start = 8.dp, top = if (row == 0) 2.dp else 8.dp, bottom = 2.dp),
                                color = JewelTheme.globalColors.text.info,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        is Pair<*, *> -> {
                            val index = item.first as Int
                            val chat = item.second as ChatSummary
                            when (chat.id) {
                                editing ->
                                    RenameRow(chat, onDone = { title ->
                                        title?.let { agent.renameChat(chat.id, it) }
                                        editing = null
                                        focus.requestFocus()
                                    })
                                deleting ->
                                    DeleteRow(onConfirm = {
                                        agent.deleteChat(chat.id)
                                        deleting = null
                                    }, onCancel = { deleting = null })
                                else ->
                                    ChatRow(
                                        chat,
                                        time = chatTime(chat.updatedAt, today, zone),
                                        current = chat.id == agent.chatId && agent.messages.isNotEmpty(),
                                        highlighted = index == highlighted,
                                        onClick = { open(chat) },
                                        onRename = {
                                            deleting = null
                                            editing = chat.id
                                        },
                                        onDelete = {
                                            editing = null
                                            deleting = chat.id
                                        },
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, time: String, current: Boolean, highlighted: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (highlighted || hovered) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chat.title, Modifier.weight(1f), fontWeight = if (current) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The time gives way to the row's actions on hover, so the title keeps its width.
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
            if (hovered) {
                Row {
                    RowAction(AllIconsKeys.Actions.Edit, "Rename", onRename)
                    RowAction(AllIconsKeys.General.Delete, "Delete", onDelete)
                }
            } else {
                Text(time, Modifier.padding(end = 6.dp), color = JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RowAction(icon: org.jetbrains.jewel.ui.icon.IconKey, tooltip: String, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) { IconButton(onClick = onClick) { Icon(icon, tooltip, Modifier.size(14.dp)) } }
}

/** The title, editable in place: Enter keeps it, Esc or an empty title leaves it as it was. */
@Composable
private fun RenameRow(chat: ChatSummary, onDone: (String?) -> Unit) {
    val title = rememberTextFieldState(chat.title)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        title.edit { selection = TextRange(0, length) }
    }
    TextField(
        title,
        Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> true.also { onDone(title.text.toString().trim().takeIf { it.isNotEmpty() && it != chat.title }) }
                Key.Escape -> true.also { onDone(null) }
                else -> false
            }
        },
    )
}

/** Asks once, in the row itself: deleting a chat can't be undone. */
@Composable
private fun DeleteRow(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delete this chat?", Modifier.weight(1f), maxLines = 1)
        Text("Cancel", Modifier.clickable(onClick = onCancel), color = JewelTheme.globalColors.text.info)
        Text("Delete", Modifier.clickable(onClick = onConfirm), color = JewelTheme.globalColors.text.error, fontWeight = FontWeight.SemiBold)
    }
}
