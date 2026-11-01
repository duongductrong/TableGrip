package dev.datagrok.app.documents

import dev.datagrok.app.grid.FieldChange
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class DocumentRecordJsonTest {
    private fun doc(json: String) = Json.parseToJsonElement(json).jsonObject

    private val documents =
        listOf(
            doc(
                """{"_id": {"${'$'}oid": "65f0c0ffee0000000000abcd"}, "name": "Ada", "age": 36, "big": {"${'$'}numberLong": "5"}, "score": 1.5,
                "joined": {"${'$'}date": "2026-10-01T10:00:00Z"}, "address": {"city": "London", "zip": {"${'$'}numberLong": "1"}},
                "blob": {"${'$'}binary": {"base64": "AA==", "subType": "00"}}, "note": null}""",
            ),
            doc("""{"_id": 2, "name": "Bob"}"""),
            doc("""{"name": "no id"}"""),
        )

    private fun model() = DocumentEditModel(documents, zone = ZoneOffset.UTC)

    private fun DocumentEditModel.col(name: String) = fields.indexOf(name)

    private fun JsonObject.with(vararg fields: Pair<String, String>): JsonObject =
        JsonObject(toMutableMap().apply { fields.forEach { (k, v) -> put(k, Json.parseToJsonElement(v)) } })

    private fun JsonObject.without(vararg names: String) = JsonObject(filterKeys { it !in names })

    @Test
    fun `the record is the whole document and an untouched one changes nothing`() {
        val model = model()
        val base = model.recordJson(0)
        assertEquals(documents[0], base)
        assertTrue(model.plan(0, base, base).changes.isEmpty())
        // Bob's document only has two fields; the record doesn't invent the others.
        assertEquals(setOf("_id", "name"), model.recordJson(1).keys)
    }

    @Test
    fun `changed fields are written as Extended JSON, keeping their types`() {
        val model = model()
        val base = model.recordJson(0)
        val edited = base.with("age" to "37", "big" to "6", "score" to "2", "address" to """{"city": "Paris", "zip": {"${'$'}numberLong": "75001"}}""")
        val plan = model.plan(0, base, edited)
        assertTrue(plan.canApply, plan.problems.toString())
        assertTrue(plan.notes.any { "big" in it && "score" in it }, "says which numbers kept their type: ${plan.notes}")
        model.apply(0, base, edited)

        val update = model.toChanges().single() as DocumentEditModel.Change.Update
        assertEquals("37", update.set["age"])
        assertEquals("""{"${'$'}numberLong":"6"}""", update.set["big"], "a plain number in a long field stays a long")
        assertEquals("2.0", update.set["score"], "and in a double field a double")
        assertEquals("""{"city":"Paris","zip":{"${'$'}numberLong":"75001"}}""", update.set["address"], "nested types are kept as written")
        assertEquals(setOf("age", "big", "score", "address"), update.set.keys, "only what changed")
        assertEquals("36", update.expected["age"], "guarded by the values as loaded")
        // The grid and the JSON view show the staged values.
        assertEquals("Paris", (model.json(listOf(model.col("address")))[0] as JsonObject)["address"]!!.jsonObject["city"].toString().trim('"'))
        assertEquals("6", model.value(0, model.col("big")))
    }

    @Test
    fun `fields can change type, be removed and be added`() {
        val model = model()
        val base = model.recordJson(0)
        val edited = base.without("note").with("age" to "\"thirty-six\"", "nickname" to "\"Countess\"")
        val plan = model.plan(0, base, edited)
        assertEquals(
            listOf(FieldChange("age", FieldChange.Kind.Changed), FieldChange("nickname", FieldChange.Kind.Added), FieldChange("note", FieldChange.Kind.Removed)),
            plan.changes,
        )
        assertTrue(plan.notes.any { "int → string" in it })
        model.apply(0, base, edited)
        assertTrue("nickname" in model.fields, "a new field gets a column")
        val update = model.toChanges().single() as DocumentEditModel.Change.Update
        assertEquals(mapOf("age" to "\"thirty-six\"", "nickname" to "\"Countess\""), update.set)
        assertEquals(setOf("note"), update.unset)
        assertNull(update.expected["nickname"], "a field that wasn't there must still be missing")
    }

    @Test
    fun `a binary field can be rewritten as JSON though its cell can't`() {
        val model = model()
        assertFalse(model.canEdit(0, model.col("blob")))
        val base = model.recordJson(0)
        val edited = base.with("blob" to """{"${'$'}binary": {"base64": "AQ==", "subType": "00"}}""")
        assertTrue(model.plan(0, base, edited).canApply)
        model.apply(0, base, edited)
        assertEquals("""{"${'$'}binary":{"base64":"AQ==","subType":"00"}}""", (model.toChanges().single() as DocumentEditModel.Change.Update).set["blob"])
    }

    @Test
    fun `typing a value back to what was loaded drops the edit`() {
        val model = model()
        val base = model.recordJson(1)
        model.apply(1, base, base.with("name" to "\"Robert\""))
        assertTrue(model.hasChanges)
        val again = model.recordJson(1)
        model.apply(1, again, again.with("name" to "\"Bob\""))
        assertFalse(model.hasChanges)
    }

    @Test
    fun `_id stays as it is and documents without one can't be edited`() {
        val model = model()
        val base = model.recordJson(1)
        assertFalse(model.plan(1, base, base.with("_id" to "3")).canApply)
        assertFalse(model.plan(1, base, base.without("_id")).canApply)
        assertNotNull(model.whyNotEditable(2))
        assertFalse(model.plan(1, base, base.with("a.b" to "1")).canApply, "dotted names aren't editable")
    }

    @Test
    fun `a new document is inserted as written and survives a reload`() {
        val model = model()
        val template = model.newRecord()
        assertTrue(template.json.isEmpty())
        assertTrue(template.hint!!.contains("_id"))
        val written = doc("""{"name": "Cy", "visits": {"${'$'}numberLong": "9"}, "tags": ["x"], "at": {"${'$'}date": "2026-11-01T00:00:00Z"}}""")
        val plan = model.plan(null, template.json, written)
        assertTrue(plan.isNew && plan.canApply)
        val row = model.apply(null, template.json, written)!!
        assertEquals(3, row)
        val insert = model.toChanges().single() as DocumentEditModel.Change.Insert
        assertEquals(written, doc(insert.document), "every value goes in exactly as written")
        assertEquals("9", model.value(row, model.col("visits")))

        val pending = model.pending(setOf(row))
        val reloaded = DocumentEditModel(documents, extraFields = pending.fields, zone = ZoneOffset.UTC)
        reloaded.restore(pending)
        assertEquals(written, doc((reloaded.toChanges().single() as DocumentEditModel.Change.Insert).document))
    }

    @Test
    fun `edits from the record editor carry over a reload`() {
        val model = model()
        val base = model.recordJson(0)
        model.apply(0, base, base.with("address" to """{"city": "Rome"}"""))
        val pending = model.pending(setOf(0))
        val reloaded = DocumentEditModel(documents, zone = ZoneOffset.UTC)
        assertEquals(0, reloaded.restore(pending))
        assertEquals("""{"city":"Rome"}""", (reloaded.toChanges().single() as DocumentEditModel.Change.Update).set["address"])
    }

    @Test
    fun `describes documents by their _id`() {
        val model = model()
        assertEquals("_id: ObjectId(65f0c0ffee0000000000abcd)", model.describeRecord(0))
        assertEquals("_id: 2", model.describeRecord(1))
    }
}
