package dev.datagrok.app.agent

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.isMac
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.provider.AgentMode
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

private val ComposerShape = RoundedCornerShape(10.dp)

/** The agent chat: a header, the conversation, and a composer that sends with Enter. */
@Composable
internal fun AgentPanel(app: AppState) {
    val agent = app.agent
    LaunchedEffect(Unit) { agent.discover() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agent", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Tooltip({ Text("New chat") }) {
                IconButton(onClick = agent::newChat, enabled = agent.messages.isNotEmpty()) { Icon(AllIconsKeys.General.Add, "New chat") }
            }
            Tooltip({ Text("Hide") }) { IconButton(onClick = { app.agentOpen = false }) { Icon(AllIconsKeys.General.HideToolWindow, "Hide") } }
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
            Text("Can change or delete data", color = DangerColor, fontWeight = FontWeight.SemiBold)
        }
        risks.forEach { Text("• $it", color = DangerColor) }
    }
}

@Composable
private fun ConfirmRunDialog(app: AppState, run: RunConfirmation) {
    val agent = app.agent
    AppDialog(onCloseRequest = { agent.pendingRun = null }, size = DpSize(620.dp, 400.dp), title = "Run Query from Agent?") {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RiskNotice(run.reasons)
            Text("The agent wrote this query. Check it before running: changes may not be undoable.", color = JewelTheme.globalColors.text.info)
            val text = remember(run) { TextFieldState(run.sql.trim()) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth().border(1.dp, DangerColor, RoundedCornerShape(4.dp)), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                // Cancel is the default and Esc closes: running takes a deliberate click.
                DefaultButton(onClick = { agent.pendingRun = null }) { Text("Cancel") }
                OutlinedButton(onClick = agent::confirmRun) { Text("Run anyway", color = DangerColor) }
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
        Icon(AllIconsKeys.Toolwindows.ToolWindowAskAI, null, Modifier.size(28.dp))
        when {
            agent.discovering -> Text("Looking for agents on this machine…", color = info)
            agent.agents.isEmpty() -> {
                Text("No agent found", fontWeight = FontWeight.SemiBold)
                Text(
                    "Install Claude Code, Codex, Gemini CLI, Cursor Agent, OpenCode or another supported CLI, then rescan.",
                    color = info,
                    textAlign = TextAlign.Center,
                )
                Text("Rescan", Modifier.clickable { agent.discover(rescan = true) }, color = LocalIslands.current.accent)
                Text("Or use an API provider…", Modifier.clickable { agent.openSettings() }, color = LocalIslands.current.accent)
            }
            else -> {
                Text("Ask about your data", fontWeight = FontWeight.SemiBold)
                Text(
                    "Write queries, explain a schema or fix an error. The agent sees the current connection and the open console.",
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
    // Follow the answer as it streams in.
    LaunchedEffect(agent.messages.size, last?.text?.length, last?.error) {
        if (agent.messages.isNotEmpty()) listState.scrollToItem(agent.messages.lastIndex, Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(agent.messages) { _, message -> MessageView(app, message) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        VerticalScrollbar(listState, Modifier.align(Alignment.TopEnd).padding(vertical = 2.dp))
    }
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
                if (hovered && agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, "Rewind to here: the run request is asked again") { agent.rewind(message) }
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
                    MessageAction(AllIconsKeys.Actions.Copy, "Copy") { copyToClipboard(message.mentionText.plain) }
                    if (agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, "Rewind to here: removes this message and everything after, to edit and resend") { agent.rewind(message) }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().hoverable(interaction).padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parseMarkdown(message.text).forEachIndexed { index, block ->
            when {
                block is MarkdownBlock.Paragraph -> Text(block.text, lineHeight = ProseLineHeight)
                block is MarkdownBlock.Code && block.runRequest && block.isSql && app.agent.canRunSql -> RunRequestCard(app, message, index, block)
                block is MarkdownBlock.Code -> CodeBlock(app, block)
            }
        }
        if (message.streaming && message.text.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Thinking…", color = JewelTheme.globalColors.text.info)
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
                    if (message.text.isNotBlank()) MessageAction(AllIconsKeys.Actions.Copy, "Copy") { copyToClipboard(message.text) }
                    if (agent.canRewind && agent.selected != null) MessageAction(AllIconsKeys.Actions.Refresh, "Retry: asks again for this answer") { agent.retry(message) }
                    if (agent.canRewind) MessageAction(AllIconsKeys.Actions.Rollback, "Remove this answer and everything after") { agent.rewind(message) }
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
            Icon(AllIconsKeys.Toolwindows.ToolWindowAskAI, null, Modifier.size(14.dp))
            Text("${app.agent.selected?.descriptor?.name ?: "The agent"} wants to run a query", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
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
                    risks.isNotEmpty() -> "Not run from here: it opens in a console for you to review and run yourself."
                    !app.agent.sharesCurrentConnection -> "This connection isn't shared with agents: it opens in a console, and no result goes back."
                    else -> "Runs in a console, then the result (up to ${AgentContext.ResultRows} rows) is sent to the agent."
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
                    OutlinedButton(onClick = { app.agent.declineRun(message, index) }, enabled = ready) { Text("Decline") }
                    DefaultButton(onClick = { app.agent.allowRun(message, index, block.code) }, enabled = ready) {
                        Text(if (risks.isEmpty() && app.agent.sharesCurrentConnection) "Run" else "Open in console")
                    }
                }
                RunRequestState.Running -> {
                    CircularProgressIndicator()
                    Text("Running…", color = info)
                }
                is RunRequestState.Done -> Text(state.summary, color = info)
                RunRequestState.Declined -> Text("Declined", color = info)
                RunRequestState.Refused -> Text("This query changes data, so it was only opened in the console for you to review and run.", color = DangerColor)
                RunRequestState.NotShared -> Text("Opened in the console. This connection isn't shared with agents, so no result was sent.", color = info)
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
            Text(block.language.ifEmpty { "code" }, Modifier.weight(1f), color = info)
            Tooltip({ DisableSelection { Text("Copy") } }) { IconButton(onClick = { copyToClipboard(block.code) }) { Icon(AllIconsKeys.Actions.Copy, "Copy") } }
            if (block.isSql && app.agent.canRunSql) {
                Tooltip({ DisableSelection { Text("Open in console") } }) {
                    IconButton(onClick = { app.agent.openSql(block.code) }) { Icon(AllIconsKeys.Nodes.Console, "Open in console") }
                }
                val running = app.agent.consoleRunning
                val tooltip = if (running) "The console is running a query" else if (danger) "Run in console (asks first)" else "Run in console"
                Tooltip({ DisableSelection { Text(tooltip) } }) {
                    IconButton(onClick = { app.agent.runSql(block.code) }, enabled = !running) {
                        Icon(AllIconsKeys.Actions.Execute, "Run in console")
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
                        Text("Ask anything: @ mentions tables, tabs and saved queries, ${Shortcuts.AddToChat} what's selected", color = JewelTheme.globalColors.text.info)
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
            Spacer(Modifier.weight(1f))
            if (agent.busy) {
                RoundButton(AllIconsKeys.Actions.Suspend, "Stop", enabled = true, onClick = agent::stop)
            } else {
                RoundButton(AllIconsKeys.General.ArrowUp, "Send (Enter)", enabled = canSend, onClick = ::submit)
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
            Icon(AllIconsKeys.Toolwindows.ToolWindowAskAI, null, Modifier.size(14.dp))
            Text(
                selected?.descriptor?.name ?: if (agent.discovering) "Detecting…" else "No agent",
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
                    }) { Text("Rescan installed agents") }
                }
                selectableItem(selected = false, iconKey = AllIconsKeys.General.Settings, onClick = {
                    agent.openSettings()
                    open = false
                }) { Text("Agent settings…") }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: org.jetbrains.jewel.ui.icon.IconKey, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
    val islands = LocalIslands.current
    Tooltip({ Text(tooltip) }) {
        Box(
            Modifier.size(28.dp)
                .clip(CircleShape)
                .background(if (enabled) islands.accent else islands.chipHover)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, tooltip, Modifier.size(16.dp), tint = if (enabled) Color.White else JewelTheme.globalColors.text.disabled)
        }
    }
}
