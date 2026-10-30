package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.ddl.DdlScript
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
import dev.datagrok.core.schema.SchemaSnapshot

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

    /**
     * Every table of [namespace] with columns, keys and indexes, for schema compare and the ER diagram. [onProgress] gets
     * tables read and the total. Cancel the coroutine to stop between tables.
     */
    suspend fun schemaSnapshot(namespace: Namespace, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SchemaSnapshot

    /**
     * Runs a schema tool's [script]: in one transaction when the engine can roll DDL back, else statement by statement,
     * stopping at the first failure ([ScriptException] says how far it got). [onProgress] gets statements done.
     */
    suspend fun executeScript(script: DdlScript, onProgress: (Int) -> Unit = {}): Int

    /**
     * Inserts [rows] into [columns] of [table] in batches, all in one transaction: on failure or cancellation nothing is
     * kept. Values are text, bound with each column's JDBC type; [describe] names a row in error messages (e.g. its line
     * in a file). [onProgress] gets rows inserted so far. Returns the row count.
     */
    suspend fun <T> insertRows(
        table: TableRef,
        columns: List<ColumnInfo>,
        rows: Sequence<T>,
        values: (T) -> List<String?>,
        describe: (index: Long, row: T) -> String = { index, _ -> "row ${index + 1}" },
        onProgress: (Long) -> Unit = {},
    ): Long
}

/** A script statement failed. [completed] statements ran before it; [rolledBack] says whether they were undone. */
class ScriptException(message: String, val statement: String, val completed: Int, val rolledBack: Boolean, cause: Throwable?) :
    java.sql.SQLException(message, cause)

/** An insert failed on [row] (as the caller described it); nothing was kept. */
class InsertException(message: String, val row: String, cause: Throwable?) : java.sql.SQLException(message, cause)
