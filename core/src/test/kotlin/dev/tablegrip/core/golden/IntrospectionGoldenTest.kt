package dev.tablegrip.core.golden

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.metadata.TableStructure
import dev.tablegrip.core.sql.SqlSplitter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Records what `Database` (JDBC metadata, `StructureReader`, result reading) reports for the schemas in
 * `core/src/test/resources/structure-it/`, into `conformance/core/golden/db/introspection-<engine>.json`, so the Rust
 * drivers can be held to it. SQLite always runs; PostgreSQL runs with `-Pit.postgres=host:port` against a server
 * seeded with `postgres.sql` (password `pw`). `-Pconformance.record=true` rewrites the files; without it the test
 * fails when the output drifts.
 */
class IntrospectionGoldenTest {
    private val record = System.getProperty("conformance.record") == "true"

    @Test
    fun sqlite() = runBlocking {
        val file = Files.createTempFile("tg-introspection", ".db").toFile().apply { delete() }
        try {
            val config = ConnectionConfig(id = "golden", name = "golden", type = DatabaseType.SQLite, database = file.absolutePath)
            Database.connect(config, null).use { db ->
                val script = File(root.parentFile.parentFile, "core/src/test/resources/structure-it/sqlite.sql").readText()
                db.openSession().use { session ->
                    SqlSplitter(db.dialect.syntax).split(script).forEach { session.execute(it.text) }
                }
                compare("sqlite", dump(db, listOf("SELECT * FROM orders ORDER BY id", SqliteQueries)))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun postgres() = runBlocking {
        val (host, port) = System.getProperty("it.postgres")?.split(":") ?: return@runBlocking
        val config =
            ConnectionConfig(id = "golden", name = "golden", type = DatabaseType.PostgreSQL, host = host, port = port.toInt(), database = "postgres", user = "postgres")
        Database.connect(config, "pw").use { db ->
            val queries = listOf("SELECT * FROM shop.orders ORDER BY id", PostgresQueries)
            compare("postgres", dump(db, queries, namespaces = { it.schema == "shop" || it.schema == "public" }))
        }
    }

    private suspend fun dump(db: Database, queries: List<String>, namespaces: (Namespace) -> Boolean = { true }): JsonObject {
        val all = db.namespaces()
        return buildJsonObject {
            put("namespaces", JsonArray(all.map(::namespace)))
            put("objects", buildJsonArray {
                for (ns in all.filter(namespaces)) {
                    add(buildJsonObject {
                        put("namespace", namespace(ns))
                        val tables = db.tables(ns)
                        put("tables", JsonArray(tables.map { buildJsonObject { put("ref", ref(it.ref)); put("isView", it.isView); put("remarks", it.remarks) } }))
                        put("columnsOf", JsonObject(db.columnsOf(ns).mapValues { (_, cols) -> JsonArray(cols.map(::column)) }))
                        put("details", JsonArray(tables.map { details(db.tableDetails(it.ref)) }))
                        put("columns", JsonArray(tables.map { details(db.tableColumns(it.ref)) }))
                        put("structures", JsonArray(tables.map { structure(db.tableStructure(it.ref)) }))
                        put("relations", JsonArray(tables.map { t -> JsonArray(db.relations(t.ref).map { r ->
                            buildJsonObject { put("from", ref(r.fromTable)); put("fromColumns", strings(r.fromColumns)); put("to", ref(r.toTable)); put("toColumns", strings(r.toColumns)) }
                        }) }))
                    })
                }
            })
            put("queries", buildJsonArray {
                db.openSession().use { session ->
                    for (sql in queries) {
                        val result = session.execute(sql) as QueryResult.Rows
                        add(buildJsonObject {
                            put("sql", sql)
                            put("columns", JsonArray(result.columns.map { buildJsonObject { put("name", it.name); put("typeName", it.typeName); put("jdbcType", it.jdbcType) } }))
                            put("rows", JsonArray(result.rows.map { row -> JsonArray(row.map { JsonPrimitive(it) }) }))
                        })
                    }
                }
            })
        }
    }

    private fun compare(engine: String, actual: JsonObject) {
        val file = File(root, "golden/db/introspection-$engine.json")
        if (record) {
            file.parentFile.mkdirs()
            file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actual) + "\n")
        } else {
            assertEquals<JsonElement>(Json.parseToJsonElement(file.readText()), actual, "Re-record with -Pconformance.record=true if intended.")
        }
    }

    private fun namespace(ns: Namespace) = buildJsonObject { put("catalog", ns.catalog); put("schema", ns.schema) }

    private fun ref(t: TableRef) = buildJsonObject { put("namespace", namespace(t.namespace)); put("name", t.name) }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun column(c: ColumnInfo) =
        buildJsonObject {
            put("name", c.name)
            put("typeName", c.typeName)
            put("size", c.size)
            put("nullable", c.nullable)
            put("defaultValue", c.defaultValue)
            put("autoIncrement", c.autoIncrement)
            put("primaryKey", c.primaryKey)
            put("jdbcType", c.jdbcType)
            put("scale", c.scale)
        }

    private fun details(d: TableDetails) =
        buildJsonObject {
            put("table", ref(d.table))
            put("columns", JsonArray(d.columns.map(::column)))
            put("indexes", JsonArray(d.indexes.map { buildJsonObject { put("name", it.name); put("columns", strings(it.columns)); put("unique", it.unique) } }))
            put("foreignKeys", JsonArray(d.foreignKeys.map {
                buildJsonObject {
                    put("name", it.name)
                    put("columns", strings(it.columns))
                    put("referencedTable", ref(it.referencedTable))
                    put("referencedColumns", strings(it.referencedColumns))
                    put("onUpdate", it.onUpdate)
                    put("onDelete", it.onDelete)
                }
            }))
            put("primaryKeyName", d.primaryKeyName)
        }

    private fun structure(s: TableStructure) =
        buildJsonObject {
            put("details", details(s.details))
            put("constraints", JsonArray(s.constraints.map { buildJsonObject { put("name", it.name); put("kind", it.kind.name); put("columns", strings(it.columns)); put("definition", it.definition) } }))
            put("triggers", JsonArray(s.triggers.map {
                buildJsonObject { put("name", it.name); put("timing", it.timing); put("events", it.events); put("enabled", it.enabled); put("definition", it.definition) }
            }))
            put("ddl", s.ddl)
        }

    private companion object {
        const val SqliteQueries =
            "SELECT 1 AS i, 2.5 AS r, 'a' AS t, x'00ff' AS b, NULL AS n, cast(7 as text), 3000000000 AS big, 1.0 AS one, 0.25 AS q, COUNT(*) FROM orders"
        const val PostgresQueries =
            "SELECT 1 AS i, 2.5::numeric AS n, 2.5::float8 AS f, 'a'::text AS t, '\\x00ff'::bytea AS b, NULL AS z, true AS yes, " +
                "DATE '2024-02-03' AS d, TIMESTAMP '2024-02-03 04:05:06.5' AS ts, INTERVAL '1 day 2 hours' AS iv, ARRAY[1,2] AS arr, " +
                "'{\"a\": 1}'::jsonb AS j, 'b0a1d9d0-0f3e-4b1a-9b6c-6c1f1c0f9a11'::uuid AS u"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "conformance/core") }.firstOrNull { it.isDirectory }
                ?: error("conformance/core not found")
        }
    }
}
