package dev.tablegrip.core.driver.document

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Amazon DynamoDB through its HTTP API, signed with Signature Version 4 by TableGrip itself: no SDK to download. The
 * host is the endpoint (`dynamodb.eu-west-1.amazonaws.com`, or `localhost` for DynamoDB Local), the user the access key
 * id and the password the secret key; properties add `region` (else read from the host, else us-east-1) and
 * `sessionToken`. Port 443 speaks HTTPS unless `ssl=false`.
 *
 * Items show as plain JSON; the types JSON lacks keep DynamoDB's own wrapper: `{"SS": [...]}`, `{"NS": [...]}`,
 * `{"B": "base64"}`, `{"BS": [...]}`. Browsing and queries are PartiQL, paged with its NextToken. An item's key is its
 * primary key plus a hash of the item as read; a change or delete re-reads it (strongly consistent) and refuses when it
 * changed since, and the write itself is conditional on the item still existing.
 */
class DynamoClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("DynamoDB", config, host, port, defaultTls = port == 443)
        val region = config.properties["region"]?.trim()?.takeIf { it.isNotEmpty() } ?: regionOf(config.host) ?: "us-east-1"
        val signer = Signer(config.user.trim(), password.orEmpty(), config.properties["sessionToken"]?.trim()?.takeIf { it.isNotEmpty() }, region, hostHeader(host, port, rest.base))
        if (signer.accessKey.isEmpty()) throw IOException(tr("DynamoDB needs an access key id as the user and the secret key as the password."))
        return Connection(config, rest, signer, region).also { it.ping() }
    }

    /** Signs requests with AWS Signature Version 4 for the dynamodb service. */
    internal class Signer(val accessKey: String, private val secretKey: String, private val sessionToken: String?, private val region: String, private val host: String) {
        fun headers(target: String, body: String, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): Map<String, String> {
            val amzDate = now.format(AmzDate)
            val date = amzDate.substring(0, 8)
            val headers = sortedMapOf(
                "content-type" to ContentType,
                "host" to host,
                "x-amz-date" to amzDate,
                "x-amz-target" to target,
            )
            sessionToken?.let { headers["x-amz-security-token"] = it }
            val signedHeaders = headers.keys.joinToString(";")
            val canonical = listOf("POST", "/", "", headers.entries.joinToString("") { "${it.key}:${it.value}\n" }, signedHeaders, sha256Hex(body)).joinToString("\n")
            val scope = "$date/$region/dynamodb/aws4_request"
            val toSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonical)).joinToString("\n")
            val key = listOf(date, region, "dynamodb", "aws4_request").fold("AWS4$secretKey".toByteArray()) { k, part -> hmac(k, part) }
            val signature = hmac(key, toSign).joinToString("") { "%02x".format(it) }
            return buildMap {
                put("X-Amz-Date", amzDate)
                put("X-Amz-Target", target)
                sessionToken?.let { put("X-Amz-Security-Token", it) }
                put("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            }
        }

        private fun hmac(key: ByteArray, data: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient, private val signer: Signer, private val region: String) : RecordConnection {
        /** Each table's key attributes, partition key first. */
        private val keys = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** One API call: `DynamoDB_20120810.<operation>` with a JSON body, failing with the service's message. */
        internal fun call(operation: String, body: JsonObject): JsonObject {
            val text = body.toString()
            val response = rest.send("POST", "/", text, ContentType, signer.headers("DynamoDB_20120810.$operation", text))
            val json = runCatching { response.json().jsonObject }.getOrNull()
            if (!response.ok) {
                val type = json?.get("__type")?.jsonPrimitive?.contentOrNull?.substringAfterLast('#')
                val message = (json?.get("message") ?: json?.get("Message"))?.jsonPrimitive?.contentOrNull ?: response.body.take(200)
                throw DynamoException(type, "DynamoDB: " + listOfNotNull(type, message).joinToString(": ") + " (${response.status})")
            }
            return json ?: JsonObject(emptyMap())
        }

        internal fun ping() {
            try {
                call("ListTables", buildJsonObject { put("Limit", 1) })
            } catch (e: DynamoException) {
                if (e.type in setOf("UnrecognizedClientException", "InvalidSignatureException", "AccessDeniedException", "MissingAuthenticationTokenException")) {
                    throw IOException(tr("{0} refused the credentials: {1}", "DynamoDB", e.message), e)
                }
                throw e
            }
        }

        override suspend fun server(): String = io {
            ping()
            "Amazon DynamoDB ($region, ${rest.base.substringAfter("://")})"
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val names = mutableListOf<String>()
                var start: String? = null
                do {
                    val reply = call("ListTables", buildJsonObject { start?.let { put("ExclusiveStartTableName", it) } })
                    names += reply["TableNames"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                    start = reply["LastEvaluatedTableName"]?.jsonPrimitive?.contentOrNull
                } while (start != null)
                // Describing each table costs a call; past a few dozen tables the counts aren't worth the wait.
                names.sorted().map { name ->
                    val table = if (names.size <= 50) runCatching { describe(name) }.getOrNull() else null
                    RecordContainer(listOf(name), "table", table?.get("ItemCount")?.jsonPrimitive?.longOrNull, table?.let { "key (" + keyOf(it).joinToString(", ") + ")" })
                }
            }

        private fun describe(table: String): JsonObject =
            call("DescribeTable", buildJsonObject { put("TableName", table) })["Table"]?.jsonObject ?: throw IOException(tr("There's no table {0}.", table))

        private fun keyOf(table: JsonObject): List<String> =
            table["KeySchema"]?.jsonArray.orEmpty().map { it.jsonObject }.sortedBy { if (it["KeyType"]?.jsonPrimitive?.content == "HASH") 0 else 1 }
                .map { it["AttributeName"]!!.jsonPrimitive.content }
                .also { keys[table["TableName"]!!.jsonPrimitive.content] = it }

        private fun keyNames(table: String): List<String> = keys[table] ?: keyOf(describe(table))

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(describe(container.single())) }

        override val queryLanguage =
            RecordLanguage(
                tr("WHERE"),
                tr("pk = 'user#1' AND begins_with(sk, 'order#')   or a whole PartiQL SELECT"),
                tr("A PartiQL WHERE clause on the table, or a whole PartiQL SELECT. A condition on the partition key reads one partition; anything else scans."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val table = container.single()
                val keyNames = keyNames(table)
                val reply = call("ExecuteStatement", buildJsonObject {
                    put("Statement", select(table, query))
                    put("Limit", limit)
                    cursor?.let { put("NextToken", it) }
                })
                val items = reply["Items"]?.jsonArray.orEmpty().map { plain(it.jsonObject) }
                RecordPage(items.map { record(it, keyNames) }, reply["NextToken"]?.jsonPrimitive?.contentOrNull)
            }

        private fun record(item: JsonObject, keyNames: List<String>): Record {
            val ordered = JsonObject(keyNames.filter { it in item }.associateWith { item.getValue(it) } + item)
            val key = if (keyNames.all { it in item }) JsonObject(keyNames.associateWith { item.getValue(it) } + (VersionField to JsonPrimitive(version(item)))) else null
            return Record(key, ordered)
        }

        override suspend fun template(container: List<String>): JsonObject = io { JsonObject(keyNames(container.single()).associateWith { JsonNull }) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val table = container.single()
                val keyNames = keyNames(table)
                keyNames.firstOrNull { document[it] == null || document[it] is JsonNull }?.let { throw IOException(tr("An item needs its {0}.", it)) }
                val names = buildJsonObject { keyNames.forEachIndexed { i, name -> put("#k$i", name) } }
                if (key == null) {
                    try {
                        call("PutItem", buildJsonObject {
                            put("TableName", table)
                            put("Item", typed(document))
                            put("ConditionExpression", keyNames.indices.joinToString(" AND ") { "attribute_not_exists(#k$it)" })
                            put("ExpressionAttributeNames", names)
                        })
                    } catch (e: DynamoException) {
                        if (e.type == "ConditionalCheckFailedException") throw IOException(tr("An item with this key already exists."), e)
                        throw e
                    }
                } else {
                    keyNames.firstOrNull { document[it] != key[it] }?.let { throw IOException(tr("An item's {0} can't change; add a new item instead.", it)) }
                    checkUnchanged(table, key, keyNames)
                    try {
                        call("PutItem", buildJsonObject {
                            put("TableName", table)
                            put("Item", typed(document))
                            put("ConditionExpression", keyNames.indices.joinToString(" AND ") { "attribute_exists(#k$it)" })
                            put("ExpressionAttributeNames", names)
                        })
                    } catch (e: DynamoException) {
                        if (e.type == "ConditionalCheckFailedException") throw IOException(tr("The item was deleted by someone else since it was read; reload and try again."), e)
                        throw e
                    }
                }
                JsonObject(keyNames.associateWith { document.getValue(it) } + (VersionField to JsonPrimitive(version(document))))
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val table = container.single()
                val keyNames = keyNames(table)
                keys.count { key ->
                    val current = current(table, key, keyNames) ?: return@count false
                    key[VersionField]?.let { if (version(current) != it.jsonPrimitive.content) throw changed(key, keyNames) }
                    val reply = call("DeleteItem", buildJsonObject {
                        put("TableName", table)
                        put("Key", typed(JsonObject(keyNames.associateWith { key.getValue(it) })))
                        put("ReturnValues", "ALL_OLD")
                    })
                    reply["Attributes"] != null
                }.toLong()
            }

        private fun current(table: String, key: JsonObject, keyNames: List<String>): JsonObject? =
            call("GetItem", buildJsonObject {
                put("TableName", table)
                put("Key", typed(JsonObject(keyNames.associateWith { key[it] ?: throw IOException(tr("The key has no {0}.", it)) })))
                put("ConsistentRead", true)
            })["Item"]?.jsonObject?.let(::plain)

        private fun checkUnchanged(table: String, key: JsonObject, keyNames: List<String>) {
            val current = current(table, key, keyNames) ?: throw IOException(tr("The item was deleted by someone else since it was read; reload and try again."))
            key[VersionField]?.let { if (version(current) != it.jsonPrimitive.content) throw changed(key, keyNames) }
        }

        private fun changed(key: JsonObject, keyNames: List<String>) =
            IOException(tr("The item ({0}) was changed by someone else since it was read; reload and try again.", keyNames.joinToString { "$it = ${key[it]}" }))

        override val consoleLanguage =
            RecordLanguage(
                tr("PartiQL"),
                "SELECT * FROM \"orders\" WHERE \"pk\" = 'user#1'",
                tr("PartiQL statements (SELECT, INSERT, UPDATE, DELETE), or an API call: its name and a JSON body, e.g. DescribeTable {\"TableName\": \"orders\"}."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "SELECT * FROM \"${it.replace("\"", "\"\"")}\"" } ?: "ListTables {}"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val text = statement.trim().removeSuffix(";").trim()
                if (text.isEmpty()) throw IOException(tr("Nothing to run"))
                val started = System.currentTimeMillis()
                val api = ApiCall.matchEntire(text)
                if (api != null) {
                    val body = api.groupValues[2].trim().ifEmpty { "{}" }
                    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw IOException(tr("The request body isn't a JSON object: {0}", it.message)) }
                    val reply = call(api.groupValues[1], json)
                    val elapsed = System.currentTimeMillis() - started
                    val items = (reply["Items"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                    return@io if (items != null) ConsoleReply.Table(JsonTables.rows(items.map { plain(it.jsonObject) }, elapsedMillis = elapsed))
                    else ConsoleReply.Text(JsonTables.pretty(reply), elapsed)
                }
                val items = mutableListOf<JsonObject>()
                var next: String? = null
                do {
                    val reply = call("ExecuteStatement", buildJsonObject { put("Statement", text); next?.let { put("NextToken", it) } })
                    items += reply["Items"]?.jsonArray.orEmpty().map { plain(it.jsonObject) }
                    next = reply["NextToken"]?.jsonPrimitive?.contentOrNull
                } while (next != null && items.size < MaxConsoleRows)
                val elapsed = System.currentTimeMillis() - started
                if (items.isEmpty()) ConsoleReply.Text(if (isReadStatement(text)) tr("No items.") else tr("Done."), elapsed)
                else ConsoleReply.Table(JsonTables.rows(items, elapsedMillis = elapsed), note = if (next != null) tr("First {0} items", items.size) else null)
            }

        override fun isRead(statement: String): Boolean = isReadStatement(statement)

        override fun close() = Unit
    }

    /** An error the service returned, with its type (`ConditionalCheckFailedException`…). */
    internal class DynamoException(val type: String?, message: String) : IOException(message)

    companion object {
        internal const val VersionField = "_version"
        private const val ContentType = "application/x-amz-json-1.0"
        private const val MaxConsoleRows = 1_000
        private val AmzDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val ApiCall = Regex("^([A-Z][A-Za-z]+)\\s*(\\{[\\s\\S]*\\})?$")
        private val ReadOperations = Regex("^(Describe|List|Get|BatchGet|Query|Scan)[A-Za-z]*$")

        /** The region in an AWS endpoint, e.g. `dynamodb.eu-west-1.amazonaws.com`. */
        internal fun regionOf(host: String): String? = Regex("""dynamodb(?:-fips)?\.([a-z0-9-]+)\.amazonaws\.com""").find(host.lowercase())?.groupValues?.get(1)

        /** The Host header the client sends, which the signature covers: the port only when it isn't the scheme's own. */
        internal fun hostHeader(host: String, port: Int, base: String): String {
            val https = base.startsWith("https")
            val bracketed = RestClient.formatHost(host)
            return if (https && port == 443 || !https && port == 80) bracketed else "$bracketed:$port"
        }

        /** PartiQL SELECTs read, as do API calls that describe, list, get, query or scan. */
        internal fun isReadStatement(statement: String): Boolean {
            val text = statement.trim().removeSuffix(";").trim()
            ApiCall.matchEntire(text)?.let { return ReadOperations.matches(it.groupValues[1]) }
            return Regex("^select\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        /** The browser's query as PartiQL: a WHERE clause on the table, or a whole SELECT. */
        internal fun select(table: String, query: String): String {
            val text = query.trim().removeSuffix(";").trim()
            val from = "SELECT * FROM \"${table.replace("\"", "\"\"")}\""
            return when {
                text.isEmpty() -> from
                Regex("^select\\s", RegexOption.IGNORE_CASE).containsMatchIn(text) -> text
                Regex("^where\\s", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "$from $text"
                else -> "$from WHERE $text"
            }
        }

        /** A DynamoDB item (attribute values) as plain JSON; sets and binary keep their wrapper. */
        internal fun plain(item: JsonObject): JsonObject = JsonObject(item.mapValues { plainValue(it.value.jsonObject) })

        @OptIn(ExperimentalSerializationApi::class)
        private fun plainValue(value: JsonObject): JsonElement {
            val (type, inner) = value.entries.single()
            return when (type) {
                "S" -> inner
                "N" -> JsonUnquotedLiteral(BigDecimal(inner.jsonPrimitive.content).toPlainString())
                "BOOL" -> inner
                "NULL" -> JsonNull
                "M" -> plain(inner.jsonObject)
                "L" -> JsonArray(inner.jsonArray.map { plainValue(it.jsonObject) })
                else -> value
            }
        }

        private val WrappedTypes = setOf("SS", "NS", "BS", "B")

        /** Plain JSON back as attribute values: the inverse of [plain]. */
        internal fun typed(item: JsonObject): JsonObject = JsonObject(item.mapValues { typedValue(it.value) })

        private fun typedValue(value: JsonElement): JsonObject =
            when (value) {
                is JsonNull -> buildJsonObject { put("NULL", true) }
                is JsonPrimitive ->
                    when {
                        value.isString -> buildJsonObject { put("S", value.content) }
                        value.booleanOrNull != null -> buildJsonObject { put("BOOL", value.booleanOrNull!!) }
                        else -> buildJsonObject { put("N", value.content) }
                    }
                is JsonArray -> buildJsonObject { put("L", JsonArray(value.map(::typedValue))) }
                is JsonObject -> {
                    val wrapper = value.entries.singleOrNull()?.takeIf { (type, inner) -> type in WrappedTypes && (if (type == "B") inner is JsonPrimitive else inner is JsonArray) }
                    if (wrapper != null) value else buildJsonObject { put("M", typed(value)) }
                }
            }

        /** A hash of the item, keys sorted so attribute order doesn't matter: its version, for telling whether it changed. */
        internal fun version(item: JsonObject): String =
            MessageDigest.getInstance("SHA-256").digest(canonical(item).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        private fun canonical(value: JsonElement): String =
            when (value) {
                is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
                is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
                is JsonPrimitive -> if (value.isString) value.toString() else value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.content
                JsonNull -> "null"
            }

        private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
