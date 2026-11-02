package dev.tablegrip.core.filter

import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FilterValuesTest {
    private val now = LocalDateTime.of(2026, 10, 28, 13, 45)

    private fun parse(text: String) = FilterValues.temporal(text, now, ZoneOffset.UTC)

    @Test
    fun `classifies column types across engines`() {
        assertEquals(ValueKind.Number, ValueKind.of("int4"))
        assertEquals(ValueKind.Number, ValueKind.of("DECIMAL(10,2)"))
        assertEquals(ValueKind.Number, ValueKind.of("INT UNSIGNED"))
        assertEquals(ValueKind.Number, ValueKind.of("double precision"))
        assertEquals(ValueKind.Boolean, ValueKind.of("bool"))
        assertEquals(ValueKind.Boolean, ValueKind.of("TINYINT", Types.BIT, size = 1))
        assertEquals(ValueKind.DateTime, ValueKind.of("timestamptz"))
        assertEquals(ValueKind.DateTime, ValueKind.of("timestamp without time zone"))
        assertEquals(ValueKind.DateTime, ValueKind.of("DATETIME"))
        assertEquals(ValueKind.Date, ValueKind.of("date"))
        assertEquals(ValueKind.Time, ValueKind.of("time with time zone"))
        assertEquals(ValueKind.Text, ValueKind.of("character varying"))
        assertEquals(ValueKind.Text, ValueKind.of("LONGTEXT"))
        assertEquals(ValueKind.Text, ValueKind.of(""))
        assertEquals(ValueKind.Uuid, ValueKind.of("uuid"))
        assertEquals(ValueKind.Json, ValueKind.of("jsonb"))
        assertEquals(ValueKind.Binary, ValueKind.of("bytea"))
        assertEquals(ValueKind.Binary, ValueKind.of("MEDIUMBLOB"))
        assertEquals(ValueKind.Other, ValueKind.of("_int4", Types.ARRAY))
        assertEquals(ValueKind.Other, ValueKind.of("inet"))
        assertEquals(ValueKind.Other, ValueKind.of("mood"))
        assertEquals(ValueKind.Number, ValueKind.of("whatever", Types.BIGINT))
    }

    @Test
    fun `parses absolute dates, moments and times`() {
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 1, 5)), parse("2026-01-05"))
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 1, 5)), parse("2026/01/05"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 1, 5, 9, 30)), parse("2026-01-05 09:30"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 1, 5, 9, 30, 15)), parse("2026-01-05T09:30:15"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 1, 5, 2, 30)), parse("2026-01-05 09:30:00+07"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 1, 5, 9, 30)), parse("2026-01-05T09:30:00Z"))
        assertEquals(TemporalValue.TimeOfDay(LocalTime.of(17, 5)), parse("17:05"))
    }

    @Test
    fun `parses relative expressions`() {
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 10, 28)), parse("Today"))
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 10, 29)), parse("tomorrow"))
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 10, 14)), parse("today - 2w"))
        assertEquals(TemporalValue.Day(LocalDate.of(2026, 9, 28)), parse("-1mo"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 10, 28, 13, 15)), parse("-30min"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 10, 27, 13, 45)), parse("now-1d"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 10, 28, 10, 45)), parse("3 hours ago"))
        assertEquals(TemporalValue.Day(LocalDate.of(2025, 10, 28)), parse("1 year ago"))
        assertEquals(TemporalValue.Moment(LocalDateTime.of(2026, 10, 28, 6, 0)), parse("today+6h"))
    }

    @Test
    fun `explains what it can't parse`() {
        assertEquals("Unknown unit “fortnights”; use s, min, h, d, w, mo or y", assertFailsWith<FilterValueException> { parse("2 fortnights ago") }.message)
        assertFailsWith<FilterValueException> { parse("next tuesday") }
        assertFailsWith<FilterValueException> { parse("2026-13-01") }
        assertFailsWith<FilterValueException> { parse("") }
    }

    @Test
    fun `parses lists, numbers and booleans`() {
        assertEquals(listOf("a", "b c", null, "", "x,y"), FilterValues.parseList(" a ,b c,\nNULL, '', \"x,y\" ,"))
        assertEquals("1234.5", FilterValues.number(" 1_234.5 ").toPlainString())
        assertEquals(true, FilterValues.boolean("t"))
        assertEquals(false, FilterValues.boolean("No"))
        assertEquals("2026-01-05 09:30:00.5", FilterValues.format(LocalDateTime.of(2026, 1, 5, 9, 30, 0, 500_000_000)))
    }
}
