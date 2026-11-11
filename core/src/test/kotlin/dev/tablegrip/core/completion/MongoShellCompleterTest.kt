package dev.tablegrip.core.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MongoShellCompleterTest {
    private val catalog =
        object : MongoCatalog {
            override fun databases() = listOf("shop", "admin", "local")

            override fun collections() = listOf("orders", "order-items", "users")

            override fun fields(collection: String) =
                when (collection) {
                    "orders", "order-items" ->
                        listOf(
                            DocumentField("_id", listOf("objectId")),
                            DocumentField("status", listOf("string"), listOf("'A'", "'B'")),
                            DocumentField("total", listOf("number")),
                            DocumentField("address", listOf("object")),
                            DocumentField("address.city", listOf("string"), listOf("'London'")),
                        )
                    else -> listOf(DocumentField("name", listOf("string")))
                }
        }

    private fun complete(text: String, readOnly: Boolean = false): CompletionResult {
        val caret = text.indexOf('|')
        return MongoShellCompleter.complete(text.removeRange(caret, caret + 1), caret, catalog, readOnly)
    }

    private fun labels(text: String, readOnly: Boolean = false) = complete(text, readOnly).items.map { it.label }

    private fun accept(text: String, label: String? = null): String {
        val caret = text.indexOf('|')
        val plain = text.removeRange(caret, caret + 1)
        val result = MongoShellCompleter.complete(plain, caret, catalog)
        val item = label?.let { l -> result.items.first { it.label == l } } ?: result.items.first()
        val after = plain.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)
        val at = result.replaceStart + item.caretOffset
        return after.substring(0, at) + "|" + after.substring(at)
    }

    @Test
    fun `top-level words, show and use`() {
        assertEquals("db.|", accept("d|"))
        assertEquals(listOf("dbs", "databases"), labels("show d|"))
        assertEquals("use shop|", accept("use sh|"))
    }

    @Test
    fun `db dot offers collections and database methods`() {
        val all = labels("db.|")
        assertTrue(all.containsAll(listOf("orders", "users", "runCommand", "getCollectionNames")), all.toString())
        assertEquals("db.orders|", accept("db.ord|"))
        // A name that isn't an identifier goes through getCollection.
        assertEquals("db.getCollection('order-items')|", accept("db.order|", "order-items"))
        assertEquals("db.runCommand({|})", accept("db.runC|"))
    }

    @Test
    fun `collection and cursor methods insert their brackets`() {
        assertEquals("db.orders.find({|})", accept("db.orders.fi|"))
        assertEquals("db.orders.aggregate([|])", accept("db.orders.agg|"))
        assertEquals("db.orders.distinct('|')", accept("db.orders.disti|"))
        // An existing call keeps its arguments.
        assertEquals("db.orders.findOne|({a: 1})", accept("db.orders.fin|({a: 1})", "findOne"))
        // After find(), the cursor's methods, each once.
        assertEquals(listOf("sort", "limit", "skip", "projection"), labels("db.orders.find({}).|"))
        assertFalse("sort" in labels("db.orders.find({}).sort({total: 1}).|"))
        assertEquals("db.orders.find({}).limit(|)", accept("db.orders.find({}).lim|"))
        // db.getCollection('…') is a collection too.
        assertTrue("find" in labels("db.getCollection('order-items').|"))
    }

    @Test
    fun `read-only consoles don't offer writes`() {
        val methods = labels("db.orders.|", readOnly = true)
        assertTrue("find" in methods && "aggregate" in methods)
        assertFalse("insertOne" in methods || "deleteMany" in methods || "drop" in methods)
        assertFalse("dropDatabase" in labels("db.|", readOnly = true))
    }

    @Test
    fun `arguments complete as the method takes them`() {
        // find's filter and projection.
        assertEquals("db.orders.find({status: |})", accept("db.orders.find({sta|})"))
        assertEquals(listOf("'A'", "'B'"), labels("db.orders.find({status: |})").take(2))
        assertEquals(listOf("1", "0"), labels("db.orders.find({}, {status: |})"))
        // sort's directions.
        assertEquals(listOf("1", "-1"), labels("db.orders.find({}).sort({total: |})"))
        // An update's operators, then the fields they change.
        assertEquals("db.orders.updateOne({}, {\$set: {|}})", accept("db.orders.updateOne({}, {\$se|})", "\$set"))
        assertTrue("status" in labels("db.orders.updateMany({}, {\$set: {|}})"))
        // Pipeline stages, and the filter inside \$match.
        assertEquals("db.orders.aggregate([{\$match: {|}}])", accept("db.orders.aggregate([|])", "\$match"))
        assertEquals("status", labels("db.orders.aggregate([{\$match: {st|}}])").first())
        assertEquals("\$total", labels("db.orders.aggregate([{\$group: {_id: '\$status', sum: {\$sum: '\$to|'}}}])").first())
        // distinct's field name, and a filter after it.
        assertEquals("db.orders.distinct('address.city'|)", accept("db.orders.distinct('cit|')"))
        assertEquals("status", labels("db.orders.distinct('status', {st|})").first())
        // Commands.
        assertEquals("db.runCommand({ping: 1|})", accept("db.runCommand({pin|})"))
        // With no brackets typed yet, they come with the suggestion.
        assertEquals("db.orders.find({status: |})", accept("db.orders.find(|)", "status"))
        // Inserted documents: their fields, without dotted paths.
        val insert = labels("db.orders.insertOne({|})")
        assertTrue("status" in insert && "address.city" !in insert, insert.toString())
    }

    @Test
    fun `strings, brackets and parentheses inside arguments don't confuse the chain`() {
        assertEquals(listOf("1", "-1"), labels("db.orders.find({note: 'a), (b'}).sort({total: |})"))
        assertEquals("status", labels("db.orders.find({\$or: [{total: 1}, {sta|}]})").first())
    }

    @Test
    fun `the hint is the signature of the method being called`() {
        val text = "db.orders.find({}, "
        assertEquals("find(filter, projection) — documents matching the filter", MongoShellCompleter.hint(text, text.length))
        assertNull(MongoShellCompleter.hint("db.orders.", 10))
    }
}
