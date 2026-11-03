package dev.tablegrip.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class JsonTreeEditTest {
    private fun open(text: String) = assertIs<JsonTreeSource.Ok>(jsonTreeSource(text))

    private fun key(name: String) = JsonStep.Key(name)

    private fun index(i: Int) = JsonStep.Index(i)

    @Test
    fun `untouched values encode back to the same JSON`() {
        val compact = """{"price":19.90,"big":123456789012345678901234567890,"exp":1.5E+10,"neg":-0.0,"s":"a\"b\\c\n","u":"é","arr":[1,true,null,{}]}"""
        val source = open(compact)
        assertEquals(Json.parseToJsonElement(compact), Json.parseToJsonElement(encodeJson(source.root, source.layout)))
        assertTrue("19.90" in encodeJson(source.root, source.layout))
        assertTrue("123456789012345678901234567890" in encodeJson(source.root, source.layout))
        assertTrue("1.5E+10" in encodeJson(source.root, source.layout))
    }

    @Test
    fun `edited values keep the original layout`() {
        assertEquals(JsonLayout(null), jsonLayout("""{"a":1}"""))
        assertEquals(JsonLayout("    "), jsonLayout("{\n    \"a\": 1\n}"))
        assertEquals(JsonLayout("\t"), jsonLayout("{\n\t\"a\": 1\n}"))
        val source = open("{\n    \"a\": 1,\n    \"b\": [2]\n}")
        assertEquals("{\n    \"a\": 5,\n    \"b\": [\n        2\n    ]\n}", encodeJson(source.root.withValue(listOf(key("a")), leafValue(JsonValueType.Number, "5")!!), source.layout))
    }

    @Test
    fun `the tree refuses what it couldn't write back unchanged`() {
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource(""))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("{\"a\":"))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("\"just a string\""))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("42"))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("""{"a": abc}"""))
        val repeated = assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("""{"a":1,"b":{"x":1,"x":2}}"""))
        assertTrue("\"x\"" in repeated.reason)
        // The same key in different objects, or a key's text inside a string, is fine.
        open("""[{"a":1},{"a":2,"s":"\"a\": {"}]""")
    }

    @Test
    fun `whitespace around the value is written back as it was`() {
        val source = open("  {\n  \"a\": 1\n}\n")
        assertEquals(JsonLayout("  ", "  ", "\n"), source.layout)
        assertEquals("  {\n  \"a\": 2\n}\n", encodeJson(source.root.withValue(listOf(key("a")), leafValue(JsonValueType.Number, "2")!!), source.layout))
        val padded = open("{\"a\":1}   ")
        assertEquals("{\"a\":2}   ", encodeJson(padded.root.withValue(listOf(key("a")), leafValue(JsonValueType.Number, "2")!!), padded.layout))
    }

    @Test
    fun `half a surrogate pair opens as text, a whole one opens as a tree`() {
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("""{"a":"\ud800"}"""))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("""["x\udc00y"]"""))
        assertIs<JsonTreeSource.Unavailable>(jsonTreeSource("""{"\ud800":1}"""))
        val emoji = open("""{"e":"\ud83d\ude00"}""")
        assertEquals("\ud83d\ude00", (emoji.root.at(listOf(key("e"))) as JsonPrimitive).content)
    }

    @Test
    fun `text cells open as a tree only for a JSON object or array it can write back`() {
        assertTrue(textOpensAsTree("""{"a":1}"""))
        assertTrue(textOpensAsTree(" \n[1, 2]"))
        for (text in listOf("", "hello", "42", "\"s\"", "true", "null", "{not json}", "[1,", """{"a":1,"a":2}""", "{\"a\":1} trailing")) {
            assertFalse(textOpensAsTree(text), text)
        }
        assertFalse(textOpensAsTree("[" + "1,".repeat(MaxTextTreeLength / 2) + "1]"))
        val sources = JsonTreeSources()
        assertTrue(textOpensAsTree("""{"a":1}""", sources))
        assertIs<JsonTreeSource.Ok>(sources.of("""{"a":1}"""))
    }

    @Test
    fun `repeated keys are found after unescaping`() {
        assertEquals("a", firstRepeatedKey("""{"a":1,"a":2}"""))
        assertNull(firstRepeatedKey("""{"a":{"a":{"a":1}},"b":["a","a"]}"""))
    }

    @Test
    fun `edits change only the node they target`() {
        val root = open("""{"name":"An","age":30,"address":{"city":"HN","zip":"1"},"tags":["x","y"]}""").root
        val city = root.withValue(listOf(key("address"), key("city")), leafValue(JsonValueType.String, "Hà \"Nội\"")!!)
        assertEquals("""{"name":"An","age":30,"address":{"city":"Hà \"Nội\"","zip":"1"},"tags":["x","y"]}""", encodeJson(city, JsonLayout(null)))

        val renamed = root.withKeyRenamed(listOf(key("age")), "years")!!
        assertEquals("""{"name":"An","years":30,"address":{"city":"HN","zip":"1"},"tags":["x","y"]}""", encodeJson(renamed, JsonLayout(null)))
        assertNull(root.withKeyRenamed(listOf(key("age")), "name"))

        assertEquals("""{"name":"An","age":30,"address":{"city":"HN","zip":"1"},"tags":["y"]}""", encodeJson(root.without(listOf(key("tags"), index(0))), JsonLayout(null)))

        val (withKey, keyPath) = root.withChildAdded(listOf(key("address")))
        assertEquals(listOf(key("address"), key("key")), keyPath)
        assertEquals(JsonPrimitive(""), withKey.at(keyPath))
        val (withItem, itemPath) = root.withChildAdded(listOf(key("tags")))
        assertEquals(listOf(key("tags"), index(2)), itemPath)
        assertEquals("""["x","y",""]""", encodeJson(withItem.at(listOf(key("tags")))!!, JsonLayout(null)))
    }

    @Test
    fun `leaf input is checked against its type`() {
        assertNull(leafValue(JsonValueType.Number, "1,5"))
        assertNull(leafValue(JsonValueType.Number, "01"))
        assertNull(leafValue(JsonValueType.Number, "NaN"))
        assertEquals("1.50", encodeJson(leafValue(JsonValueType.Number, "1.50")!!, JsonLayout(null)))
        assertNull(leafValue(JsonValueType.Boolean, "yes"))
        // A string is stored as typed, even when it looks like JSON.
        assertEquals("\"{\\\"a\\\":1}\"", encodeJson(leafValue(JsonValueType.String, "{\"a\":1}")!!, JsonLayout(null)))
    }

    @Test
    fun `type changes keep what carries over`() {
        assertEquals("\"19.90\"", encodeJson(converted(leafValue(JsonValueType.Number, "19.90")!!, JsonValueType.String), JsonLayout(null)))
        assertEquals("42", encodeJson(converted(JsonPrimitive("42"), JsonValueType.Number), JsonLayout(null)))
        assertEquals("0", encodeJson(converted(JsonPrimitive("abc"), JsonValueType.Number), JsonLayout(null)))
        assertEquals("true", encodeJson(converted(JsonPrimitive("true"), JsonValueType.Boolean), JsonLayout(null)))
    }

    @Test
    fun `rows follow folding and paths read like JSONPath`() {
        val root = open("""{"a":{"b":[1,2]},"first name":"x"}""").root
        assertEquals(5, jsonTreeRows(root, emptySet()).size)
        assertEquals(listOf("$.a", "$[\"first name\"]"), jsonTreeRows(root, setOf(listOf(key("a")))).map { jsonPathText(it.path) })
        assertEquals("$.a.b[1]", jsonPathText(listOf(key("a"), key("b"), index(1))))
        assertEquals(setOf(listOf(key("a"), key("b"))), jsonContainersFrom(root, 2))
    }
}
