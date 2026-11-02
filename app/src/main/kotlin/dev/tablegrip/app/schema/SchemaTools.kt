package dev.tablegrip.app.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.SqlEditor
import dev.tablegrip.app.explorer.ExplorerNode
import dev.tablegrip.app.explorer.TableKey
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.ddl.DdlScript
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ScriptException
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Tells everything that shows [namespace] of [config] that its schema changed: completion and the agent forget what they
 * read, the explorer reloads the schema (and [tables]), and open table tabs reload their structure and rows.
 */
internal fun AppState.schemaChanged(config: ConnectionConfig, namespace: Namespace, tables: Collection<TableRef> = emptyList()) {
    forgetSchema(config.id)
    explorer.refresh(ExplorerNode.Schema(config.id, namespace))
    tables.forEach { explorer.refresh(TableKey(config.id, it)) }
    workspace.tabs.filterIsInstance<TableDataTab>().filter { it.connectionId == config.id && it.table in tables }.forEach { tab ->
        if (tab.structure != null) tab.loadStructure()
        if (tab.model != null && !tab.hasUnsavedWork) tab.reload()
    }
}

/** What the script dialog says about how a script runs on [dialect]. */
internal fun transactionNote(script: DdlScript, dialect: SqlDialect): String =
    if (script.transactional) "Runs in one transaction: if any statement fails, nothing changes."
    else "$dialect can't roll back schema changes: statements apply one by one and stop at the first failure."

/**
 * A script waiting for the user's go-ahead, and then running. [onDone] runs on the UI thread once it succeeded.
 */
internal class ScriptRun(
    val title: String,
    val config: ConnectionConfig,
    val script: DdlScript,
    val runLabel: String = "Apply",
    val onDone: (statements: Int) -> Unit,
) {
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Some statements were applied and stayed applied before the failure, so the schema is half changed. */
    var partial by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun start(app: AppState, close: () -> Unit) {
        if (running) return
        running = true
        error = null
        progress = 0
        job =
            app.scope.launch {
                try {
                    val count = app.databases.database(config).executeScript(script) { progress = it }
                    close()
                    onDone(count)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ScriptException) {
                    error = e.message
                    partial = !e.rolledBack && e.completed > 0
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    running = false
                }
            }
    }
}

/** Shows [run]'s statements and what to know about them, and runs them on the user's go-ahead. */
@Composable
internal fun ScriptDialog(app: AppState, run: ScriptRun, onClose: () -> Unit) {
    AppDialog(onCloseRequest = { if (!run.running) onClose() }, size = DpSize(760.dp, 560.dp), title = run.title, closeOnEscape = !run.running) {
        ScriptDialogContent(app, run, onClose)
    }
}

/** The dialog's body, apart from its window so snapshots can show it. */
@Composable
internal fun ScriptDialogContent(app: AppState, run: ScriptRun, onClose: () -> Unit) {
    val dialect = SqlDialect.of(run.config.type)
    val production = run.config.environment == Environment.Production
    Column(
        Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (production) AllIconsKeys.General.Warning else AllIconsKeys.General.Information, null)
            Text("${run.script.statements.size} statement(s) on ${run.config.name}", fontWeight = FontWeight.SemiBold)
            EnvironmentChip(run.config)
        }
        Text(transactionNote(run.script, dialect), color = JewelTheme.globalColors.text.info)
        if (production) Notice("This is a Production connection. Read the statements before running them.", NoticeKind.Warning)
        run.script.warnings.forEach { Notice(it, NoticeKind.Warning) }
        val text = remember(run.script) { TextFieldState(run.script.text) }
        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))) {
            SqlEditor(text, dialect.syntax, app.settings.editorFontSize, Modifier.fillMaxSize(), readOnly = true)
        }
        run.error?.let { message ->
            Notice(
                if (run.partial) "$message\nRefresh the explorer to see the schema as it is now." else message,
                NoticeKind.Error,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (run.running) {
                Text("Running ${(run.progress + 1).coerceAtMost(run.script.statements.size)} of ${run.script.statements.size}…", color = JewelTheme.globalColors.text.info)
            }
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { copyToClipboard(run.script.text) }) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    onClose()
                    app.workspace.openConsole(run.config, run.script.text)
                },
                enabled = !run.running,
            ) { Text("Open in Console") }
            OutlinedButton(onClick = onClose, enabled = !run.running) { Text("Cancel") }
            DefaultButton(onClick = { run.start(app, onClose) }, enabled = !run.running && !run.script.isEmpty) {
                Text(if (production) "${run.runLabel} on Production" else run.runLabel)
            }
        }
    }
}

internal enum class NoticeKind { Info, Warning, Error }

/** A compact tinted line with an icon: warnings and errors inside dialogs and panels. */
@Composable
internal fun Notice(message: String, kind: NoticeKind, modifier: Modifier = Modifier) {
    val color =
        when (kind) {
            NoticeKind.Info -> JewelTheme.globalColors.text.info
            NoticeKind.Warning -> WarningColor
            NoticeKind.Error -> JewelTheme.globalColors.text.error
        }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            when (kind) {
                NoticeKind.Info -> AllIconsKeys.General.Information
                NoticeKind.Warning -> AllIconsKeys.General.Warning
                NoticeKind.Error -> AllIconsKeys.General.Error
            },
            null,
            Modifier.padding(top = 1.dp).size(14.dp),
        )
        Text(message, color = if (kind == NoticeKind.Error) color else JewelTheme.globalColors.text.normal)
    }
}

/** Orange that reads on both themes, for warnings and pending changes. */
internal val WarningColor = Color(0xFFD9822B)
internal val AddedColor = Color(0xFF3FA66B)
internal val ChangedColor = Color(0xFF3D8BD9)
internal val RemovedColor = Color(0xFFD95757)

/** A small rounded label, e.g. `PK`, `new`, a count. */
@Composable
internal fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A column header cell of the editable lists. */
@Composable
internal fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp), color = JewelTheme.globalColors.text.info, fontSize = 12.sp, maxLines = 1)
}

/** A flat clickable row of a section header, e.g. "+ Add column". */
@Composable
internal fun AddRowLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Add, null, Modifier.size(14.dp))
        Text(label, color = JewelTheme.globalColors.text.normal)
    }
}

/** An empty state in the middle of a pane: a title and a line of help. */
@Composable
internal fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.padding(24.dp).width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(detail, color = JewelTheme.globalColors.text.info)
        Box(Modifier.heightIn(min = 4.dp).height(4.dp))
        content()
    }
}

/** The native save dialog, starting in Downloads; null when cancelled. */
internal fun chooseSaveFile(suggested: String, title: String = "Save"): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.directory = System.getProperty("user.home") + "/Downloads"
    dialog.file = suggested
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it) }
}

/** The native open dialog for files with one of [extensions]; null when cancelled. */
internal fun chooseOpenFile(title: String, extensions: Set<String>): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.directory = System.getProperty("user.home") + "/Downloads"
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it) }
}

/** Opens the import wizard for [namespace], into [table] when given. */
internal fun AppState.openImport(config: ConnectionConfig, namespace: Namespace, table: TableRef? = null) {
    importWizard = ImportWizard(this, config, namespace, table)
}

internal fun AppState.openTestData(config: ConnectionConfig, table: TableRef) {
    testData = TestDataState(this, config, table)
}

/**
 * Runs [action] with the namespace a connection opens in (the only one for SQLite, `public` for PostgreSQL…), connecting
 * first if needed: for schema tools started from a connection rather than a schema.
 */
internal fun AppState.withDefaultNamespace(config: ConnectionConfig, action: (Namespace) -> Unit) {
    scope.launch {
        try {
            val database = databases.database(config)
            val namespaces = database.namespaces()
            val name = database.dialect.defaultNamespace(namespaces.map { it.displayName }, config.database)
            (namespaces.firstOrNull { it.displayName == name } ?: namespaces.firstOrNull())?.let(action) ?: run { status = "${config.name} has no schemas" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Couldn't connect to ${config.name}: ${e.message}"
        }
    }
}

/** Schema tools on a connection's menu, working on its default namespace; none for engines without tables. */
internal fun schemaTools(app: AppState, config: ConnectionConfig): List<androidx.compose.foundation.ContextMenuItem> {
    if (config.type.paradigm != dev.tablegrip.core.connection.Paradigm.Relational) return emptyList()
    return listOfNotNull(
        androidx.compose.foundation.ContextMenuItem("New Table…") { app.withDefaultNamespace(config) { app.workspace.openTableDesigner(config, it) } }.takeUnless { config.readOnly },
        androidx.compose.foundation.ContextMenuItem("Import Data…") { app.withDefaultNamespace(config) { app.openImport(config, it) } }.takeUnless { config.readOnly },
        androidx.compose.foundation.ContextMenuItem("Show ER Diagram") { app.withDefaultNamespace(config) { app.workspace.openErDiagram(config, it) } },
        androidx.compose.foundation.ContextMenuItem("Compare Schema With…") { app.withDefaultNamespace(config) { app.workspace.openSchemaCompare(config, it) } },
    )
}
