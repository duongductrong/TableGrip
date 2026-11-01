package dev.datagrok.app.table

import dev.datagrok.app.grid.FieldChange
import dev.datagrok.app.grid.RowStatus
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.edit.RowChange
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableRef
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class TableRecordJsonTest {
    private val columns =
        listOf(
            ResultColumn("id", "int4", Types.INTEGER),
            ResultColumn("name", "varchar", Types.VARCHAR),
            ResultColumn("done", "bool", Types.BIT),
            ResultColumn("price", "numeric", Types.NUMERIC),
            ResultColumn("meta", "jsonb", Types.OTHER),
            ResultColumn("photo", "bytea", Types.BINARY),
            ResultColumn("created", "timestamp", Types.TIMESTAMP),
        )

    private fun info(name: String, type: String, jdbcType: Int, nullable: Boolean = true, default: String? = null, auto: Boolean = false) =
        ColumnInfo(name, type, null, nullable, default, autoIncrement = auto, primaryKey = name == "id", jdbcType = jdbcType)

    private val details =
        TableDetails(
            TableRef(Namespace(null, "public"), "items"),
            listOf(
                info("id", "int4", Types.INTEGER, nullable = false, auto = true),
                info("name", "varchar", Types.VARCHAR, nullable = false),
                info("done", "bool", Types.BIT),
                info("price", "numeric", Types.NUMERIC),
                info("meta", "jsonb", Types.OTHER),
                info("photo", "bytea", Types.BINARY),
                info("created", "timestamp", Types.TIMESTAMP, default = "now()"),
            ),
            emptyList(),
            emptyList(),
        )

    private val rows =
        listOf(
            listOf("1", "Lamp", "t", "19.90", """{"color": "red", "tags": [1, 2]}""", "\\x00ff", "2026-10-01 09:00:00"),
            listOf("2", "Desk", "f", null, null, null, "2026-10-02 09:00:00"),
        )

    private fun model(primaryKey: List<String> = listOf("id")) =
        TableEditModel(QueryResult.Rows(columns, rows, false, 1), primaryKey, details = details, dialect = SqlDialect.PostgreSQLDialect())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.with(vararg fields: Pair<String, Any?>): JsonObject =
        JsonObject(toMutableMap().apply { fields.forEach { (k, v) -> put(k, Json.parseToJsonElement(v?.toString() ?: "null")) } })

    @Test
    fun `an untouched record changes nothing`() {
        val model = model()
        val base = model.recordJson(0)
        assertEquals(setOf("id", "name", "done", "price", "meta", "photo", "created"), base.keys, "every column, hidden or not")
        val plan = model.plan(0, base, base)
        assertTrue(plan.changes.isEmpty(), plan.changes.toString())
        assertTrue(plan.problems.isEmpty(), plan.problems.toString())
        assertFalse(plan.canApply)
        // Reformatted the same values: still nothing.
        val reparsed = json(dev.datagrok.app.grid.prettyRecord(base))
        assertTrue("19.90" in dev.datagrok.app.grid.prettyRecord(base), "numbers are shown with their exact digits")
        assertTrue(model.plan(0, base, reparsed).changes.isEmpty(), "$base vs $reparsed: " + model.plan(0, base, reparsed).changes)
    }

    @Test
    fun `changed values become the same pending cell edits as typing`() {
        val model = model()
        val base = model.recordJson(0)
        val edited = base.with("name" to "\"Floor lamp\"", "done" to false, "price" to 24.5, "meta" to """{"color":"blue"}""")
        val plan = model.plan(0, base, edited)
        assertEquals(listOf("name", "done", "price", "meta"), plan.changes.map { it.name })
        assertTrue(plan.canApply, plan.problems.toString())
        assertEquals(0, model.apply(0, base, edited))

        assertEquals("Floor lamp", model.value(0, 1))
        assertEquals(false, model.columnEdit(2).booleanOf(model.value(0, 2)))
        assertEquals("24.5", model.value(0, 3))
        assertEquals("""{"color":"blue"}""", model.value(0, 4), "JSON columns take the object as compact JSON")
        val update = model.toChanges().single() as RowChange.Update
        assertEquals(setOf("name", "done", "price", "meta"), update.values.keys)
        assertEquals("1", update.key.getValue("id").value)
    }

    @Test
    fun `values a column can't take are refused with the reason`() {
        val model = model()
        val base = model.recordJson(0)
        val plan = model.plan(0, base, base.with("price" to "\"cheap\"", "name" to null, "done" to """{"a":1}""", "nope" to 1, "photo" to "\"x\""))
        assertFalse(plan.canApply)
        assertEquals(5, plan.problems.size, plan.problems.toString())
        assertTrue(plan.problems.any { "nope" in it && "no column" in it })
        assertTrue(plan.problems.any { "name" in it && "NULL" in it })
        assertTrue(plan.problems.any { "done" in it && "object" in it })
        assertTrue(plan.problems.any { "photo" in it && "binary" in it })
        assertNull(model.apply(0, base, base.with("price" to "\"cheap\"")))
        assertFalse(model.hasChanges, "a refused record stages nothing")
    }

    @Test
    fun `a column left out keeps its value`() {
        val model = model()
        val base = model.recordJson(1)
        val edited = JsonObject(base.filterKeys { it != "created" }).with("name" to "\"Desk 2\"")
        val plan = model.plan(1, base, edited)
        assertEquals(listOf("name"), plan.changes.map { it.name })
        assertTrue(plan.notes.any { "created" in it && "keep" in it })
        model.apply(1, base, edited)
        assertEquals("2026-10-02 09:00:00", model.value(1, 6))
    }

    @Test
    fun `edits made in the grid after opening survive unless the record changes them too`() {
        val model = model()
        val base = model.recordJson(0)
        model.setValue(0, 3, "30")
        model.apply(0, base, base.with("name" to "\"Lamp XL\""))
        assertEquals("30", model.value(0, 3), "the grid's edit stays")
        assertEquals("Lamp XL", model.value(0, 1))

        val again = model.recordJson(0)
        model.setValue(0, 1, "From grid")
        val plan = model.plan(0, again, again.with("name" to "\"From JSON\""))
        assertTrue(plan.notes.any { "Also edited in the grid" in it })
    }

    @Test
    fun `numbers too big for a double survive untouched`() {
        val big = TableEditModel(QueryResult.Rows(listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("n", "numeric", Types.NUMERIC), ResultColumn("s", "text")), listOf(listOf("1", "12345678901234567890.12", "a")), false, 1), listOf("id"))
        val base = big.recordJson(0)
        val text = dev.datagrok.app.grid.prettyRecord(base)
        assertTrue("12345678901234567890.12" in text, text)
        val edited = json(text).with("s" to "\"b\"")
        assertEquals(listOf("s"), big.plan(0, base, edited).changes.map { it.name })
    }

    @Test
    fun `changing the primary key is allowed with a note`() {
        val model = model()
        val base = model.recordJson(0)
        val plan = model.plan(0, base, base.with("id" to 10))
        assertTrue(plan.canApply)
        assertTrue(plan.notes.any { "primary key" in it })
    }

    @Test
    fun `a new record leaves out columns with defaults and inserts what's given`() {
        val model = model()
        val template = model.newRecord()
        assertEquals(setOf("name", "done", "price", "meta"), template.json.keys, "nullable columns are listed; generated and defaulted ones aren't")
        assertNotNull(template.hint)
        assertTrue("id" in template.hint!! && "created" in template.hint!!)

        val row = model.apply(null, template.json, json("""{"name": "Chair", "price": 49, "meta": [1, 2]}"""))
        assertEquals(2, row)
        assertEquals(RowStatus.Inserted, model.rowStatus(2))
        val insert = model.toChanges().single() as RowChange.Insert
        assertEquals(mapOf("name" to "Chair", "price" to "49", "meta" to "[1,2]"), insert.values.mapValues { it.value.value })
        // Re-opened, it shows just what was set; leaving a column out again gives it back its default.
        val reopened = model.recordJson(2)
        assertEquals(setOf("name", "price", "meta"), reopened.keys)
        model.apply(2, reopened, JsonObject(reopened.filterKeys { it != "meta" }))
        assertEquals(setOf("name", "price"), (model.toChanges().single() as RowChange.Insert).values.keys)
    }

    @Test
    fun `a new record still checks required columns`() {
        val model = model()
        val plan = model.plan(null, model.newRecord().json, json("""{"name": null}"""))
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("name"))
        assertTrue(model.plan(null, JsonObject(emptyMap()), json("""{"name": "x"}""")).let { it.canApply && it.changes.single().kind == FieldChange.Kind.Added })
    }

    @Test
    fun `without a primary key only new rows can be edited`() {
        val model = model(primaryKey = emptyList())
        assertNotNull(model.whyNotEditable(0))
        assertFalse(model.plan(0, model.recordJson(0), model.recordJson(0).with("name" to "\"x\"")).canApply)
        assertTrue(model.canAddRecord)
    }

    @Test
    fun `deleted and read-only rows can't be opened`() {
        val model = model()
        model.deleteRows(listOf(0))
        assertNotNull(model.whyNotEditable(0))
        val readOnly = TableEditModel(QueryResult.Rows(columns, rows, false, 1), listOf("id"), readOnly = true)
        assertNull(readOnly.records)
    }

    @Test
    fun `describes rows by their key`() {
        val model = model()
        assertEquals("id = 1", model.describeRecord(0))
        assertEquals("New row", model.describeRecord(model.addRow()))
    }
}
