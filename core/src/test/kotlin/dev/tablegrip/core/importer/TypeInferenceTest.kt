package dev.tablegrip.core.importer

import dev.tablegrip.core.importer.InferredType.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeInferenceTest {
    private fun kind(vararg values: String?) = TypeInference.infer(values.toList()).kind

    @Test
    fun numbers() {
        assertEquals(Kind.Integer, kind("1", "-42", "0", "9223372036854775807"))
        assertEquals(Kind.BigInteger, kind("1", "9223372036854775808"))
        assertEquals(Kind.Decimal, kind("1", "2.5", "-0.25", "1e10", "6.02E+23", ".5"))
        assertEquals(Kind.Text, kind("007", "1"))
        assertEquals(Kind.Text, kind("+84901234567"))
        assertEquals(Kind.Text, kind("1,234"))
        assertEquals(Kind.Text, kind("01.5"))
        assertEquals(Kind.Text, kind("1."))
        assertEquals(Kind.Text, kind("NaN"))
    }

    @Test
    fun booleans() {
        assertEquals(Kind.Integer, kind("0", "1", "1"))
        assertEquals(Kind.Boolean, kind("true", "False", "1", "0"))
        assertEquals(Kind.Boolean, kind("yes", "no", "T", "f"))
        assertEquals(Kind.Text, kind("yes", "maybe"))
    }

    @Test
    fun `dates and timestamps`() {
        assertEquals(Kind.Date, kind("2024-02-29", "1999-12-31"))
        assertEquals(Kind.Text, kind("2023-02-29"))
        assertEquals(Kind.Text, kind("31/12/2024"))
        assertEquals(Kind.Timestamp, kind("2024-01-01T10:00:00", "2024-01-01 10:00", "2024-01-01T10:00:00.123456Z", "2024-01-01T10:00:00+07:00"))
        assertEquals(Kind.Timestamp, kind("2024-01-01", "2024-01-02 03:04:05"))
        assertEquals(Kind.Text, kind("2024-01-01 25:00"))
    }

    @Test
    fun `json and text`() {
        assertEquals(Kind.Json, kind("""{"a":1}""", "[1,2]"))
        assertEquals(Kind.Text, kind("{not json}"))
        assertEquals(Kind.Text, kind("hello", "1"))
    }

    @Test
    fun `nulls and blanks only affect nullability and length`() {
        assertEquals(InferredType(Kind.Integer, 3, nullable = true), TypeInference.infer(listOf("1", null, "", "123")))
        assertEquals(InferredType(Kind.Integer, 1, nullable = false), TypeInference.infer(listOf("1")))
        assertEquals(InferredType(Kind.Text, 0, nullable = true), TypeInference.infer(listOf(null, null)))
        assertEquals(InferredType(Kind.Text, 0, nullable = false), TypeInference.infer(emptyList()))
        // Code points, not UTF-16 units.
        assertEquals(2, TypeInference.infer(listOf("😀😀")).maxLength)
    }
}
