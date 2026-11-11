package dev.tablegrip.engines.kafka

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.driver.RecordStore
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Kafka adapter. The server test is skipped unless the property is passed:
 * ```
 * docker run -d --name dg-it-kafka -p 59092:9092 -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
 *   -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:59092 \
 *   -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
 *   -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
 *   -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 apache/kafka:3.9.0
 * -Pit.kafka=localhost:59092
 * ```
 */
class KafkaClientTest {
    @Test
    fun `seeks, commands and payloads`() {
        assertEquals(KafkaClient.Seek(), KafkaClient.Seek.parse(" "))
        assertEquals(KafkaClient.Seek(latest = true), KafkaClient.Seek.parse("latest"))
        assertEquals(KafkaClient.Seek(partition = 1, offset = 20), KafkaClient.Seek.parse("partition=1 offset=20"))
        assertEquals(KafkaClient.Seek(contains = "order 7"), KafkaClient.Seek.parse("order 7"))
        assertTrue(KafkaClient.isReadCommand("describe group billing"))
        assertFalse(KafkaClient.isReadCommand("delete topic orders"))
        assertEquals("""{"a":1}""", KafkaClient.json("""{"a":1}""".toByteArray()).toString())
        assertEquals(JsonPrimitive("plain"), KafkaClient.json("plain".toByteArray()))
        assertTrue("base64" in KafkaClient.json(byteArrayOf(-1, -2)).jsonObject)
        val config = ConnectionConfig(id = "k", name = "k", type = DatabaseType.Kafka, host = "a, b:9093", user = "app", properties = mapOf("ssl" to "true"))
        val properties = KafkaClient.properties(config, "a", 9092, "secret")
        assertEquals("a:9092,b:9093", properties["bootstrap.servers"])
        assertEquals("SASL_SSL", properties["security.protocol"])
        assertTrue("username=\"app\" password=\"secret\"" in properties["sasl.jaas.config"].toString())
    }

    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = true)

    @Test
    fun `kafka lists topics, pages messages and produces`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.kafka")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "kafka", name = "kafka", type = DatabaseType.Kafka, host = host, port = port.toInt())
        RecordStore.connect(config, null, drivers = drivers).use { kafka ->
            assertTrue(kafka.server().startsWith("Apache Kafka ("), kafka.server())
            runCatching { kafka.execute("delete topic tg_orders") }
            Thread.sleep(1_000)
            kafka.execute("create topic tg_orders partitions=3")
            val path = listOf("tg_orders")
            repeat(45) { n ->
                kafka.put(path, null, buildJsonObject {
                    put("key", "customer-${n % 5}")
                    put("value", buildJsonObject { put("order", n); put("total", n * 1.5) })
                    put("partition", n % 3)
                    put("headers", buildJsonObject { put("source", "test") })
                })
            }
            kafka.put(path, null, buildJsonObject { put("value", "plain text 7"); put("partition", 0) })
            val topic = kafka.containers().single { it.name == "tg_orders" }
            assertEquals(46L, topic.count)
            assertTrue("\"partitions\"" in kafka.definition(path))

            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = kafka.page(path, "", cursor, 20)
                page.records.forEach { seen += "${it.meta["partition"]}/${it.meta["offset"]}" }
                cursor = page.cursor
            } while (cursor != null)
            assertEquals(46, seen.toSet().size)
            assertEquals(46, seen.size, "no message twice")

            val first = kafka.page(path, "partition=1 offset=0", null, 100).records
            assertEquals(15, first.size)
            assertEquals(JsonPrimitive("test"), first.first().document["headers"]!!.jsonObject["source"])
            assertEquals(1, (first.first().document["value"] as JsonObject)["order"]!!.jsonPrimitive.content.toInt())
            // The newest two of each partition, a page of two at a time.
            val latest = kafka.page(path, "latest", null, 2)
            assertEquals(2, latest.records.size)
            var newest = latest.records.size
            var next = latest.cursor
            while (next != null) kafka.page(path, "latest", next, 2).also { newest += it.records.size; next = it.cursor }
            assertEquals(6, newest)
            assertEquals(listOf("plain text 7"), kafka.page(path, "text 7", null, 50).records.map { it.document["value"]!!.jsonPrimitive.content })
            assertNull(first.first().key)
            assertFailsWith<IOException> { kafka.delete(path, listOf(JsonObject(emptyMap()))) }

            val topics = kafka.execute("topics") as ConsoleReply.Table
            assertTrue(topics.rows.rows.any { it[0] == "tg_orders" })
            assertFailsWith<IOException> { kafka.execute("nonsense") }
            kafka.execute("delete topic tg_orders")
        }
        RecordStore.connect(config.copy(readOnly = true), null, drivers = drivers).use {
            assertTrue(it.execute("topics") is ConsoleReply)
            assertFailsWith<ReadOnlyViolationException> { it.execute("create topic tg_nope") }
        }
    }
}
