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

/**
 * What an agent connected from outside the app (over MCP) may change on a connection. Nothing by default: reading is
 * governed by [AiAccess], writing needs this as well, and a read-only connection never allows it.
 */
@Serializable
enum class AgentWrites(val label: String, val description: String) {
    None(tr("None"), tr("Agents can only read.")),
    Data(tr("Data"), tr("Agents may insert, update and delete rows; risky statements ask first.")),
    DataAndSchema(tr("Data and schema"), tr("Also CREATE, ALTER and DROP; risky statements ask first."));

    val allowsData: Boolean
        get() = this != None

    val allowsSchema: Boolean
        get() = this == DataAndSchema
}
