package dev.tablegrip.core.explain

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.QueryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Engines whose plans TableGrip reads into a tree; the others show EXPLAIN's output as it comes. */
enum class PlanEngine {
    PostgreSQL,
    MySQL,
    MariaDB,
}

/** EXPLAIN statements whose output [parse] reads, and reading it. */
object ExplainPlans {
    /**
     * Null for engines without a plan format TableGrip reads. Forks that speak the protocol (CockroachDB, Redshift, TiDB,
     * PlanetScale…) print plans their own way.
     */
    fun engine(type: DatabaseType): PlanEngine? =
        when {
            type.dialect is SqlDialect.PostgreSQLDialect && type.dialect.hasPostgresCatalog -> PlanEngine.PostgreSQL
            type == DatabaseType.MySQL -> PlanEngine.MySQL
            type == DatabaseType.MariaDB -> PlanEngine.MariaDB
            else -> null
        }

    private val Placeholder = Regex("""\$\d+""")

    /**
     * The statement showing [sql]'s plan. With [analyze] it runs [sql] too, to report actual rows and times. A PostgreSQL
     * statement with `$1` placeholders, as the activity monitor shows prepared ones, gets a generic plan (PostgreSQL 16+).
     */
    fun statement(engine: PlanEngine, sql: String, analyze: Boolean): String {
        val body = sql.trim().removeSuffix(";").trimEnd()
        return when (engine) {
            PlanEngine.PostgreSQL ->
                when {
                    analyze -> "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $body"
                    Placeholder.containsMatchIn(body) -> "EXPLAIN (GENERIC_PLAN, FORMAT JSON) $body"
                    else -> "EXPLAIN (FORMAT JSON) $body"
                }
            // MySQL's EXPLAIN ANALYZE prints a tree; JSON needs version 2 of the format, which isn't the default.
            PlanEngine.MySQL -> if (analyze) "EXPLAIN ANALYZE $body" else "EXPLAIN FORMAT=JSON $body"
            PlanEngine.MariaDB -> if (analyze) "ANALYZE FORMAT=JSON $body" else "EXPLAIN FORMAT=JSON $body"
        }
    }

    /** The plan in EXPLAIN's result: one text cell, or a line per row as some drivers split it. */
    fun parse(result: QueryResult.Rows): QueryPlan? {
        if (result.columns.size != 1 || result.rows.isEmpty()) return null
        return parse(result.rows.joinToString("\n") { it.firstOrNull().orEmpty() })
    }

    /** The plan in [text], whichever of the formats above it's in; null when it's none of them or doesn't read. */
    fun parse(text: String): QueryPlan? {
        val trimmed = text.trim()
        return runCatching {
            when {
                trimmed.startsWith("->") -> MySqlPlans.tree(trimmed)
                trimmed.startsWith("[") || trimmed.startsWith("{") -> {
                    val json: JsonElement = Json.parseToJsonElement(trimmed)
                    val top = json as? JsonObject
                    when {
                        top != null && "query_block" in top -> MySqlPlans.classic(trimmed, top)
                        top != null && "operation" in top -> MySqlPlans.version2(trimmed, top)
                        else -> PostgresPlans.parse(trimmed, json)
                    }
                }
                else -> null
            }
        }.getOrNull()
    }
}
