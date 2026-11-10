package dev.tablegrip.app.agent

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.app.ui.AppIcons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.provider.AgentMode
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlinx.coroutines.delay

private val ComposerShape = RoundedCornerShape(10.dp)

/** The agent chat: a header, the conversation, and a composer that sends with Enter. */
@Composable
internal fun AgentPanel(app: AppState) {
    val agent = app.agent
    LaunchedEffect(Unit) { agent.discover() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(agent.chatTitle ?: tr("New chat"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.chatStore != null) {
                var history by remember { mutableStateOf(false) }
                Box {
                    Tooltip({ Text(tr("Chat history")) }) { IconButton(onClick = { history = !history }) { Icon(AllIconsKeys.Vcs.History, tr("Chat history")) } }
                    if (history) ChatHistoryPopup(agent, onDismiss = { history = false })
                }
            }
            Tooltip({ Text(tr("New chat")) }) {
                IconButton(onClick = agent::newChat, enabled = agent.messages.isNotEmpty()) { Icon(AllIconsKeys.General.Add, tr("New chat")) }
            }
            Tooltip({ Text(tr("Hide")) }) { IconButton(onClick = { app.agentOpen = false }) { Icon(AllIconsKeys.General.HideToolWindow, tr("Hide")) } }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (agent.messages.isEmpty()) EmptyState(agent) else Conversation(app, agent)
        }
        Composer(agent, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
    }
    agent.pendingRun?.let { ConfirmRunDialog(app, it) }
}

/** Red, so a query that can lose data never looks like one that only reads. */
private val DangerColor = Color(0xFFDB4B4B)

/** What a risky query would do, in red, above its code. */
@Composable
private fun RiskNotice(risks: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AllIconsKeys.General.Warning, null, Modifier.size(14.dp))
            Text(tr("Can change or delete data"), color = DangerColor, fontWeight = FontWeight.SemiBold)
        }
        risks.forEach { Text("• $it", color = DangerColor) }
    }
}

@Composable
private fun ConfirmRunDialog(app: AppState, run: RunConfirmation) {
    val agent = app.agent
    AppDialog(onCloseRequest = { agent.pendingRun = null }, size = DpSize(620.dp, 400.dp), title = tr("Run Query from Agent?")) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RiskNotice(run.reasons)
            Text(tr("The agent wrote this query. Check it before running: changes may not be undoable."), color = JewelTheme.globalColors.text.info)
            val text = remember(run) { TextFieldState(run.sql.trim()) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth().border(1.dp, DangerColor, RoundedCornerShape(4.dp)), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                // Cancel is the default and Esc closes: running takes a deliberate click.
                DefaultButton(onClick = { agent.pendingRun = null }) { Text(tr("Cancel")) }
                OutlinedButton(onClick = agent::confirmRun) { Text(tr("Run anyway"), color = DangerColor) }
            }
        }
    }
}

@Composable
private fun EmptyState(agent: AgentViewModel) {
    val info = JewelTheme.globalColors.text.info
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Agent, null, Modifier.size(28.dp))
        when {
            agent.discovering -> Text(tr("Looking for agents on this machine…"), color = info)
            agent.agents.isEmpty() -> {
                Text(tr("No agent found"), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Install Claude Code, Codex, Gemini CLI, Cursor Agent, OpenCode or another supported CLI, then rescan."),
                    color = info,
                    textAlign = TextAlign.Center,
                )
                Text(tr("Rescan"), Modifier.clickable { agent.discover(rescan = true) }, color = LocalIslands.current.accent)
                Text(tr("Or use an API provider…"), Modifier.clickable { agent.openSettings() }, color = LocalIslands.current.accent)
            }
            else -> {
                Text(tr("Ask about your data"), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Write queries, explain a schema or fix an error. The agent sees the current connection and the open console."),
                    color = info,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Conversation(app: AppState, agent: AgentViewModel) {
    val listState = rememberLazyListState()
    val last = agent.messages.lastOrNull()
    // Follow the answer as it streams in, while the view is at the bottom: scrolling up to read stops that until the
    // user scrolls back down, jumps to the latest, or sends something.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) -> if (scrolling) follow = !more }
    }
    LaunchedEffect(agent.messages.size) { follow = true }
    LaunchedEffect(agent.messages.size, last?.text?.length, last?.error, follow) {
        if (follow && agent.messages.isNotEmpty()) listState.scrollToItem(agent.messages.lastIndex, Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(agent.messages) { _, message -> MessageView(app, message) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        VerticalScrollbar(listState, Modifier.align(Alignment.TopEnd).padding(vertical = 2.dp))
        if (!follow && listState.canScrollForward) JumpToLatest(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { follow = true }
    }
}

@Composable
private fun JumpToLatest(modifier: Modifier, onClick: () -> Unit) {
    val islands = LocalIslands.current
    Text(
        tr("↓  Latest"),
        modifier
            .clip(CircleShape)
            .background(islands.island)
            .border(1.dp, islands.islandBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * The agent's reasoning, under a header that says how long it took. Open while the agent thinks, showing its latest
 * lines; folded once the answer starts, until the user opens it to read the whole.
 */
@Composable
private fun ReasoningBlock(message: ChatMessage) {
    val info = JewelTheme.globalColors.text.info
    val rule = LocalIslands.current.divider
    val thinking = message.streaming && message.thoughtSeconds == null
    var chosen by remember(message) { mutableStateOf<Boolean?>(null) }
    val open = chosen ?: thinking
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DisableSelection {
            Row(Modifier.clickable { chosen = !open }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, null, Modifier.size(14.dp))
                val label =
                    when {
                        thinking -> elapsedSeconds(message.startedAt).let { if (it > 0) tr("Thinking… {0}s", it) else tr("Thinking…") }
                        message.thoughtSeconds != null -> tr("Thought for {0}s", maxOf(1, message.thoughtSeconds ?: 1))
                        else -> tr("Thought")
                    }
                Text(label, color = info)
            }
        }
        if (open) {
            val text = if (chosen == null) message.reasoning.trimEnd().lines().takeLast(ReasoningPreviewLines).joinToString("\n") else message.reasoning.trim()
            Text(
                text,
                Modifier.drawBehind { drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f) }.padding(start = 12.dp),
                color = info,
                lineHeight = ProseLineHeight,
            )
        }
    }
}

private const val ReasoningPreviewLines = 6

/** What the agent did besides writing, one row each: running, done or failed. */
@Composable
private fun Activities(activities: List<AgentEvent.Activity>) {
    val info = JewelTheme.globalColors.text.info
    DisableSelection {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            activities.forEach { activity ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        when (activity.state) {
                            AgentEvent.Activity.State.Running -> CircularProgressIndicator()
                            AgentEvent.Activity.State.Done -> Icon(AllIconsKeys.General.InspectionsOK, null, Modifier.size(14.dp))
                            AgentEvent.Activity.State.Failed -> Icon(AllIconsKeys.General.Error, null, Modifier.size(14.dp))
                        }
                    }
                    Text(activity.label, color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The blinking end-of-text marker a streaming answer carries, so it reads as still being written. */
@Composable
private fun rememberCaret(): AnnotatedString {
    val blink = rememberInfiniteTransition()
    val alpha by blink.animateFloat(1f, 0.15f, infiniteRepeatable(tween(530), RepeatMode.Reverse))
    val color = JewelTheme.globalColors.text.normal.copy(alpha = alpha)
    return remember(color) { buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(" ▍") } } }
}

/** Whole seconds since [startedAt] (a [System.nanoTime]), counting while shown. */
@Composable
private fun elapsedSeconds(startedAt: Long): Int {
    val seconds by produceState(0, startedAt) {
        while (true) {
            value = ((System.nanoTime() - startedAt) / 1_000_000_000).toInt()
            delay(1_000)
        }
    }
    return seconds
}

/** Prose line height: a touch looser than labels, so longer answers read comfortably. */
private val ProseLineHeight = 1.5.em

@Composable
private fun MessageView(app: AppState, message: ChatMessage) {
    val islands = LocalIslands.current
    val agent = app.agent
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (message.kind == ChatMessage.Kind.Note) {
        Row(
            Modifier.fillMaxWidth().hoverable(interaction).padding(horizontal = 16.dp).padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.General.Information, null, Modifier.size(14.dp))
            Text(message.label ?: message.text, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp)) {
                if (hovered && agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, tr("Rewind to here: the run request is asked again")) { agent.rewind(message) }
            }
        }
        return
    }
    if (message.role == AgentMessage.Role.User) {
        Column(Modifier.fillMaxWidth().hoverable(interaction).padding(start = 48.dp, end = 16.dp, top = 20.dp), horizontalAlignment = Alignment.End) {
            MentionMessageText(
                message.text,
                message.mentions,
                Modifier.clip(RoundedCornerShape(12.dp)).background(islands.chipHover).padding(horizontal = 12.dp, vertical = 8.dp),
                lineHeight = ProseLineHeight,
            )
            // Reserved even when hidden, so hovering doesn't shift the conversation.
            Row(Modifier.height(26.dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hovered) {
                    MessageAction(AllIconsKeys.Actions.Copy, tr("Copy")) { copyToClipboard(message.mentionText.plain) }
                    if (agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, tr("Rewind to here: removes this message and everything after, to edit and resend")) { agent.rewind(message) }
                }
            }
        }
        return
    }
    val markdown = remember(message) { StreamingMarkdown() }
    val blocks = remember(message.text, message.streaming) { if (message.streaming) markdown.parse(message.text, open = true) else parseMarkdown(message.text) }
    val caret = if (message.streaming && blocks.lastOrNull() is MarkdownBlock.Paragraph) rememberCaret() else null
    Column(Modifier.fillMaxWidth().hoverable(interaction).padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.reasoning.isNotEmpty()) ReasoningBlock(message)
        if (message.activities.isNotEmpty()) Activities(message.activities)
        blocks.forEachIndexed { index, block ->
            when {
                block is MarkdownBlock.Paragraph && caret != null && index == blocks.lastIndex -> Text(block.text + caret, lineHeight = ProseLineHeight)
                block is MarkdownBlock.Paragraph -> Text(block.text, lineHeight = ProseLineHeight)
                block is MarkdownBlock.Code && block.runRequest && block.isSql && app.agent.canRunSql -> RunRequestCard(app, message, index, block)
                block is MarkdownBlock.Code -> CodeBlock(app, block)
            }
        }
        val working = message.reasoning.isNotEmpty() || message.activities.any { it.state == AgentEvent.Activity.State.Running }
        if (message.streaming && message.text.isEmpty() && !working) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                val seconds = elapsedSeconds(message.startedAt)
                Text(if (seconds > 0) tr("Thinking… {0}s", seconds) else tr("Thinking…"), color = JewelTheme.globalColors.text.info)
            }
        }
        message.error?.let { error ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(AllIconsKeys.General.Error, null)
                Text(error, color = JewelTheme.globalColors.text.error, lineHeight = ProseLineHeight)
            }
        }
        // A failed reply keeps its actions in view: retrying or rewinding is the way out of it.
        if (!message.streaming) {
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hovered || message.error != null) {
                    if (message.text.isNotBlank()) MessageAction(AllIconsKeys.Actions.Copy, tr("Copy")) { copyToClipboard(message.text) }
                    if (agent.canRewind && agent.selected != null) MessageAction(AllIconsKeys.Actions.Refresh, tr("Retry: asks again for this answer")) { agent.retry(message) }
                    if (agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, tr("Remove this answer and everything after")) { agent.rewind(message) }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(icon: org.jetbrains.jewel.ui.icon.IconKey, tooltip: String, onClick: () -> Unit) {
    Tooltip({ DisableSelection { Text(tooltip) } }) { IconButton(onClick = onClick) { Icon(icon, tooltip, Modifier.size(14.dp)) } }
}

/** The agent asks to run a query: nothing runs until the user allows it here. */
@Composable
private fun RunRequestCard(app: AppState, message: ChatMessage, index: Int, block: MarkdownBlock.Code) {
    val islands = LocalIslands.current
    val info = JewelTheme.globalColors.text.info
    val shape = RoundedCornerShape(8.dp)
    val state = message.runs[index]
    val risks = remember(block.code, app.currentConnection?.id) { app.agent.risks(block.code) }
    val border = if (risks.isNotEmpty()) DangerColor else if (state == null) islands.accent else islands.islandBorder
    val background = if (risks.isNotEmpty()) DangerColor.copy(alpha = 0.08f) else islands.frame
    Column(Modifier.fillMaxWidth().clip(shape).background(islands.frame).background(background).border(1.dp, border, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Agent, null, Modifier.size(14.dp))
            Text(tr("{0} wants to run a query", app.agent.selected?.descriptor?.name ?: tr("The agent")), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        }
        if (risks.isNotEmpty()) RiskNotice(risks, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Text(
            block.code,
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            style = JewelTheme.editorTextStyle,
            lineHeight = 1.45.em,
            softWrap = false,
        )
        if (state == null) {
            Text(
                when {
                    risks.isNotEmpty() -> tr("Not run from here: it opens in a console for you to review and run yourself.")
                    !app.agent.sharesCurrentConnection -> tr("This connection isn't shared with agents: it opens in a console, and no result goes back.")
                    else -> tr("Runs in a console, then the result (up to {0} rows) is sent to the agent.", AgentContext.ResultRows)
                },
                Modifier.padding(horizontal = 12.dp).padding(top = 6.dp),
                color = info,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                null -> {
                    Spacer(Modifier.weight(1f))
                    val ready = !message.streaming && !app.agent.busy && !app.agent.consoleRunning
                    OutlinedButton(onClick = { app.agent.declineRun(message, index) }, enabled = ready) { Text(tr("Decline")) }
                    DefaultButton(onClick = { app.agent.allowRun(message, index, block.code) }, enabled = ready) {
                        Text(if (risks.isEmpty() && app.agent.sharesCurrentConnection) tr("Run") else tr("Open in console"))
                    }
                }
                RunRequestState.Running -> {
                    CircularProgressIndicator()
                    Text(tr("Running…"), color = info)
                }
                is RunRequestState.Done -> Text(state.summary, color = info)
                RunRequestState.Declined -> Text(tr("Declined"), color = info)
                RunRequestState.Refused -> Text(tr("This query changes data, so it was only opened in the console for you to review and run."), color = DangerColor)
                RunRequestState.NotShared -> Text(tr("Opened in the console. This connection isn't shared with agents, so no result was sent."), color = info)
            }
        }
    }
}

@Composable
private fun CodeBlock(app: AppState, block: MarkdownBlock.Code) {
    val islands = LocalIslands.current
    val info = JewelTheme.globalColors.text.info
    val shape = RoundedCornerShape(8.dp)
    val risks = if (block.isSql && app.agent.canRunSql) remember(block.code, app.currentConnection?.id) { app.agent.risks(block.code) } else emptyList()
    val danger = risks.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clip(shape).background(islands.frame)
            .background(if (danger) DangerColor.copy(alpha = 0.08f) else islands.frame)
            .border(1.dp, if (danger) DangerColor else islands.islandBorder, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(block.language.ifEmpty { tr("code") }, Modifier.weight(1f), color = info)
            Tooltip({ DisableSelection { Text(tr("Copy")) } }) { IconButton(onClick = { copyToClipboard(block.code) }) { Icon(AllIconsKeys.Actions.Copy, tr("Copy")) } }
            if (block.isSql && app.agent.canRunSql) {
                Tooltip({ DisableSelection { Text(tr("Open in console")) } }) {
                    IconButton(onClick = { app.agent.openSql(block.code) }) { Icon(AllIconsKeys.Nodes.Console, tr("Open in console")) }
                }
                val running = app.agent.consoleRunning
                val tooltip = if (running) tr("The console is running a query") else if (danger) tr("Run in console (asks first)") else tr("Run in console")
                Tooltip({ DisableSelection { Text(tooltip) } }) {
                    IconButton(onClick = { app.agent.runSql(block.code) }, enabled = !running) {
                        Icon(AllIconsKeys.Actions.Execute, tr("Run in console"))
                    }
                }
            }
        }
        if (danger) RiskNotice(risks, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
        Text(
            block.code,
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            style = JewelTheme.editorTextStyle,
            lineHeight = 1.45.em,
            softWrap = false,
        )
    }
}

@Composable
private fun Composer(agent: AgentViewModel, modifier: Modifier = Modifier) {
    val islands = LocalIslands.current
    val composer = agent.composer
    val state = composer.text
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val canSend = agent.selected != null && !agent.busy && !composer.isBlank
    val pills = PillColors.current()
    val display = remember(composer, pills.text) { composer.display(pills.style) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun submit() {
        if (canSend) agent.sendComposer()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A mention or a rewind put something in the composer from elsewhere: go on typing there.
    LaunchedEffect(composer.focusRequests) { focus.requestFocus() }
    // While typing `@…`: what it can mention, read on every composition so tables show up once they've loaded.
    val typing = composer.typing
    val candidates = typing?.let { agent.sources.search(it.second) }.orEmpty()
    LaunchedEffect(typing?.second) { composer.highlighted = 0 }
    // An Esc-closed list opens again once its @ is gone.
    LaunchedEffect(state.text) {
        val at = composer.dismissed ?: return@LaunchedEffect
        if (at >= state.text.length || state.text[at] != '@') composer.dismissed = null
    }

    Column(
        modifier.fillMaxWidth()
            .clip(ComposerShape)
            .background(islands.island)
            .border(if (focused) 2.dp else 1.dp, if (focused) islands.accent else islands.divider, ComposerShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 220.dp).verticalScroll(rememberScrollState())) {
            BasicTextField(
                state = state,
                modifier =
                    Modifier.fillMaxWidth()
                        .drawBehind {
                            val shown = layout?.layoutInput?.text?.length ?: return@drawBehind
                            drawPills(layout, shown, composer.pills(), pills.fill)
                        }
                        .onPrimaryPress { position ->
                            val pill = pillUnder(layout, position, composer.layout())
                            composer.preview = pill?.takeIf { it != composer.preview }
                        }
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && handleComposerKey(event, composer, candidates, ::submit) },
                textStyle = JewelTheme.defaultTextStyle.copy(color = JewelTheme.globalColors.text.normal),
                cursorBrush = SolidColor(JewelTheme.globalColors.text.normal),
                inputTransformation = composer.filter,
                outputTransformation = display,
                onTextLayout = { getResult -> layout = getResult() },
                decorator = { inner ->
                    if (state.text.isEmpty()) {
                        Text(tr("Ask anything: @ mentions tables, tabs and saved queries, {0} what's selected", Shortcuts.AddToChat), color = JewelTheme.globalColors.text.info)
                    }
                    inner()
                },
            )
        }
        if (typing != null && focused) {
            MentionSuggestions(typing.second, candidates, composer.highlighted) { composer.accept(it.mention) }
        }
        composer.preview?.takeIf { typing == null }?.let { char ->
            composer.mention(char)?.let { mention -> MentionPreview(mention, onDismiss = { composer.preview = null }, onRemove = { composer.remove(char) }) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentPicker(agent)
            ModelPicker(agent)
            Spacer(Modifier.weight(1f))
            if (agent.busy) {
                ComposerButton(tr("Stop"), enabled = true, onClick = agent::stop) { Icon(AllIconsKeys.Actions.Suspend, tr("Stop"), Modifier.size(16.dp), tint = it) }
            } else {
                ComposerButton(tr("Send (Enter)"), enabled = canSend, onClick = ::submit) { ArrowUpGlyph(it) }
            }
        }
    }
}

/**
 * Enter sends, or picks from the `@` list while it's open; copy, cut and paste keep pills readable outside and whole
 * inside; Esc closes the `@` list or a pill's details.
 */
private fun handleComposerKey(event: KeyEvent, composer: ComposerState, candidates: List<MentionCandidate>, submit: () -> Unit): Boolean {
    val primary = if (isMac) event.isMetaPressed else event.isCtrlPressed
    val typing = composer.typing
    if (typing != null) {
        val last = candidates.lastIndex
        when {
            event.key == Key.Escape -> return true.also { composer.dismissed = typing.first }
            candidates.isEmpty() -> Unit
            event.key == Key.DirectionDown -> return true.also { composer.highlighted = if (composer.highlighted >= last) 0 else composer.highlighted + 1 }
            event.key == Key.DirectionUp -> return true.also { composer.highlighted = if (composer.highlighted <= 0) last else composer.highlighted - 1 }
            (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab) && !event.isShiftPressed ->
                return true.also { composer.accept(candidates[composer.highlighted.coerceIn(0, last)].mention) }
        }
    }
    return when {
        event.key == Key.Enter && !event.isShiftPressed -> true.also { submit() }
        event.key == Key.Escape && composer.preview != null -> true.also { composer.preview = null }
        primary && !event.isShiftPressed && event.key == Key.C -> composer.copy()
        primary && !event.isShiftPressed && event.key == Key.X -> composer.copy(cut = true)
        primary && !event.isShiftPressed && event.key == Key.V -> composer.paste()
        else -> false
    }
}

@Composable
private fun AgentPicker(agent: AgentViewModel) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    val selected = agent.selected
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (hovered || open) islands.chipHover else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Agent, null, Modifier.size(14.dp))
            Text(
                selected?.descriptor?.name ?: if (agent.discovering) tr("Detecting…") else tr("No agent"),
                Modifier.widthIn(max = 180.dp),
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(12.dp))
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                agent.agents.forEach { candidate ->
                    selectableItem(selected = candidate == selected, onClick = {
                        agent.select(candidate)
                        open = false
                    }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.descriptor.name)
                            Text(candidate.descriptor.transport.name.uppercase(), color = JewelTheme.globalColors.text.info)
                        }
                    }
                }
                if (agent.agents.isNotEmpty()) separator()
                if (agent.mode == AgentMode.Cli) {
                    selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Refresh, onClick = {
                        agent.discover(rescan = true)
                        open = false
                    }) { Text(tr("Rescan installed agents")) }
                }
                selectableItem(selected = false, iconKey = AllIconsKeys.General.Settings, onClick = {
                    agent.openSettings()
                    open = false
                }) { Text(tr("Agent settings…")) }
            }
        }
    }
}

@Composable
private fun ComposerButton(tooltip: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable (tint: Color) -> Unit) {
    val islands = LocalIslands.current
    Tooltip({ Text(tooltip) }) {
        Box(
            Modifier.size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) islands.accent else islands.chipHover)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (enabled) Color.White else JewelTheme.globalColors.text.disabled)
        }
    }
}

/** An up arrow drawn as a stem and a head, with no base line. */
@Composable
private fun ArrowUpGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        val x = size.width / 2
        val top = size.height * 0.22f
        val bottom = size.height * 0.78f
        val head = size.width * 0.28f
        drawLine(tint, Offset(x, top), Offset(x, bottom), stroke, StrokeCap.Round)
        drawLine(tint, Offset(x - head, top + head), Offset(x, top), stroke, StrokeCap.Round)
        drawLine(tint, Offset(x + head, top + head), Offset(x, top), stroke, StrokeCap.Round)
    }
}
