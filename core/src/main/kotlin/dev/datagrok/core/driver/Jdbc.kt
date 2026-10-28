package dev.datagrok.core.driver

import dev.datagrok.core.export.ResultWriter
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs [block] with a fresh statement on the IO dispatcher. Cancelling the calling coroutine cancels the statement on the
 * server, then waits for the driver to return before closing it.
 */
internal suspend fun <T> Connection.withStatement(timeoutSeconds: Int, block: (Statement) -> T): T =
    runStatement({ createStatement() }, timeoutSeconds, block)

/** [withStatement] for a prepared [sql] statement. */
internal suspend fun <T> Connection.withPrepared(sql: String, timeoutSeconds: Int, block: (PreparedStatement) -> T): T =
    runStatement({ prepareStatement(sql) }, timeoutSeconds, block)

private suspend fun <S : Statement, T> Connection.runStatement(create: Connection.() -> S, timeoutSeconds: Int, block: (S) -> T): T = coroutineScope {
    val statement = withContext(Dispatchers.IO) { create().apply { queryTimeout = timeoutSeconds } }
    try {
        val cancelled = AtomicBoolean(false)
        val work = async(Dispatchers.IO) {
            try {
                block(statement)
            } catch (e: SQLException) {
                // The driver reports our own cancel as an error; surface it as a cancellation instead.
                if (cancelled.get()) throw CancellationException("Query cancelled").apply { initCause(e) } else throw e
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            cancelled.set(true)
            runCatching { statement.cancel() }
            withContext(NonCancellable) { work.join() }
            throw e
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { statement.close() } }
    }
}

internal fun Statement.executeForResult(sql: String, maxRows: Int): QueryResult {
    val start = System.nanoTime()
    fun elapsed() = (System.nanoTime() - start) / 1_000_000
    this.maxRows = maxRows + 1
    return if (execute(sql)) resultSet.use { it.readRows(maxRows, ::elapsed) } else QueryResult.Update(updateCount, elapsed())
}

/** Writes every remaining row to a writer created from the result's columns, then finishes it. */
internal fun ResultSet.streamTo(writer: (List<ResultColumn>) -> ResultWriter, onProgress: (Long) -> Unit): Long {
    val columns = columns()
    val out = writer(columns)
    while (next()) {
        out.write(columns.indices.map { cell(it, columns[it]) })
        if (out.rowCount % 5_000 == 0L) onProgress(out.rowCount)
    }
    out.finish()
    onProgress(out.rowCount)
    return out.rowCount
}

private fun ResultSet.columns(): List<ResultColumn> {
    val meta = metaData
    return (1..meta.columnCount).map { ResultColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it).orEmpty(), meta.getColumnType(it)) }
}

private fun ResultSet.cell(index: Int, column: ResultColumn): String? =
    if (column.isBinary) getBytes(index + 1)?.let { "<binary ${it.size} bytes>" } else getString(index + 1)

internal fun ResultSet.readRows(maxRows: Int, elapsed: () -> Long): QueryResult.Rows {
    val columns = columns()
    val rows = buildList {
        while (size <= maxRows && next()) add(columns.indices.map { cell(it, columns[it]) })
    }
    return QueryResult.Rows(columns, rows.take(maxRows), truncated = rows.size > maxRows, elapsedMillis = elapsed())
}

