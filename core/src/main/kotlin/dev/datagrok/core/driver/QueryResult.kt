package dev.datagrok.core.driver

/** Outcome of executing a single SQL statement. */
sealed interface QueryResult {
    val elapsedMillis: Long

    data class Rows(
        val columns: List<String>,
        val rows: List<List<String?>>,
        val truncated: Boolean,
        override val elapsedMillis: Long,
    ) : QueryResult

    data class Update(val affectedRows: Int, override val elapsedMillis: Long) : QueryResult
}

/** A node in the database explorer tree (schema, table, view...). */
data class DbObject(val name: String, val kind: Kind, val schema: String? = null) {
    enum class Kind { Schema, Table, View }
}
