package dev.tablegrip.app.documents

import dev.tablegrip.app.grid.RowStatus
import dev.tablegrip.core.edit.EditCheck
import dev.tablegrip.core.edit.EditKind
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DocumentEditModelTest {
    private fun doc(json: String) = Json.parseToJsonElement(json).jsonObject

    private val documents =
        listOf(
            doc(
                """{"_id": {"${'$'}oid": "65f0c0ffee0000000000abcd"}, "name": "Ada", "age": 36, "big": {"${'$'}numberLong": "5"}, "score": 1.5,
                "vip": true, "joined": {"${'$'}date": "2026-10-01T10:00:00Z"}, "tags": ["a"], "address": {"city": "London"},
                "blob": {"${'$'}binary": {"base64": "AA==", "subType": "00"}}, "note": null}""",
            ),
            doc("""{"_id": 2, "name": "Bob"}"""),
            doc("""{"name": "no id"}"""),
        )

    private fun model() = DocumentEditModel(documents, zone = ZoneOffset.UTC)

    private fun DocumentEditModel.col(name: String) = fields.indexOf(name)

    @Test
    fun `edits keep each field's BSON type`() {
        val model = model()
        assertEquals(EditKind.Integer, model.cellEdit(0, model.col("age")).kind)
        assertEquals(EditKind.Boolean, model.cellEdit(0, model.col("vip")).kind)
        assertEquals(EditKind.DateTime, model.cellEdit(0, model.col("joined")).kind)
        model.setValue(0, model.col("age"), "37")
        model.setValue(0, model.col("big"), "6")
        model.setValue(0, model.col("score"), "2")
        model.setValue(0, model.col("joined"), "2026-11-01 08:30")
        model.setValue(0, model.col("tags"), "['a', 'b']")
        model.setValue(1, model.col("name"), "Bobby")
        val update = model.toChanges().filterIsInstance<DocumentEditModel.Change.Update>().first { it.row == 0 }
        assertEquals("37", update.set["age"])
        assertEquals("{\"\$numberLong\":\"6\"}", update.set["big"], "a long stays a long")
        assertEquals("2.0", update.set["score"], "a double stays a double")
        assertEquals("{\"\$date\":\"2026-11-01T08:30:00Z\"}", update.set["joined"])
        assertEquals("['a', 'b']", update.set["tags"])
        // Only edited fields are sent, guarded by the values they had.
        assertEquals(setOf("age", "big", "score", "joined", "tags"), update.set.keys)
        assertEquals("36", update.expected["age"])
        assertEquals("[\"a\"]", update.expected["tags"])
        assertEquals("{\"\$oid\":\"65f0c0ffee0000000000abcd\"}", update.id)
    }

    @Test
    fun `typed values are checked against the field's type`() {
        val model = model()
        assertIs<EditCheck.Invalid>(model.check(0, model.col("age"), "3.5"))
        assertIs<EditCheck.Invalid>(model.check(0, model.col("vip"), "maybe"))
        assertIs<EditCheck.Invalid>(model.check(0, model.col("joined"), "yesterday-ish"))
        assertIs<EditCheck.Invalid>(model.check(0, model.col("_id"), "nope"))
        assertEquals("Stored as a 64-bit integer", (model.check(0, model.col("age"), "9999999999") as EditCheck.Ok).note)
        assertEquals(EditCheck.Empty, model.check(0, model.col("age"), ""))
        assertEquals(EditCheck.Ok(""), model.check(0, model.col("name"), ""), "an empty string is a string")
        // A value kept as typed (clicking away) blocks saving until fixed.
        model.setValue(0, model.col("age"), "abc")
        assertNotNull(model.problem(0, model.col("age")))
        assertTrue(model.firstProblem()!!.startsWith("Row 1, age:"))
    }

    @Test
    fun `what can be edited`() {
        val model = model()
        assertFalse(model.canEdit(0, model.col("_id")), "_id is immutable")
        assertFalse(model.canEdit(0, model.col("blob")), "binary is edited with the whole document")
        assertFalse(model.canEdit(2, model.col("name")), "no _id, no way to find it again")
        assertTrue(model.canEdit(1, model.col("age")), "a missing field can be added")
        assertTrue(model.canEdit(0, model.col("address")))
        assertNull(DocumentEditModel(documents, readOnly = true).editor)
    }

    @Test
    fun `unchanged values, nulls and removed fields`() {
        val model = model()
        model.setValue(0, model.col("name"), "Ada")
        assertFalse(model.hasChanges, "setting the same value is no change")
        model.setValue(0, model.col("note"), null)
        assertFalse(model.hasChanges, "null on null is no change")
        model.setValue(1, model.col("age"), "40")
        model.setDefault(0, model.col("vip"))
        assertEquals("<removed>", model.placeholder(0, model.col("vip")))
        assertEquals("Remove Field", model.setDefaultLabel)
        val changes = model.toChanges().filterIsInstance<DocumentEditModel.Change.Update>().associateBy { it.row }
        assertEquals(setOf("vip"), changes.getValue(0).unset)
        assertEquals(mapOf<String, String?>("vip" to "true"), changes.getValue(0).expected)
        assertEquals(mapOf<String, String?>("age" to null), changes.getValue(1).expected, "a field that was missing must still be missing")
    }

    @Test
    fun `new documents and deletions`() {
        val model = model()
        val row = model.addRow()
        assertEquals(RowStatus.Inserted, model.rowStatus(row))
        assertEquals("<auto>", model.placeholder(row, model.col("_id")))
        model.setValue(row, model.col("name"), "Cy")
        model.setValue(row, model.col("age"), "29")
        model.deleteRows(listOf(1, 2))
        val changes = model.toChanges()
        assertEquals(listOf(DocumentEditModel.Change.Delete(1, "2")), changes.filterIsInstance<DocumentEditModel.Change.Delete>(), "no _id, nothing to delete")
        assertEquals("{\"name\": \"Cy\", \"age\": 29}", changes.filterIsInstance<DocumentEditModel.Change.Insert>().single().document)
        assertTrue(model.preview("people").contains("db.getCollection(\"people\").deleteOne({_id: 2})"))
    }

    @Test
    fun `fields with no type yet take the type the text looks like`() {
        assertEquals("42", (convert(null, "42") as Converted.Ok).literal)
        assertEquals("\"42\"", (convert(null, "'42'") as Converted.Ok).literal)
        assertEquals("true", (convert(null, "true") as Converted.Ok).literal)
        assertEquals("{\"\$oid\":\"65f0c0ffee0000000000abcd\"}", (convert(null, "ObjectId('65F0C0FFEE0000000000ABCD')") as Converted.Ok).literal)
        assertEquals("\"hello world\"", (convert(null, "hello world") as Converted.Ok).literal)
        assertEquals("{\"\$date\":\"2026-10-01T00:00:00Z\"}", (convert(null, "ISODate('2026-10-01T00:00:00Z')") as Converted.Ok).literal)
    }

    @Test
    fun `pending edits survive a reload by _id and field`() {
        val model = model()
        model.setValue(0, model.col("age"), "50")
        model.addField("nickname")
        model.setValue(1, model.col("nickname"), "bobby")
        val pending = model.pending(setOf(0, 1))
        // The page comes back in another order, with Ada's age changed meanwhile.
        val reloaded = DocumentEditModel(listOf(documents[1], doc(documents[0].toString().replace("\"age\":36", "\"age\":40"))), extraFields = pending.fields)
        assertEquals(0, reloaded.restore(pending))
        assertEquals("50", reloaded.value(1, reloaded.col("age")))
        assertEquals("bobby", reloaded.value(0, reloaded.col("nickname")))
        assertEquals("40", reloaded.toChanges().filterIsInstance<DocumentEditModel.Change.Update>().first { it.row == 1 }.expected["age"])
    }
}
