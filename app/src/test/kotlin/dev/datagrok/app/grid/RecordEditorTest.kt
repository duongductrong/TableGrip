package dev.datagrok.app.grid

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class RecordEditorTest {
    @Test
    fun `parses an object, forgiving comments and trailing commas`() {
        val parsed = parseRecord("{\n  // the name\n  \"name\": \"Ada\",\n  \"age\": 36,\n}")
        assertIs<ParsedRecord.Ok>(parsed)
        assertEquals(setOf("name", "age"), parsed.json.keys)
    }

    @Test
    fun `points parse errors at a line and column`() {
        val parsed = parseRecord("{\n  \"name\": \"Ada\"\n  \"age\": 36\n}")
        assertIs<ParsedRecord.Invalid>(parsed)
        assertEquals(3, parsed.line, parsed.text)
        assertTrue(parsed.text.startsWith("Line 3, column"), parsed.text)
        assertTrue("offset" !in parsed.message && "JSON input" !in parsed.message, parsed.message)
    }

    @Test
    fun `asks for an object`() {
        assertIs<ParsedRecord.Invalid>(parseRecord("[1, 2]"))
        assertIs<ParsedRecord.Invalid>(parseRecord("   "))
        assertIs<ParsedRecord.Invalid>(parseRecord("{\"a\": 1"))
    }

    @Test
    fun `diffs top-level fields only`() {
        val base = Json.parseToJsonElement("""{"a": 1, "b": {"x": 1}, "c": null}""").jsonObject
        val edited = Json.parseToJsonElement("""{"a": 1, "b": {"x": 2}, "d": true}""").jsonObject
        assertEquals(
            listOf(FieldDiff.Set("b", Json.parseToJsonElement("""{"x": 2}"""), added = false), FieldDiff.Set("d", JsonPrimitive(true), added = true), FieldDiff.Removed("c")),
            diffRecord(base, edited),
        )
    }

    @Test
    fun `enter keeps the indent and opens braces onto their own lines`() {
        val inside = newLine(TextFieldValue("{}", TextRange(1)))
        assertEquals("{\n  \n}", inside.text)
        assertEquals(TextRange(4), inside.selection)

        val nested = newLine(TextFieldValue("{\n  \"a\": 1,", TextRange(11)))
        assertEquals("{\n  \"a\": 1,\n  ", nested.text)

        val afterBracket = newLine(TextFieldValue("  \"t\": [", TextRange(8)))
        assertEquals("  \"t\": [\n    ", afterBracket.text)
    }

    @Test
    fun `tab indents and shift-tab outdents the selected lines`() {
        val text = "a\nb\nc"
        val indented = indent(TextFieldValue(text, TextRange(0, 3)))
        assertEquals("  a\n  b\nc", indented.text)
        assertEquals("a\nb\nc", outdent(indented).text)
        assertEquals("  x", indent(TextFieldValue("x", TextRange(0))).text)
    }

    @Test
    fun `summarises what a record changes`() {
        val plan =
            RecordPlan(listOf(FieldChange("name", FieldChange.Kind.Changed), FieldChange("tags", FieldChange.Kind.Added), FieldChange("old", FieldChange.Kind.Removed)))
        assertEquals("Changes name · adds tags · removes old", changeSummary(plan, "row"))
        assertEquals("Adds a row with every column at its default", changeSummary(RecordPlan(emptyList(), isNew = true), "row"))
    }

    @Test
    fun `numbers compare by value unless told otherwise`() {
        val base = Json.parseToJsonElement("""{"a": 19.90, "b": [1.0]}""").jsonObject
        val edited = Json.parseToJsonElement("""{"a": 19.9, "b": [1]}""").jsonObject
        assertTrue(diffRecord(base, edited).isEmpty())
        assertEquals(2, diffRecord(base, edited) { x, y -> x == y }.size)
        assertEquals("{\n  \"a\": 19.90,\n  \"b\": [\n    1.0\n  ]\n}", prettyRecord(base))
    }
}
