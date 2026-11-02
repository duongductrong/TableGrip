package dev.tablegrip.core.edit

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.TableRef

/**
 * A column value to bind; [jdbcType] is a [java.sql.Types] constant used for NULLs and dialect binding. With [isDefault]
 * an update sets the column back to its default instead, and [value] is ignored.
 */
data class BoundValue(val value: String?, val jdbcType: Int, val numeric: Boolean, val isDefault: Boolean = false)

sealed interface RowChange {
    /** Identifies the row by its primary key values as they were read. */
    data class Update(val key: Map<String, BoundValue>, val values: Map<String, BoundValue>) : RowChange

    /** Columns not listed take their database default. */
    data class Insert(val values: Map<String, BoundValue>) : RowChange

    data class Delete(val key: Map<String, BoundValue>) : RowChange
}

/** A parameterized statement; [expectedRows] guards updates and deletes against hitting the wrong number of rows. */
data class ChangeStatement(val sql: String, val parameters: List<BoundValue>, val expectedRows: Int?) {
    /** The statement with literals inlined, for previews only. */
    fun preview(dialect: SqlDialect): String {
        val values = parameters.iterator()
        return Regex("\\?").replace(sql) { values.next().let { dialect.literal(it.value, it.numeric) } }
    }
}

object ChangeSql {
    /** Deletes first, then updates, then inserts, so a key freed by a delete can be reused. */
    fun statements(dialect: SqlDialect, table: TableRef, changes: List<RowChange>): List<ChangeStatement> {
        val target = dialect.qualifiedName(table)
        fun q(name: String) = dialect.quoteIdentifier(name)

        fun where(key: Map<String, BoundValue>): Pair<String, List<BoundValue>> {
            val parameters = mutableListOf<BoundValue>()
            val condition =
                key.entries.joinToString(" AND ") { (column, bound) ->
                    if (bound.value == null) "${q(column)} IS NULL" else "${q(column)} = ?".also { parameters += bound }
                }
            return condition to parameters
        }

        val ordered = changes.filterIsInstance<RowChange.Delete>() + changes.filterIsInstance<RowChange.Update>() +
            changes.filterIsInstance<RowChange.Insert>()
        return ordered.map { change ->
            when (change) {
                is RowChange.Delete -> {
                    val (condition, parameters) = where(change.key)
                    ChangeStatement("DELETE FROM $target WHERE $condition", parameters, expectedRows = 1)
                }
                is RowChange.Update -> {
                    val (condition, keyParameters) = where(change.key)
                    val assignments = change.values.entries.joinToString(", ") { (column, bound) -> "${q(column)} = " + if (bound.isDefault) "DEFAULT" else "?" }
                    val values = change.values.values.filterNot { it.isDefault }
                    ChangeStatement("UPDATE $target SET $assignments WHERE $condition", values + keyParameters, expectedRows = 1)
                }
                is RowChange.Insert ->
                    if (change.values.isEmpty()) ChangeStatement("INSERT INTO $target DEFAULT VALUES", emptyList(), expectedRows = null)
                    else
                        ChangeStatement(
                            "INSERT INTO $target (${change.values.keys.joinToString(", ") { q(it) }}) VALUES (${change.values.keys.joinToString(", ") { "?" }})",
                            change.values.values.toList(),
                            expectedRows = null,
                        )
            }
        }
    }
}
