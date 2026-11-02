package dev.tablegrip.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlClassifierTest {
    private val classifier = SqlClassifier(SqlSyntax.PostgreSQL)

    private fun kind(sql: String) = classifier.classify(sql).kind

    @Test
    fun `classifies statement kinds`() {
        assertEquals(StatementKind.Read, kind("-- note\n select 1"))
        assertEquals(StatementKind.Read, kind("EXPLAIN SELECT * FROM t"))
        assertEquals(StatementKind.Write, kind("EXPLAIN ANALYZE DELETE FROM t WHERE id = 1"))
        assertEquals(StatementKind.Write, kind("insert into t values (1)"))
        assertEquals(StatementKind.Ddl, kind("CREATE TABLE t (id int)"))
        assertEquals(StatementKind.Transaction, kind("BEGIN"))
        assertEquals(StatementKind.Other, kind("SET search_path = app"))
    }

    @Test
    fun `finds the verb after common table expressions`() {
        assertEquals(StatementKind.Read, kind("WITH x AS (SELECT 1) SELECT * FROM x"))
        // A data-modifying CTE writes even though the outer statement is a SELECT.
        assertEquals(StatementKind.Write, kind("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"))
        assertEquals(StatementKind.Write, kind("WITH ids AS (SELECT id FROM t) DELETE FROM u WHERE id IN (SELECT id FROM ids)"))
    }

    @Test
    fun `flags dangerous statements`() {
        assertNotNull(classifier.classify("DELETE FROM users").danger)
        assertNotNull(classifier.classify("update users set active = false").danger)
        assertNull(classifier.classify("UPDATE users SET active = false WHERE id = 1").danger)
        // A WHERE inside a subquery doesn't count.
        assertNotNull(classifier.classify("UPDATE t SET x = (SELECT max(y) FROM u WHERE u.k = 1)").danger)
        assertNotNull(classifier.classify("DROP TABLE users").danger)
        assertNotNull(classifier.classify("TRUNCATE users").danger)
        assertNull(classifier.classify("SELECT * FROM users").danger)
    }
}
