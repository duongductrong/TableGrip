package dev.datagrok.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.datagrok.app.ui.copyToClipboard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

private val PrettyJson = Json { prettyPrint = true }

/** Shows (and, for editable cells, edits) the full value of the grid's focused cell. */
@Composable
fun ValuePanel(model: GridModel, state: DataGridState, modifier: Modifier = Modifier) {
    val focus = state.focus?.takeIf { it.row < model.rowCount && it.column < state.visibleColumns.size }
    Column(modifier.fillMaxSize()) {
        if (focus == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select a cell to see its value", color = JewelTheme.globalColors.text.info)
            }
            return@Column
        }
        val column = state.modelColumn(focus.column)
        val info = model.columns[column]
        val value = model.value(focus.row, column)
        val editor = model.editor?.takeIf { it.canEdit(focus.row, column) }
        val text = remember(focus, value) { TextFieldState(value.orEmpty()) }

        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.name, fontWeight = FontWeight.SemiBold)
            Text(info.typeName.lowercase(), color = JewelTheme.globalColors.text.info)
            Box(Modifier.weight(1f))
            Text(if (value == null) "NULL" else "${value.length} chars", color = JewelTheme.globalColors.text.info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        TextArea(
            state = text,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            readOnly = editor == null,
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text(if (value == null) "<null>" else "") },
        )
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val json = remember(value) { value?.let { runCatching { PrettyJson.parseToJsonElement(it) }.getOrNull() } }
            if (json != null) {
                OutlinedButton(onClick = { text.setTextAndPlaceCursorAtEnd(PrettyJson.encodeToString(JsonElement.serializer(), json)) }) {
                    Text("Format JSON")
                }
            }
            OutlinedButton(onClick = { copyToClipboard(text.text.toString()) }) { Text("Copy") }
            Box(Modifier.weight(1f))
            if (editor != null) {
                OutlinedButton(onClick = { editor.setValue(focus.row, column, null) }) { Text("Set NULL") }
                DefaultButton(onClick = { editor.setValue(focus.row, column, text.text.toString()) }, enabled = text.text.toString() != value) {
                    Text("Apply")
                }
            }
        }
    }
}
