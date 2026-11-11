package dev.tablegrip.mcp

import dev.tablegrip.core.connection.AgentWrites
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.queries.QueryLibrary
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.SettingsStore
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpServerTest {
    private lateinit var home: Path
    private lateinit var database: Path
    private lateinit var connections: ConnectionStore
    private lateinit var settings: SettingsStore
    private val secrets = InMemorySecretStore()
    private val clients = mutableListOf<TestClient>()

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("tablegrip-mcp")
        database = home.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT NOT NULL, tier TEXT)")
                st.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER REFERENCES customers(id), total REAL)")
                for (i in 1..30) st.executeUpdate("INSERT INTO customers VALUES ($i, 'Customer $i', '${if (i % 3 == 0) "gold" else "basic"}')")
                st.executeUpdate("INSERT INTO orders VALUES (1, 1, 9.5), (2, 1, 20), (3, 2, 7.25)")
            }
        }
        connections = ConnectionStore(home.resolve("connections.json"), secrets)
        settings = SettingsStore(home.resolve("settings.json"))
        settings.save(Settings(mcpEnabled = true, mcpMaxRows = 10))
        save(AiAccess.SchemaAndSamples)
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        home.toFile().deleteRecursively()
    }

    private fun save(access: AiAccess?, writes: AgentWrites = AgentWrites.None, environment: Environment = Environment.None, readOnly: Boolean = false) {
        val config = ConnectionConfig(
            "shop-id", "shop", DatabaseType.SQLite, database = database.toString(),
            aiAccess = access, agentWrites = writes, environment = environment, readOnly = readOnly,
        )
        connections.save(config, password = Secret)
    }

    private fun client(options: McpOptions = McpOptions(), elicitation: ((JsonObject) -> JsonObject)? = null): TestClient {
        val workspace = Workspace(
            options,
            connections = connections,
            settingsStore = settings,
            drivers = DriverLibrary(home.resolve("drivers")),
            history = QueryHistory(home.resolve("history.jsonl")),
            library = QueryLibrary(home.resolve("queries")),
            audit = AuditLog(home.resolve("mcp.log.jsonl")),
        )
        return TestClient(workspace, elicitation).also {
            clients += it
            it.initialize()
        }
    }

    @Test
    fun `initialize negotiates the version and lists every tool`() {
        val client = client()
        val tools = client.request("tools/list")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        listOf("list_connections", "describe_table", "query", "execute", "kv_scan", "doc_find", "rec_browse", "rec_execute", "list_saved_queries").forEach { assertTrue(it in tools, it) }
        assertEquals("2025-06-18", client.initialized["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("tablegrip", client.initialized["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing is served while MCP is off`() {
        settings.save(Settings(mcpEnabled = false))
        val client = client()
        val (text, error) = client.call("list_connections")
        assertTrue(error)
        assertTrue("Settings › MCP" in text)
        assertTrue(client.call("query", "connection" to "shop", "sql" to "SELECT 1").second)
    }

    @Test
    fun `connections are listed without secrets`() {
        val client = client()
        val (text, error) = client.call("list_connections")
        assertFalse(error)
        val connection = Json.parseToJsonElement(text).jsonObject["connections"]!!.jsonArray.single().jsonObject
        assertEquals("shop", connection["name"]!!.jsonPrimitive.content)
        assertEquals("schema and data", connection["dataAccess"]!!.jsonPrimitive.content)
        assertEquals("none", connection["writes"]!!.jsonPrimitive.content)
        assertFalse(client.transcript.any { Secret in it })
    }

    @Test
    fun `schema only connections refuse data`() {
        save(access = null)
        val client = client()
        val (tables, tablesError) = client.call("list_tables", "connection" to "shop")
        assertFalse(tablesError, tables)
        assertTrue("customers" in tables)
        val (describe, _) = client.call("describe_table", "connection" to "shop", "table" to "orders")
        assertTrue("customer_id" in describe && "customers" in describe)
        val (refused, error) = client.call("query", "connection" to "shop", "sql" to "SELECT * FROM customers")
        assertTrue(error)
        assertTrue("Schema and sample data" in refused)
    }

    @Test
    fun `query returns capped rows and refuses anything but one read`() {
        val client = client()
        val (text, error) = client.call("query", "connection" to "shop", "sql" to "SELECT id, name FROM customers ORDER BY id")
        assertFalse(error, text)
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(10, result["rowCount"]!!.jsonPrimitive.int)
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("Customer 1", result["rows"]!!.jsonArray[0].jsonArray[1].jsonPrimitive.content)

        assertTrue(client.call("query", "connection" to "shop", "sql" to "SELECT 1; DELETE FROM customers").second)
        assertTrue(client.call("query", "connection" to "shop", "sql" to "DELETE FROM customers WHERE id = 1").second)
        assertTrue(client.call("query", "connection" to "shop", "sql" to "PRAGMA writable_schema = 1").second)
        assertEquals("30", scalar("SELECT COUNT(*) FROM customers"))
    }

    @Test
    fun `sample, count and column values work without SQL`() {
        val client = client()
        val (sample, error) = client.call("sample_rows", "connection" to "shop", "table" to "customers", "where" to "tier = 'gold'", "limit" to 3)
        assertFalse(error, sample)
        assertEquals(3, Json.parseToJsonElement(sample).jsonObject["rowCount"]!!.jsonPrimitive.int)
        val (count, _) = client.call("count_rows", "connection" to "shop", "table" to "customers", "where" to "tier = 'gold'")
        assertEquals(10, Json.parseToJsonElement(count).jsonObject["count"]!!.jsonPrimitive.int)
        val (values, _) = client.call("column_values", "connection" to "shop", "table" to "customers", "column" to "tier")
        assertTrue("gold" in values && "basic" in values)
        assertTrue(client.call("sample_rows", "connection" to "shop", "table" to "customers", "where" to "1=1; DROP TABLE customers").second)
    }

    @Test
    fun `writes need the connection's opt-in`() {
        val client = client()
        val (refused, error) = client.call("execute", "connection" to "shop", "sql" to "UPDATE customers SET tier = 'x' WHERE id = 1")
        assertTrue(error)
        assertTrue("Agent writes" in refused)

        save(AiAccess.SchemaAndSamples, AgentWrites.Data)
        val (done, failed) = client.call("execute", "connection" to "shop", "sql" to "UPDATE customers SET tier = 'x' WHERE id = 1")
        assertFalse(failed, done)
        assertEquals(1, Json.parseToJsonElement(done).jsonObject["affectedRows"]!!.jsonPrimitive.int)
        assertEquals("x", scalar("SELECT tier FROM customers WHERE id = 1"))
        assertTrue("Data and schema" in client.call("execute", "connection" to "shop", "sql" to "DROP TABLE orders").first)
        assertTrue(client.call("execute", "connection" to "shop", "sql" to "SELECT 1").second)
    }

    @Test
    fun `read-only connections and the flag never write`() {
        save(AiAccess.SchemaAndSamples, AgentWrites.DataAndSchema, readOnly = true)
        assertTrue("read-only" in client().call("execute", "connection" to "shop", "sql" to "DELETE FROM orders WHERE id = 1").first)
        save(AiAccess.SchemaAndSamples, AgentWrites.DataAndSchema)
        assertTrue("--read-only" in client(McpOptions(readOnly = true)).call("execute", "connection" to "shop", "sql" to "DELETE FROM orders WHERE id = 1").first)
        assertEquals("3", scalar("SELECT COUNT(*) FROM orders"))
    }

    @Test
    fun `risky writes ask the user and stop when they can't or won't`() {
        save(AiAccess.SchemaAndSamples, AgentWrites.Data)
        val (refused, error) = client().call("execute", "connection" to "shop", "sql" to "DELETE FROM orders")
        assertTrue(error)
        assertTrue("approval" in refused)
        assertEquals("3", scalar("SELECT COUNT(*) FROM orders"))

        val declining = client(elicitation = { buildJsonObject { put("action", "decline") } })
        assertTrue("declined" in declining.call("execute", "connection" to "shop", "sql" to "DELETE FROM orders").first)
        assertEquals("3", scalar("SELECT COUNT(*) FROM orders"))

        var asked: JsonObject? = null
        val accepting = client(elicitation = { request -> asked = request; buildJsonObject { put("action", "accept"); putJsonObject("content") { put("confirm", true) } } })
        val (done, failed) = accepting.call("execute", "connection" to "shop", "sql" to "DELETE FROM orders")
        assertFalse(failed, done)
        assertTrue("DELETE FROM orders" in asked!!["message"]!!.jsonPrimitive.content)
        assertEquals("0", scalar("SELECT COUNT(*) FROM orders"))
    }

    @Test
    fun `production writes always ask`() {
        save(AiAccess.SchemaAndSamples, AgentWrites.Data, environment = Environment.Production)
        assertTrue("approval" in client().call("execute", "connection" to "shop", "sql" to "UPDATE orders SET total = 1 WHERE id = 1").first)
    }

    @Test
    fun `statements land in history and calls in the audit log`() {
        client().call("query", "connection" to "shop", "sql" to "SELECT COUNT(*) FROM orders")
        val history = home.resolve("history.jsonl").readText()
        assertTrue("SELECT COUNT(*) FROM orders" in history)
        assertTrue("mcp:test-client" in history)
        val audit = AuditLog(home.resolve("mcp.log.jsonl")).recent()
        assertEquals("query", audit.first().tool)
        assertTrue(audit.first().ok)
    }

    @Test
    fun `resources, prompts and saved queries`() {
        val client = client()
        val resources = client.request("resources/list")["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        assertTrue("tablegrip://connections/shop-id/schema" in resources)
        val schema = client.request("resources/read", buildJsonObject { put("uri", "tablegrip://connections/shop-id/schema") })
        assertTrue("customers" in schema.toString())
        val table = client.request("resources/read", buildJsonObject { put("uri", "tablegrip://connections/shop-id/tables/main/orders") })
        assertTrue("CREATE TABLE" in table.toString())

        val prompt = client.request("prompts/get", buildJsonObject { put("name", "write-query"); putJsonObject("arguments") { put("connection", "shop"); put("request", "top customers") } })
        assertTrue("top customers" in prompt.toString())

        assertFalse(client.call("save_query", "name" to "Gold customers", "sql" to "SELECT * FROM customers WHERE tier = 'gold'", "connection" to "shop").second)
        assertTrue(client.call("save_query", "name" to "Gold customers", "sql" to "SELECT 1").second)
        val (list, _) = client.call("list_saved_queries")
        assertTrue("mcp/Gold customers.sql" in list, list)
        assertTrue("tier = 'gold'" in client.call("get_saved_query", "path" to "mcp/Gold customers.sql").first)
    }

    @Test
    fun `unknown connections and tables are explained`() {
        val client = client()
        assertTrue("list_connections" in client.call("list_tables", "connection" to "nope").first)
        assertTrue("Did you mean: customers" in client.call("describe_table", "connection" to "shop", "table" to "customer").first)
        assertFalse(client.call("describe_table", "connection" to "SHOP", "table" to "main.orders").second)
    }

    private fun scalar(sql: String): String =
        DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } } }

    /** A client on pipes, like an MCP app with the server as its child process. */
    private class TestClient(workspace: Workspace, private val elicitation: ((JsonObject) -> JsonObject)?) : AutoCloseable {
        private val toServer = PipedOutputStream()
        private val serverIn = PipedInputStream(toServer, 1 shl 20)
        private val fromServer = PipedInputStream(1 shl 20)
        private val serverOut = PipedOutputStream(fromServer)
        private val reader: BufferedReader = fromServer.bufferedReader()
        private val responses = LinkedBlockingQueue<JsonObject>()
        val transcript = mutableListOf<String>()
        private var nextId = 1
        lateinit var initialized: JsonObject

        private val server = thread(isDaemon = true) { McpMain.serve(McpOptions(), serverIn, serverOut, workspace) }
        private val listener = thread(isDaemon = true) {
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                synchronized(transcript) { transcript += line }
                val message = Json.parseToJsonElement(line).jsonObject
                if (message["method"] != null && message["id"] != null) {
                    val answer = elicitation?.invoke(message["params"]!!.jsonObject) ?: buildJsonObject { put("action", "cancel") }
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", message["id"]!!); put("result", answer) })
                } else {
                    responses.put(message)
                }
            }
        }

        @Synchronized
        private fun send(message: JsonObject) {
            toServer.write((message.toString() + "\n").toByteArray())
            toServer.flush()
        }

        fun initialize() {
            initialized = request("initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("clientInfo") { put("name", "test-client"); put("version", "1") }
                putJsonObject("capabilities") { if (elicitation != null) putJsonObject("elicitation") { } }
            })
            send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
        }

        fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
            val id = nextId++
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            val response = assertNotNull(responses.poll(30, TimeUnit.SECONDS), "No response to $method")
            assertEquals(id, response["id"]!!.jsonPrimitive.int)
            response["error"]?.let { throw AssertionError("$method failed: $it") }
            return response["result"]!!.jsonObject
        }

        /** The tool's text and whether it is an error. */
        fun call(tool: String, vararg arguments: Pair<String, Any>): Pair<String, Boolean> {
            val result = request("tools/call", buildJsonObject {
                put("name", tool)
                put("arguments", JsonObject(arguments.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) as JsonElement }))
            })
            val text = (result["content"] as JsonArray).joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
            return text to result["isError"]!!.jsonPrimitive.boolean
        }

        override fun close() {
            runCatching { toServer.close() }
            server.join(5_000)
            listener.join(1_000)
        }
    }

    private companion object {
        const val Secret = "s3cret-Pa55word"
    }
}
