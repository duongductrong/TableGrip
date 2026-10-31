package dev.datagrok.core.agent

import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.export.CopyFormat
import dev.datagrok.core.export.ResultFormats

/**
 * Something the user pointed the agent at from elsewhere in the app, copied when it was mentioned: the conversation keeps
 * what was asked about even after the editor changes.
 */
sealed interface Mention {
    /** How the mention reads in the chat, without the `@`. */
    val label: String

    /** What the agent reads for it. */
    val context: String

    /** A statement or selection from a console, as [lines] (1-based) of [source]. */
    data class Sql(val source: String, val lines: IntRange, val engine: String, val sql: String) : Mention {
        override val label: String
            get() = if (lines.first == lines.last) "$source:${lines.first}" else "$source:${lines.first}-${lines.last}"

        override val context: String
            get() = "$engine SQL from $source, line${if (lines.first == lines.last) " ${lines.first}" else "s ${lines.first}-${lines.last}"}:\n```sql\n${capped(sql.trim())}\n```"
    }

    /** A statement that failed in [source], with what the database said. */
    data class Error(val source: String, val engine: String, val sql: String, val message: String) : Mention {
        override val label: String
            get() = "$source error"

        override val context: String
            get() = "This $engine statement from $source failed:\n```sql\n${capped(sql.trim())}\n```\nError: ${capped(message.trim(), MessageBudget)}"
    }

    /**
     * A table or view of [connectionId]. Its structure is read when the message is sent ([details]), so the agent gets
     * the columns, keys and indexes of this table even when the schema in its framing had to be cut short.
     */
    data class Table(val connectionId: String, val connection: String, val engine: String, val namespace: String?, val name: String, val details: String? = null) :
        Mention {
        override val label: String
            get() = name

        val qualified: String
            get() = listOfNotNull(namespace, name).joinToString(".")

        override val context: String
            get() = "$engine table $qualified on connection $connection:\n${details?.let { capped(it) } ?: "(its structure couldn't be read)"}"
    }

    data class Column(val connection: String, val engine: String, val table: String, val name: String, val type: String, val primaryKey: Boolean) : Mention {
        override val label: String
            get() = "$table.$name"

        override val context: String
            get() = "Column $table.$name of $engine connection $connection: ${type.lowercase()}${if (primaryKey) ", primary key" else ""}"
    }

    /** A saved query, by its [name] in the library. */
    data class Query(val name: String, val sql: String) : Mention {
        override val label: String
            get() = name

        override val context: String
            get() = "Saved query \"$name\":\n```sql\n${capped(sql.trim())}\n```"
    }

    /**
     * Cells selected in a grid: [columns] and the 1-based [rows] of [source], counted over what [origin] returns: the
     * query that produced a result, or a table's SELECT with the viewer's filter and sort. [values] holds at most
     * [CellRows] rows, and is null when the connection doesn't share data; then only the shape of the selection is sent.
     * [keys] are primary key columns that weren't selected, with their [keyValues] per row, so the agent can tell which
     * rows these are. [notes] flag rows, by number, whose values are edits not saved to the database yet.
     */
    data class Cells(
        val source: String,
        val engine: String,
        val columns: List<ResultColumn>,
        val rows: IntRange,
        val values: List<List<String?>>?,
        val origin: String? = null,
        val keys: List<ResultColumn> = emptyList(),
        val keyValues: List<List<String?>>? = null,
        val notes: Map<Int, String> = emptyMap(),
    ) : Mention {
        val rowCount: Int
            get() = rows.last - rows.first + 1

        override val label: String
            get() {
                val where = if (rows.first == rows.last) "row ${rows.first}" else "rows ${rows.first}-${rows.last}"
                return columns.singleOrNull()?.let { "$source.${it.name} $where" } ?: "$source $where · ${columns.size} cols"
            }

        override val context: String
            get() = capped(buildString {
                append("Cells from $source ($engine), ${columns.size} column(s) × $rowCount row(s), rows ${rows.first}-${rows.last}")
                append(if (origin == null) ":\n" else " of what this returns:\n```sql\n${capped(origin.trim(), MessageBudget)}\n```\n")
                if (values == null) {
                    append(columns.joinToString(" | ") { "${it.name} ${it.typeName.lowercase()}" })
                    append("\n(Values not shared: the connection only shares its schema with agents.)")
                    return@buildString
                }
                val keyed = keyValues?.takeIf { keys.isNotEmpty() }
                if (keyed != null) append("The primary key (${keys.joinToString { it.name }}) comes first to identify the rows; it wasn't selected.\n")
                val table = values.mapIndexed { i, row -> (keyed?.getOrNull(i).orEmpty() + row).map(::cell) }
                append(ResultFormats.format(CopyFormat.Markdown, if (keyed != null) keys + columns else columns, table))
                if (rowCount > values.size) append("\n(${rowCount - values.size} more rows not shown)")
                if (notes.isNotEmpty()) append("\nNot saved to the database yet: ${notes.entries.sortedBy { it.key }.joinToString { (row, note) -> "row $row ($note)" }}")
            })
    }

    /** A mention in a chat reopened from history: what it read as and what the agent read for it, nothing more. */
    data class Saved(override val label: String, override val context: String) : Mention

    companion object {
        /** Rows of a [Cells] mention that are kept and sent, and how much of each value. */
        const val CellRows = 50
        const val CellBudget = 200

        /** Most of a mention the agent reads, so one huge selection can't crowd out the conversation. */
        const val Budget = 8_000
        const val MessageBudget = 2_000

        /** A cell of [Cells], cut to [CellBudget]; a text `NULL` is quoted so it can't pass for a missing value. */
        private fun cell(value: String?): String? =
            when {
                value == null -> null
                value == "NULL" -> "\"NULL\""
                value.length > CellBudget -> value.take(CellBudget) + "…"
                else -> value
            }

        fun capped(text: String, budget: Int = Budget): String =
            if (text.length <= budget) text else "${text.take(budget)}\n… (${text.length - budget} more characters not shown)"
    }
}

/**
 * Text with mentions in it. Each mention is a single character from Unicode's private use area standing in for it, so
 * editing treats a mention as a unit: one backspace removes it, the caret never stops inside it, and undo brings it back.
 * A stand-in without an entry in [mentions] is dropped.
 */
class MentionText(val text: String, val mentions: Map<Char, Mention> = emptyMap()) {
    /** The mentions [text] still contains, in the order they first appear. */
    val used: List<Pair<Char, Mention>>
        get() = text.filter(::isMention).toList().distinct().mapNotNull { char -> mentions[char]?.let { char to it } }

    /** As the user reads it: each mention as `@label`. */
    val plain: String
        get() = expand { "@${it.label}" }

    /** As the agent reads it: each mention as `@label [n]`, then what each one is under its number. */
    val prompt: String
        get() {
            val used = used
            if (used.isEmpty()) return plain
            val numbers = used.mapIndexed { index, (char, _) -> char to index + 1 }.toMap()
            return buildString {
                append(expand { char, mention -> "@${mention.label} [${numbers.getValue(char)}]" })
                append("\n\n# Mentioned\n")
                used.forEachIndexed { index, (_, mention) -> append("\n[${index + 1}] ${mention.context}\n") }
            }.trimEnd()
        }

    private fun expand(render: (Mention) -> String): String = expand { _, mention -> render(mention) }

    private fun expand(render: (Char, Mention) -> String): String = buildString {
        for (char in text) {
            when {
                !isMention(char) -> append(char)
                else -> mentions[char]?.let { append(render(char, it)) }
            }
        }
    }

    companion object {
        const val First = ''
        const val Last = ''

        fun isMention(char: Char): Boolean = char in First..Last
    }
}
