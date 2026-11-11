package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ClientApi
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.document.CouchClient
import dev.tablegrip.core.driver.document.DynamoClient
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * CouchDB and DynamoDB over their HTTP APIs, browsed as records. The server tests are skipped unless the properties are
 * passed:
 * ```
 * docker run -d --name dg-it-couch -p 55984:5984 -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=tablegrip couchdb:3.4.2
 * docker run -d --name dg-it-dynamo -p 58000:8000 amazon/dynamodb-local:2.5.4 -jar DynamoDBLocal.jar -inMemory -sharedDb
 * -Pit.couchdb=localhost:55984 -Pit.dynamodb=localhost:58000
 * ```
 */
class DocumentEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `couchdb and dynamodb are browsed as records, not with the mongo shell`() {
        assertEquals(ClientApi.Records, DatabaseType.CouchDB.api)
        assertEquals(ClientApi.Records, DatabaseType.DynamoDB.api)
        assertEquals(ClientApi.Document, DatabaseType.MongoDB.api)
    }

    @Test
    fun `couchdb reads are told from writes`() {
        listOf("GET /orders/_all_docs", "POST /orders/_find\n{}", "POST /orders/_design/stats/_view/by_status\n{}", "POST /orders/_all_docs\n{\"keys\": []}", "POST /orders/_explain").forEach {
            assertTrue(CouchClient.isReadRequest(it), it)
        }
        listOf("PUT /orders", "DELETE /orders", "POST /orders\n{}", "POST /orders/_bulk_docs\n{}", "POST /orders/_compact", "PUT /orders/doc1\n{}").forEach {
            assertFalse(CouchClient.isReadRequest(it), it)
        }
    }

    @Test
    fun `dynamodb items convert to plain json and back`() {
        val item = Json.parseToJsonElement(
            """{"pk": {"S": "u#1"}, "n": {"N": "12345678901234567890.50"}, "ok": {"BOOL": true}, "none": {"NULL": true},
               "tags": {"SS": ["a", "b"]}, "raw": {"B": "AAE="}, "m": {"M": {"x": {"L": [{"N": "1"}, {"S": "y"}]}}}}""",
        ).jsonObject
        val plain = DynamoClient.plain(item)
        assertEquals("""{"pk":"u#1","n":12345678901234567890.50,"ok":true,"none":null,"tags":{"SS":["a","b"]},"raw":{"B":"AAE="},"m":{"x":[1,"y"]}}""", plain.toString())
        assertEquals(item, DynamoClient.typed(plain))
        assertEquals(DynamoClient.version(plain), DynamoClient.version(JsonObject(plain.entries.reversed().associate { it.toPair() })), "attribute order doesn't matter")
    }

    @Test
    fun `dynamodb statements and endpoints`() {
        assertTrue(DynamoClient.isReadStatement("SELECT * FROM \"t\""))
        assertTrue(DynamoClient.isReadStatement("DescribeTable {\"TableName\": \"t\"}"))
        assertTrue(DynamoClient.isReadStatement("ListTables"))
        assertFalse(DynamoClient.isReadStatement("UPDATE \"t\" SET a = 1 WHERE pk = 'x'"))
        assertFalse(DynamoClient.isReadStatement("DeleteTable {\"TableName\": \"t\"}"))
        assertEquals("SELECT * FROM \"orders\" WHERE pk = 'a'", DynamoClient.select("orders", "pk = 'a'"))
        assertEquals("SELECT * FROM \"orders\" WHERE pk = 'a'", DynamoClient.select("orders", "WHERE pk = 'a';"))
        assertEquals("eu-west-1", DynamoClient.regionOf("dynamodb.eu-west-1.amazonaws.com"))
        assertNull(DynamoClient.regionOf("localhost"))
        assertEquals("localhost:8000", DynamoClient.hostHeader("localhost", 8000, "http://localhost:8000"))
        assertEquals("dynamodb.us-east-1.amazonaws.com", DynamoClient.hostHeader("dynamodb.us-east-1.amazonaws.com", 443, "https://dynamodb.us-east-1.amazonaws.com:443"))
    }

    @Test
    fun `requests are signed as AWS documents it`() {
        // The example request of AWS's Signature Version 4 test suite style: fixed key, date and body.
        val signer = DynamoClient.Signer("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null, "us-east-1", "dynamodb.us-east-1.amazonaws.com")
        val headers = signer.headers("DynamoDB_20120810.ListTables", "{}", ZonedDateTime.of(2015, 8, 30, 12, 36, 0, 0, ZoneOffset.UTC))
        assertEquals("20150830T123600Z", headers["X-Amz-Date"])
        val authorization = headers.getValue("Authorization")
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/dynamodb/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-target, Signature="))
        assertEquals(64, authorization.substringAfter("Signature=").length)
    }

    private fun server(property: String, type: DatabaseType, user: String, password: String, readOnly: Boolean = false, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), user = user, readOnly = readOnly)
            RecordStore.connect(config, password, drivers = drivers).use { test(it) }
        }

    @Test
    fun `couchdb browses, queries and writes with revisions`() = server("it.couchdb", DatabaseType.CouchDB, "admin", "tablegrip") { couch ->
        assertTrue(couch.server().startsWith("CouchDB 3."), couch.server())
        runCatching { couch.execute("DELETE /tg_orders") }
        couch.execute("PUT /tg_orders")
        val docs = (1..45).joinToString(",") { """{"_id": "order-${"%03d".format(it)}", "status": "${if (it % 3 == 0) "paid" else "open"}", "total": $it}""" }
        couch.execute("POST /tg_orders/_bulk_docs\n{\"docs\": [$docs]}")
        assertEquals(45L, couch.containers().single { it.name == "tg_orders" }.count)
        assertTrue(couch.containers().none { it.name.startsWith("_") })

        val path = listOf("tg_orders")
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = couch.page(path, "", cursor, 20)
            ids += page.records.map { it.document["_id"]!!.jsonPrimitive.content }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals((1..45).map { "order-${"%03d".format(it)}" }, ids)
        assertEquals(10, couch.page(path, "order-01", null, 50).records.size, "an _id prefix")
        // A Mango index is a design document: one more document from here on.
        couch.execute("POST /tg_orders/_index\n{\"index\": {\"fields\": [\"status\"]}, \"name\": \"by-status\"}")
        assertTrue("by-status" in couch.definition(listOf("tg_orders")))

        // Mango, paged with its bookmark.
        val first = couch.page(path, "{\"status\": \"paid\"}", null, 10)
        assertEquals(10, first.records.size)
        assertEquals(5, couch.page(path, "{\"status\": \"paid\"}", first.cursor, 10).records.size)

        val record = couch.page(path, "order-001", null, 1).records.single()
        assertTrue(record.key!!["_rev"]!!.jsonPrimitive.content.startsWith("1-"))
        val key = couch.put(path, record.key, JsonObject(record.document + ("status" to JsonPrimitive("paid"))))
        assertTrue(key["_rev"]!!.jsonPrimitive.content.startsWith("2-"))
        assertFailsWith<IOException>("a stale revision is refused") { couch.put(path, record.key, record.document) }
        assertFailsWith<IOException> { couch.put(path, null, buildJsonObject { put("_id", "order-001") }) }
        val created = couch.put(path, null, buildJsonObject { put("_id", ""); put("status", "new") })
        assertTrue(created["_id"]!!.jsonPrimitive.content.isNotEmpty())

        val fresh = couch.page(path, "order-04", null, 10).records
        assertEquals(6L, couch.delete(path, fresh.map { it.key!! }))
        assertEquals(0L, couch.delete(path, fresh.map { it.key!! }))
        assertEquals(41L, couch.page(path, "", null, 1).total)

        val found = couch.execute("POST /tg_orders/_find\n{\"selector\": {\"status\": \"open\"}, \"limit\": 3}") as ConsoleReply.Table
        assertEquals(3, found.rows.rows.size)
        assertEquals("_id", found.rows.columns.first().name)
        assertTrue(couch.execute("GET /tg_orders") is ConsoleReply.Text)
        couch.execute("DELETE /tg_orders")
    }

    @Test
    fun `a wrong couchdb password is refused`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.couchdb")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "c", name = "c", type = DatabaseType.CouchDB, host = host, port = port.toInt(), user = "admin")
        assertFailsWith<IOException> { RecordStore.connect(config, "wrong", drivers = drivers) }
    }

    @Test
    fun `dynamodb browses, queries and writes items`() = server("it.dynamodb", DatabaseType.DynamoDB, "tablegrip", "secret") { dynamo ->
        assertTrue(dynamo.server().startsWith("Amazon DynamoDB (us-east-1"), dynamo.server())
        runCatching { dynamo.execute("DeleteTable {\"TableName\": \"tg_orders\"}") }
        dynamo.execute(
            """CreateTable {"TableName": "tg_orders", "BillingMode": "PAY_PER_REQUEST",
               "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}, {"AttributeName": "sk", "AttributeType": "N"}],
               "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}]}""",
        )
        repeat(3) { user ->
            repeat(15) { n -> dynamo.execute("INSERT INTO \"tg_orders\" VALUE {'pk': 'user#$user', 'sk': $n, 'status': '${if (n % 2 == 0) "open" else "paid"}', 'tags': <<'a', 'b'>>, 'total': $n.25}") }
        }
        val table = dynamo.containers().single { it.name == "tg_orders" }
        assertEquals("key (pk, sk)", table.detail)
        assertTrue("\"KeySchema\"" in dynamo.definition(listOf("tg_orders")))

        val path = listOf("tg_orders")
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = dynamo.page(path, "", cursor, 20)
            page.records.forEach { seen += "${it.document["pk"]}/${it.document["sk"]}" }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals(45, seen.size)
        val partition = dynamo.page(path, "pk = 'user#1'", null, 50).records
        assertEquals(15, partition.size)
        assertEquals(listOf("pk", "sk"), partition.first().document.keys.take(2), "the key comes first")
        assertEquals(buildJsonObject { put("SS", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))) }, partition.first().document["tags"])

        val record = partition.first()
        val key = dynamo.put(path, record.key, JsonObject(record.document + ("status" to JsonPrimitive("shipped"))))
        assertEquals("shipped", dynamo.page(path, "pk = 'user#1' AND sk = ${record.document["sk"]}", null, 5).records.single().document["status"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale item isn't overwritten") { dynamo.put(path, record.key, record.document) }
        assertFailsWith<IOException> { dynamo.put(path, key, JsonObject(record.document + ("sk" to JsonPrimitive(99)))) }
        dynamo.put(path, null, buildJsonObject { put("pk", "user#9"); put("sk", 1); put("note", "new") })
        assertFailsWith<IOException> { dynamo.put(path, null, buildJsonObject { put("pk", "user#9"); put("sk", 1) }) }

        val doomed = dynamo.page(path, "pk = 'user#9' OR pk = 'user#2'", null, 50).records
        assertEquals(16L, dynamo.delete(path, doomed.map { it.key!! }))
        assertEquals(0L, dynamo.delete(path, doomed.map { it.key!! }))

        val rows = dynamo.execute("SELECT pk, sk, total FROM \"tg_orders\" WHERE pk = 'user#0'") as ConsoleReply.Table
        assertEquals(15, rows.rows.rows.size)
        assertTrue(dynamo.execute("DescribeTable {\"TableName\": \"tg_orders\"}") is ConsoleReply.Text)
        assertFailsWith<IOException> { dynamo.execute("SELECT * FROM \"tg_missing\"") }
        dynamo.execute("DeleteTable {\"TableName\": \"tg_orders\"}")
    }

    @Test
    fun `a read-only dynamodb profile only reads`() = server("it.dynamodb", DatabaseType.DynamoDB, "tablegrip", "secret", readOnly = true) { dynamo ->
        assertTrue(dynamo.execute("ListTables {}") is ConsoleReply.Text)
        assertFailsWith<ReadOnlyViolationException> { dynamo.execute("DeleteTable {\"TableName\": \"x\"}") }
    }
}
