package dev.datagrok.app.grid

import androidx.compose.foundation.horizontalScroll
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
import dev.datagrok.app.table.ViewChip
import dev.datagrok.app.table.ViewChipGroup
import dev.datagrok.app.ui.copyToClipboard
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

/** The JSON of a set of rows, built off the UI thread: the full [text] for copying, shortened [lines] for display. */
private class JsonContent(val json: JsonArray, val text: String, val lines: List<String>, val shortened: Boolean) {
    val longest = lines.maxOfOrNull { it.length } ?: 0
}

/** Lines longer than this are cut on screen. */
private const val MaxLineChars = 1000

/** The grid's rows as JSON, in its column order and without hidden columns; read-only, as text or a tree. */
@Composable
fun ResultJsonView(model: GridModel, state: DataGridState, modifier: Modifier = Modifier) {
    // Rows are captured on the UI thread (cheap), then parsed and encoded in the background; edits and column changes rebuild.
    val content by produceState<JsonContent?>(null, model, state) {
        snapshotFlow { model.json(state.visibleColumns) ?: rowsSnapshot(model, state.visibleColumns) }.collectLatest { snapshot ->
            value = withContext(Dispatchers.Default) {
                val json = snapshot as? JsonArray ?: rowsJson(snapshot as RowsSnapshot)
                val text = PrettyJson.encodeToString(JsonElement.serializer(), json)
                JsonContent(json, text, displayLines(text, MaxLineChars), text.lineSequence().any { it.length > MaxLineChars })
            }
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
            OutlinedButton(onClick = { content?.let { copyToClipboard(it.text) } }, enabled = content != null) { Text("Copy") }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val current = content
            when {
                current == null -> Text("Building JSON…", Modifier.align(Alignment.Center), color = info)
                tree -> JsonTree(current.json)
                else -> JsonLines(current)
            }
        }
    }
}

/** Only the lines on screen are laid out, so thousands of lines or one huge value stay smooth to scroll. */
@Composable
private fun JsonLines(content: JsonContent) {
    val style = JewelTheme.editorTextStyle
    val width = with(LocalDensity.current) { (editorCharWidth() * content.longest).toDp() } + 16.dp
    val palette = jsonPalette()
    ScrollableLines(width, selectable = true) {
        items(content.lines) { line -> Text(remember(line, palette) { highlightJson(line, palette) }, style = style, softWrap = false, maxLines = 1) }
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

private val DarkJsonPalette =
    JsonPalette(
        key = SpanStyle(color = Color(0xFFC77DBB)),
        string = SpanStyle(color = Color(0xFF6AAB73)),
        number = SpanStyle(color = Color(0xFF2AACB8)),
        literal = SpanStyle(color = Color(0xFFCF8E6D)),
    )

private val LightJsonPalette =
    JsonPalette(
        key = SpanStyle(color = Color(0xFF871094)),
        string = SpanStyle(color = Color(0xFF067D17)),
        number = SpanStyle(color = Color(0xFF1750EB)),
        literal = SpanStyle(color = Color(0xFF0033B3)),
    )

@Composable
internal fun jsonPalette(): JsonPalette = if (JewelTheme.isDark) DarkJsonPalette else LightJsonPalette

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
