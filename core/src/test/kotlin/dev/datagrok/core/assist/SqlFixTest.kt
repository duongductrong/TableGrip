package dev.datagrok.core.assist

import dev.datagrok.core.completion.ColumnEntry
import dev.datagrok.core.completion.Relation
import dev.datagrok.core.completion.SchemaProvider
import dev.datagrok.core.completion.TableEntry
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.QueryError
import dev.datagrok.core.sql.SqlSyntax
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SqlFixTest {
    private val schema =
        object : SchemaProvider {
            override val defaultNamespace = "public"
            override fun namespaces() = listOf("public")
            override fun tables(namespace: String?) = listOf(TableEntry("users"), TableEntry("orders"), TableEntry("order_items"))
            override fun columns(namespace: String?, table: String): List<ColumnEntry>? =
                when (table.lowercase()) {
                    "users" -> listOf(ColumnEntry("id", "int", true), ColumnEntry("email", "text"), ColumnEntry("emails", "text[]"), ColumnEntry("created_at", "timestamp"))
                    "orders" -> listOf(ColumnEntry("id", "int", true), ColumnEntry("user_id", "int"), ColumnEntry("total", "numeric"), ColumnEntry("Status", "text"))
                    else -> null
                }
            override fun relations(namespace: String?, table: String): List<Relation>? = null
        }

    private val postgres = SqlDialect.of(DatabaseType.PostgreSQL)
    private val fixer = SqlNameFixer(postgres)

    private fun error(message: String) = QueryError(message)

    @Test
    fun `reads the unknown name from each engine's wording`() {
        val cases =
            mapOf(
                "ERROR: column \"usr_id\" does not exist\n  Position: 8" to MissingName(MissingName.Kind.Column, "usr_id"),
                "ERROR: column o.usr_id does not exist" to MissingName(MissingName.Kind.Column, "usr_id"),
                "ERROR: relation \"userz\" does not exist" to MissingName(MissingName.Kind.Table, "userz"),
                "Unknown column 'usr_id' in 'field list'" to MissingName(MissingName.Kind.Column, "usr_id"),
                "Table 'shop.userz' doesn't exist" to MissingName(MissingName.Kind.Table, "userz"),
                "[SQLITE_ERROR] SQL error or missing database (no such column: usr_id)" to MissingName(MissingName.Kind.Column, "usr_id"),
                "[SQLITE_ERROR] SQL error or missing database (no such table: userz)" to MissingName(MissingName.Kind.Table, "userz"),
                "Invalid column name 'usr_id'." to MissingName(MissingName.Kind.Column, "usr_id"),
                "Invalid object name 'dbo.userz'." to MissingName(MissingName.Kind.Table, "userz"),
                "Binder Error: Referenced column \"usr_id\" not found in FROM clause!" to MissingName(MissingName.Kind.Column, "usr_id"),
                "Catalog Error: Table with name userz does not exist!" to MissingName(MissingName.Kind.Table, "userz"),
            )
        cases.forEach { (message, expected) -> assertEquals(expected, SqlErrors.missingName(error(message)), message) }
        assertNull(SqlErrors.missingName(error("ERROR: syntax error at or near \"FORM\"")))
    }

    @Test
    fun `a misspelt column or table is replaced by the one closest name`() {
        val column = fixer.fix("SELECT o.usr_id, total FROM orders o WHERE o.usr_id > 1", error("column o.usr_id does not exist"), schema)
        assertEquals("SELECT o.user_id, total FROM orders o WHERE o.user_id > 1", column?.sql)
        assertEquals("There is no column usr_id; the closest is user_id.", column?.explanation)

        assertEquals("SELECT * FROM users", fixer.fix("SELECT * FROM userz", error("relation \"userz\" does not exist"), schema)?.sql)
        // Swapped letters are one typo.
        assertEquals("SELECT email FROM users", fixer.fix("SELECT emial FROM users", error("column \"emial\" does not exist"), schema)?.sql)
    }

    @Test
    fun `names that need quotes get them, and quoted ones stay quoted`() {
        assertEquals("SELECT \"Status\" FROM orders", fixer.fix("SELECT statuss FROM orders", error("column \"statuss\" does not exist"), schema)?.sql)
        assertEquals("SELECT \"user_id\" FROM orders", fixer.fix("SELECT \"usr_id\" FROM orders", error("column \"usr_id\" does not exist"), schema)?.sql)
        // MySQL keeps its own quotes.
        val mysql = SqlNameFixer(SqlDialect.of(DatabaseType.MySQL))
        assertEquals("SELECT `user_id` FROM orders", mysql.fix("SELECT `usr_id` FROM orders", error("Unknown column 'usr_id' in 'field list'"), schema)?.sql)
    }

    @Test
    fun `no fix when nothing is close, two names are equally close, or the schema isn't loaded`() {
        assertNull(fixer.fix("SELECT nonsense FROM orders", error("column \"nonsense\" does not exist"), schema))
        assertNull(fixer.fix("SELECT emaill FROM users", error("column \"emaill\" does not exist"), schema))
        assertNull(fixer.fix("SELECT usr_id FROM orders", error("column \"usr_id\" does not exist"), SchemaProvider.Empty))
        assertEquals(listOf("user_id"), fixer.nearMisses("SELECT usr_id FROM orders", MissingName(MissingName.Kind.Column, "usr_id"), schema))
    }

    @Test
    fun `review flags a fix that writes where the original read`() {
        assertEquals(emptyList(), reviewFix("SELECT usr_id FROM orders", "SELECT user_id FROM orders", SqlSyntax.PostgreSQL))
        assertEquals(
            listOf("DELETE without WHERE affects every row", "The fix changes data or schema (DELETE); the original only read."),
            reviewFix("SELECT * FROM orders", "DELETE FROM orders", SqlSyntax.PostgreSQL),
        )
        assertEquals(listOf("The fix runs DELETE instead of UPDATE."), reviewFix("UPDATE t SET a = 1 WHERE id = 1", "DELETE FROM t WHERE id = 1", SqlSyntax.PostgreSQL))
    }

    @Test
    fun `the agent's fix must be one changed statement, or none with a reason`() {
        val task = SqlFixTask(postgres, "PostgreSQL", "SELECT usr_id FROM orders", QueryError("column \"usr_id\" does not exist", "42703", position = 7), "orders(id int pk, user_id int)", listOf("user_id"))
        fun parse(json: String) = task.parse(Json.parseToJsonElement(json).jsonObject)

        assertEquals(SqlFix("SELECT user_id FROM orders", "Renamed."), parse("""{"sql": "SELECT user_id FROM orders;", "explanation": "Renamed."}"""))
        assertEquals(SqlFix(null, "No such table."), parse("""{"sql": null, "explanation": "No such table."}"""))
        assertTrue("exactly one statement" in assertFailsWith<AssistInvalid> { parse("""{"sql": "SELECT 1; SELECT 2", "explanation": "x"}""") }.message!!)
        assertTrue("same statement" in assertFailsWith<AssistInvalid> { parse("""{"sql": "SELECT  usr_id\nFROM orders", "explanation": "x"}""") }.message!!)
        assertFailsWith<AssistInvalid> { parse("""{"sql": "SELECT 1"}""") }

        val prompt = task.prompt()
        assertTrue("SQLSTATE: 42703" in prompt)
        assertTrue("near: usr_id FROM orders" in prompt)
        assertTrue("close to the unknown one: user_id" in prompt)
        assertTrue("orders(id int pk, user_id int)" in prompt)
        val unshared = SqlFixTask(postgres, "PostgreSQL", "SELECT 1", QueryError("boom"), schema = null).prompt()
        assertTrue("Not shared" in unshared)
    }

    /** Shaped like pgjdbc's PSQLException, which the app only ever sees through another class loader. */
    class FakePsqlException : SQLException("column \"x\" does not exist", "42703", 7) {
        class Server {
            fun getPosition(): Int = 8
        }

        fun getServerErrorMessage() = Server()
    }

    @Test
    fun `query errors keep the SQL state, vendor code and pgjdbc's position`() {
        assertEquals(QueryError("column \"x\" does not exist", "42703", 7, 7), QueryError.of(RuntimeException("wrapped", FakePsqlException())).copy(message = "column \"x\" does not exist"))
        assertEquals(QueryError("plain"), QueryError.of(IllegalStateException("plain")))
    }
}
