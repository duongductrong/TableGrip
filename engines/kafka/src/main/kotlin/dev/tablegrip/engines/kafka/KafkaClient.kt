package dev.tablegrip.engines.kafka

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.withContextClassLoader
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer

/**
 * TableGrip's Kafka adapter over the Apache Kafka client. The host field takes the bootstrap servers
 * (`broker1:9092,broker2:9092`); a user and password sign in with SASL PLAIN (SCRAM with `sasl.mechanism`), `ssl=true`
 * turns on TLS, and every other property goes to the client as it is (`security.protocol`, `sasl.jaas.config`…).
 *
 * Topics are the containers. A page of messages is read by assigning the topic's partitions and seeking, with no consumer
 * group, so nothing is committed. Adding a record produces a message; messages are never changed or deleted. Keys and
 * values show as text, or as JSON when they are.
 */
class KafkaClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection =
        withContextClassLoader(KafkaClient::class.java.classLoader) {
            val properties = properties(config, host, port, password)
            val admin = Admin.create(properties)
            try {
                admin.describeCluster().clusterId().get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                admin.close(Duration.ofSeconds(1))
                throw IOException(tr("Couldn't reach {0} at {1}: {2}", "Kafka", properties[AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG], rootMessage(e)), e)
            }
            Connection(config, admin, properties)
        }

    internal class Connection(override val config: ConnectionConfig, private val admin: Admin, private val properties: Properties) : RecordConnection {
        /** Opened on the first message produced. */
        @Volatile private var openProducer: KafkaProducer<ByteArray, ByteArray>? = null

        @Synchronized
        private fun producer(): KafkaProducer<ByteArray, ByteArray> =
            openProducer ?: KafkaProducer(Properties().apply { putAll(properties); put(ProducerConfig.ACKS_CONFIG, "all") }, ByteArraySerializer(), ByteArraySerializer())
                .also { openProducer = it }

        private suspend fun <T> io(block: () -> T): T =
            withContext(Dispatchers.IO) {
                withContextClassLoader(KafkaClient::class.java.classLoader) {
                    try {
                        block()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw IOException("Kafka: ${rootMessage(e)}", e.cause)
                    }
                }
            }

        override suspend fun server(): String =
            io {
                val cluster = admin.describeCluster()
                "Apache Kafka (${cluster.clusterId().get(15, TimeUnit.SECONDS)}, " + tr("{0} brokers", cluster.nodes().get(15, TimeUnit.SECONDS).size) + ")"
            }

        private fun topics(): List<String> = admin.listTopics().names().get(30, TimeUnit.SECONDS).filter { !it.startsWith("__") }.sorted()

        private fun partitions(topic: String): List<TopicPartition> =
            admin.describeTopics(listOf(topic)).allTopicNames().get(30, TimeUnit.SECONDS).getValue(topic).partitions().map { TopicPartition(topic, it.partition()) }

        private fun offsets(partitions: List<TopicPartition>, spec: OffsetSpec): Map<TopicPartition, Long> =
            admin.listOffsets(partitions.associateWith { spec }).all().get(30, TimeUnit.SECONDS).mapValues { it.value.offset() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> = io { if (parent.isEmpty()) topicContainers() else emptyList() }

        private fun topicContainers(): List<RecordContainer> {
                val names = topics()
                val described = if (names.isEmpty()) emptyMap() else admin.describeTopics(names).allTopicNames().get(30, TimeUnit.SECONDS)
                val all = described.flatMap { (topic, d) -> d.partitions().map { TopicPartition(topic, it.partition()) } }
                val earliest = if (all.isEmpty()) emptyMap() else offsets(all, OffsetSpec.earliest())
                val latest = if (all.isEmpty()) emptyMap() else offsets(all, OffsetSpec.latest())
                return names.map { topic ->
                    val parts = all.filter { it.topic() == topic }
                    RecordContainer(listOf(topic), "topic", parts.sumOf { (latest[it] ?: 0) - (earliest[it] ?: 0) }, tr("{0} partitions", parts.size))
                }
            }

        override suspend fun definition(container: List<String>): String = io { topicDefinition(container.single()) }

        private fun topicDefinition(topic: String): String {
                val description = admin.describeTopics(listOf(topic)).allTopicNames().get(30, TimeUnit.SECONDS).getValue(topic)
                val configs = admin.describeConfigs(listOf(ConfigResource(ConfigResource.Type.TOPIC, topic))).all().get(30, TimeUnit.SECONDS).values.single()
                return JsonTables.pretty(buildJsonObject {
                    put("topic", topic)
                    put("partitions", JsonArray(description.partitions().map { p ->
                        buildJsonObject {
                            put("partition", p.partition())
                            put("leader", p.leader()?.idString() ?: "none")
                            put("replicas", JsonArray(p.replicas().map { JsonPrimitive(it.id()) }))
                            put("isr", JsonArray(p.isr().map { JsonPrimitive(it.id()) }))
                        }
                    }))
                    put("config", JsonObject(configs.entries().filter { it.source() != ConfigEntry.ConfigSource.DEFAULT_CONFIG }.associate { it.name() to JsonPrimitive(it.value()) }))
                })
        }

        override val queryLanguage =
            RecordLanguage(
                tr("Seek"),
                tr("Nothing for the first messages; latest; partition=0 offset=1200; or text the key or value contains"),
                tr("Where to start reading: blank for the oldest messages, latest for the newest, partition= and offset= for a place. Any other text keeps the messages whose key or value contains it."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val topic = container.single()
                val seek = Seek.parse(query)
                val partitions = partitions(topic).filter { seek.partition == null || it.partition() == seek.partition }
                val earliest = offsets(partitions, OffsetSpec.earliest())
                val latest = offsets(partitions, OffsetSpec.latest())
                val start: Map<TopicPartition, Long> =
                    cursor?.let { text -> Json.parseToJsonElement(text).jsonObject.entries.associate { (p, o) -> TopicPartition(topic, p.toInt()) to o.jsonPrimitive.long } }
                        ?: partitions.associateWith { p ->
                            when {
                                seek.offset != null -> seek.offset.coerceIn(earliest.getValue(p), latest.getValue(p))
                                seek.latest -> maxOf(earliest.getValue(p), latest.getValue(p) - limit)
                                else -> earliest.getValue(p)
                            }
                        }
                val positions = start.toMutableMap()
                val records = mutableListOf<Record>()
                var scanned = 0
                consumer().use { consumer ->
                    val open = positions.filter { (p, o) -> o < latest.getValue(p) }.keys
                    if (open.isNotEmpty()) {
                        consumer.assign(open)
                        open.forEach { consumer.seek(it, positions.getValue(it)) }
                        val deadline = System.currentTimeMillis() + 10_000
                        while (records.size < limit && scanned < MaxScanned && System.currentTimeMillis() < deadline &&
                            positions.any { (p, o) -> o < latest.getValue(p) }
                        ) {
                            for (message in consumer.poll(Duration.ofMillis(500))) {
                                val partition = TopicPartition(topic, message.partition())
                                if (records.size >= limit) break
                                positions[partition] = message.offset() + 1
                                scanned++
                                val record = record(message.partition(), message.offset(), message.timestamp(), message.key(), message.value(), message.headers().map { it.key() to it.value() })
                                if (seek.contains == null || listOf(record.document["key"], record.document["value"]).any { it.toString().contains(seek.contains, ignoreCase = true) }) records += record
                            }
                        }
                    }
                }
                val more = positions.any { (p, o) -> o < latest.getValue(p) }
                val next = if (more) JsonObject(positions.entries.associate { (p, o) -> p.partition().toString() to JsonPrimitive(o) }).toString() else null
                RecordPage(records, next, if (seek.contains == null && seek.offset == null && !seek.latest && cursor == null) partitions.sumOf { latest.getValue(it) - earliest.getValue(it) } else null)
            }

        private fun consumer(): KafkaConsumer<ByteArray, ByteArray> =
            KafkaConsumer(
                Properties().apply {
                    putAll(properties)
                    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                    put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                    put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                },
                ByteArrayDeserializer(),
                ByteArrayDeserializer(),
            )

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("key", JsonNull); put("value", ""); put("headers", JsonObject(emptyMap())) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                if (key != null) throw IOException(tr("Kafka messages can't be changed; produce a new one."))
                val topic = container.single()
                val headers = (document["headers"] as? JsonObject).orEmpty()
                val record = ProducerRecord(
                    topic,
                    (document["partition"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    bytes(document["key"]),
                    bytes(document["value"]),
                    headers.map { (name, value) -> org.apache.kafka.common.header.internals.RecordHeader(name, bytes(value)) },
                )
                val written = producer().send(record).get(30, TimeUnit.SECONDS)
                buildJsonObject { put("partition", written.partition()); put("offset", written.offset()) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("Kafka messages can't be deleted; the topic's retention removes them."))

        override val consoleLanguage =
            RecordLanguage(
                tr("Commands"),
                "describe topic orders",
                tr("topics; describe topic <name>; groups; describe group <id> (lag per partition); create topic <name> [partitions=3] [replication=1]; delete topic <name>."),
            )

        override fun sampleStatement(container: List<String>): String = container.firstOrNull()?.let { "describe topic $it" } ?: "topics"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val words = statement.trim().removeSuffix(";").split(Regex("\\s+")).filter { it.isNotEmpty() }
                val started = System.currentTimeMillis()
                fun table(rows: List<JsonObject>) = if (rows.isEmpty()) ConsoleReply.Text(tr("No rows."), System.currentTimeMillis() - started) else ConsoleReply.Table(JsonTables.rows(rows, elapsedMillis = System.currentTimeMillis() - started))
                when (words.joinToString(" ") { it.lowercase() }.let { c -> Commands.firstOrNull { c == it || c.startsWith("$it ") } }) {
                    "topics" -> table(topicContainers().map { buildJsonObject { put("topic", it.name); put("messages", it.count); put("partitions", it.detail) } })
                    "groups" -> table(admin.listConsumerGroups().all().get(30, TimeUnit.SECONDS).map { g ->
                        buildJsonObject { put("group", g.groupId()); put("state", g.state().map { it.toString() }.orElse("")) }
                    })
                    "describe topic" -> ConsoleReply.Text(topicDefinition(words.getOrElse(2) { throw IOException(tr("Name the topic.")) }), System.currentTimeMillis() - started)
                    "describe group" -> {
                        val group = words.getOrElse(2) { throw IOException(tr("Name the group.")) }
                        val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS)
                        val ends = if (committed.isEmpty()) emptyMap() else offsets(committed.keys.toList(), OffsetSpec.latest())
                        table(committed.entries.sortedWith(compareBy({ it.key.topic() }, { it.key.partition() })).map { (p, o) ->
                            buildJsonObject {
                                put("topic", p.topic()); put("partition", p.partition()); put("committed", o?.offset()); put("end", ends[p]); put("lag", ends[p]?.minus(o?.offset() ?: 0))
                            }
                        })
                    }
                    "create topic" -> {
                        val name = words.getOrElse(2) { throw IOException(tr("Name the topic.")) }
                        val options = words.drop(3).associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
                        admin.createTopics(listOf(NewTopic(name, options["partitions"]?.toIntOrNull() ?: 1, (options["replication"]?.toIntOrNull() ?: 1).toShort()))).all().get(30, TimeUnit.SECONDS)
                        ConsoleReply.Text(tr("Done."), System.currentTimeMillis() - started)
                    }
                    "delete topic" -> {
                        admin.deleteTopics(listOf(words.getOrElse(2) { throw IOException(tr("Name the topic.")) })).all().get(30, TimeUnit.SECONDS)
                        ConsoleReply.Text(tr("Done."), System.currentTimeMillis() - started)
                    }
                    else -> throw IOException(consoleLanguage.help)
                }
            }

        override fun isRead(statement: String): Boolean = isReadCommand(statement)

        override fun close() {
            withContextClassLoader(KafkaClient::class.java.classLoader) {
                openProducer?.let { runCatching { it.close(Duration.ofSeconds(2)) } }
                admin.close(Duration.ofSeconds(2))
            }
        }
    }

    /** Where the query field says to start reading, and what to keep. */
    internal data class Seek(val latest: Boolean = false, val partition: Int? = null, val offset: Long? = null, val contains: String? = null) {
        companion object {
            fun parse(query: String): Seek {
                val text = query.trim()
                if (text.isEmpty()) return Seek()
                if (text.equals("latest", ignoreCase = true)) return Seek(latest = true)
                val tokens = text.split(Regex("\\s+"))
                if (tokens.all { Regex("^(partition|offset)=\\d+$", RegexOption.IGNORE_CASE).matches(it) }) {
                    val values = tokens.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
                    return Seek(partition = values["partition"]?.toInt(), offset = values["offset"]?.toLong())
                }
                return Seek(contains = text)
            }
        }
    }

    companion object {
        private const val MaxScanned = 20_000
        private val Commands = listOf("topics", "groups", "describe topic", "describe group", "create topic", "delete topic")

        internal fun isReadCommand(statement: String): Boolean {
            val command = statement.trim().removeSuffix(";").lowercase().split(Regex("\\s+")).joinToString(" ")
            return listOf("topics", "groups", "describe topic", "describe group").any { command == it || command.startsWith("$it ") }
        }

        /** The client's settings: bootstrap servers from the host field, SASL from the user, TLS from ssl, then the properties. */
        internal fun properties(config: ConnectionConfig, host: String, port: Int, password: String?): Properties =
            Properties().apply {
                val servers = if (',' in config.host) config.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",") { if (':' in it) it else "$it:$port" }
                else "$host:$port"
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers)
                put(AdminClientConfig.CLIENT_ID_CONFIG, "tablegrip")
                put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000")
                put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000")
                val tls = config.properties["ssl"]?.toBoolean() == true
                val user = config.user.trim()
                if (user.isNotEmpty()) {
                    put("security.protocol", if (tls) "SASL_SSL" else "SASL_PLAINTEXT")
                    val mechanism = config.properties["sasl.mechanism"] ?: "PLAIN"
                    put("sasl.mechanism", mechanism)
                    val module = if (mechanism.startsWith("SCRAM")) "org.apache.kafka.common.security.scram.ScramLoginModule" else "org.apache.kafka.common.security.plain.PlainLoginModule"
                    put("sasl.jaas.config", "$module required username=\"${escape(user)}\" password=\"${escape(password.orEmpty())}\";")
                } else if (tls) put("security.protocol", "SSL")
                config.properties.filterKeys { it != "ssl" }.forEach { (k, v) -> put(k, v) }
            }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

        /** Bytes as JSON when they are JSON, else text; base64 when they aren't UTF-8. */
        internal fun json(bytes: ByteArray?): JsonElement {
            if (bytes == null) return JsonNull
            val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                ?: return buildJsonObject { put("base64", java.util.Base64.getEncoder().encodeToString(bytes)) }
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) runCatching { return Json.parseToJsonElement(trimmed) }
            return JsonPrimitive(text)
        }

        /** The inverse: text as itself, anything else as its JSON. */
        internal fun bytes(value: JsonElement?): ByteArray? =
            when {
                value == null || value is JsonNull -> null
                value is JsonPrimitive && value.isString -> value.content.toByteArray()
                else -> value.toString().toByteArray()
            }

        internal fun record(partition: Int, offset: Long, timestamp: Long, key: ByteArray?, value: ByteArray?, headers: List<Pair<String, ByteArray?>>): Record =
            Record(
                null,
                buildJsonObject {
                    put("key", json(key))
                    put("value", json(value))
                    if (headers.isNotEmpty()) put("headers", JsonObject(headers.associate { (k, v) -> k to json(v) }))
                },
                buildJsonObject { put("partition", partition); put("offset", offset); put("timestamp", Instant.ofEpochMilli(timestamp).toString()) },
            )

        private fun rootMessage(e: Throwable): String = generateSequence(e) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }
    }
}
