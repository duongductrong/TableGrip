package dev.tablegrip.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatementGuardTest {
    private val guard = StatementGuard(SqlSyntax.PostgreSQL)
    private val writes = setOf(StatementKind.Write)

    private fun rejected(sql: String, allowed: Set<StatementKind> = StatementGuard.Reads) = assertIs<StatementGuard.Verdict.Rejected>(guard.check(sql, allowed))

    @Test
    fun `accepts one read and strips its delimiter`() {
        val verdict = assertIs<StatementGuard.Verdict.Accepted>(guard.check("  select * from t where note = 'a;b' ; ", StatementGuard.Reads))
        assertEquals("select * from t where note = 'a;b'", verdict.sql)
        assertIs<StatementGuard.Verdict.Accepted>(guard.check("-- why\nWITH x AS (SELECT 1) SELECT * FROM x", StatementGuard.Reads))
        assertIs<StatementGuard.Verdict.Accepted>(guard.check("EXPLAIN SELECT 1", StatementGuard.Reads))
    }

    @Test
    fun `a second statement can't ride along`() {
        rejected("SELECT 1; DROP TABLE users")
        rejected("SELECT 1; DELETE FROM users", writes + StatementKind.Read)
        assertTrue((rejected("select 1; select 2") as StatementGuard.Verdict.Rejected).reason.contains("one statement"))
    }

    @Test
    fun `reads that write are refused`() {
        rejected("WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone")
        rejected("EXPLAIN ANALYZE SELECT * FROM t")
        rejected("SELECT * INTO backup_users FROM users")
        rejected("UPDATE t SET x = 1 WHERE id = 2")
        rejected("CREATE TABLE t (id int)", writes)
        // INTO inside a subquery or a string is not the statement's own.
        assertIs<StatementGuard.Verdict.Accepted>(guard.check("SELECT 'insert into' AS x", StatementGuard.Reads))
    }

    @Test
    fun `session and transaction control are refused`() {
        rejected("BEGIN", StatementKind.entries.toSet() - StatementKind.Transaction - StatementKind.Other)
        rejected("SET search_path = evil")
        rejected("COMMIT", writes)
        rejected("   ")
    }

    @Test
    fun `writes pass when allowed`() {
        val verdict = assertIs<StatementGuard.Verdict.Accepted>(guard.check("DELETE FROM t", writes))
        assertEquals(StatementKind.Write, verdict.info.kind)
        assertTrue(verdict.info.danger != null)
    }
}
