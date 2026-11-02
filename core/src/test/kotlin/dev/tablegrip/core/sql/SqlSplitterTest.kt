package dev.tablegrip.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlSplitterTest {
    private val pg = SqlSplitter(SqlSyntax.PostgreSQL)
    private val mysql = SqlSplitter(SqlSyntax.MySQL)
    private val sqlite = SqlSplitter(SqlSyntax.SQLite)

    private fun SqlSplitter.texts(sql: String) = split(sql).map { it.text }

    @Test
    fun `splits on semicolons and drops empty statements`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), pg.texts("SELECT 1;\n\n;  SELECT 2  "))
    }

    @Test
    fun `ignores delimiters inside strings, identifiers and comments`() {
        val sql = "SELECT 'a;b', \"c;d\" -- x;y\nFROM /* ; */ t /* trailing */;SELECT 'it''s;'"
        assertEquals(listOf("SELECT 'a;b', \"c;d\" -- x;y\nFROM /* ; */ t", "SELECT 'it''s;'"), pg.texts(sql))
    }

    @Test
    fun `keeps PostgreSQL dollar-quoted bodies together`() {
        val fn = "CREATE FUNCTION f() RETURNS int AS \$body\$ BEGIN RETURN 1; END; \$body\$ LANGUAGE plpgsql"
        assertEquals(listOf(fn, "SELECT f()"), pg.texts("$fn;\nSELECT f();"))
    }

    @Test
    fun `honours MySQL backslash escapes, hash comments and DELIMITER`() {
        val sql =
            """
            SELECT 'a\';b' # c;d
            ;
            DELIMITER //
            CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END//
            DELIMITER ;
            CALL p();
            """
                .trimIndent()
        assertEquals(
            listOf("SELECT 'a\\';b'", "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END", "CALL p()"),
            mysql.texts(sql),
        )
    }

    @Test
    fun `keeps SQLite trigger bodies together`() {
        val trigger = "CREATE TRIGGER t AFTER INSERT ON a BEGIN UPDATE b SET n = CASE WHEN 1 THEN 2 END; DELETE FROM c; END"
        assertEquals(listOf(trigger, "BEGIN", "COMMIT"), sqlite.texts("$trigger;\nBEGIN;\nCOMMIT;"))
    }

    @Test
    fun `finds the statement at the caret`() {
        val sql = "SELECT 1;\nSELECT 2;   \n\nSELECT 3"
        assertEquals("SELECT 1", pg.statementAt(sql, 3)?.text)
        assertEquals("SELECT 1", pg.statementAt(sql, 9)?.text) // right after the delimiter
        assertEquals("SELECT 2", pg.statementAt(sql, sql.indexOf("   ") + 2)?.text) // trailing spaces on its line
        assertEquals("SELECT 3", pg.statementAt(sql, sql.length)?.text)
    }
}
