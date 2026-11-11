package dev.tablegrip.core

import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.RelationalConnection
import dev.tablegrip.core.filter.SelectFunction
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the SQL a dialect writes for filters, charts and the select builder on a real server, after [RelationalContract]
 * seeded `users`: string comparisons alone don't prove the engine accepts it.
 */
class DialectSmoke(
    private val connection: RelationalConnection,
    /** Wednesday 15 May 2024, 13:45, as the engine writes a timestamp literal. */
    private val at: String = "TIMESTAMP '2024-05-15 13:45:00'",
) {
    private val d = connection.dialect

    private fun q(name: String) = d.quoteIdentifier(name)

    suspend fun run() =
        connection.openSession().use { session ->
            val users = q("users")
            suspend fun rows(sql: String) = (session.execute(sql) as QueryResult.Rows).rows
            suspend fun one(select: String) = rows("SELECT $select FROM $users WHERE ${q("id")} = 3").single().single()

            // Case-insensitive LIKE, with the dialect's own escaping of the pattern. The contract left bob untouched.
            val (before, after) = d.textMatch(q("name"), caseSensitive = false, negated = false)
            val pattern = d.textPattern("B%", caseSensitive = false).replace("'", "''")
            assertEquals(listOf("bob"), rows("SELECT ${q("name")} FROM $users WHERE $before'$pattern'$after").map { it[0] })

            if (d.supportsRegex) {
                val (rb, ra) = d.regexMatch(q("name"), caseSensitive = false, negated = false)!!
                assertEquals(listOf("bob"), rows("SELECT ${q("name")} FROM $users WHERE $rb'^B'$ra").map { it[0] })
            }

            val expected =
                mapOf(
                    TimeBucket.Day to "2024-05-15", TimeBucket.Week to "2024-05-13", TimeBucket.Month to "2024-05-01",
                    TimeBucket.Quarter to "2024-04-01", TimeBucket.Year to "2024-01-01", TimeBucket.Hour to "2024-05-15 13:00",
                )
            for ((bucket, start) in expected) {
                val sql = d.timeBucket(at, bucket) ?: continue
                val value = one(sql)?.replace('T', ' ')
                assertTrue(value != null && value.startsWith(start), "$bucket: $sql gave $value")
            }

            val name = q("name")
            val functions =
                mapOf(
                    SelectFunction.Upper to listOf<String>() to "BOB", SelectFunction.Lower to listOf<String>() to "bob",
                    SelectFunction.Length to listOf<String>() to "3", SelectFunction.Substring to listOf("2", "2") to "ob",
                    SelectFunction.Trim to listOf<String>() to "bob", SelectFunction.AsText to listOf<String>() to "bob",
                )
            for ((call, result) in functions) {
                val (function, arguments) = call
                assertEquals(result, one(d.selectFunction(function, name, arguments)!!), function.name)
            }
            val numbers = mapOf(SelectFunction.Abs to "3", SelectFunction.Floor to "3", SelectFunction.Ceil to "3")
            for ((function, result) in numbers) assertEquals(result, one(d.selectFunction(function, q("id"))!!)?.substringBefore('.'), function.name)
            assertTrue(one(d.selectFunction(SelectFunction.Round, q("id"), listOf("1"))!!)!!.startsWith("3"))

            // Value suggestions in the filter builder.
            val namespaces = connection.namespaces()
            val namespace = namespaces.first { it.displayName == d.defaultNamespace(namespaces.map { n -> n.displayName }, connection.config.database) }
            val sample = session.execute(d.sampleValues(dev.tablegrip.core.metadata.TableRef(namespace, "users"), "name", 5, 100).replace("?", "'${d.samplePattern("b")}'"))
            assertEquals(listOf("bob"), (sample as QueryResult.Rows).rows.map { it[0] })
        }
}
