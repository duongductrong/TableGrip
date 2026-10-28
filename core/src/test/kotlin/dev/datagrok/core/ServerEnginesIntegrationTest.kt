package dev.datagrok.core

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.driver.Database
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Runs [RelationalContract] against real servers, each in a database created for the run. Skipped unless the server's
 * property is passed, e.g. `-Pit.mssql=localhost:51433`. The Docker commands that start matching servers:
 *
 * ```
 * docker run -d --name dg-it-crdb -p 56257:26257 cockroachdb/cockroach:v24.3.5 start-single-node --insecure
 * docker run -d --name dg-it-timescale -p 55433:5432 -e POSTGRES_PASSWORD=pw timescale/timescaledb:latest-pg17
 * docker run -d --name dg-it-tidb -p 54000:4000 pingcap/tidb:v8.5.1
 * docker run -d --name dg-it-mssql --platform linux/amd64 -p 51433:1433 -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=Datagrok#2026' mcr.microsoft.com/mssql/server:2022-latest
 * docker run -d --name dg-it-clickhouse -p 58123:8123 -e CLICKHOUSE_PASSWORD=pw clickhouse/clickhouse-server:25.3
 * ```
 */
class ServerEnginesIntegrationTest {
    private val run = "dg_${System.currentTimeMillis()}"

    private fun server(
        property: String,
        type: DatabaseType,
        admin: String,
        user: String,
        password: String?,
        properties: Map<String, String> = emptyMap(),
        test: suspend (Database) -> Unit,
    ) = runBlocking<Unit> {
        val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
        fun config(database: String) =
            ConnectionConfig(id = "$property-$database", name = property, type = type, host = host, port = port.toInt(), database = database, user = user, properties = properties)
        Database.connect(config(admin), password).use { it.openSession().use { session -> session.execute("CREATE DATABASE $run") } }
        Database.connect(config(run), password).use { test(it) }
    }

    /** Test Connection names the real engine when a profile picked another in its family. */
    @Test
    fun `servers are recognised within their family`() = runBlocking<Unit> {
        fun at(property: String, type: DatabaseType, database: String, user: String) =
            System.getProperty(property)?.split(":")?.let { (host, port) ->
                ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), database = database, user = user)
            }
        at("it.cockroach", DatabaseType.PostgreSQL, "defaultdb", "root")?.let { kotlin.test.assertEquals(DatabaseType.CockroachDB, Database.test(it, null)) }
        at("it.timescale", DatabaseType.PostgreSQL, "postgres", "postgres")?.let { kotlin.test.assertEquals(DatabaseType.TimescaleDB, Database.test(it, "pw")) }
        at("it.tidb", DatabaseType.MySQL, "test", "root")?.let { kotlin.test.assertEquals(DatabaseType.TiDB, Database.test(it, null)) }
        at("it.mysql", DatabaseType.MariaDB, "mysql", "root")?.let { kotlin.test.assertEquals(DatabaseType.MySQL, Database.test(it, "pw")) }
        at("it.postgres", DatabaseType.PostgreSQL, "postgres", "postgres")?.let { kotlin.test.assertEquals(null, Database.test(it, "pw")) }
        // Hosted variants are taken at their word.
        at("it.postgres", DatabaseType.Neon, "postgres", "postgres")?.let { kotlin.test.assertEquals(null, Database.test(it.copy(properties = mapOf("sslmode" to "disable")), "pw")) }
    }

    @Test
    fun cockroach() = server("it.cockroach", DatabaseType.CockroachDB, "defaultdb", "root", null) { RelationalContract(it).run() }

    @Test
    fun timescale() = server("it.timescale", DatabaseType.TimescaleDB, "postgres", "postgres", "pw") { RelationalContract(it).run() }

    @Test
    fun tidb() = server("it.tidb", DatabaseType.TiDB, "test", "root", null) { RelationalContract(it).run() }

    @Test
    fun mssql() =
        server("it.mssql", DatabaseType.SqlServer, "master", "sa", "Datagrok#2026", mapOf("trustServerCertificate" to "true")) {
            RelationalContract(it).run()
            // EXPLAIN is a SHOWPLAN batch around the statement; the plan comes back as rows.
            it.openSession().use { session ->
                val results = it.dialect.explain("SELECT * FROM [users] WHERE [id] = 1").map { sql -> session.execute(sql) }
                val plan = results[1] as dev.datagrok.core.driver.QueryResult.Rows
                kotlin.test.assertTrue(plan.rows.isNotEmpty())
            }
            // Brackets and GO survive the splitter, as consoles run scripts.
            val script = "CREATE TABLE [odd]]name] ([a b] INT)\nGO\nINSERT INTO [odd]]name] VALUES (1)\nGO 1\nSELECT * FROM [odd]]name]"
            val statements = dev.datagrok.core.sql.SqlSplitter(it.dialect.syntax).split(script).map { s -> s.text }
            kotlin.test.assertEquals(3, statements.size)
            it.openSession().use { session -> statements.forEach { sql -> session.execute(sql) } }
        }

    @Test
    fun clickhouse() =
        server("it.clickhouse", DatabaseType.ClickHouse, "default", "default", "pw") {
            RelationalContract(it, intType = "Int32", textType = "String", tableSuffix = " ENGINE = MergeTree ORDER BY id", foreignKeys = false, transactions = false).run()
        }
}
