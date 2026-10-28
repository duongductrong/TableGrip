package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.edit.ChangeStatement
import dev.datagrok.core.export.ResultWriter
import dev.datagrok.core.filter.SqlFilter
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableInfo
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.metadata.TableRelation
import dev.datagrok.core.metadata.TableStructure

/**
 * A live connection to one profile. There is one interface per [dev.datagrok.core.connection.Paradigm], and the UI
 * chooses its screens by which one it holds: tables and SQL for [RelationalConnection]; key-value, document and the rest
 * get theirs as engines of those kinds arrive.
 */
sealed interface DatabaseConnection : AutoCloseable {
    val config: ConnectionConfig
}

/** Namespaces, tables and SQL: what the explorer, table viewer, structure view and consoles work with. */
interface RelationalConnection : DatabaseConnection {
    val dialect: SqlDialect

    suspend fun namespaces(): List<Namespace>

    suspend fun tables(namespace: Namespace): List<TableInfo>

    suspend fun tableDetails(table: TableRef): TableDetails

    /** Just the columns and primary key, which is all a table viewer needs. */
    suspend fun tableColumns(table: TableRef): TableDetails

    /** Columns, keys and indexes plus constraints, triggers and DDL, for the structure view. */
    suspend fun tableStructure(table: TableRef): TableStructure

    suspend fun columnsOf(namespace: Namespace): Map<String, List<ColumnInfo>>

    suspend fun relations(table: TableRef): List<TableRelation>

    /** Values of [column] starting with [prefix], for suggestions; bounded in rows scanned and time. */
    suspend fun sampleValues(table: TableRef, column: String, prefix: String, limit: Int = 30): List<String>

    suspend fun fetchPage(table: TableRef, filter: SqlFilter?, orderBy: List<SortColumn>, limit: Int, offset: Long, timeoutSeconds: Int = 0): QueryResult.Rows

    /** Streams every row of [table] (with the viewer's filter and sort) into [writer]. Cancel the coroutine to stop. */
    suspend fun exportTable(
        table: TableRef,
        filter: SqlFilter?,
        orderBy: List<SortColumn>,
        writer: (List<ResultColumn>) -> ResultWriter,
        onProgress: (Long) -> Unit = {},
    ): Long

    suspend fun countRows(table: TableRef, filter: SqlFilter?, timeoutSeconds: Int = 0): Long

    /** Applies [statements] in one transaction, rolling back unless each hits the rows it expects. */
    suspend fun applyChanges(statements: List<ChangeStatement>): Int

    /** A dedicated session for a console, with its own transaction state. */
    suspend fun openSession(): QuerySession
}
