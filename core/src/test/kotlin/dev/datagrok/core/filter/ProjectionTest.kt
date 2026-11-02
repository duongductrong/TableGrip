package dev.datagrok.core.filter

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.Database
import dev.datagrok.core.metadata.TableRef
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ProjectionCompilerTest {
    private val columns =
        listOf(
            FilterColumn("id", "int4", ValueKind.Number),
            FilterColumn("name", "text", ValueKind.Text),
            FilterColumn("total", "float8", ValueKind.Number),
            FilterColumn("created_at", "timestamptz", ValueKind.DateTime),
            FilterColumn("photo", "bytea", ValueKind.Binary),
        )

    private var ids = 0L

    private fun field(column: String, function: SelectFunction = SelectFunction.None, argument: String = "", alias: String = "", enabled: Boolean = true) =
        SelectField(++ids, column, function, argument, alias, enabled)

    private fun compile(vararg items: SelectItem, distinct: Boolean = false, dialect: SqlDialect = SqlDialect.PostgreSQL) =
        ProjectionCompiler(dialect, columns).compile(Projection(items.toList(), distinct))

    private fun sql(vararg items: SelectItem, distinct: Boolean = false, dialect: SqlDialect = SqlDialect.PostgreSQL): String? {
        val compiled = compile(*items, distinct = distinct, dialect = dialect)
        assertEquals(emptyMap(), compiled.issues)
        return compiled.projection?.clause
    }

    @Test fun `no items or every column unchanged is all columns`() {
        assertNull(sql())
        assertNull(sql(*columns.map { field(it.name) }.toTypedArray()))
        assertTrue(compile().editable)
    }

    @Test fun `a subset or new order lists the columns and stays editable`() {
        val compiled = compile(field("name"), field("id"), field("total", enabled = false))
        assertEquals("\"name\", \"id\"", compiled.projection?.clause)
        assertTrue(compiled.editable)
    }

    @Test fun `functions get a readable default name and make rows read-only`() {
        val compiled = compile(field("id"), field("name", SelectFunction.Upper))
        assertEquals("\"id\", UPPER(\"name\") AS \"upper(name)\"", compiled.projection?.clause)
        assertFalse(compiled.editable)
    }

    @Test fun `an alias renames even a plain column`() {
        val compiled = compile(field("name", alias = "customer"))
        assertEquals("\"name\" AS \"customer\"", compiled.projection?.clause)
        assertFalse(compiled.editable)
    }

    @Test fun `arguments are validated and inlined`() {
        assertEquals("ROUND(CAST(\"total\" AS NUMERIC), 2) AS \"t\"", sql(field("total", SelectFunction.Round, "2", "t")))
        assertEquals("ROUND(`total`, 0) AS `round(total)`", sql(field("total", SelectFunction.Round), dialect = SqlDialect.MySQL))
        assertEquals("SUBSTRING(\"name\", 2, 3) AS \"substring(name)\"", sql(field("name", SelectFunction.Substring, "2, 3")))
        assertEquals("COALESCE(\"name\", 'it''s') AS \"coalesce(name)\"", sql(field("name", SelectFunction.Coalesce, "it's")))
        assertEquals("COALESCE(\"total\", -1.5) AS \"coalesce(total)\"", sql(field("total", SelectFunction.Coalesce, "-1.5")))
        assertEquals("date_trunc('month', \"created_at\") AS \"truncate(created_at)\"", sql(field("created_at", SelectFunction.Truncate, "Month")))

        assertEquals(setOf(ids + 1), compile(field("total", SelectFunction.Round, "eleven")).issues.keys)
        assertEquals(setOf(ids + 1), compile(field("name", SelectFunction.Substring, "0, 3")).issues.keys)
        assertEquals(setOf(ids + 1), compile(field("total", SelectFunction.Coalesce, "n/a")).issues.keys)
        assertEquals(setOf(ids + 1), compile(field("created_at", SelectFunction.Truncate, "decade")).issues.keys)
    }

    @Test fun `functions must suit the column`() {
        assertEquals(setOf(ids + 1), compile(field("total", SelectFunction.Upper)).issues.keys)
        assertEquals(setOf(ids + 1), compile(field("photo", SelectFunction.AsText)).issues.keys)
        assertEquals(setOf(ids + 1), compile(field("missing")).issues.keys)
        assertEquals(listOf(listOf(SelectFunction.None)), SelectFunction.forKind(ValueKind.Binary, SqlDialect.PostgreSQL))
        // Engines without date functions offer no truncation.
        assertFalse(SelectFunction.forKind(ValueKind.Date, SqlDialect.Generic).flatten().contains(SelectFunction.Truncate))
    }

    @Test fun `engine-specific function names`() {
        assertEquals("CHAR_LENGTH(`name`) AS `length(name)`", sql(field("name", SelectFunction.Length), dialect = SqlDialect.MySQL))
        assertEquals("LEN([name]) AS [length(name)]", sql(field("name", SelectFunction.Length), dialect = SqlDialect.SqlServer))
        assertEquals("substr(\"name\", 1, 2) AS \"substring(name)\"", sql(field("name", SelectFunction.Substring, "1,2"), dialect = SqlDialect.SQLite))
    }

    @Test fun `expressions show under their text unless named`() {
        // The SQL is kept as typed; only its default name is tidied.
        assertEquals("total  *\n2 AS \"total * 2\"", sql(SelectExpression(++ids, "total  *\n2;")))
        assertEquals("total * 2 AS \"double\"", sql(SelectExpression(++ids, "total * 2", "double")))
        assertEquals(setOf(ids + 1), compile(SelectExpression(++ids, " ")).issues.keys)
    }

    @Test fun `two columns can't show under one name`() {
        val compiled = compile(field("name"), field("id", alias = "NAME"))
        assertEquals(setOf(ids), compiled.issues.keys)
        assertNull(compiled.projection)
    }

    @Test fun `hiding everything is a problem with the list`() {
        assertEquals(setOf(0L), compile(field("id", enabled = false)).issues.keys)
    }

    @Test fun `distinct keeps the columns and counts distinct rows`() {
        val compiled = compile(*columns.take(2).map { field(it.name) }.toTypedArray(), distinct = true)
        assertEquals("DISTINCT \"id\", \"name\"", compiled.projection?.clause)
        assertFalse(compiled.editable)
        val table = TableRef(dev.datagrok.core.metadata.Namespace(null, "public"), "t")
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT DISTINCT \"id\", \"name\" FROM \"public\".\"t\" WHERE x = 1) d",
            SqlDialect.PostgreSQL.countRows(table, "x = 1", compiled.projection),
        )
        assertEquals(
            "SELECT DISTINCT [id], [name] FROM [public].[t] ORDER BY 1 OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
            SqlDialect.SqlServer.selectPage(table, null, emptyList(), 10, 0, SqlProjection("[id], [name]", distinct = true)),
        )
    }
}

/** Compiled select lists run against a real (SQLite) database, through the same paging and counting the table viewer uses. */
class ProjectionExecutionTest {
    private val config = ConnectionConfig(id = UUID.randomUUID().toString(), name = "test", type = DatabaseType.SQLite, database = ":memory:")
    private val database = runBlocking { Database.connect(config, password = null) }

    @AfterTest fun close() = database.close()

    private val table: TableRef =
        runBlocking {
            database.openSession().use { session ->
                session.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, total NUMERIC, placed DATE)")
                session.execute(
                    """
                    INSERT INTO orders VALUES
                      (1, '  Alice ', 10.456, '2026-10-28'),
                      (2, 'bob',      -99,    '2026-10-20'),
                      (3, 'bob',      -99,    '2026-10-20'),
                      (4, NULL,       0,      NULL)
                    """.trimIndent()
                )
            }
            TableRef(database.namespaces().single(), "orders")
        }

    private var ids = 0L

    private fun field(column: String, function: SelectFunction = SelectFunction.None, argument: String = "", alias: String = "") =
        SelectField(++ids, column, function, argument, alias)

    private fun rows(vararg items: SelectItem, distinct: Boolean = false, filter: SqlFilter? = null, sort: String? = null): Pair<List<String>, List<List<String?>>> =
        runBlocking {
            val details = database.tableDetails(table)
            val columns = details.columns.map { FilterColumn(it.name, it.typeName, ValueKind.of(it.typeName, it.jdbcType, it.size)) }
            val compiled = ProjectionCompiler(database.dialect, columns).compile(Projection(items.toList(), distinct))
            assertEquals(emptyMap(), compiled.issues)
            val order = listOfNotNull(sort?.let { SortColumn(it, SortDirection.Asc) })
            val page = database.fetchPage(table, filter, order, limit = 100, offset = 0, projection = compiled.projection)
            assertEquals(page.rows.size.toLong(), database.countRows(table, filter, projection = compiled.projection))
            page.columns.map { it.name } to page.rows
        }

    @Test fun `text functions`() {
        val (names, rows) = rows(field("id"), field("customer", SelectFunction.Trim, alias = "c"), field("customer", SelectFunction.Upper), field("customer", SelectFunction.Length), sort = "id")
        assertEquals(listOf("id", "c", "upper(customer)", "length(customer)"), names)
        assertEquals(listOf("1", "Alice", "  ALICE ", "8"), rows[0])
        assertEquals(listOf("4", null, null, null), rows[3])
        assertEquals(listOf("li", ""), rows(field("customer", SelectFunction.Substring, "4, 2"), sort = "id").second.take(2).map { it[0] })
    }

    @Test fun `numbers, NULL fallbacks and dates`() {
        val (_, rows) =
            rows(
                field("total", SelectFunction.Round, "1"),
                field("total", SelectFunction.Abs, alias = "abs"),
                field("customer", SelectFunction.Coalesce, "(none)"),
                field("placed", SelectFunction.Truncate, "month"),
                sort = "abs",
            )
        assertEquals(listOf("0", "(none)", null), rows[0].drop(1))
        // Sorted by the alias: 0, 10.456, 99, 99.
        assertEquals("10.5", rows[1][0])
        assertEquals("2026-10-01", rows[1][3])
    }

    @Test fun `expressions, distinct and filters combine`() {
        val (names, rows) =
            rows(field("customer"), SelectExpression(++ids, "total * 2", "double"), distinct = true, filter = SqlFilter.raw("total < 0"))
        assertEquals(listOf("customer", "double"), names)
        assertEquals(listOf(listOf("bob", "-198")), rows)
    }
}
