package dev.datagrok.app.keyvalue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** A redis-cli style console: one command per line, replies printed below, in the database [keyspace] selects. */
class CommandConsoleTab(private val app: AppState, val config: ConnectionConfig, name: String, keyspace: Int) : WorkspaceTab {
    override val connectionId = config.id
    override val title = name

    var keyspace by mutableIntStateOf(keyspace)
        private set

    val input = TextFieldState()

    data class Entry(val prompt: String, val command: String, val reply: String, val isError: Boolean)

    val entries = mutableStateListOf<Entry>()

    var running by mutableStateOf(false)
        private set

    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var job: Job? = null

    val prompt: String
        get() = "db$keyspace>"

    fun run() {
        val line = input.text.toString().trim()
        if (line.isEmpty() || running) return
        input.clearText()
        if (history.lastOrNull() != line) history += line
        historyIndex = history.size
        if (line.equals("clear", ignoreCase = true)) {
            entries.clear()
            return
        }
        val arguments =
            try {
                KeyValueStore.splitArguments(line)
            } catch (e: IllegalArgumentException) {
                entries += Entry(prompt, line, "(error) ${e.message}", isError = true)
                return
            }
        val prompt = prompt
        running = true
        job =
            app.scope.launch {
                try {
                    val reply = app.databases.keyValue(config).command(keyspace, arguments)
                    entries += Entry(prompt, line, reply, isError = reply.startsWith("(error)"))
                    // The console follows SELECT, like redis-cli's prompt.
                    if (arguments.first().equals("SELECT", ignoreCase = true) && reply == "OK") arguments.getOrNull(1)?.toIntOrNull()?.let { keyspace = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    entries += Entry(prompt, line, "(error) ${e.message ?: e}", isError = true)
                } finally {
                    running = false
                }
            }
    }

    /** Steps through earlier commands: -1 for older, +1 for newer. */
    fun recall(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size)
        input.setTextAndPlaceCursorAtEnd(history.getOrElse(historyIndex) { "" })
    }

    override fun dispose() {
        job?.cancel()
    }
}

@Composable
internal fun CommandConsoleView(tab: CommandConsoleTab) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab.entries.size) { if (tab.entries.isNotEmpty()) listState.animateScrollToItem(tab.entries.size - 1) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tab.entries.isEmpty()) {
                    item {
                        Text(
                            "Commands run as in redis-cli, e.g. HGETALL user:1 or SCAN 0 MATCH session:* COUNT 100. " +
                                "↑/↓ recall earlier commands; \"clear\" empties this view.",
                            color = JewelTheme.globalColors.text.info,
                        )
                    }
                }
                items(tab.entries) { entry ->
                    Column {
                        Text("${entry.prompt} ${entry.command}", style = JewelTheme.editorTextStyle, color = JewelTheme.globalColors.text.info)
                        Text(
                            entry.reply,
                            style = JewelTheme.editorTextStyle,
                            color = if (entry.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                        )
                    }
                }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.prompt, style = JewelTheme.editorTextStyle)
            TextField(
                tab.input,
                Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> true.also { tab.recall(-1) }
                        Key.DirectionDown -> true.also { tab.recall(1) }
                        else -> false
                    }
                },
                textStyle = JewelTheme.editorTextStyle,
                enabled = !tab.running,
                placeholder = { Text(if (tab.config.readOnly) "Read-only: only commands that read run" else "Command") },
                onKeyboardAction = KeyboardActionHandler { tab.run() },
            )
        }
    }
}
