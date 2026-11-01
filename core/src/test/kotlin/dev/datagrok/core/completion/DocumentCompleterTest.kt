package dev.datagrok.core.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentCompleterTest {
    private val fields =
        listOf(
            DocumentField("_id", listOf("objectId"), listOf("ObjectId('65f0c0ffee0000000000abcd')")),
            DocumentField("status", listOf("string"), listOf("'A'", "'B'", "'archived'")),
            DocumentField("total", listOf("number"), listOf("30", "10")),
            DocumentField("tags", listOf("array"), listOf("'pen'", "'ink'")),
            DocumentField("items", listOf("array")),
            DocumentField("items.sku", listOf("string"), listOf("'X-1'")),
            DocumentField("items.qty", listOf("number")),
            DocumentField("address", listOf("object")),
            DocumentField("address.city", listOf("string"), listOf("'London'")),
            DocumentField("first name", listOf("string")),
        )

    /** Completes at `|` in [text]. */
    private fun complete(text: String, query: DocumentQuery = DocumentQuery.Filter): CompletionResult {
        val caret = text.indexOf('|')
        return DocumentCompleter.complete(text.removeRange(caret, caret + 1), caret, query, fields)
    }

    private fun labels(text: String, query: DocumentQuery = DocumentQuery.Filter) = complete(text, query).items.map { it.label }

    /** The text after accepting the first suggestion, with `|` at the caret. */
    private fun accept(text: String, label: String? = null, query: DocumentQuery = DocumentQuery.Filter): String {
        val caret = text.indexOf('|')
        val plain = text.removeRange(caret, caret + 1)
        val result = DocumentCompleter.complete(plain, caret, query, fields)
        val item = label?.let { l -> result.items.first { it.label == l } } ?: result.items.first()
        val after = plain.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)
        val at = result.replaceStart + item.caretOffset
        return after.substring(0, at) + "|" + after.substring(at)
    }

    @Test
    fun `filter keys are fields, nested paths and query operators`() {
        val all = labels("{|}")
        assertTrue(all.containsAll(listOf("_id", "status", "items.sku", "address.city", "\$and", "\$or", "\$expr")), all.toString())
        assertEquals("status", labels("{sta|}").first())
        assertEquals("{status: |}", accept("{sta|}"))
        assertEquals("{status: 'A', total: |}", accept("{status: 'A', tot|}"))
        // Paths and names that aren't identifiers are quoted.
        assertEquals("{'address.city': |}", accept("{cit|}"))
        assertEquals("{'first name': |}", accept("{first|}"))
        // Typing inside quotes replaces the whole quoted word; an existing colon isn't doubled.
        assertEquals("{'address.city'|: 'x'}", accept("{'addr|ess.c': 'x'}", "address.city"))
        // Without braces, the text is the filter's content.
        assertEquals("status: |", accept("stat|"))
    }

    @Test
    fun `a field's condition offers operators and the field's values`() {
        assertEquals(listOf("\$gt", "\$gte"), labels("{total: {\$g|}}"))
        assertEquals("{total: {\$in: [|]}}", accept("{total: {\$i|}}", "\$in"))
        assertEquals("{tags: {\$elemMatch: {|}}}", accept("{tags: {\$elem|}}"))
        assertEquals(listOf("'A'", "'archived'"), labels("{status: 'a|"))
        assertEquals("{status: 'archived'|}", accept("{status: 'ar|'}"))
        assertTrue(labels("{status: |}").containsAll(listOf("'A'", "'B'", "{\$in: []}")))
        assertEquals(listOf("30", "10"), labels("{total: {\$gt: |}}"))
        assertEquals(listOf("'pen'", "'ink'"), labels("{tags: {\$in: ['ink', |]}}"))
        assertEquals(listOf("true", "false"), labels("{address: {\$exists: |}}"))
        assertTrue("'objectId'" in labels("{_id: {\$type: |}}"))
        assertEquals("'London'", labels("{'address.city': |}").first())
    }

    @Test
    fun `nested contexts know their fields`() {
        // $and and $or hold filters; $elemMatch the array's element fields; an embedded document its own.
        assertTrue("status" in labels("{\$or: [{|}]}"))
        assertEquals("{\$or: [{|}]}", accept("{\$o|}", "\$or"))
        assertEquals(listOf("sku", "qty"), labels("{items: {\$elemMatch: {|}}}").filterNot { it.startsWith("$") })
        assertEquals("'X-1'", labels("{items: {\$elemMatch: {sku: |}}}").first())
        assertEquals("city", labels("{address: {ci|}}").first())
        assertTrue(labels("{\$text: {|}}").contains("\$search"))
        assertTrue("\$toLower" in labels("{\$expr: {|}}"))
    }

    @Test
    fun `sort offers fields then directions`() {
        assertEquals("{total: |}", accept("{tot|}", query = DocumentQuery.Sort))
        assertEquals(listOf("1", "-1"), labels("{total: |}", DocumentQuery.Sort))
        assertTrue(labels("{|}", DocumentQuery.Sort).none { it.startsWith("$") })
    }

    @Test
    fun `projection offers fields then include or exclude`() {
        assertEquals("{total: |}", accept("{tot|}", query = DocumentQuery.Projection))
        assertEquals(listOf("1", "0"), labels("{total: |}", DocumentQuery.Projection))
        assertTrue(labels("{|}", DocumentQuery.Projection).none { it.startsWith("$") })
    }

    @Test
    fun `nothing is offered where nothing fits`() {
        assertEquals(emptyList(), labels("{status: 'A' |}"))
        assertEquals(emptyList(), labels("{_id: ObjectId('65|')}"))
        assertEquals(emptyList(), labels("{total: {\$gt: 1} |}"))
        assertEquals(emptyList(), labels("{} |"))
    }
}
