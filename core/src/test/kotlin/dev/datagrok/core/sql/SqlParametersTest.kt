package dev.datagrok.core.sql

import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.dialect.SqlDialect
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlParametersTest {
    private val postgres = SqlParameters(SqlDialect.of(DatabaseType.PostgreSQL).syntax)

    @Test
    fun `finds colon and dollar-brace parameters once each`() {
        val sql = "SELECT * FROM orders WHERE user_id = :user_id AND created_at >= \${from} AND user_id <> :user_id"
        assertEquals(listOf("user_id", "from"), postgres.find(sql))
    }

    @Test
    fun `ignores casts, strings and comments`() {
        val sql = "SELECT a::int, ':nope', \"x:y\" -- :comment\n/* \${also} */ FROM t WHERE b = :real"
        assertEquals(listOf("real"), postgres.find(sql))
    }

    @Test
    fun `substitutes values as written`() {
        val sql = "SELECT * FROM t WHERE status = :status AND id IN (\${ids}) AND x = :missing"
        assertEquals(
            "SELECT * FROM t WHERE status = 'paid' AND id IN (1, 2) AND x = :missing",
            postgres.substitute(sql, mapOf("status" to "'paid'", "ids" to "1, 2")),
        )
    }
}
