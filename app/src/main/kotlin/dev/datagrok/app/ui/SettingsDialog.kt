package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.datagrok.app.AppState
import dev.datagrok.app.AppTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
internal fun SettingsDialog(app: AppState) {
    val settings = app.settings
    var theme by remember { mutableStateOf(app.theme) }
    val fontSize = remember { TextFieldState(settings.editorFontSize.toString()) }
    val pageSize = remember { TextFieldState(settings.pageSize.toString()) }
    val maxRows = remember { TextFieldState(settings.maxResultRows.toString()) }
    val timeout = remember { TextFieldState(settings.queryTimeoutSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        fun TextFieldState.int(range: IntRange, label: String): Int? =
            text.toString().trim().toIntOrNull()?.takeIf { it in range }
                ?: null.also { error = "$label must be between ${range.first} and ${range.last}." }
        val font = fontSize.int(9..32, "Editor font size") ?: return
        val page = pageSize.int(10..10_000, "Page size") ?: return
        val rows = maxRows.int(10..1_000_000, "Max result rows") ?: return
        val seconds = timeout.int(0..86_400, "Query timeout") ?: return
        app.updateSettings {
            it.copy(theme = theme.name, editorFontSize = font, pageSize = page, maxResultRows = rows, queryTimeoutSeconds = seconds)
        }
        app.settingsOpen = false
    }

    DialogWindow(onCloseRequest = { app.settingsOpen = false }, state = rememberDialogState(size = DpSize(480.dp, 400.dp)), title = "Settings", resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Appearance", fontWeight = FontWeight.SemiBold)
            Field("Theme") {
                ListComboBox(
                    items = AppTheme.entries.map { it.label },
                    selectedIndex = theme.ordinal,
                    onSelectedItemChange = { theme = AppTheme.entries[it] },
                    modifier = Modifier.width(180.dp),
                )
            }
            Field("Editor font size") { TextField(fontSize, Modifier.width(80.dp)) }
            Text("Queries", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Field("Table page size") { TextField(pageSize, Modifier.width(100.dp)) }
            Field("Max result rows") { TextField(maxRows, Modifier.width(100.dp)) }
            Field("Query timeout (s)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(timeout, Modifier.width(80.dp))
                    Text("0 = none", color = JewelTheme.globalColors.text.info)
                }
            }
            Spacer(Modifier.weight(1f))
            error?.let { Text(it, color = JewelTheme.globalColors.text.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { app.settingsOpen = false }) { Text("Cancel") }
                DefaultButton(onClick = ::save) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(150.dp))
        content()
    }
}
