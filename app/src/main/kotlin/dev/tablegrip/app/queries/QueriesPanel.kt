package dev.tablegrip.app.queries

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.console.ParameterRequest
import dev.tablegrip.app.search.fuzzyScore
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.queries.SavedQuery
import kotlin.io.path.exists
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Saved queries by folder; a click opens one in a console bound to its file. */
@Composable
internal fun QueriesPanel(app: AppState) {
    val saved = app.savedQueries
    val search = rememberTextFieldState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved Queries", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Tooltip({ Text("Open the queries folder") }) { IconButton(onClick = { saved.reveal() }) { Icon(AllIconsKeys.Actions.MenuOpen, "Open folder") } }
            Tooltip({ Text("Refresh") }) { IconButton(onClick = saved::reload) { Icon(AllIconsKeys.Actions.Refresh, "Refresh") } }
        }
        TextField(search, Modifier.fillMaxWidth().padding(horizontal = 8.dp), placeholder = { Text("Search saved queries") })
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(top = 6.dp))

        val needle = search.text.toString().trim()
        val shown =
            if (needle.isEmpty()) saved.queries.toList()
            else saved.queries.filter { fuzzyScore("${it.folder} ${it.name}", needle) != null || it.sql.contains(needle, ignoreCase = true) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (saved.queries.isEmpty()) "Press ${Shortcuts.Save} in a console to save its query here" else "No matches",
                    color = JewelTheme.globalColors.text.info,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            shown.groupBy { it.folder }.forEach { (folder, queries) ->
                if (folder.isNotEmpty()) {
                    item(key = "folder:$folder") {
                        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(AllIconsKeys.Nodes.Folder, null)
                            Text(folder, color = JewelTheme.globalColors.text.info)
                        }
                    }
                }
                items(queries, key = { it.file.toString() }) { query -> QueryRow(app, query, indent = folder.isNotEmpty()) }
            }
        }
    }
}

@Composable
private fun QueryRow(app: AppState, query: SavedQuery, indent: Boolean) {
    val saved = app.savedQueries
    val config = query.connection?.let { name -> app.connections.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Open") { saved.open(query) },
                ContextMenuItem("Rename / Move…") { saved.renaming = query },
                ContextMenuItem("Copy SQL") { copyToClipboard(query.sql) },
                ContextMenuItem("Reveal in File Manager") { saved.reveal(query) },
                ContextMenuItem("Delete…") { saved.pendingDelete = query },
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { saved.open(query) })
                .padding(start = if (indent) 30.dp else 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.FileTypes.Text, null)
                Text(query.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (config != null) Badge(config, size = 14)
            }
            val preview = query.sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = JewelTheme.editorTextStyle, color = JewelTheme.globalColors.text.info)
        }
    }
}

/** Names a console's query the first time it's saved (⌘S); it can still go to a file instead. */
@Composable
internal fun SaveQueryDialog(app: AppState, request: SaveQueryRequest) {
    val saved = app.savedQueries
    val tab = request.tab
    val name = remember(request) { TextFieldState(suggestedName(tab)) }
    val folder = remember(request) { TextFieldState("") }
    var link by remember(request) { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.text.isNotBlank()
    val replaces = valid && saved.library.fileFor(name.text.toString(), folder.text.toString()).exists()
    fun submit() {
        if (valid) saved.save(name.text.toString(), folder.text.toString(), link)
    }
    AppDialog(onCloseRequest = saved::cancelSave, size = DpSize(480.dp, 300.dp), title = "Save Query", resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) true.also { submit() } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledField("Name") { TextField(name, Modifier.fillMaxWidth().focusRequester(focus)) }
            LabeledField("Folder") {
                TextField(folder, Modifier.fillMaxWidth(), placeholder = { Text(saved.folders.firstOrNull()?.let { "e.g. $it (optional)" } ?: "optional, e.g. reports/monthly") })
            }
            if (saved.folders.isNotEmpty()) {
                Row(Modifier.padding(start = 76.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    saved.folders.take(5).forEach { existing ->
                        Text(existing, Modifier.combinedClickable(onClick = { folder.edit { replace(0, length, existing) } }), color = JewelTheme.globalColors.text.info)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(link, { link = it })
                Text("Always run on ${tab.config.name}")
            }
            if (replaces) Text("A query with this name exists and will be replaced.", color = JewelTheme.globalColors.text.warning)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = saved::saveToFile) { Text("Save to File…") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = saved::cancelSave) { Text("Cancel") }
                DefaultButton(onClick = ::submit, enabled = valid) { Text("Save") }
            }
        }
    }
}

@Composable
internal fun RenameQueryDialog(app: AppState, query: SavedQuery) {
    val saved = app.savedQueries
    val name = remember(query) { TextFieldState(query.name) }
    val folder = remember(query) { TextFieldState(query.folder) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        if (name.text.isNotBlank()) saved.rename(query, name.text.toString(), folder.text.toString())
    }
    AppDialog(onCloseRequest = { saved.renaming = null }, size = DpSize(460.dp, 200.dp), title = "Rename Query", resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) true.also { submit() } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledField("Name") { TextField(name, Modifier.fillMaxWidth().focusRequester(focus)) }
            LabeledField("Folder") { TextField(folder, Modifier.fillMaxWidth()) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { saved.renaming = null }) { Text("Cancel") }
                DefaultButton(onClick = ::submit, enabled = name.text.isNotBlank()) { Text("Rename") }
            }
        }
    }
}

@Composable
internal fun DeleteQueryDialog(app: AppState, query: SavedQuery) {
    val saved = app.savedQueries
    AppDialog(onCloseRequest = { saved.pendingDelete = null }, size = DpSize(420.dp, 160.dp), title = "Delete Query", resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete “${query.name}”? Its file ${query.file.fileName} is removed from disk.")
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { saved.pendingDelete = null }) { Text("Cancel") }
                DefaultButton(onClick = { saved.delete(query) }) { Text("Delete") }
            }
        }
    }
}

/**
 * Asks for the values of a query's `:name` / `${'$'}{name}` parameters, prefilled with the last ones used. Values go in
 * as written: `'paid'` for text, `42` for a number.
 */
@Composable
internal fun ParametersDialog(app: AppState, tab: ConsoleTab, request: ParameterRequest) {
    val fields = remember(request) { request.names.associateWith { TextFieldState(app.parameterValues[it].orEmpty()) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun run() {
        tab.pendingParameters = null
        request.run(fields.mapValues { it.value.text.toString() }.filterValues { it.isNotEmpty() })
    }
    val height = (190 + request.names.size.coerceAtMost(8) * 40).dp
    AppDialog(onCloseRequest = { tab.pendingParameters = null }, size = DpSize(480.dp, height), title = "Query Parameters") {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) true.also { run() } else false
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Values are inserted as written: quote text ('paid'), leave numbers bare (42).", color = JewelTheme.globalColors.text.info)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(request.names) { name ->
                    LabeledField(name, labelWidth = 140) {
                        val modifier = if (name == request.names.first()) Modifier.fillMaxWidth().focusRequester(focus) else Modifier.fillMaxWidth()
                        TextField(fields.getValue(name), modifier, textStyle = JewelTheme.editorTextStyle)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingParameters = null }) { Text("Cancel") }
                DefaultButton(onClick = ::run) { Text("Run") }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, labelWidth: Int = 68, field: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(labelWidth.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f)) { field() }
    }
}

/** The script's first meaningful words, e.g. `select from orders` → "orders query"; falls back to the tab's title. */
private fun suggestedName(tab: ConsoleTab): String {
    val sql = tab.editor.text.toString()
    val table = Regex("""\b(?:from|into|update|table)\s+[`"\[]?([\w.]+)""", RegexOption.IGNORE_CASE).find(sql)?.groupValues?.get(1)?.substringAfterLast('.')
    return table?.let { "$it query" } ?: tab.title.removeSuffix(".sql")
}
