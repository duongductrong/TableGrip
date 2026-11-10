package dev.tablegrip.app.grid

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.driver.BinaryText
import dev.tablegrip.core.edit.ColumnEdit
import dev.tablegrip.core.edit.EditCheck
import dev.tablegrip.core.edit.EditKind
import dev.tablegrip.core.edit.Uuids
import dev.tablegrip.core.filter.ValueKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val PrettyJson = Json { prettyPrint = true }

/** How the value panel shows a value; only the ones that fit the value are offered. */
private enum class ValueView(val label: String) { Text(tr("Text")), Tree(tr("Tree")), Hex(tr("Hex")), Image(tr("Image")) }

/** Shows (and, for editable cells, edits) the full value of the grid's focused cell. */
@Composable
fun ValuePanel(model: GridModel, state: DataGridState, modifier: Modifier = Modifier) {
    val focus = state.focus?.takeIf { it.row < model.rowCount && it.column < state.visibleColumns.size }
    Column(modifier.fillMaxSize()) {
        if (focus == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Select a cell to see its value"), color = JewelTheme.globalColors.text.info)
            }
            return@Column
        }
        val column = state.modelColumn(focus.column)
        val info = model.columns[column]
        val value = model.value(focus.row, column)
        val editor = model.editor?.takeIf { it.canEdit(focus.row, column) }
        val edit = editor?.cellEdit(focus.row, column)
        val kind = edit?.kind
        val text = remember(focus, value) { TextFieldState(value.orEmpty()) }
        val typed = text.text.toString()
        val check = remember(edit, typed) { editor?.takeIf { typed != value.orEmpty() }?.check(focus.row, column, typed) }
        val problem = model.problem(focus.row, column)
        val json = remember(value) { value?.takeIf { it.trimStart().startsWith('{') || it.trimStart().startsWith('[') }?.let { runCatching { PrettyJson.parseToJsonElement(it) }.getOrNull() } }
        val binary = remember(value) { value?.takeIf { info.isBinary }?.let(BinaryText::decode) }
        val image = remember(binary) { binary?.takeIf { it.complete }?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.bytes).toComposeImageBitmap() }.getOrNull() } }
        val timestamp = remember(value, info) {
            value?.takeIf { ValueKind.of(info.typeName, info.jdbcType).let { it == ValueKind.DateTime || it == ValueKind.Date } }?.let(::parseTimestamp)
        }

        val views = buildList {
            add(ValueView.Text)
            if (json != null && (json is JsonObject || json is JsonArray)) add(ValueView.Tree)
            if (binary != null) add(ValueView.Hex)
            if (image != null) add(ValueView.Image)
        }
        // Structured values open in their richer view, and the choice sticks while moving between similar cells.
        var chosen by remember(info.name) { mutableStateOf<ValueView?>(null) }
        val preferred = when {
            image != null -> ValueView.Image
            binary != null -> ValueView.Hex
            else -> ValueView.Text
        }
        val view = chosen?.takeIf { it in views } ?: preferred

        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.name, fontWeight = FontWeight.SemiBold)
            Text(info.typeName.lowercase(), color = JewelTheme.globalColors.text.info)
            Box(Modifier.weight(1f))
            val size =
                when {
                    value == null -> "NULL"
                    binary != null -> listOfNotNull(binaryKind(binary.bytes), tr("{0} bytes", binary.totalSize)).joinToString(" · ")
                    else -> tr("{0} chars", value.length)
                }
            Text(size, color = JewelTheme.globalColors.text.info)
        }
        if (views.size > 1) {
            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                views.forEach { option -> ViewChip(option.label, option == view) { chosen = option } }
            }
        }
        if (timestamp != null) TimestampInfo(timestamp)
        (check?.hint() ?: problem?.let { it to ErrorColor })?.let { (message, color) ->
            Text(message, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color ?: JewelTheme.globalColors.text.info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(top = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            when (view) {
                ValueView.Tree -> JsonTree(json!!)
                ValueView.Hex -> HexView(binary!!)
                ValueView.Image -> Image(image!!, contentDescription = info.name, modifier = Modifier.fillMaxSize())
                ValueView.Text ->
                    when {
                        editor != null && kind == EditKind.Boolean -> BooleanChoice(edit, value) { editor.setValue(focus.row, column, it) }
                        editor != null && kind?.isTemporal == true ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(text, Modifier.fillMaxWidth(), textStyle = JewelTheme.editorTextStyle, placeholder = { Text(if (value == null) "<null>" else "") })
                                TemporalPickerContent(kind, text, onApply = { apply(editor, focus.row, column, check) })
                            }
                        else ->
                            TextArea(
                                state = text,
                                modifier = Modifier.fillMaxSize(),
                                readOnly = editor == null,
                                textStyle = JewelTheme.editorTextStyle,
                                placeholder = { Text(if (value == null) "<null>" else "") },
                            )
                    }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if ((json != null || kind == EditKind.Json) && view == ValueView.Text) {
                OutlinedButton(onClick = { reformatJson(text.text.toString(), pretty = true)?.let(text::setTextAndPlaceCursorAtEnd) }) {
                    Text(tr("Format JSON"))
                }
                if (editor != null) {
                    OutlinedButton(onClick = { reformatJson(text.text.toString(), pretty = false)?.let(text::setTextAndPlaceCursorAtEnd) }) {
                        Text(tr("Minify"))
                    }
                }
            }
            if (editor != null && kind == EditKind.Uuid) {
                OutlinedButton(onClick = { text.setTextAndPlaceCursorAtEnd(Uuids.v4().toString()) }) { Text(tr("New UUID")) }
                OutlinedButton(onClick = { text.setTextAndPlaceCursorAtEnd(Uuids.v7().toString()) }) { Text(tr("New v7")) }
            }
            OutlinedButton(onClick = { copyToClipboard(text.text.toString()) }) { Text(tr("Copy")) }
            Box(Modifier.weight(1f))
            if (editor != null) {
                if (editor.canSetDefault(focus.row, column)) OutlinedButton(onClick = { editor.setDefault(focus.row, column) }) { Text(tr("Set DEFAULT")) }
                // In a text column, empty and NULL are different values; the text area alone can't say which.
                if (kind == EditKind.Text && value != "") OutlinedButton(onClick = { editor.setValue(focus.row, column, "") }) { Text(tr("Empty")) }
                if (editor.canSetNull(focus.row, column)) OutlinedButton(onClick = { editor.setValue(focus.row, column, null) }) { Text(tr("Set NULL")) }
                if (kind != EditKind.Boolean || view != ValueView.Text) {
                    DefaultButton(onClick = { apply(editor, focus.row, column, check) }, enabled = typed != value.orEmpty() && check !is EditCheck.Invalid) {
                        Text(tr("Apply"))
                    }
                }
            }
        }
    }
}

/** Stores what the panel's text [check]ed to: its canonical form, or NULL for blank input in a typed column. */
private fun apply(editor: GridEditor, row: Int, column: Int, check: EditCheck?) {
    when (check) {
        is EditCheck.Ok -> editor.setValue(row, column, check.value)
        EditCheck.Empty -> editor.setValue(row, column, null)
        is EditCheck.Invalid, null -> {}
    }
}

/** True, false and (when the column takes it) NULL, for boolean cells. */
@Composable
private fun BooleanChoice(edit: ColumnEdit, value: String?, onChange: (String?) -> Unit) {
    val current = edit.booleanOf(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RadioButtonRow(tr("True"), selected = value != null && current == true, onClick = { onChange(edit.booleanText(true)) })
        RadioButtonRow(tr("False"), selected = value != null && current == false, onClick = { onChange(edit.booleanText(false)) })
        if (edit.nullable) RadioButtonRow("NULL", selected = value == null, onClick = { onChange(null) })
        if (value != null && current == null) Text(tr("“{0}” isn't a boolean", value), color = ErrorColor)
    }
}

@Composable
private fun TimestampInfo(value: TimestampValue) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        timestampConversions(value).forEach { (label, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.width(150.dp), color = JewelTheme.globalColors.text.info)
                Text(text, style = JewelTheme.editorTextStyle)
            }
        }
        if (!value.hadOffset) Text(tr("No time zone in the value: read as UTC"), color = JewelTheme.globalColors.text.info)
    }
}

/**
 * A collapsible JSON tree; objects and arrays open on click. With [recordAction], each top-level element of an array
 * gets it (e.g. an edit button) in front, given its index.
 */
@Composable
internal fun JsonTree(root: JsonElement, recordAction: (@Composable (Int) -> Unit)? = null) {
    var expanded by remember(root) { mutableStateOf(jsonContainerPaths(root).filter { path -> path.count { it == '.' || it == '[' } < 2 }.toSet()) }
    val lines = remember(root, expanded) { jsonLines(root, expanded) }
    val info = JewelTheme.globalColors.text.info
    val palette = jsonPalette()
    // Indent and chevron, plus "label: summary" in the monospace font; long values are already cut in the summary.
    val charWidth = editorCharWidth()
    val density = LocalDensity.current
    val width = remember(lines, charWidth, density) {
        with(density) {
            lines.maxOfOrNull { (it.depth * 14 + 26).dp + ((it.label?.length?.plus(2) ?: 0) + it.summary.length).times(charWidth).toDp() } ?: 0.dp
        } + 16.dp
    }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { expanded = jsonContainerPaths(root) }) { Text(tr("Expand All")) }
            OutlinedButton(onClick = { expanded = emptySet() }) { Text(tr("Collapse All")) }
        }
        ScrollableLines(width, Modifier.padding(top = 6.dp)) {
            items(lines, key = { it.path }) { line ->
                val open = line.path == "$" || line.path in expanded
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = line.isContainer && line.path != "$") {
                            expanded = if (open) expanded - line.path else expanded + line.path
                        }
                        .padding(start = (line.depth * 14).dp, top = 1.dp, bottom = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (recordAction != null && line.depth == 1 && root is JsonArray) {
                        Box(Modifier.size(18.dp)) { line.label?.toIntOrNull()?.let { recordAction(it) } }
                    }
                    Box(Modifier.size(14.dp)) {
                        if (line.isContainer && line.path != "$") {
                            Icon(if (open) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, null, tint = info)
                        }
                    }
                    line.label?.let { label ->
                        // Array indexes stay muted; object keys take the key colour.
                        val color = if (line.path.endsWith("]")) info else palette.key.color
                        Text("$label:", style = JewelTheme.editorTextStyle, color = color)
                    }
                    val element = line.element
                    val color =
                        when {
                            line.isContainer -> info
                            element is JsonPrimitive && element.isString -> palette.string.color
                            element is JsonNull || (element is JsonPrimitive && element.booleanOrNull != null) -> palette.literal.color
                            else -> palette.number.color
                        }
                    Text(line.summary, style = JewelTheme.editorTextStyle, color = color, softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HexView(binary: BinaryText.Preview) {
    val dump = remember(binary) { hexDump(binary.bytes) }
    val state = remember(dump) { TextFieldState(dump) }
    Column(Modifier.fillMaxSize()) {
        TextArea(state = state, modifier = Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
        if (binary.totalSize > minOf(binary.bytes.size, 4096)) {
            Text(tr("Showing the first {0} of {1} bytes", minOf(binary.bytes.size, 4096), binary.totalSize), color = JewelTheme.globalColors.text.info)
        }
    }
}
