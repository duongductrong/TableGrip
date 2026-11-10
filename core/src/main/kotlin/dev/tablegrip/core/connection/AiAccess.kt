package dev.tablegrip.core.connection

import dev.tablegrip.core.i18n.tr
import kotlinx.serialization.Serializable

/**
 * What of a connection may be sent to an AI agent. Restrictive by default: the schema and the SQL around it help an
 * agent write queries, while the data itself can be personal or confidential and is only sent when the user opts in.
 */
@Serializable
enum class AiAccess(val label: String, val description: String) {
    Off(tr("Off"), tr("Nothing about this connection is sent to an agent; AI actions are hidden.")),
    SchemaOnly(tr("Schema only"), tr("Table and column names, types, DDL, your SQL and error messages. No values from the data.")),
    SchemaAndSamples(tr("Schema and sample data"), tr("Also sample values and query results, e.g. a column's distinct values."));

    val sharesSchema: Boolean
        get() = this != Off

    val sharesData: Boolean
        get() = this == SchemaAndSamples

    companion object {
        /** The app-wide default when settings hold nothing usable. */
        val Default = SchemaOnly

        /** Unknown or blank names fall back to [Default]: a bad edit must never share more than intended. */
        fun of(name: String): AiAccess = entries.firstOrNull { it.name == name } ?: Default

        /**
         * What [config] may share, given the app-wide [default]. A connection's own choice wins; otherwise production
         * connections never share data, even when the default would.
         */
        fun effective(default: AiAccess, config: ConnectionConfig?): AiAccess {
            config?.aiAccess?.let { return it }
            return if (config?.environment == Environment.Production && default == SchemaAndSamples) SchemaOnly else default
        }
    }
}
