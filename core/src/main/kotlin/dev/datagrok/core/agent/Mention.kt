package dev.datagrok.core.agent

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

    companion object {
        /** Most of a mention the agent reads, so one huge selection can't crowd out the conversation. */
        const val Budget = 8_000
        const val MessageBudget = 2_000

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
