package dev.tablegrip.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlFormatterTest {
    private val pg = SqlFormatter(SqlSyntax.PostgreSQL)

    @Test
    fun `lays out clauses, lists and conditions`() {
        assertEquals(
            """
            SELECT
                u.id,
                u.name,
                count(*) AS orders
            FROM users u
            LEFT JOIN orders o ON o.user_id = u.id
            WHERE u.active = TRUE
                AND o.total >= 10
                OR u.vip
            GROUP BY u.id, u.name
            ORDER BY orders DESC
            LIMIT 10
            """.trimIndent().replace("GROUP BY u.id, u.name", "GROUP BY\n    u.id,\n    u.name"),
            pg.format(
                "select u.id,u.name, count(*) as orders from users u left join orders o on o.user_id=u.id " +
                    "where u.active = true and o.total>=10 or u.vip group by u.id,u.name order by orders desc limit 10"
            ),
        )
    }

    @Test
    fun `keeps single items on the clause line`() {
        assertEquals("SELECT *\nFROM t\nWHERE id = 1", pg.format("select * from t where id=1"))
    }

    @Test
    fun `indents subqueries and keeps function calls and IN lists inline`() {
        assertEquals(
            """
            SELECT name
            FROM users
            WHERE id IN (
                SELECT user_id
                FROM orders
                WHERE total > coalesce(1, 2)
            )
                AND status IN ('a', 'b')
            """.trimIndent(),
            pg.format("SELECT name FROM users WHERE id IN (SELECT user_id FROM orders WHERE total > coalesce(1,2)) AND status IN ('a','b')"),
        )
    }

    @Test
    fun `never touches strings, comments, quoted identifiers or casts`() {
        assertEquals(
            "SELECT\n    'select  from' AS \"Select\",\n    x::TEXT -- keep  me\nFROM t",
            pg.format("select 'select  from' as \"Select\", x::text -- keep  me\nfrom t"),
        )
    }

    @Test
    fun `BETWEEN ranges stay on one line and statements are separated`() {
        assertEquals(
            "SELECT *\nFROM t\nWHERE a BETWEEN 1 AND 5\n    AND b < -1;\n\nDELETE FROM t\nWHERE a = 2;",
            pg.format("select * from t where a between 1 and 5 and b < -1; delete from t where a = 2;"),
        )
    }

    @Test
    fun `insert, update and set lists`() {
        assertEquals(
            "INSERT INTO t (a, b)\nVALUES (1, 'x');\n\nUPDATE t\nSET\n    a = 1,\n    b = 2\nWHERE id = 3",
            pg.format("insert into t (a, b) values (1, 'x'); update t set a=1, b=2 where id=3"),
        )
    }

    @Test
    fun `keyword case and indent follow the editor's settings`() {
        val sql = "select id, name from users where active and vip"
        assertEquals(
            "select\n\tid,\n\tname\nfrom users\nwhere active\n\tand vip",
            SqlFormatter(SqlSyntax.PostgreSQL, indent = "\t", keywordCase = KeywordCase.Lower).format(sql),
        )
        assertEquals("Select x\nFrom t", SqlFormatter(SqlSyntax.PostgreSQL, keywordCase = KeywordCase.Preserve).format("Select x From t"))
    }
}
