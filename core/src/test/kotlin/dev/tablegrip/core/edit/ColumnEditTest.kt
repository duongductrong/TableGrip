package dev.tablegrip.core.edit

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import java.sql.Types
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ColumnEditTest {
    private val now = LocalDateTime.of(2026, 10, 31, 13, 45, 10)
    private val postgres = SqlDialect.PostgreSQLDialect()
    private val mysql = SqlDialect.MySQLDialect()
    private val sqlite = SqlDialect.SQLiteDialect()

    private fun info(type: String, jdbcType: Int, size: Int? = null, scale: Int? = null, nullable: Boolean = true, default: String? = null, auto: Boolean = false) =
        ColumnInfo("c", type, size, nullable, default, auto, primaryKey = false, jdbcType = jdbcType, scale = scale)

    private fun edit(type: String, jdbcType: Int, dialect: SqlDialect = postgres, size: Int? = null, scale: Int? = null) =
        ColumnEdit.of(ResultColumn("c", type, jdbcType), info(type, jdbcType, size, scale), dialect)

    private fun ColumnEdit.ok(text: String): String = (check(text, now, ZoneOffset.ofHours(7)) as EditCheck.Ok).value

    private fun ColumnEdit.invalid(text: String): String = (check(text, now, ZoneOffset.ofHours(7)) as EditCheck.Invalid).message

    @Test
    fun `classifies columns into edit kinds`() {
        assertEquals(EditKind.Integer, edit("int4", Types.INTEGER).kind)
        assertEquals(EditKind.Decimal, edit("numeric", Types.NUMERIC, size = 10, scale = 2).kind)
        assertEquals(EditKind.Float, edit("float8", Types.DOUBLE).kind)
        assertEquals(EditKind.Boolean, edit("bool", Types.BIT).kind)
        assertEquals(EditKind.Other, edit("money", Types.DOUBLE).kind)
        assertEquals(EditKind.DateTime, edit("timestamptz", Types.TIMESTAMP).kind)
        assertTrue(edit("timestamptz", Types.TIMESTAMP).withTimeZone)
        assertEquals(EditKind.Json, edit("jsonb", Types.OTHER).kind)
        assertEquals(EditKind.Uuid, edit("uuid", Types.OTHER).kind)
        assertEquals(EditKind.Other, edit("_int4", Types.ARRAY).kind)
    }

    @Test
    fun `blank input in a typed column means no value, in a text column an empty string`() {
        assertEquals(EditCheck.Empty, edit("int4", Types.INTEGER).check("  "))
        assertEquals(EditCheck.Empty, edit("date", Types.DATE).check(""))
        assertEquals(EditCheck.Ok(""), edit("text", Types.VARCHAR).check(""))
    }

    @Test
    fun `integers are checked against their type's range`() {
        val small = edit("int2", Types.SMALLINT)
        assertEquals("32767", small.ok("32_767"))
        assertTrue(small.invalid("32768").startsWith("Out of range"))
        assertEquals("12", edit("int4", Types.INTEGER).ok("12.0"))
        assertEquals("“1.5” isn't a whole number", edit("int4", Types.INTEGER).invalid("1.5"))
        assertEquals("255", edit("tinyint unsigned", Types.TINYINT, mysql).ok("255"))
        assertTrue(edit("tinyint unsigned", Types.TINYINT, mysql).invalid("-1").startsWith("Out of range"))
        // SQLite integers are 64-bit whatever they're declared as.
        assertEquals("3000000000", edit("integer", Types.INTEGER, sqlite).ok("3000000000"))
    }

    @Test
    fun `decimals respect precision and warn about rounding`() {
        val money = edit("numeric", Types.NUMERIC, size = 6, scale = 2)
        assertEquals("1234.5", money.ok("1 234.5"))
        assertTrue(money.invalid("12345.5").startsWith("Too large"))
        val rounded = money.check("1.234") as EditCheck.Ok
        assertTrue(rounded.warning)
        assertEquals("Rounds to 2 decimal places", rounded.note)
    }

    @Test
    fun `floats take special values`() {
        val double = edit("float8", Types.DOUBLE)
        assertEquals("NaN", double.ok("nan"))
        assertEquals("-Infinity", double.ok("-inf"))
        assertEquals("1e-5", double.ok("1e-5"))
    }

    @Test
    fun `booleans follow the engine's spelling`() {
        assertEquals("true", edit("bool", Types.BIT).ok("yes"))
        assertEquals("false", edit("bool", Types.BIT).ok("f"))
        assertEquals("1", edit("tinyint", Types.BIT, mysql, size = 1).ok("true"))
        assertEquals("“maybe” isn't true or false", edit("bool", Types.BIT).invalid("maybe"))
        assertTrue(edit("bool", Types.BIT).sameValue("t", "true"))
    }

    @Test
    fun `dates and times resolve relative input to canonical text`() {
        val date = edit("date", Types.DATE)
        assertEquals("2026-10-24", date.ok("today-7d"))
        assertEquals("Sat 24 Oct 2026", (date.check("today-7d", now) as EditCheck.Ok).note)
        assertEquals("2026-10-31", date.ok("2026/10/31"))
        assertTrue(date.invalid("13:45").startsWith("Enter a date"))

        assertEquals("2026-10-31 11:45:10", edit("timestamp", Types.TIMESTAMP).ok("now-2h"))
        assertEquals("2026-10-31 13:45:00", edit("datetime", Types.TIMESTAMP, mysql).ok("2026-10-31T13:45"))
        assertEquals("2026-10-31 13:45:00+07:00", edit("timestamptz", Types.TIMESTAMP).ok("2026-10-31 13:45"))
        // An offset typed in is kept as the same instant, shown in the local zone.
        assertEquals("2026-10-31 20:45:00+07:00", edit("timestamptz", Types.TIMESTAMP).ok("2026-10-31 13:45:00Z"))
        assertEquals("13:45:00", edit("time", Types.TIME).ok("13:45"))

        val seconds = edit("timestamp", Types.TIMESTAMP, scale = 0).check("2026-10-31 13:45:00.25", now) as EditCheck.Ok
        assertEquals("2026-10-31 13:45:00", seconds.value)
        assertEquals("Seconds keep 0 decimal places", seconds.note)
    }

    @Test
    fun `same values compare by meaning`() {
        assertTrue(edit("numeric", Types.NUMERIC).sameValue("1.50", "1.5"))
        assertTrue(edit("timestamptz", Types.TIMESTAMP).sameValue("2026-10-31 13:45:00+07", "2026-10-31 13:45:00+07:00"))
        assertFalse(edit("text", Types.VARCHAR).sameValue("a", "a "))
    }

    @Test
    fun `uuids and json are validated`() {
        val uuid = edit("uuid", Types.OTHER)
        assertEquals("0b9e3b4a-7f6c-4a8e-9d2b-1c3e5f7a9b0d", uuid.ok("{0B9E3B4A7F6C4A8E9D2B1C3E5F7A9B0D}"))
        assertEquals("“12” isn't a UUID", uuid.invalid("12"))
        assertEquals("7", Uuids.v7().version().toString())

        val json = edit("jsonb", Types.OTHER)
        assertEquals("""{"a": 1}""", json.ok("""{"a": 1}"""))
        assertTrue(json.invalid("""{"a": }""").startsWith("Invalid JSON"))
    }

    @Test
    fun `text length is held to the declared limit where the engine enforces it`() {
        assertTrue(edit("varchar", Types.VARCHAR, size = 3).invalid("abcd").contains("at most 3"))
        assertTrue((edit("varchar", Types.VARCHAR, sqlite, size = 3).check("abcd") as EditCheck.Ok).warning)
        assertEquals("4/5", (edit("varchar", Types.VARCHAR, size = 5).check("abcd") as EditCheck.Ok).note)
    }

    @Test
    fun `new rows show what a left-out column gets`() {
        assertEquals("<auto>", ColumnEdit.of(ResultColumn("id", "serial", Types.INTEGER), info("serial", Types.INTEGER, nullable = false, auto = true)).placeholder)
        assertEquals("<default: now()>", ColumnEdit.of(ResultColumn("at", "timestamp", Types.TIMESTAMP), info("timestamp", Types.TIMESTAMP, default = "now()")).placeholder)
        assertEquals("<required>", ColumnEdit.of(ResultColumn("n", "text", Types.VARCHAR), info("text", Types.VARCHAR, nullable = false)).placeholder)
    }

    @Test
    fun `updates can set a column back to its default`() {
        val table = TableRef(Namespace(null, "public"), "t")
        val change = RowChange.Update(mapOf("id" to BoundValue("1", Types.INTEGER, true)), mapOf("at" to BoundValue(null, Types.TIMESTAMP, false, isDefault = true), "n" to BoundValue("x", Types.VARCHAR, false)))
        val statement = ChangeSql.statements(postgres, table, listOf(change)).single()
        assertEquals("""UPDATE "public"."t" SET "at" = DEFAULT, "n" = ? WHERE "id" = ?""", statement.sql)
        assertEquals(listOf("x", "1"), statement.parameters.map { it.value })
    }
}
