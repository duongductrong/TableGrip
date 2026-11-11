package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
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
 * docker run -d --name dg-it-mssql --platform linux/amd64 -p 51433:1433 -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=TableGrip#2026' mcr.microsoft.com/mssql/server:2022-latest
 * docker run -d --name dg-it-clickhouse -p 58123:8123 -e CLICKHOUSE_PASSWORD=pw clickhouse/clickhouse-server:25.3
 * docker run -d --name dg-it-oracle -p 51521:1521 -e ORACLE_PASSWORD=pw gvenzl/oracle-free:23-slim-faststart
 * docker run -d --name dg-it-firebird -p 53050:3050 -e FIREBIRD_ROOT_PASSWORD=pw firebirdsql/firebird:5
 * docker run -d --name dg-it-trino -p 58080:8080 trinodb/trino:476
 * docker run -d --name dg-it-yugabyte -p 55434:5433 yugabytedb/yugabyte:2.25.2.0-b359 bin/yugabyted start --background=false
 * docker run -d --name dg-it-questdb -p 58812:8812 questdb/questdb:10.2.0
 * docker run -d --name dg-it-starrocks -p 59030:9030 starrocks/allin1-ubuntu:4.1.7
 * docker run -d --name dg-it-oceanbase -p 52881:2881 -e MODE=mini oceanbase/oceanbase-ce:4.4.2-lts
 * docker run -d --name dg-it-presto -p 58081:8080 prestodb/presto:0.300
 * ```
 *
 * Oracle, Firebird, Trino, Presto and QuestDB have no CREATE DATABASE that fits: each run gets its own user, database file
 * or schema, or (QuestDB) starts from no tables.
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
        at("it.yugabyte", DatabaseType.PostgreSQL, "yugabyte", "yugabyte")?.let { kotlin.test.assertEquals(DatabaseType.YugabyteDB, Database.test(it, null)) }
        at("it.tidb", DatabaseType.MySQL, "test", "root")?.let { kotlin.test.assertEquals(DatabaseType.TiDB, Database.test(it, null)) }
        at("it.questdb", DatabaseType.PostgreSQL, "qdb", "admin")?.let { kotlin.test.assertEquals(DatabaseType.QuestDB, Database.test(it, "quest")) }
        at("it.starrocks", DatabaseType.MySQL, "", "root")?.let { kotlin.test.assertEquals(DatabaseType.StarRocks, Database.test(it, null)) }
        // MariaDB Connector/J can't connect to OceanBase at all; MySQL's own driver can.
        at("it.oceanbase", DatabaseType.MySQL, "test", "root")?.let { kotlin.test.assertEquals(DatabaseType.OceanBase, Database.test(it.copy(driver = dev.tablegrip.core.connection.DriverId.MySQL), null)) }
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
        server("it.mssql", DatabaseType.SqlServer, "master", "sa", "TableGrip#2026", mapOf("trustServerCertificate" to "true")) {
            RelationalContract(it).run()
            // EXPLAIN is a SHOWPLAN batch around the statement; the plan comes back as rows.
            it.openSession().use { session ->
                val results = it.dialect.explain("SELECT * FROM [users] WHERE [id] = 1").map { sql -> session.execute(sql) }
                val plan = results[1] as dev.tablegrip.core.driver.QueryResult.Rows
                kotlin.test.assertTrue(plan.rows.isNotEmpty())
            }
            // Brackets and GO survive the splitter, as consoles run scripts.
            val script = "CREATE TABLE [odd]]name] ([a b] INT)\nGO\nINSERT INTO [odd]]name] VALUES (1)\nGO 1\nSELECT * FROM [odd]]name]"
            val statements = dev.tablegrip.core.sql.SqlSplitter(it.dialect.syntax).split(script).map { s -> s.text }
            kotlin.test.assertEquals(3, statements.size)
            it.openSession().use { session -> statements.forEach { sql -> session.execute(sql) } }
        }

    @Test
    fun clickhouse() =
        server("it.clickhouse", DatabaseType.ClickHouse, "default", "default", "pw") {
            RelationalContract(it, intType = "Int32", textType = "String", tableSuffix = " ENGINE = MergeTree ORDER BY id", foreignKeys = false, transactions = false).run()
        }

    @Test
    fun yugabyte() = server("it.yugabyte", DatabaseType.YugabyteDB, "yugabyte", "yugabyte", null) {
            RelationalContract(it).run()
            DialectSmoke(it).run()
        }

    /** A user (that is, a schema) per run, made by SYSTEM in the default pluggable database. */
    @Test
    fun oracle() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.oracle")?.split(":") ?: return@runBlocking
        fun config(user: String) =
            ConnectionConfig(id = "it.oracle-$user", name = "oracle", type = DatabaseType.Oracle, host = host, port = port.toInt(), database = "FREEPDB1", user = user)
        val user = run.uppercase()
        Database.connect(config("system"), "pw").use { admin ->
            admin.openSession().use { session ->
                session.execute("CREATE USER $user IDENTIFIED BY pw QUOTA UNLIMITED ON USERS")
                session.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE TRIGGER TO $user")
            }
        }
        Database.connect(config(user), "pw").use { db ->
            RelationalContract(db, intType = "NUMBER(10)", textType = "VARCHAR2(100)").run()
            DialectSmoke(db).run()
            // The connected user's schema comes first, so it's the default; DBMS_METADATA writes the DDL.
            val namespace = db.namespaces().first()
            kotlin.test.assertEquals(user, namespace.displayName)
            val structure = db.tableStructure(dev.tablegrip.core.metadata.TableRef(namespace, "users"))
            kotlin.test.assertTrue("CREATE TABLE" in structure.ddl && "\"users\"" in structure.ddl, structure.ddl)
            // EXPLAIN PLAN, then DBMS_XPLAN reads it back.
            db.openSession().use { session ->
                val results = db.dialect.explain("SELECT * FROM \"users\"").map { sql -> session.execute(sql) }
                kotlin.test.assertTrue((results.last() as dev.tablegrip.core.driver.QueryResult.Rows).rows.isNotEmpty())
            }
        }
    }

    /** A database file per run, created by Jaybird on first connect. */
    @Test
    fun firebird() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.firebird")?.split(":") ?: return@runBlocking
        val config =
            ConnectionConfig(
                id = "it.firebird", name = "firebird", type = DatabaseType.Firebird, host = host, port = port.toInt(),
                database = "/var/lib/firebird/data/$run.fdb", user = "SYSDBA", properties = mapOf("createDatabaseIfNotExist" to "true"),
            )
        Database.connect(config, "pw").use { db ->
            RelationalContract(db).run()
            DialectSmoke(db).run()
            // Non-ASCII text survives the UTF8 connection character set TableGrip sets.
            db.openSession().use { session ->
                session.execute("CREATE TABLE \"notes\" (\"body\" VARCHAR(50))")
                session.execute("INSERT INTO \"notes\" VALUES ('Tiếng Việt ✓')")
                val rows = session.execute("SELECT \"body\" FROM \"notes\"") as dev.tablegrip.core.driver.QueryResult.Rows
                kotlin.test.assertEquals("Tiếng Việt ✓", rows.rows.single().single())
            }
        }
    }

    /** A schema per run in the memory catalog; tables there have no keys or transactions. */
    @Test
    fun trino() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.trino")?.split(":") ?: return@runBlocking
        fun config(database: String) =
            ConnectionConfig(id = "it.trino-$database", name = "trino", type = DatabaseType.Trino, host = host, port = port.toInt(), database = database, user = "it")
        Database.connect(config("memory"), null).use { it.openSession().use { session -> session.execute("CREATE SCHEMA memory.$run") } }
        Database.connect(config("memory/$run"), null).use { db ->
            RelationalContract(db, keys = false, transactions = false).run()
            DialectSmoke(db).run()
            kotlin.test.assertTrue(db.namespaces().all { it.catalog == "memory" })
        }
    }

    /** One database, `qdb`, emptied first. No keys, no transactions, no VARCHAR length. */
    @Test
    fun questdb() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.questdb")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "it.questdb", name = "questdb", type = DatabaseType.QuestDB, host = host, port = port.toInt(), database = "qdb", user = "admin")
        Database.connect(config, "quest").use { db ->
            // One database for every run: start from no tables.
            db.openSession().use { session -> listOf("users", "teams").forEach { session.execute("DROP TABLE IF EXISTS $it") } }
            RelationalContract(db, intType = "INT", textType = "VARCHAR", keys = false, transactions = false).run()
            DialectSmoke(db).run()
        }
    }


    @Test
    fun starrocks() =
        server("it.starrocks", DatabaseType.StarRocks, "", "root", null) {
            RelationalContract(it, intType = "INT", tableSuffix = " PROPERTIES (\"replication_num\" = \"1\")", keys = false, transactions = false).run()
            // No TIMESTAMP '…' literal here.
            DialectSmoke(it, at = "CAST('2024-05-15 13:45:00' AS DATETIME)").run()
        }

    @Test
    fun oceanbase() =
        server("it.oceanbase", DatabaseType.OceanBase, "test", "root", null) {
            RelationalContract(it).run()
            DialectSmoke(it).run()
        }

    /** A schema per run in the memory catalog, as for Trino, through Presto's own driver. */
    @Test
    fun presto() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.presto")?.split(":") ?: return@runBlocking
        fun config(database: String) =
            ConnectionConfig(id = "it.presto-$database", name = "presto", type = DatabaseType.Presto, host = host, port = port.toInt(), database = database, user = "it")
        Database.connect(config("memory"), null).use { it.openSession().use { session -> session.execute("CREATE SCHEMA memory.$run") } }
        Database.connect(config("memory/$run"), null).use { db ->
            RelationalContract(db, keys = false, transactions = false).run()
            DialectSmoke(db).run()
        }
    }
}
