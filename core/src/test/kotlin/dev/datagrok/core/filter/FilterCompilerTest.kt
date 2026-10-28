package dev.datagrok.core.filter

import dev.datagrok.core.dialect.SqlDialect
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterCompilerTest {
    // Wednesday 28 October 2026, 13:45.
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 28, 13, 45, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val columns =
        listOf(
            FilterColumn("name", "text", ValueKind.Text),
            FilterColumn("total", "numeric", ValueKind.Number),
            FilterColumn("qty", "int4", ValueKind.Number),
            FilterColumn("active", "bool", ValueKind.Boolean),
            FilterColumn("born", "date", ValueKind.Date),
            FilterColumn("created_at", "timestamptz", ValueKind.DateTime),
            FilterColumn("opens", "time", ValueKind.Time),
            FilterColumn("token", "uuid", ValueKind.Uuid),
            FilterColumn("payload", "jsonb", ValueKind.Json),
            FilterColumn("mood", "mood", ValueKind.Other),
            FilterColumn("photo", "bytea", ValueKind.Binary),
        )

    private var ids = 0L

    private fun cond(column: String, op: FilterOperator, value: String = "", value2: String = "", caseSensitive: Boolean = false, enabled: Boolean = true) =
        FilterCondition(++ids, column, op, value, value2, caseSensitive, enabled)

    private fun all(vararg nodes: FilterNode) = FilterGroup(0, Combinator.All, nodes.toList())

    private fun compile(vararg nodes: FilterNode, dialect: SqlDialect = SqlDialect.PostgreSQL) =
        FilterCompiler(dialect, columns, clock).compile(all(*nodes))

    private fun sql(vararg nodes: FilterNode, dialect: SqlDialect = SqlDialect.PostgreSQL) = compile(*nodes, dialect = dialect).filter!!

    @Test
    fun `an empty or fully disabled filter compiles to nothing`() {
        assertNull(compile().filter)
        assertTrue(compile().isValid)
        assertNull(compile(cond("name", FilterOperator.Equals, "x", enabled = false)).filter)
    }

    @Test
    fun `binds typed values instead of splicing them`() {
        val filter = sql(cond("qty", FilterOperator.GreaterOrEqual, "10"), cond("total", FilterOperator.Less, "99.5"), cond("name", FilterOperator.Equals, "O'Brien"))
        assertEquals("\"qty\" >= ? AND \"total\" < ? AND \"name\" = ?", filter.sql)
        assertEquals(listOf<Any>(10L, BigDecimal("99.5"), "O'Brien"), filter.parameters.map { it.value })
        assertEquals("\"qty\" >= 10 AND \"total\" < 99.5 AND \"name\" = 'O''Brien'", filter.preview)
    }

    @Test
    fun `text matching escapes wildcards and follows case sensitivity per dialect`() {
        val pg = sql(cond("name", FilterOperator.Contains, "50%_off!"))
        assertEquals("\"name\" ILIKE ? ESCAPE '!'", pg.sql)
        assertEquals("%50!%!_off!!%", pg.parameters.single().value)
        assertEquals("\"name\" NOT LIKE ? ESCAPE '!'", sql(cond("name", FilterOperator.NotContains, "a", caseSensitive = true)).sql)
        assertEquals("ab%", sql(cond("name", FilterOperator.StartsWith, "ab")).parameters.single().value)
        assertEquals("%ab", sql(cond("name", FilterOperator.EndsWith, "ab")).parameters.single().value)

        assertEquals("LOWER(`name`) LIKE LOWER(?) ESCAPE '!'", sql(cond("name", FilterOperator.Contains, "a"), dialect = SqlDialect.MySQL).sql)
        assertEquals(
            "CAST(`name` AS BINARY) LIKE CAST(? AS BINARY) ESCAPE '!'",
            sql(cond("name", FilterOperator.Contains, "a", caseSensitive = true), dialect = SqlDialect.MySQL).sql,
        )

        val glob = sql(cond("name", FilterOperator.Contains, "a*b", caseSensitive = true), dialect = SqlDialect.SQLite)
        assertEquals("\"name\" GLOB ?", glob.sql)
        assertEquals("*a[*]b*", glob.parameters.single().value)
    }

    @Test
    fun `like keeps the user's wildcards and non-text columns are matched as text`() {
        assertEquals("a%b_!!", sql(cond("name", FilterOperator.Like, "a%b_!")).parameters.single().value)
        assertEquals("CAST(\"qty\" AS TEXT) ILIKE ? ESCAPE '!'", sql(cond("qty", FilterOperator.StartsWith, "12")).sql)
        assertEquals("CAST(\"qty\" AS TEXT) LIKE ? ESCAPE '!'", sql(cond("qty", FilterOperator.StartsWith, "12"), dialect = SqlDialect.SQLite).sql)
        assertEquals("LOWER(CAST(`qty` AS CHAR)) LIKE LOWER(?) ESCAPE '!'", sql(cond("qty", FilterOperator.StartsWith, "12"), dialect = SqlDialect.MySQL).sql)
    }

    @Test
    fun `regex uses each engine's operator and is refused where there is none`() {
        assertEquals("\"name\" ~* ?", sql(cond("name", FilterOperator.Regex, "^a")).sql)
        assertEquals("\"name\" !~ ?", sql(cond("name", FilterOperator.NotRegex, "^a", caseSensitive = true)).sql)
        assertEquals("`name` REGEXP ?", sql(cond("name", FilterOperator.Regex, "^a"), dialect = SqlDialect.MySQL).sql)
        val sqlite = compile(cond("name", FilterOperator.Regex, "^a"), dialect = SqlDialect.SQLite)
        assertNull(sqlite.filter)
        assertTrue(sqlite.issues.values.single().contains("doesn't apply"))
    }

    @Test
    fun `a date on a date-time column means the whole day`() {
        val on = sql(cond("created_at", FilterOperator.Equals, "2026-01-15"))
        assertEquals("(\"created_at\" >= ? AND \"created_at\" < ?)", on.sql)
        assertEquals(listOf<Any>(LocalDateTime.of(2026, 1, 15, 0, 0), LocalDateTime.of(2026, 1, 16, 0, 0)), on.parameters.map { it.value })
        assertEquals("\"created_at\" < ?", sql(cond("created_at", FilterOperator.LessOrEqual, "2026-01-15")).sql)
        assertEquals(LocalDateTime.of(2026, 1, 16, 0, 0), sql(cond("created_at", FilterOperator.LessOrEqual, "2026-01-15")).parameters.single().value)
        assertEquals(LocalDateTime.of(2026, 1, 16, 0, 0), sql(cond("created_at", FilterOperator.Greater, "2026-01-15")).parameters.single().value)
        // An exact moment compares exactly, converted from its offset to the session zone.
        val exact = sql(cond("created_at", FilterOperator.Equals, "2026-01-15 10:00:00.25+02"))
        assertEquals("\"created_at\" = ?", exact.sql)
        assertEquals(LocalDateTime.of(2026, 1, 15, 8, 0, 0, 250_000_000), exact.parameters.single().value)
        assertEquals("\"created_at\" = '2026-01-15 08:00:00.25'", exact.preview)
    }

    @Test
    fun `relative dates resolve against the clock`() {
        assertEquals(LocalDate.of(2026, 10, 21), sql(cond("born", FilterOperator.GreaterOrEqual, "today-7d")).parameters.single().value)
        assertEquals(LocalDateTime.of(2026, 10, 28, 11, 45, 30), sql(cond("created_at", FilterOperator.Greater, "now - 2h")).parameters.single().value)
        assertEquals(LocalDate.of(2026, 7, 28), sql(cond("born", FilterOperator.Less, "3 months ago")).parameters.single().value)
        assertEquals(LocalDate.of(2026, 10, 27), sql(cond("born", FilterOperator.Equals, "yesterday")).parameters.single().value)
    }

    @Test
    fun `date presets become half-open ranges`() {
        val today = sql(cond("created_at", FilterOperator.Today))
        assertEquals(listOf<Any>(LocalDateTime.of(2026, 10, 28, 0, 0), LocalDateTime.of(2026, 10, 29, 0, 0)), today.parameters.map { it.value })
        assertEquals(listOf<Any>(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), sql(cond("born", FilterOperator.ThisMonth)).parameters.map { it.value })
        assertEquals(listOf<Any>(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), sql(cond("born", FilterOperator.ThisYear)).parameters.map { it.value })
        assertEquals(listOf<Any>(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29)), sql(cond("born", FilterOperator.LastDays, "7")).parameters.map { it.value })
        assertEquals("Enter a whole number of days", compile(cond("born", FilterOperator.LastDays, "1.5")).issues.values.single())
    }

    @Test
    fun `between is inclusive and checks its bounds`() {
        assertEquals("\"qty\" BETWEEN ? AND ?", sql(cond("qty", FilterOperator.Between, "1", "5")).sql)
        assertEquals("\"qty\" NOT BETWEEN ? AND ?", sql(cond("qty", FilterOperator.NotBetween, "1", "5")).sql)
        assertEquals("The first bound is after the second", compile(cond("qty", FilterOperator.Between, "9", "5")).issues.values.single())
        assertEquals("Enter both bounds", compile(cond("qty", FilterOperator.Between, "9")).issues.values.single())
        val days = sql(cond("created_at", FilterOperator.Between, "2026-01-01", "2026-01-31"))
        assertEquals("(\"created_at\" >= ? AND \"created_at\" < ?)", days.sql)
        assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0), days.parameters[1].value)
        assertEquals("(\"opens\" >= ? AND \"opens\" <= ?)", sql(cond("opens", FilterOperator.Between, "09:00", "17:30")).sql)
        assertEquals(LocalTime.of(17, 30), sql(cond("opens", FilterOperator.Between, "09:00", "17:30")).parameters[1].value)
    }

    @Test
    fun `lists parse quotes and NULL`() {
        val list = sql(cond("name", FilterOperator.In, "alice, 'b, c', \"d\"\"e\", NULL"))
        assertEquals("(\"name\" IN (?, ?, ?) OR \"name\" IS NULL)", list.sql)
        assertEquals(listOf<Any>("alice", "b, c", "d\"e"), list.parameters.map { it.value })
        assertEquals("\"qty\" NOT IN (?, ?)", sql(cond("qty", FilterOperator.NotIn, "1\n2\n2")).sql)
        assertEquals("NOT (\"qty\" IN (?) OR \"qty\" IS NULL)", sql(cond("qty", FilterOperator.NotIn, "1, null")).sql)
        assertEquals("“x” isn't a number", compile(cond("qty", FilterOperator.In, "1, x")).issues.values.single())
    }

    @Test
    fun `booleans, uuids, json and unmodelled types bind appropriately`() {
        assertEquals(true, sql(cond("active", FilterOperator.IsTrue)).parameters.single().value)
        assertEquals("\"active\" = FALSE", sql(cond("active", FilterOperator.IsFalse)).preview)
        val token = "6f1c2d3e-4a5b-4c6d-8e9f-a0b1c2d3e4f5"
        assertEquals(UUID.fromString(token), sql(cond("token", FilterOperator.Equals, token.uppercase())).parameters.single().value)
        assertEquals("“nope” isn't a UUID", compile(cond("token", FilterOperator.Equals, "nope")).issues.values.single())
        assertEquals("CAST(\"payload\" AS TEXT) = ?", sql(cond("payload", FilterOperator.Equals, "{}")).sql)
        assertEquals(ValueKind.Other, sql(cond("mood", FilterOperator.Equals, "happy")).parameters.single().kind)
        assertEquals("(\"name\" IS NULL OR \"name\" = '')", sql(cond("name", FilterOperator.IsEmpty)).sql)
        assertEquals("\"photo\" IS NOT NULL", sql(cond("photo", FilterOperator.IsNotNull)).sql)
    }

    @Test
    fun `groups nest, negate and combine with raw SQL`() {
        val inner = FilterGroup(50, Combinator.Any, listOf(cond("qty", FilterOperator.Equals, "1"), cond("qty", FilterOperator.Equals, "2")))
        val negated = FilterGroup(51, Combinator.All, listOf(cond("name", FilterOperator.Equals, "x")), negated = true)
        val filter = sql(inner, negated, RawCondition(52, "length(name) > 3;"))
        assertEquals("(\"qty\" = ? OR \"qty\" = ?) AND NOT (\"name\" = ?) AND (length(name) > 3)", filter.sql)
        val any = FilterCompiler(SqlDialect.PostgreSQL, columns, clock).compile(FilterGroup(0, Combinator.Any, listOf(cond("qty", FilterOperator.IsNull), inner)))
        assertEquals("\"qty\" IS NULL OR (\"qty\" = ? OR \"qty\" = ?)", any.filter!!.sql)
    }

    @Test
    fun `reports each problem against its node`() {
        val missing = cond("name", FilterOperator.Equals)
        val badColumn = cond("gone", FilterOperator.IsNull)
        val badDate = cond("born", FilterOperator.Equals, "someday")
        val result = compile(missing, badColumn, badDate, cond("name", FilterOperator.IsNull))
        assertNull(result.filter)
        assertEquals("Enter a value", result.issues[missing.id])
        assertEquals("No column “gone” in this table", result.issues[badColumn.id])
        assertTrue(result.issues.getValue(badDate.id).startsWith("“someday” isn't a date"))
        assertEquals(3, result.issues.size)
    }

    @Test
    fun `a raw question mark can't share a statement with bound values`() {
        val raw = RawCondition(9, "payload ? 'key'")
        assertEquals("(payload ? 'key')", sql(raw).sql)
        assertTrue(compile(raw, cond("qty", FilterOperator.Equals, "1")).issues.containsKey(9))
        assertTrue(compile(RawCondition(10, "name = 'why?'"), cond("qty", FilterOperator.Equals, "1")).isValid)
    }
}
