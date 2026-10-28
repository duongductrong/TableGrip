package dev.datagrok.engines.mongodb

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.driver.DocumentConnection
import dev.datagrok.core.driver.DocumentStore
import dev.datagrok.core.driver.ReadOnlyViolationException
import dev.datagrok.core.drivers.DriverLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The adapter against a real server, skipped unless `-Pit.mongo=localhost:57017` is passed:
 * ```
 * docker run -d --name dg-it-mongo -p 57017:27017 mongo:8.0 --replSet rs0 --bind_ip_all
 * docker exec dg-it-mongo mongosh --eval "rs.initiate({_id:'rs0', members:[{_id:0, host:'localhost:27017'}]})"
 * ```
 */
class MongoClientTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"))
    private val db = "dg_${System.currentTimeMillis()}"

    private fun server(readOnly: Boolean = false, url: Boolean = false, test: suspend (DocumentConnection) -> Unit) = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.mongo")?.split(":") ?: return@runBlocking
        val config =
            ConnectionConfig(
                id = "mongo", name = "mongo", type = DatabaseType.MongoDB, host = host, port = port.toInt(), readOnly = readOnly,
                url = if (url) "mongodb://$host:$port/$db?directConnection=true" else null,
            )
        DocumentStore.connect(config, password = null, drivers = drivers).use { test(it) }
    }

    @Test
    fun `documents are listed, paged, filtered and edited`() = server { mongo ->
        assertTrue(mongo.server().startsWith("MongoDB 8") && "replica set rs0" in mongo.server(), mongo.server())
        val seeded =
            mongo.command(
                db,
                """db.orders.insertMany([
                    {_id: 1, customer: 'ada', status: 'A', total: 30, items: ['pen'], placed: ISODate('2026-10-01T10:00:00Z')},
                    {_id: 2, customer: 'linus', status: 'B', total: 10, address: {city: 'Helsinki'}},
                    {_id: 3, customer: 'grace', status: 'A', total: 50},
                    {_id: 4, customer: 'alan', status: 'A', total: 20}
                ])""".replace(Regex("\\s+"), " "),
            )
        assertEquals("{ acknowledged: true, insertedCount: 4 }", seeded)
        assertTrue(db in mongo.databases())
        assertEquals(listOf("orders" to 4L), mongo.collections(db).map { it.name to it.documents })

        val page = mongo.find(db, "orders", "{status: 'A'}", "{total: -1}", skip = 0, limit = 2)
        assertEquals(listOf(3, 1), page.documents.map { Regex("\"_id\": (\\d+)").find(it)!!.groupValues[1].toInt() })
        assertTrue(page.hasMore)
        assertEquals(false, mongo.find(db, "orders", "{status: 'A'}", "{total: -1}", skip = 2, limit = 2).hasMore)
        assertTrue("\"\$date\": \"2026-10-01T10:00:00Z\"" in mongo.find(db, "orders", "{_id: 1}", "", 0, 1).documents.single())
        assertEquals(3L, mongo.count(db, "orders", "{status: 'A'}"))

        // Insert gets an ObjectId; replace keeps the _id; it can't be changed.
        val id = mongo.insert(db, "orders", "{customer: 'new', total: 1}")
        assertTrue(id.startsWith("{\"\$oid\":"), id)
        mongo.replace(db, "orders", id, "{customer: 'renamed', total: 2}")
        assertTrue("renamed" in mongo.find(db, "orders", "{_id: $id}", "", 0, 1).documents.single())
        assertFailsWith<IllegalArgumentException> { mongo.replace(db, "orders", id, "{_id: 99, customer: 'x'}") }
        assertEquals(2L, mongo.delete(db, "orders", listOf(id, "2")))
        assertFailsWith<IllegalStateException> { mongo.replace(db, "orders", "2", "{customer: 'gone'}") }

        mongo.command(db, "db.orders.drop()")
    }

    @Test
    fun `the console speaks a JavaScript-free subset of the shell`() = server { mongo ->
        mongo.command(db, "db.people.insertMany([{_id: 'a', name: 'Ada', age: 36}, {_id: 'b', name: 'Bob', age: 25}, {_id: 'c', name: 'Cy', age: 36}])")
        assertTrue(mongo.command(db, "show collections").lines().contains("people"))
        val found = mongo.command(db, "db.people.find({age: {\$gt: 30}}, {name: 1}).sort({name: -1}).limit(5)")
        assertTrue(found.indexOf("Cy") in 0 until found.indexOf("Ada") && "age" !in found, found)
        assertTrue("\"Bob\"" in mongo.command(db, "db.people.findOne({_id: 'b'})"))
        assertEquals("2", mongo.command(db, "db.people.countDocuments({age: 36})"))
        assertTrue("25" in mongo.command(db, "db.people.distinct('age')"))
        val grouped = mongo.command(db, "db.people.aggregate([{\$group: {_id: '\$age', n: {\$sum: 1}}}, {\$sort: {_id: 1}}])")
        assertTrue(Regex("\"_id\": 36,\\s*\"n\": 2").containsMatchIn(grouped), grouped)
        assertEquals("{ matchedCount: 2, modifiedCount: 2 }", mongo.command(db, "db.people.updateMany({age: 36}, {\$inc: {age: 1}})"))
        assertEquals("{ deletedCount: 1 }", mongo.command(db, "db.people.deleteOne({_id: 'c'});"))
        assertEquals("age_1", mongo.command(db, "db.people.createIndex({age: 1})"))
        assertTrue("age_1" in mongo.command(db, "db.getCollection('people').getIndexes()"))
        assertTrue("\"ok\": 1" in mongo.command(db, "db.runCommand({ping: 1})"))
        assertTrue("\"ok\": 1" in mongo.command(db, "db.adminCommand({listDatabases: 1, nameOnly: true})"))
        assertTrue(mongo.command(db, "db.people.mapReduce(function(){})").startsWith("Error:"))
        assertTrue(mongo.command(db, "var x = 1").startsWith("Error:"))
        assertTrue(mongo.command(db, "db.people.find({\$bad: 1})").startsWith("Error:"))
        mongo.command(db, "db.dropDatabase()")
    }

    @Test
    fun `read-only profiles refuse writes, also from the console`() = server(readOnly = true) { mongo ->
        assertFailsWith<ReadOnlyViolationException> { mongo.insert(db, "x", "{a: 1}") }
        assertFailsWith<ReadOnlyViolationException> { mongo.command(db, "db.x.insertOne({a: 1})") }
        assertFailsWith<ReadOnlyViolationException> { mongo.command(db, "db.x.aggregate([{\$out: 'y'}])") }
        assertFailsWith<ReadOnlyViolationException> { mongo.command(db, "db.runCommand({drop: 'x'})") }
        assertEquals("(no documents)", mongo.command(db, "db.x.find()"))
        assertEquals("0", mongo.command(db, "db.x.countDocuments({})"))
    }

    @Test
    fun `a connection string works as well as host and port`() = server(url = true) { mongo ->
        assertTrue(mongo.server().startsWith("MongoDB"))
    }
}
