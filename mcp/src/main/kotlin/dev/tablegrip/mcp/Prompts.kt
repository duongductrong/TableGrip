package dev.tablegrip.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Ready-made starting points the client may offer as slash commands. */
object Prompts {
    class Prompt(val name: String, val title: String, val description: String, val arguments: List<Pair<String, String>>, val text: (Map<String, String>) -> String) {
        fun describe(): JsonObject =
            buildJsonObject {
                put("name", name)
                put("title", title)
                put("description", description)
                putJsonArray("arguments") {
                    arguments.forEach { (argument, about) -> add(buildJsonObject { put("name", argument); put("description", about); put("required", true) }) }
                }
            }
    }

    private const val ConnectionArgument = "The connection's name, as list_connections shows it"

    val all =
        listOf(
            Prompt("explore-database", "Explore a database", "Get a tour of a database: its main tables and how they relate", listOf("connection" to ConnectionArgument)) {
                """
                Using the TableGrip tools, explore the database behind the connection "${it["connection"]}".
                1. Call schema_overview for it.
                2. Pick the 5–10 tables that look central (most referenced, or named after the domain) and describe_table each.
                3. Explain in a few short paragraphs what the database is for, its main entities, and how they relate (with the
                   foreign keys). End with three example questions it could answer, each with the SQL to answer it.
                Don't run queries that return data unless the connection allows data access.
                """.trimIndent()
            },
            Prompt(
                "write-query",
                "Write a query",
                "Write and run SQL for a question about the data",
                listOf("connection" to ConnectionArgument, "request" to "What you want to know"),
            ) {
                """
                Answer this with SQL on the connection "${it["connection"]}": ${it["request"]}
                First read the tables you need (schema_overview, describe_table; column_values for status-like columns). Then write
                one read-only statement, run it with query, and answer from the result. Show the SQL you ran. If the result was
                truncated, say so and refine the query rather than guessing.
                """.trimIndent()
            },
            Prompt(
                "optimize-query",
                "Optimize a query",
                "Find out why a query is slow and how to speed it up",
                listOf("connection" to ConnectionArgument, "sql" to "The slow statement"),
            ) {
                """
                The statement below is slow on the connection "${it["connection"]}". Run explain_query on it, describe_table the
                tables involved, and explain what the plan shows (full scans, sorts, row estimates). Propose concrete fixes: a
                rewrite, or CREATE INDEX statements. Don't execute any of them; the user will decide.

                ${it["sql"]}
                """.trimIndent()
            },
            Prompt(
                "review-migration",
                "Review a migration",
                "Check a schema or data change before it runs",
                listOf("connection" to ConnectionArgument, "sql" to "The migration's SQL"),
            ) {
                """
                Review this migration for the connection "${it["connection"]}" before it runs. For each statement, describe_table
                what it touches and say: what it changes, whether it can lose data, whether it locks or rewrites a large table
                (count_rows when data access allows), and whether it can be rolled back. Suggest a safer order or form where
                there is one. Do not execute anything.

                ${it["sql"]}
                """.trimIndent()
            },
        )

    fun get(name: String, arguments: JsonObject): JsonElement {
        val prompt = all.firstOrNull { it.name == name } ?: throw RpcException(RpcException.InvalidParams, "Unknown prompt: $name")
        val values = prompt.arguments.associate { (argument, _) -> argument to (arguments.text(argument) ?: throw RpcException(RpcException.InvalidParams, "'$argument' is required")) }
        return buildJsonObject {
            put("description", prompt.description)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonObject("content") { put("type", "text"); put("text", prompt.text(values)) }
                })
            }
        }
    }
}
