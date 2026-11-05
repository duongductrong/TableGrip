package dev.tablegrip.app.table

import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.completion.SchemaCache
import dev.tablegrip.core.completion.CompletionKind
import dev.tablegrip.core.completion.SchemaProvider
import dev.tablegrip.core.completion.SqlCompleter
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.TableRef

/**
 * Completion for a table viewer's WHERE filter: the filter is completed as the tail of
 * `SELECT * FROM <table> WHERE <filter>`, so columns, operators and values come from the table being viewed, and
 * clauses that can't appear in a filter are left out.
 */
internal fun filterCompletion(dialect: SqlDialect, table: TableRef, schema: SchemaProvider, cache: SchemaCache?): CompletionSource {
    val completer = SqlCompleter(dialect)
    val prefix = "SELECT * FROM ${dialect.qualifiedName(table)} WHERE "
    return CompletionSource(
        complete = { filter, caret ->
            val result = completer.complete(prefix + filter, prefix.length + caret, schema)
            result.copy(
                replaceStart = result.replaceStart - prefix.length,
                replaceEnd = result.replaceEnd - prefix.length,
                items = result.items.filterNot { it.kind == CompletionKind.Keyword && it.label in NotInFilters || it.kind == CompletionKind.Function && it.label in Aggregates },
            )
        },
        dialect = dialect,
        cache = cache,
    )
}

private val NotInFilters = setOf("FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "UNION", "JOIN", "AS", "ASC", "DESC", "DISTINCT")
private val Aggregates = setOf("count()", "sum()", "avg()", "min()", "max()", "string_agg()", "array_agg()", "group_concat()")
