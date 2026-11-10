package dev.tablegrip.app.schema

import dev.tablegrip.core.i18n.tr
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.grid.ResultGridModel
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.testdata.ColumnPlan
import dev.tablegrip.core.testdata.GeneratorKind
import dev.tablegrip.core.testdata.GeneratorSuggestion
import dev.tablegrip.core.testdata.TestDataGenerator
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip

/** A column of the table and how to fill it; edits replace [plan]. */
class TestDataColumn(plan: ColumnPlan, val foreignKey: Boolean, val choices: List<GeneratorKind>) {
    var plan by mutableStateOf(plan)
    val constant = TextFieldState(plan.constant.orEmpty())
}

/**
 * Fills a table with realistic fake rows: a generator per column chosen from its name and type, foreign keys drawn from
 * the referenced tables' existing keys, all inserted in one transaction.
 */
class TestDataState(private val app: AppState, val config: ConnectionConfig, val table: TableRef) {
    val dialect: SqlDialect = SqlDialect.of(config.type)
    val columns = mutableStateListOf<TestDataColumn>()
    val count = TextFieldState("100")
    val seed = TextFieldState(Random.nextInt(1, 100_000).toString())
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Existing keys of referenced tables, by referencing column. */
    private var foreignKeyValues by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var running by mutableStateOf(false)
        private set
    var inserted by mutableStateOf(0L)
        private set
    /** Production writes need an explicit tick. */
    var productionConfirmed by mutableStateOf(false)
    private var job: Job? = null

    init {
        load()
    }

    private fun load() {
        app.scope.launch {
            try {
                val database = app.databases.database(config)
                val details = database.tableDetails(table)
                val foreignKeyColumns = details.foreignKeys.flatMap { it.columns }.toSet()
                val uniqueColumns = details.indexes.filter { it.unique && it.columns.size == 1 }.map { it.columns.single() }.toSet()
                columns.clear()
                details.columns.forEach { column ->
                    val fk = column.name in foreignKeyColumns
                    columns += TestDataColumn(GeneratorSuggestion.suggest(column, fk, column.name in uniqueColumns), fk, GeneratorSuggestion.choices(column, fk))
                }
                // A sample of each referenced table's keys: enough variety without reading a huge table.
                val values = mutableMapOf<String, List<String>>()
                for (fk in details.foreignKeys) {
                    val page = database.fetchPage(fk.referencedTable, filter = null, orderBy = emptyList(), limit = 5_000, offset = 0, timeoutSeconds = 15)
                    fk.columns.zip(fk.referencedColumns).forEach { (column, referenced) ->
                        val index = page.columns.indexOfFirst { it.name.equals(referenced, ignoreCase = true) }
                        if (index >= 0) values[column] = page.rows.mapNotNull { it[index] }.distinct()
                    }
                }
                foreignKeyValues = values
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr("Couldn't read {0}: {1}", table.name, e.message ?: e)
            } finally {
                loading = false
            }
        }
    }

    val rowCount: Int?
        get() = count.text.toString().replace(",", "").replace("_", "").trim().toIntOrNull()?.takeIf { it in 1..MaxRows }

    private val seedValue: Long
        get() = seed.text.toString().trim().toLongOrNull() ?: seed.text.toString().hashCode().toLong()

    fun plans(): List<ColumnPlan> = columns.map { column -> column.plan.copy(constant = column.constant.text.toString().takeIf { column.plan.kind == GeneratorKind.Constant }) }

    fun generator(): TestDataGenerator = TestDataGenerator(plans(), seedValue, foreignKeyValues)

    /** What would make the insert fail, before running it. */
    fun problems(): List<String> {
        val count = rowCount ?: return listOf(tr("Enter a row count between 1 and {0}.", "%,d".format(MaxRows)))
        if (plans().all { it.kind == GeneratorKind.Skip }) return listOf(tr("Every column is left to the database: pick a generator for at least one."))
        return runCatching { generator().problems(count) }.getOrElse { listOf(it.message ?: it.toString()) }
    }

    /** The first rows the insert would write, as the preview grid shows them. */
    fun preview(): QueryResult.Rows? {
        if (loading || columns.isEmpty()) return null
        return runCatching {
            val generator = generator()
            QueryResult.Rows(generator.columns.map { ResultColumn(it.name, it.typeName, it.jdbcType) }, generator.rows(PreviewRows).toList(), truncated = false, elapsedMillis = 0)
        }.getOrNull()
    }

    fun shuffle() {
        seed.setTextAndPlaceCursorAtEnd(Random.nextInt(1, 100_000).toString())
    }

    fun insert(close: () -> Unit) {
        val count = rowCount ?: return
        if (problems().isNotEmpty() || config.readOnly) return
        if (config.environment == Environment.Production && !productionConfirmed) return
        val generator = generator()
        running = true
        error = null
        inserted = 0
        job =
            app.scope.launch {
                try {
                    val total = app.databases.database(config).insertRows(table, generator.columns, generator.rows(count), values = { it }, onProgress = { inserted = it })
                    app.status = tr("Inserted {0} test rows into {1}", "%,d".format(total), table.name)
                    app.workspace.tabs.filterIsInstance<TableDataTab>().filter { it.connectionId == config.id && it.table == table && !it.hasUnsavedWork }.forEach { it.reload() }
                    close()
                } catch (e: CancellationException) {
                    app.status = tr("Cancelled; no rows were inserted")
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    running = false
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        const val MaxRows = 1_000_000
        const val PreviewRows = 12
    }
}

@Composable
internal fun TestDataDialog(app: AppState, state: TestDataState) {
    AppDialog(
        onCloseRequest = { if (!state.running) app.testData = null },
        size = DpSize(940.dp, 680.dp),
        title = tr("Generate Test Data for {0}", state.table.name),
        closeOnEscape = !state.running,
    ) {
        TestDataContent(app, state)
    }
}

/** The dialog's body, apart from its window so snapshots can show it. */
@Composable
internal fun TestDataContent(app: AppState, state: TestDataState) {
    val problems by remember(state) { derivedStateOf { state.problems() } }
    val preview by remember(state) { derivedStateOf { state.preview() } }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Rows"), color = JewelTheme.globalColors.text.info)
            TextField(state.count, Modifier.width(100.dp), outline = if (state.rowCount == null) Outline.Error else Outline.None)
            Text(tr("Seed"), color = JewelTheme.globalColors.text.info)
            TextField(state.seed, Modifier.width(90.dp))
            Tooltip({ Text(tr("The same seed gives the same rows; shuffle for new ones")) }) { Link(tr("Shuffle"), onClick = state::shuffle) }
            Box(Modifier.weight(1f))
            EnvironmentChip(state.config)
            Text(state.config.name, color = JewelTheme.globalColors.text.info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Notice(it, NoticeKind.Error) }
                ColumnPlans(state, Modifier.weight(1.1f))
                Text(tr("Preview"), fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))) {
                    val rows = preview
                    if (rows != null && rows.columns.isNotEmpty()) {
                        DataGrid(model = remember(rows) { ResultGridModel(rows) }, state = remember { DataGridState() }, actions = GridActions(dialect = state.dialect, tableName = state.table.name))
                    } else {
                        Text(tr("Nothing to preview"), Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                    }
                }
                problems.forEach { Notice(it, NoticeKind.Warning) }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val production = state.config.environment == Environment.Production
            when {
                state.config.readOnly -> Text(tr("This connection is read-only."), color = JewelTheme.globalColors.text.error)
                production -> CheckboxRow(tr("Write test rows to this Production database"), state.productionConfirmed, onCheckedChange = { state.productionConfirmed = it })
                else -> Text(tr("Inserted in one transaction: if any row fails, none are kept."), color = JewelTheme.globalColors.text.info)
            }
            Box(Modifier.weight(1f))
            if (state.running) {
                CircularProgressIndicator()
                Text(tr("Inserted {0}…", "%,d".format(state.inserted)), color = JewelTheme.globalColors.text.info)
                OutlinedButton(onClick = state::cancel) { Text(tr("Cancel")) }
            } else {
                OutlinedButton(onClick = { app.testData = null }) { Text(tr("Close")) }
                DefaultButton(
                    onClick = { state.insert { app.testData = null } },
                    enabled = problems.isEmpty() && !state.loading && !state.config.readOnly && (!production || state.productionConfirmed),
                ) { Text(tr("Insert {0} Rows", state.rowCount?.let { "%,d".format(it) } ?: "")) }
            }
        }
    }
}

@Composable
private fun ColumnPlans(state: TestDataState, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell(tr("Column"), Modifier.weight(1f))
            HeaderCell(tr("Type"), Modifier.weight(0.6f))
            HeaderCell(tr("Fill with"), Modifier.weight(1.4f))
            HeaderCell(tr("NULLs"), Modifier.width(70.dp))
            HeaderCell(tr("Unique"), Modifier.width(56.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.columns, key = { it.plan.column.name }) { column ->
                val plan = column.plan
                Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(plan.column.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (plan.column.primaryKey) FontWeight.SemiBold else FontWeight.Normal)
                        if (plan.column.primaryKey) Tag(tr("PK"), ChangedColor)
                        if (column.foreignKey) Tag(tr("FK"), AddedColor)
                    }
                    Text(plan.column.typeName.lowercase(), Modifier.weight(0.6f), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Picker(plan.kind.label, placeholder = plan.kind == GeneratorKind.Skip, options = column.choices.map { it.label to it }, onPick = { column.plan = plan.copy(kind = it) }, modifier = Modifier.weight(1f))
                        if (plan.kind == GeneratorKind.Constant) TextField(column.constant, Modifier.width(120.dp), placeholder = { Text(tr("value")) })
                    }
                    Box(Modifier.width(70.dp)) {
                        if (plan.column.nullable && plan.kind != GeneratorKind.Skip && plan.kind != GeneratorKind.Null) {
                            Picker("${(plan.nullRatio * 100).toInt()}%", placeholder = false, options = NullRatios.map { "${(it * 100).toInt()}%" to it }, onPick = { column.plan = plan.copy(nullRatio = it) })
                        }
                    }
                    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        if (plan.kind != GeneratorKind.Skip && plan.kind != GeneratorKind.Null) Checkbox(plan.unique, { column.plan = plan.copy(unique = it) })
                    }
                }
            }
        }
    }
}

private val NullRatios = listOf(0.0, 0.05, 0.1, 0.25, 0.5)
