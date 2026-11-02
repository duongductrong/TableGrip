package dev.tablegrip.app.grid

import dev.tablegrip.app.console.SyntaxToken
import dev.tablegrip.app.ui.theme.LocalColorTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.ui.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

private val PrettyJson = Json { prettyPrint = true }

/**
 * The JSON of a set of rows, built off the UI thread: the full [text] for copying, shortened [lines] for display. [rows]
 * is the model row of each object in the array (rows marked for deletion are left out); [recordStarts] maps the line
 * each object opens on to its index in the array.
 */
private class JsonContent(val json: JsonArray, val text: String, val lines: List<String>, val shortened: Boolean, val rows: List<Int>) {
    val longest = lines.maxOfOrNull { it.length } ?: 0
    val recordStarts: Map<Int, Int> =
        lines.withIndex().filter { (_, line) -> line.startsWith(RecordIndent) && line.getOrNull(RecordIndent.length) == '{' }.withIndex()
            .associate { (record, line) -> line.index to record }
}

/** How deep [PrettyJson] indents the array's objects: a line starting with exactly this and `{` opens a record. */
private const val RecordIndent = "    "

/** Lines longer than this are cut on screen. */
private const val MaxLineChars = 1000

/**
 * The grid's rows as JSON, in its column order and without hidden columns, as text or a tree. Where the model takes
 * edits, each record has an edit button that opens it in the JSON record editor, and new records can be added.
 */
@Composable
fun ResultJsonView(model: GridModel, state: DataGridState, modifier: Modifier = Modifier) {
    // Rows are captured on the UI thread (cheap), then parsed and encoded in the background; edits and column changes rebuild.
    val content by produceState<JsonContent?>(null, model, state) {
        snapshotFlow {
            val rows = (0 until model.rowCount).filter { model.rowStatus(it) != RowStatus.Deleted }
            (model.json(state.visibleColumns) ?: rowsSnapshot(model, state.visibleColumns)) to rows
        }.collectLatest { (snapshot, rows) ->
            value = withContext(Dispatchers.Default) {
                val json = snapshot as? JsonArray ?: rowsJson(snapshot as RowsSnapshot)
                // Exact digits: the encoder alone would print big or fixed-point numbers through a Double.
                val text = PrettyJson.encodeToString(JsonElement.serializer(), exactNumbers(json))
                JsonContent(json, text, displayLines(text, MaxLineChars), text.lineSequence().any { it.length > MaxLineChars }, rows)
            }
        }
    }
    val records = model.records
    val edit: (@Composable (Int) -> Unit)? =
        records?.let { editor ->
            { index ->
                content?.rows?.getOrNull(index)?.let { row -> RecordEditButton(editor, row) { state.editRecord(model, row) } }
            }
        }
    var tree by remember { mutableStateOf(false) }
    val info = JewelTheme.globalColors.text.info
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewChipGroup {
                ViewChip("Text", !tree) { tree = false }
                ViewChip("Tree", tree) { tree = true }
            }
            Box(Modifier.weight(1f))
            content?.let { current ->
                if (current.shortened) Text("Long values are cut here; Copy gets them in full", Modifier.padding(end = 8.dp), color = info)
                Text("${current.json.size} object(s)", Modifier.padding(end = 8.dp), color = info)
            }
            if (records != null && records.canAddRecord) {
                val noun = records.recordNoun.replaceFirstChar(Char::uppercase)
                Tooltip({ Text("Write a new ${records.recordNoun} as JSON") }) {
                    OutlinedButton(onClick = { state.newRecord(model) }) { Text("Add $noun…") }
                }
            }
            OutlinedButton(onClick = { content?.let { copyToClipboard(it.text) } }, enabled = content != null) { Text("Copy") }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val current = content
            when {
                current == null -> Text("Building JSON…", Modifier.align(Alignment.Center), color = info)
                tree -> JsonTree(current.json, recordAction = edit)
                else -> JsonLines(current, edit)
            }
        }
    }
}

/**
 * Only the lines on screen are laid out, so thousands of lines or one huge value stay smooth to scroll. With [edit],
 * a gutter holds an edit button on the line each record opens on.
 */
@Composable
private fun JsonLines(content: JsonContent, edit: (@Composable (Int) -> Unit)?) {
    val style = JewelTheme.editorTextStyle
    val gutter = if (edit != null) RecordGutter else 0.dp
    val width = with(LocalDensity.current) { (editorCharWidth() * content.longest).toDp() } + 16.dp + gutter
    val palette = jsonPalette()
    ScrollableLines(width, selectable = true) {
        itemsIndexed(content.lines) { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (edit != null) {
                    Box(Modifier.width(gutter), contentAlignment = Alignment.Center) { content.recordStarts[index]?.let { DisableSelection { edit(it) } } }
                }
                Text(remember(line, palette) { highlightJson(line, palette) }, style = style, softWrap = false, maxLines = 1)
            }
        }
    }
}

private val RecordGutter = 22.dp

/** Tags each record's edit button, for tests. */
internal const val RecordEditTag = "record-edit"

/** A small pencil that opens a record in the JSON editor; dimmed until hovered, and disabled with the reason when it can't be edited. */
@Composable
internal fun RecordEditButton(records: RecordEditor, row: Int, onClick: () -> Unit) {
    val reason = records.whyNotEditable(row)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip({ Text(reason ?: "Edit this ${records.recordNoun} as JSON") }) {
        Box(
            Modifier.size(18.dp)
                .testTag(RecordEditTag)
                .clip(RoundedCornerShape(3.dp))
                .background(if (hovered && reason == null) JewelTheme.globalColors.borders.normal.copy(alpha = 0.35f) else Color.Transparent)
                .hoverable(interaction)
                .then(if (reason == null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
                .alpha(if (reason != null) 0.25f else if (hovered) 1f else 0.55f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AllIconsKeys.Actions.Edit, null, Modifier.size(14.dp))
        }
    }
}

/** JSON token colours, matching the SQL editor's palette for each theme. */
internal class JsonPalette(val key: SpanStyle, val string: SpanStyle, val number: SpanStyle, val literal: SpanStyle) {
    fun style(type: JsonTokenType): SpanStyle =
        when (type) {
            JsonTokenType.Key -> key
            JsonTokenType.String -> string
            JsonTokenType.Number -> number
            JsonTokenType.Literal -> literal
        }
}

/** Keys, strings, numbers and literals in the theme's quoted-identifier, string, number and keyword colours, as the SQL editor shows them. */
@Composable
internal fun jsonPalette(): JsonPalette {
    val syntax = LocalColorTheme.current.syntax
    return remember(syntax) {
        fun color(token: SyntaxToken) = SpanStyle(color = syntax.getValue(token).color)
        JsonPalette(key = color(SyntaxToken.QuotedIdentifier), string = color(SyntaxToken.String), number = color(SyntaxToken.Number), literal = color(SyntaxToken.Keyword))
    }
}

/** One line of pretty JSON with its tokens coloured; punctuation keeps the default text colour. */
internal fun highlightJson(line: String, palette: JsonPalette): AnnotatedString = buildAnnotatedString {
    append(line)
    jsonTokens(line).forEach { addStyle(palette.style(it.type), it.start, it.end) }
}

/** The width of one character in the (monospace) editor font, to size content that is never laid out in full. */
@Composable
internal fun editorCharWidth(): Float {
    val style = JewelTheme.editorTextStyle
    val measurer = rememberTextMeasurer()
    return remember(style) { measurer.measure("0123456789", style).size.width / 10f }
}

/**
 * A virtualized list of lines with vertical and horizontal scrollbars. [contentWidth] is the widest line, since only
 * the visible lines are measured; narrower content still fills the pane so rows stay clickable across it.
 */
@Composable
internal fun ScrollableLines(contentWidth: Dp, modifier: Modifier = Modifier, selectable: Boolean = false, content: LazyListScope.() -> Unit) {
    val vertical = rememberLazyListState()
    val horizontal = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxWidth - ScrollbarAllowance
        val lines =
            @Composable {
                Box(Modifier.fillMaxSize().padding(end = ScrollbarAllowance, bottom = ScrollbarAllowance).horizontalScroll(horizontal)) {
                    LazyColumn(Modifier.width(maxOf(contentWidth, viewport)).fillMaxHeight(), state = vertical, content = content)
                }
            }
        if (selectable) SelectionContainer { lines() } else lines()
        VerticalScrollbar(vertical, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(bottom = ScrollbarAllowance))
        HorizontalScrollbar(horizontal, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = ScrollbarAllowance))
    }
}
