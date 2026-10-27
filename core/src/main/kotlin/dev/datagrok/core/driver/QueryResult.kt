package dev.datagrok.core.driver

import java.sql.Types

/** A result column. [jdbcType] is a [java.sql.Types] constant. */
data class ResultColumn(val name: String, val typeName: String, val jdbcType: Int = Types.VARCHAR) {
    val isNumeric: Boolean
        get() = jdbcType in NumericTypes

    val isBinary: Boolean
        get() = jdbcType in BinaryTypes

    companion object {
        private val NumericTypes =
            setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL)
        internal val BinaryTypes = setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
    }
}

/** Outcome of executing a single SQL statement. */
sealed interface QueryResult {
    val elapsedMillis: Long

    data class Rows(
        val columns: List<ResultColumn>,
        val rows: List<List<String?>>,
        val truncated: Boolean,
        override val elapsedMillis: Long,
    ) : QueryResult

    data class Update(val affectedRows: Int, override val elapsedMillis: Long) : QueryResult
}
