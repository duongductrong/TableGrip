package dev.datagrok.app.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.DataGrid
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.grid.GridActions
import dev.datagrok.app.grid.ResultGridModel
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.app.ui.EnvironmentChip
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.importer.ImportFormat
import dev.datagrok.core.importer.InferredType
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.CircularProgressIndicatorBig
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun ImportDialog(app: AppState, wizard: ImportWizard) {
    val running = wizard.step == ImportStep.Running
    AppDialog(
        onCloseRequest = { if (!running) app.importWizard = null },
        size = DpSize(920.dp, 660.dp),
        title = "Import Data into ${wizard.config.name}",
        closeOnEscape = !running,
    ) {
        ImportDialogContent(app, wizard)
    }
}

/** The wizard's body, apart from its window so snapshots can show it. */
@Composable
internal fun ImportDialogContent(app: AppState, wizard: ImportWizard) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Steps(wizard)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            when (wizard.step) {
                ImportStep.Source -> SourceStep(wizard)
                ImportStep.Target -> TargetStep(wizard)
                ImportStep.Running -> RunningStep(wizard, Modifier.align(Alignment.Center))
                ImportStep.Done -> DoneStep(app, wizard, Modifier.align(Alignment.Center))
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Footer(app, wizard)
    }
}

@Composable
private fun Steps(wizard: ImportWizard) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val current = if (wizard.step == ImportStep.Done) 3 else wizard.step.ordinal + 1
        listOf("Choose file", "Choose target", "Import").forEachIndexed { i, label ->
            if (i > 0) Icon(AllIconsKeys.General.ChevronRight, null, tint = JewelTheme.globalColors.text.info)
            val number = i + 1
            val done = number < current || wizard.step == ImportStep.Done
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).background(
                        if (number == current || done) ChangedColor else JewelTheme.globalColors.borders.normal,
                        RoundedCornerShape(10.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (done) "✓" else "$number", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(label, fontWeight = if (number == current) FontWeight.SemiBold else FontWeight.Normal, color = if (number == current) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info)
            }
        }
        Box(Modifier.weight(1f))
        EnvironmentChip(wizard.config)
        Text(wizard.namespace.displayName, color = JewelTheme.globalColors.text.info)
    }
}

// region Source

@Composable
private fun SourceStep(wizard: ImportWizard) {
    val file = wizard.file
    if (file == null) {
        Box(Modifier.fillMaxSize().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(8.dp))) {
            EmptyState(
                "Choose a file to import",
                "CSV or TSV, Excel workbooks (.xlsx) and JSON (an array of objects, or one object per line). " +
                    "You'll see the first rows and pick the target before anything is written.",
                Modifier.align(Alignment.Center),
            ) {
                DefaultButton(onClick = wizard::chooseFile) { Text("Choose File…") }
                wizard.previewError?.let { Notice(it, NoticeKind.Error) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AllIconsKeys.FileTypes.Text, null)
            Text(file.name, fontWeight = FontWeight.SemiBold)
            Text("${byteSize(file.length())} · ${wizard.formatLabel}", color = JewelTheme.globalColors.text.info)
            Link("Change…", onClick = wizard::chooseFile)
            Box(Modifier.weight(1f))
            if (wizard.loadingPreview) CircularProgressIndicator()
        }
        OptionsRow(wizard)
        wizard.previewError?.let { Notice(it, NoticeKind.Error) }
        val preview = wizard.preview
        if (preview != null) {
            val model =
                remember(preview) {
                    ResultGridModel(
                        QueryResult.Rows(
                            preview.columns.mapIndexed { i, name -> ResultColumn(name, typeLabel(preview.types[i])) },
                            preview.rows.map { it.values },
                            truncated = false,
                            elapsedMillis = 0,
                        ),
                    )
                }
            Text(
                "First ${preview.rows.size} row(s) · ${preview.columns.size} column(s)" + if (preview.rows.size >= 200) ". The whole file is imported." else "",
                color = JewelTheme.globalColors.text.info,
            )
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))) {
                DataGrid(model = model, state = remember(preview) { DataGridState() }, actions = GridActions())
            }
        }
    }
}

@Composable
private fun OptionsRow(wizard: ImportWizard) {
    val options = wizard.options ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (options.format) {
            ImportFormat.Csv -> {
                Text("Delimiter", color = JewelTheme.globalColors.text.info)
                Picker(
                    Delimiters.firstOrNull { it.second == options.delimiter }?.first ?: "Detect",
                    placeholder = false,
                    options = Delimiters,
                    onPick = wizard::setDelimiter,
                    modifier = Modifier.width(130.dp),
                )
                Text("Encoding", color = JewelTheme.globalColors.text.info)
                Picker(
                    options.charset?.displayName() ?: "Detect",
                    placeholder = false,
                    options = Encodings.map { it to java.nio.charset.Charset.forName(it) },
                    onPick = wizard::setCharset,
                    modifier = Modifier.width(140.dp),
                )
                CheckboxRow("First row is the header", options.hasHeader, onCheckedChange = wizard::setHeader)
            }
            ImportFormat.Xlsx -> {
                Text("Sheet", color = JewelTheme.globalColors.text.info)
                Picker(options.sheet ?: "First sheet", placeholder = false, options = wizard.preview?.sheets.orEmpty().map { it to it }, onPick = wizard::setSheet, modifier = Modifier.width(180.dp))
                CheckboxRow("First row is the header", options.hasHeader, onCheckedChange = wizard::setHeader)
            }
            ImportFormat.Json -> Text("Each object becomes a row; nested values are kept as JSON text.", color = JewelTheme.globalColors.text.info)
        }
    }
}

// endregion

// region Target

@Composable
private fun TargetStep(wizard: ImportWizard) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButtonRow("New table", selected = !wizard.intoExisting, onClick = { wizard.intoExisting = false })
            TextField(wizard.newTableName, Modifier.width(220.dp), enabled = !wizard.intoExisting, placeholder = { Text("table_name") })
            RadioButtonRow("Existing table", selected = wizard.intoExisting, onClick = { wizard.intoExisting = true })
            Picker(
                wizard.targetTable?.name ?: "Choose table",
                placeholder = wizard.targetTable == null,
                options = wizard.tables.map { it.name to it },
                onPick = { table ->
                    wizard.intoExisting = true
                    wizard.pickExisting(table)
                },
                modifier = Modifier.width(200.dp),
            )
        }
        wizard.targetError?.let { Notice(it, NoticeKind.Error) }
        wizard.runError?.let { Notice("$it", NoticeKind.Error) }
        if (wizard.intoExisting) ExistingMapping(wizard, Modifier.weight(1f)) else NewMapping(wizard, Modifier.weight(1f))
        wizard.targetProblem?.let { Notice(it, NoticeKind.Info) }
    }
}

@Composable
private fun NewMapping(wizard: ImportWizard, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        MappingHeader {
            Box(Modifier.width(32.dp))
            HeaderCell("File column", Modifier.weight(1f))
            HeaderCell("Column", Modifier.weight(1f))
            HeaderCell("Type", Modifier.weight(1f))
            HeaderCell("Found in the first rows", Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(wizard.newColumns, key = { it.source }) { plan ->
                Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { Checkbox(plan.include, { plan.include = it }) }
                    Text(plan.sourceName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (plan.include) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info)
                    TextField(plan.name, Modifier.weight(1f), enabled = plan.include, outline = if (plan.include && plan.name.text.isBlank()) Outline.Error else Outline.None)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        TextField(plan.type, Modifier.weight(1f), enabled = plan.include)
                    }
                    Text(typeLabel(plan.inferred) + if (plan.inferred.nullable) ", has blanks" else "", Modifier.weight(1f), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ExistingMapping(wizard: ImportWizard, modifier: Modifier) {
    val sources = wizard.preview?.columns.orEmpty()
    Column(modifier.fillMaxWidth()) {
        MappingHeader {
            HeaderCell("Table column", Modifier.weight(1f))
            HeaderCell("Type", Modifier.weight(0.7f))
            HeaderCell("Filled from", Modifier.weight(1.2f))
        }
        if (wizard.targetTable == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { Text("Choose a table above.", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info) }
            return
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(wizard.existingColumns, key = { it.column.name }) { plan ->
                val column = plan.column
                val required = !column.nullable && column.defaultValue == null && !column.autoIncrement
                Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(column.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (column.primaryKey) FontWeight.SemiBold else FontWeight.Normal)
                        if (required) Tag("REQUIRED", WarningColor)
                        if (column.autoIncrement) Tag("AUTO", ChangedColor)
                    }
                    Text(column.typeName.lowercase(), Modifier.weight(0.7f), color = JewelTheme.globalColors.text.info)
                    val skip = if (column.autoIncrement || column.defaultValue != null) "Leave to the database" else "Leave empty (NULL)"
                    Picker(
                        plan.source?.let { sources.getOrNull(it) } ?: skip,
                        placeholder = plan.source == null,
                        options = listOf<Pair<String, Int?>>(skip to null) + sources.mapIndexed { i, name -> name to i },
                        onPick = { plan.source = it },
                        modifier = Modifier.weight(1.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MappingHeader(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

// endregion

@Composable
private fun RunningStep(wizard: ImportWizard, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicatorBig()
        Text("Importing %,d rows…".format(wizard.imported), fontWeight = FontWeight.SemiBold)
        Text("Everything goes in one transaction: cancelling or a bad row leaves the database as it was.", color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun DoneStep(app: AppState, wizard: ImportWizard, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(AllIconsKeys.General.InspectionsOK, null, Modifier.size(32.dp))
        Text("Imported %,d rows into ${wizard.result?.name}".format(wizard.imported), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("from ${wizard.file?.name}", color = JewelTheme.globalColors.text.info)
        wizard.result?.let { table ->
            DefaultButton(onClick = {
                app.importWizard = null
                app.workspace.openTable(wizard.config, table)
            }) { Text("Open ${table.name}") }
        }
    }
}

@Composable
private fun Footer(app: AppState, wizard: ImportWizard) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wizard.config.environment == Environment.Production && wizard.step == ImportStep.Target) {
            Icon(AllIconsKeys.General.Warning, null)
            Text("Writes to a Production connection", color = WarningColor)
        }
        if (wizard.config.readOnly) Text("This connection is read-only.", color = JewelTheme.globalColors.text.error)
        Box(Modifier.weight(1f))
        when (wizard.step) {
            ImportStep.Source -> {
                OutlinedButton(onClick = { app.importWizard = null }) { Text("Cancel") }
                DefaultButton(onClick = wizard::next, enabled = wizard.canContinue) { Text("Next") }
            }
            ImportStep.Target -> {
                OutlinedButton(onClick = wizard::back) { Text("Back") }
                OutlinedButton(onClick = { app.importWizard = null }) { Text("Cancel") }
                DefaultButton(onClick = wizard::start, enabled = wizard.targetProblem == null && !wizard.config.readOnly) {
                    Text(if (wizard.intoExisting) "Import" else "Create Table & Import")
                }
            }
            ImportStep.Running -> OutlinedButton(onClick = wizard::cancel) { Text("Cancel Import") }
            ImportStep.Done -> DefaultButton(onClick = { app.importWizard = null }) { Text("Close") }
        }
    }
}

private val Delimiters = listOf("Comma  ," to ',', "Semicolon  ;" to ';', "Tab" to '\t', "Pipe  |" to '|')
private val Encodings = listOf("UTF-8", "UTF-16", "windows-1252", "ISO-8859-1")

private fun typeLabel(type: InferredType): String =
    when (type.kind) {
        InferredType.Kind.Integer -> "integer"
        InferredType.Kind.BigInteger -> "big integer"
        InferredType.Kind.Decimal -> "decimal"
        InferredType.Kind.Boolean -> "boolean"
        InferredType.Kind.Date -> "date"
        InferredType.Kind.Timestamp -> "timestamp"
        InferredType.Kind.Json -> "json"
        InferredType.Kind.Text -> if (type.maxLength > 0) "text, up to ${type.maxLength} chars" else "text"
    }

internal fun byteSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }
