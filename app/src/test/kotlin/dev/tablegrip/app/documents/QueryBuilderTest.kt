package dev.tablegrip.app.documents

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryBuilderTest {
    private val kinds =
        mapOf(
            "status" to BsonKind.String,
            "total" to BsonKind.Int32,
            "vip" to BsonKind.Bool,
            "_id" to BsonKind.ObjectId,
            "tags" to BsonKind.Array,
            "placed" to BsonKind.Date,
        )

    private fun builder(vararg conditions: Triple<String, FilterOperator, String>) =
        QueryBuilder().apply {
            conditions.forEach { (field, operator, value) -> addCondition(field, operator).value.setTextAndPlaceCursorAtEnd(value) }
        }

    private fun QueryBuilder.filter() = render { kinds[it] }.filter

    @Test
    fun `values are typed like their fields`() {
        assertEquals("{status: 'A'}", builder(Triple("status", FilterOperator.Eq, "A")).filter())
        assertEquals("{total: {\$gt: 10}}", builder(Triple("total", FilterOperator.Gt, "10")).filter())
        assertEquals("{vip: true}", builder(Triple("vip", FilterOperator.Eq, "yes")).filter())
        assertEquals("{_id: ObjectId('65f0c0ffee0000000000abcd')}", builder(Triple("_id", FilterOperator.Eq, "65f0c0ffee0000000000abcd")).filter())
        // Shell values go as typed; an array field matches its elements.
        assertEquals("{status: ObjectId('65f0c0ffee0000000000abcd')}", builder(Triple("status", FilterOperator.Eq, "ObjectId('65f0c0ffee0000000000abcd')")).filter())
        assertEquals("{tags: 'pen'}", builder(Triple("tags", FilterOperator.Eq, "pen")).filter())
        assertEquals("{other: 42}", builder(Triple("other", FilterOperator.Eq, "42")).filter())
        assertEquals("{placed: {\$gte: ISODate('2026-10-01T00:00:00Z')}}", builder(Triple("placed", FilterOperator.Gte, "2026-10-01T00:00:00Z")).filter())
    }

    @Test
    fun `conditions on one field merge, and any becomes or`() {
        val range = builder(Triple("total", FilterOperator.Gte, "10"), Triple("total", FilterOperator.Lt, "50"), Triple("status", FilterOperator.In, "A, B"))
        assertEquals("{total: {\$gte: 10, \$lt: 50}, status: {\$in: ['A', 'B']}}", range.filter())
        range.matchAny = true
        assertEquals("{\$or: [{total: {\$gte: 10}}, {total: {\$lt: 50}}, {status: {\$in: ['A', 'B']}}]}", range.filter())
        val twice = builder(Triple("status", FilterOperator.Eq, "A"), Triple("status", FilterOperator.Ne, "B"))
        assertEquals("{\$and: [{status: 'A'}, {status: {\$ne: 'B'}}]}", twice.filter())
    }

    @Test
    fun `text matches escape the text`() {
        assertEquals("{status: {\$regex: 'a\\\\.b', \$options: 'i'}}", builder(Triple("status", FilterOperator.Contains, "a.b")).filter())
        assertEquals("{status: {\$regex: '^ab', \$options: 'i'}}", builder(Triple("status", FilterOperator.StartsWith, "ab")).filter())
        assertEquals("{status: {\$exists: false}}", builder(Triple("status", FilterOperator.Missing, "")).filter())
    }

    @Test
    fun `disabled, blank and invalid conditions stay out`() {
        val query = builder(Triple("total", FilterOperator.Gt, "lots"), Triple("", FilterOperator.Eq, "x"), Triple("status", FilterOperator.Eq, "A"))
        query.conditions.last().enabled = false
        val rendered = query.render { kinds[it] }
        assertEquals("{}", rendered.filter)
        assertEquals(1, rendered.problems.size)
    }

    @Test
    fun `sort and projection`() {
        val query = QueryBuilder()
        query.addSort("total")
        query.addSort("status")
        query.addSort("total")
        query.addProjection("status")
        query.addProjection("_id")
        query.projection.last().include = false
        val rendered = query.render()
        assertEquals("{total: -1, status: 1}", rendered.sort)
        assertEquals("{status: 1, _id: 0}", rendered.projection)
        assertTrue(rendered.problems.isEmpty())
        query.addProjection("total")
        query.projection.last().include = false
        assertEquals(1, query.render().problems.size)
    }

    @Test
    fun `queries read back into the builder`() {
        val query = QueryBuilder()
        val filter = "{status: 'A', total: {\$gte: 10, \$lt: 50}, _id: {\"\$oid\": \"65f0c0ffee0000000000abcd\"}, name: {\$regex: '^ad', \$options: 'i'}, tags: {\$in: ['a', 'b']}}"
        assertTrue(query.load(filter, "{total: -1}", "{status: 1, _id: 0}") { kinds[it] })
        assertEquals(
            listOf("status = A", "total ≥ 10", "total < 50", "_id = 65f0c0ffee0000000000abcd", "name starts with ad", "tags in 'a', 'b'"),
            query.conditions.map { "${it.field} ${it.operator.symbol} ${it.value.text}" },
        )
        assertEquals(listOf(false), query.sort.map { it.ascending })
        assertEquals(listOf(true, false), query.projection.map { it.include })
        // What was read writes back the same.
        val again = QueryBuilder().apply { load(query.render { kinds[it] }.filter, "", "") { kinds[it] } }
        assertEquals(query.render { kinds[it] }.filter, again.render { kinds[it] }.filter)

        assertTrue(query.load("status: 'A'", "", "") { kinds[it] }, "braces may be left out")
        assertTrue(query.load("{\$or: [{status: 'A'}, {total: {\$gt: 1}}]}", "", "") { kinds[it] })
        assertTrue(query.matchAny)
    }

    @Test
    fun `what the builder can't show leaves it empty`() {
        val query = QueryBuilder()
        assertFalse(query.load("{\$or: [{status: 'A', total: 1}]}", "", ""))
        assertFalse(query.load("{tags: {\$elemMatch: {a: 1}}}", "", ""))
        assertFalse(query.load("{\$expr: {\$gt: ['\$a', '\$b']}}", "", ""))
        assertFalse(query.load("{}", "{score: {\$meta: 'textScore'}}", ""))
        assertTrue(query.unreadable)
        assertTrue(query.conditions.isEmpty())
    }
}
