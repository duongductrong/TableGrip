package dev.datagrok.core.driver

import java.sql.Connection
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
internal suspend fun <T> Connection.withStatement(timeoutSeconds: Int, block: (Statement) -> T): T = coroutineScope {
    val statement = withContext(Dispatchers.IO) { createStatement().apply { queryTimeout = timeoutSeconds } }
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

internal fun ResultSet.readRows(maxRows: Int, elapsed: () -> Long): QueryResult.Rows {
    val meta = metaData
    val columns =
        (1..meta.columnCount).map { ResultColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it).orEmpty(), meta.getColumnType(it)) }
    val binary = columns.map { it.isBinary }
    val rows = buildList {
        while (size <= maxRows && next()) {
            add(columns.indices.map { i -> if (binary[i]) getBytes(i + 1)?.let { "<binary ${it.size} bytes>" } else getString(i + 1) })
        }
    }
    return QueryResult.Rows(columns, rows.take(maxRows), truncated = rows.size > maxRows, elapsedMillis = elapsed())
}

