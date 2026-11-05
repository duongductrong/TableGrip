package dev.tablegrip.app.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.AppState
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.export.CopyFormat
import dev.tablegrip.core.export.ResultWriter
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/** Streams all rows of a source into a writer built from its columns; returns the row count. */
typealias RowStreamer = suspend (writer: (List<ResultColumn>) -> ResultWriter, onProgress: (Long) -> Unit) -> Long

/**
 * What to export: the rows on screen, and optionally a streamer for the full source (e.g. every row of a table with
 * the viewer's filter and sort), which can be far larger than what's loaded.
 */
class ExportRequest(
    val title: String,
    val fileName: String,
    val columns: List<ResultColumn>,
    val loadedRows: List<List<String?>>,
    val dialect: SqlDialect,
    val tableName: String,
    val streamAll: RowStreamer? = null,
)

private val Formats = listOf(CopyFormat.Csv, CopyFormat.Tsv, CopyFormat.Json, CopyFormat.SqlInsert, CopyFormat.Markdown)

@Composable
internal fun ExportDialog(app: AppState, request: ExportRequest) {
    var format by remember { mutableStateOf(CopyFormat.Csv) }
    var header by remember { mutableStateOf(true) }
    var all by remember { mutableStateOf(request.streamAll != null) }

    fun export() {
        val file = chooseTarget("${request.fileName}.${format.extension}") ?: return
        app.exportRequest = null
        val chosenFormat = format
        val includeHeader = header
        val everything = all && request.streamAll != null
        app.scope.launch {
            app.status = "Exporting ${request.title}…"
            try {
                val count =
                    withContext(Dispatchers.IO) {
                        file.bufferedWriter().use { out ->
                            fun writer(columns: List<ResultColumn>) = ResultWriter(chosenFormat, columns, out, includeHeader, request.dialect, request.tableName)
                            if (everything) {
                                request.streamAll!!.invoke(::writer) { rows -> app.status = "Exporting ${request.title}: %,d rows…".format(rows) }
                            } else {
                                writeLoaded(writer(request.columns), request.loadedRows)
                            }
                        }
                    }
                app.status = "Exported %,d rows to ${file.path}".format(count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.status = "Export failed: ${e.message}"
                runCatching { file.delete() }
            }
        }
    }

    AppDialog(
        onCloseRequest = { app.exportRequest = null },
        size = DpSize(460.dp, 300.dp),
        title = "Export ${request.title}",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Format", Modifier.width(80.dp))
                ListComboBox(
                    items = Formats.map { it.label },
                    selectedIndex = Formats.indexOf(format),
                    onSelectedItemChange = { format = Formats[it] },
                    modifier = Modifier.width(180.dp),
                )
            }
            if (format == CopyFormat.Csv || format == CopyFormat.Tsv) {
                CheckboxRow("Include column names", header, onCheckedChange = { header = it })
            }
            if (request.streamAll != null) {
                RadioButtonRow("All rows (with the current filter and sort)", selected = all, onClick = { all = true })
                RadioButtonRow("Only the %,d rows loaded".format(request.loadedRows.size), selected = !all, onClick = { all = false })
            } else {
                Text("%,d rows".format(request.loadedRows.size), color = JewelTheme.globalColors.text.info)
            }
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { app.exportRequest = null }) { Text("Cancel") }
                DefaultButton(onClick = ::export) { Text("Export…") }
            }
        }
    }
}

private fun writeLoaded(writer: ResultWriter, rows: List<List<String?>>): Long {
    rows.forEach(writer::write)
    writer.finish()
    return writer.rowCount
}

/** Native save dialog; null when cancelled. */
private fun chooseTarget(suggested: String): File? {
    val dialog = FileDialog(null as Frame?, "Export", FileDialog.SAVE)
    dialog.directory = System.getProperty("user.home") + "/Downloads"
    dialog.file = suggested
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

