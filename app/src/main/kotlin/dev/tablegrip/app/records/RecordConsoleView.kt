package dev.tablegrip.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.completion.CompletionController
import dev.tablegrip.app.completion.CompletionPopup
import dev.tablegrip.app.completion.handleCompletionKey
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.textAreaStyle

@Composable
internal fun RecordConsoleView(tab: RecordConsoleTab) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(AllIconsKeys.Actions.Execute, tr("Run the selection, or everything ({0})", Shortcuts.Submit), enabled = !tab.running) { tab.run() }
            ToolButton(AllIconsKeys.Actions.Suspend, tr("Cancel"), enabled = tab.running) { tab.cancel() }
            Text(tab.language?.name.orEmpty(), Modifier.padding(horizontal = 6.dp), color = info)
            Box(Modifier.weight(1f))
            if (tab.running) Text(tr("Running…"), Modifier.padding(end = 8.dp), color = info)
            if (tab.config.readOnly) Text(tr("Read-only: only statements that read run"), Modifier.padding(end = 8.dp), color = info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        VerticalSplitLayout(
            first = { StatementEditor(tab, Modifier.fillMaxSize().focusRequester(focus)) },
            second = { Result(tab) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.4f),
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val reply = tab.reply) {
                is ConsoleReply.Table -> Text(reply.note ?: tr("{0} row(s)", reply.rows.rows.size), color = info)
                is ConsoleReply.Text -> Text(tab.ran.orEmpty(), color = info, maxLines = 1)
                null -> Unit
            }
            tab.reply?.let { Text(tr("{0} ms", it.elapsedMillis), color = info) }
            Box(Modifier.weight(1f))
            Text(tab.config.type.displayName, color = info)
        }
    }
}

@Composable
private fun Result(tab: RecordConsoleTab) {
    val error = tab.error
    val model = tab.model
    val reply = tab.reply
    when {
        error != null ->
            SelectionContainer {
                Text(error, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), color = JewelTheme.globalColors.text.error, style = JewelTheme.editorTextStyle)
            }
        model != null -> DataGrid(model, tab.grid)
        reply is ConsoleReply.Text ->
            SelectionContainer { Text(reply.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), style = JewelTheme.editorTextStyle) }
        else -> Text(tab.help, Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
    }
}

/**
 * The statement editor, completing in the console's language as the SQL editor does: the list opens as a name is typed
 * (or after `.`, `:`, `/`, a bracket or a space, with what can come next), Tab or Enter picks from it, ⌃Space opens it
 * and Esc closes it. ⌘⏎ runs, whether or not the list is open.
 */
@Composable
private fun StatementEditor(tab: RecordConsoleTab, modifier: Modifier) {
    val state = tab.editor
    val source = tab.completion
    val scope = rememberCoroutineScope()
    val controller = remember(tab) { CompletionController(scope) }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var width by remember { mutableStateOf(0) }
    // Set by an accepted item that something follows (`keyspace.`, `"query": {}`), to go straight on with what comes next.
    var trigger by remember { mutableStateOf<Boolean?>(null) }
    // Whether ↑/↓ moved through the list since the text last changed: only then does Enter pick from a list that opened
    // with nothing typed (after a space or a bracket), rather than break the line.
    var navigated by remember { mutableStateOf(false) }
    if (source != null) {
        LaunchedEffect(controller, source) {
            var previous = state.text.toString()
            snapshotFlow { state.text.toString() to state.selection }.collect { (text, selection) ->
                // Typing is read from the text, not the keys, so input methods (Telex, dead keys…) complete too.
                val typed = if (text.length == previous.length + 1 && selection.collapsed) text.getOrNull(selection.start - 1) else null
                if (text != previous) navigated = false
                previous = text
                val explicit =
                    trigger
                        ?: when {
                            typed == null -> null
                            // Not for indenting.
                            typed == ' ' && text.getOrNull(selection.start - 2)?.isWhitespace() != false -> null
                            typed in OpeningChars -> true
                            typed.isLetterOrDigit() || typed in "_$.'\"-" -> false
                            else -> null
                        }
                trigger = null
                if (explicit != null || controller.isActive) controller.update(state, source, explicit = explicit == true)
            }
        }
        // Names that arrive fill a list that's already open.
        LaunchedEffect(tab.catalog.version) { if (controller.isActive) controller.update(state, source, explicit = true) }
        LaunchedEffect(tab.running) { if (tab.running) controller.close() }
    }

    fun accept(index: Int = controller.selected) {
        controller.accept(state, index)
        val before = state.text.substring(0, state.selection.start)
        if (before.isNotEmpty() && before.last() in ContinueAfter) trigger = true
    }

    val padding = JewelTheme.textAreaStyle.metrics.contentPadding
    val direction = LocalLayoutDirection.current
    Box(modifier.onSizeChanged { width = it.width }) {
        TextArea(
            state,
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (Shortcuts.Submit.matches(event)) return@onPreviewKeyEvent true.also {
                    controller.close()
                    tab.run()
                }
                if (source == null) return@onPreviewKeyEvent false
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (enter && controller.isOpen && controller.result?.prefix.isNullOrEmpty() && !navigated) {
                    controller.close()
                    return@onPreviewKeyEvent false
                }
                if (controller.isOpen && event.key in NavigationKeys) navigated = true
                if (handleCompletionKey(controller, event.key) { accept() }) return@onPreviewKeyEvent true
                if (Shortcuts.Completion.matches(event)) return@onPreviewKeyEvent true.also { controller.update(state, source, explicit = true) }
                false
            },
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text(tab.language?.placeholder.orEmpty(), style = JewelTheme.editorTextStyle) },
            onTextLayout = { getResult -> layout = getResult() },
            scrollState = scroll,
        )
        val cursor = layout?.takeIf { it.layoutInput.text.length == state.text.length }?.getCursorRect(state.selection.start.coerceAtMost(state.text.length))
        if (cursor != null && source != null) {
            val density = LocalDensity.current
            val x = with(density) { cursor.left + padding.calculateLeftPadding(direction).toPx() }
            val y = with(density) { cursor.bottom - scroll.value + padding.calculateTopPadding().toPx() + 2 }
            CompletionPopup(controller, IntOffset(x.toInt().coerceAtMost((width - 200).coerceAtLeast(0)), y.toInt().coerceAtLeast(0))) { accept(it) }
        }
    }
}

/** Typed characters after which what can come next is offered, with nothing typed yet. */
private const val OpeningChars = " ./:{[(,\""

/** An accepted item ending with one of these is followed by what can come next. */
private const val ContinueAfter = " ./({["

private val NavigationKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown)
