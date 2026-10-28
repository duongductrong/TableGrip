package dev.datagrok.app.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.datagrok.app.console.SqlEditor
import dev.datagrok.app.grid.DataGrid
import dev.datagrok.app.grid.GridActions
import dev.datagrok.app.grid.ResultGridModel
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.metadata.TableStructure
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/** Columns, indexes, keys, constraints, triggers and DDL of the tab's table, one section at a time. */
@Composable
internal fun StructureView(tab: TableDataTab, modifier: Modifier = Modifier) {
    val structure = tab.structure
    Column(modifier) {
        TabStrip(
            tabs =
                StructureSection.entries.map { section ->
                    val count = structure?.let { StructureTables.count(it, section) }
                    TabData.Default(
                        selected = section == tab.section,
                        content = { state -> SimpleTabContent(if (count == null) section.label else "${section.label} $count", state) },
                        closable = false,
                        onClick = { tab.section = section },
                    )
                },
            style = JewelTheme.defaultTabStyle,
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                structure == null ->
                    if (tab.structureError == null) Text("Loading…", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                tab.section == StructureSection.Ddl -> {
                    // Keyed on the DDL so a refresh replaces the text.
                    val text = remember(structure.ddl) { TextFieldState(structure.ddl) }
                    SqlEditor(text, tab.dialect.syntax, tab.editorFontSize, Modifier.fillMaxSize(), readOnly = true)
                }
                else -> {
                    val model = remember(structure, tab.section) { ResultGridModel(StructureTables.rows(structure, tab.section)) }
                    if (model.rowCount == 0) {
                        Text("No ${tab.section.label.lowercase()}", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                    } else {
                        DataGrid(model = model, state = tab.structureGrids.getValue(tab.section), actions = GridActions(dialect = tab.dialect))
                    }
                }
            }
        }
    }
}

/** Each list section as a read-only result, so it gets the data grid's selection, copy and resizing. */
internal object StructureTables {
    fun count(structure: TableStructure, section: StructureSection): Int? =
        when (section) {
            StructureSection.Columns -> structure.details.columns.size
            StructureSection.Indexes -> structure.details.indexes.size
            StructureSection.ForeignKeys -> structure.details.foreignKeys.size
            StructureSection.Constraints -> structure.constraints.size
            StructureSection.Triggers -> structure.triggers.size
            StructureSection.Ddl -> null
        }

    fun rows(structure: TableStructure, section: StructureSection): QueryResult.Rows {
        val details = structure.details
        return when (section) {
            StructureSection.Columns -> {
                val foreignKeyColumns = details.foreignKeys.flatMap { it.columns }.toSet()
                val uniqueColumns = details.indexes.filter { it.unique && it.columns.size == 1 }.map { it.columns.single() }.toSet()
                result(
                    listOf("name", "type", "nullable", "default", "key", "auto increment"),
                    details.columns.map { column ->
                        val type = column.typeName + (column.size?.takeIf { column.typeName.lowercase() in SizedTypes }?.let { "($it)" } ?: "")
                        val key =
                            listOfNotNull(
                                "PK".takeIf { column.primaryKey },
                                "FK".takeIf { column.name in foreignKeyColumns },
                                "UQ".takeIf { !column.primaryKey && column.name in uniqueColumns },
                            ).joinToString(" ")
                        listOf(column.name, type, yesNo(column.nullable), column.defaultValue, key, yesNo(column.autoIncrement))
                    },
                )
            }
            StructureSection.Indexes ->
                result(
                    listOf("name", "columns", "unique", "primary"),
                    details.indexes.map { index ->
                        val primary = index.unique && index.columns == details.primaryKey
                        listOf(index.name, index.columns.joinToString(", "), yesNo(index.unique), yesNo(primary))
                    },
                )
            StructureSection.ForeignKeys ->
                result(
                    listOf("name", "columns", "references", "on update", "on delete"),
                    details.foreignKeys.map { fk ->
                        listOf(
                            fk.name,
                            fk.columns.joinToString(", "),
                            "${fk.referencedTable.name}(${fk.referencedColumns.joinToString(", ")})",
                            fk.onUpdate ?: "NO ACTION",
                            fk.onDelete ?: "NO ACTION",
                        )
                    },
                )
            StructureSection.Constraints ->
                result(
                    listOf("name", "type", "columns", "definition"),
                    structure.constraints.map { listOf(it.name, it.kind.label, it.columns.joinToString(", "), it.definition) },
                )
            StructureSection.Triggers ->
                result(
                    listOf("name", "timing", "event", "enabled", "definition"),
                    structure.triggers.map { listOf(it.name, it.timing, it.events, yesNo(it.enabled), it.definition.replace(Whitespace, " ")) },
                )
            StructureSection.Ddl -> result(listOf("ddl"), listOf(listOf(structure.ddl)))
        }
    }

    /** Types whose size is part of the declared type; for others (int, date…) the driver's size is noise. */
    private val SizedTypes = setOf("varchar", "char", "character", "character varying", "nvarchar", "nchar", "varbinary", "binary", "bit", "varbit")
    private val Whitespace = Regex("\\s+")

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun result(columns: List<String>, rows: List<List<String?>>) =
        QueryResult.Rows(
            columns.map { ResultColumn(it, "") },
            rows,
            truncated = false,
            elapsedMillis = 0,
        )
}
