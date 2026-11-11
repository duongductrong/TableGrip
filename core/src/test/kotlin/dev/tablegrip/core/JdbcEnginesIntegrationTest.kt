package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.driver.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Runs [RelationalContract] and [DialectSmoke] against engines reached through their own JDBC drivers. Skipped unless
 * the server's property is passed, e.g. `-Pit.db2=localhost:50000`. The servers these ran against:
 *
 * ```
 * # Db2 v12.1.5.0 (icr.io/db2_community/db2:latest, sha256:2de8151713c2…); takes several minutes to create testdb.
 * docker run -d --name dg-it-db2 --platform linux/amd64 --privileged -p 50000:50000 -e LICENSE=accept -e DB2INSTANCE=db2inst1 -e DB2INST1_PASSWORD=pw -e DBNAME=testdb icr.io/db2_community/db2:latest
 * # Informix 15.0.1.0.3 Developer Edition (sha256:8202d69ba567…); user informix, password in4mix.
 * docker run -d --name dg-it-informix --platform linux/amd64 -p 59088:9088 -e LICENSE=accept icr.io/informix/informix-developer-database:latest
 * # IRIS Community 2026.1.0.234 (intersystemsdc/iris-community:latest, sha256:228654b16930…). Its post-start hook fails,
 * # so iris-main runs directly; then unexpire _SYSTEM's password and set it to SYS in an `iris session IRIS -U %SYS`.
 * docker run -d --name dg-it-iris -p 51972:1972 --entrypoint /tini intersystemsdc/iris-community:latest -- /iris-main
 * docker run -d --name dg-it-hive -p 51000:10000 -e SERVICE_NAME=hiveserver2 apache/hive:4.0.1
 * docker run -d --name dg-it-spark -p 51001:10000 -e SPARK_NO_DAEMONIZE=true apache/spark:3.5.5 /opt/spark/sbin/start-thriftserver.sh
 * # Cloud Spanner emulator (gcr.io/cloud-spanner-emulator/emulator:latest, sha256:5f71fecd6330…).
 * docker run -d --name dg-it-spanner -p 59010:9010 gcr.io/cloud-spanner-emulator/emulator:latest
 * ```
 *
 * Each run gets its own database (Informix, Hive, Spark, Spanner), schema (Db2) or empties the default one (IRIS).
 */
class JdbcEnginesIntegrationTest {
    private val run = "dg_${System.currentTimeMillis()}"

    private fun config(property: String, type: DatabaseType, database: String, user: String, properties: Map<String, String> = emptyMap()): ConnectionConfig? =
        System.getProperty(property)?.split(":")?.let { (host, port) ->
            ConnectionConfig(id = "$property-$database", name = property, type = type, host = host, port = port.toInt(), database = database, user = user, properties = properties)
        }

    private suspend fun execute(config: ConnectionConfig, password: String?, vararg sql: String) =
        Database.connect(config, password, drivers = TestDrivers.library).use { db -> db.openSession().use { session -> sql.forEach { session.execute(it) } } }

    /** Drops what an earlier run left in a namespace every run shares. */
    private suspend fun dropTables(config: ConnectionConfig, password: String?, vararg names: String) =
        Database.connect(config, password, drivers = TestDrivers.library).use { db ->
            db.openSession().use { session -> names.forEach { runCatching { session.execute("DROP TABLE ${db.dialect.quoteIdentifier(it)}") } } }
        }

    /** A schema per run, made the session's current one by the driver's `currentSchema`. */
    @Test
    fun db2() = runBlocking<Unit> {
        val admin = config("it.db2", DatabaseType.Db2, "testdb", "db2inst1") ?: return@runBlocking
        val schema = run.uppercase()
        execute(admin, "pw", "CREATE SCHEMA $schema")
        Database.connect(admin.copy(properties = mapOf("currentSchema" to schema)), "pw", drivers = TestDrivers.library).use { db ->
            assertEquals(schema, db.namespaces().first().displayName)
            RelationalContract(db).run()
            DialectSmoke(db).run()
        }
    }

    /** A logged database per run, so transactions work. */
    @Test
    fun informix() = runBlocking<Unit> {
        val admin = config("it.informix", DatabaseType.Informix, "sysmaster", "informix", mapOf("INFORMIXSERVER" to "informix")) ?: return@runBlocking
        execute(admin, "in4mix", "CREATE DATABASE $run WITH LOG")
        Database.connect(admin.copy(database = run), "in4mix", drivers = TestDrivers.library).use { db ->
            RelationalContract(db).run()
            DialectSmoke(db, at = "DATETIME(2024-05-15 13:45:00) YEAR TO SECOND").run()
        }
    }

    /** The USER namespace's default schema, SQLUser, emptied first. */
    @Test
    fun iris() = runBlocking<Unit> {
        val config = config("it.iris", DatabaseType.Iris, "USER", "_SYSTEM") ?: return@runBlocking
        dropTables(config, "SYS", "users", "teams")
        Database.connect(config, "SYS", drivers = TestDrivers.library).use { db ->
            RelationalContract(db).run()
            // No TIMESTAMP '…' literal.
            DialectSmoke(db, at = "CAST('2024-05-15 13:45:00' AS TIMESTAMP)").run()
        }
    }

    /** A database per run; Hive tables have no enforced keys and, outside ACID tables, no transactions. */
    @Test
    fun hive() = hiveServer("it.hive", DatabaseType.Hive)

    @Test
    fun spark() = hiveServer("it.spark", DatabaseType.SparkSql)

    private fun hiveServer(property: String, type: DatabaseType) = runBlocking<Unit> {
        val admin = config(property, type, "default", "hive") ?: return@runBlocking
        // Test Connection names the engine behind the shared protocol.
        assertEquals(type, Database.test(admin.copy(type = if (type == DatabaseType.Hive) DatabaseType.SparkSql else DatabaseType.Hive), null, drivers = TestDrivers.library))
        execute(admin, null, "CREATE DATABASE $run")
        Database.connect(admin.copy(database = run), null, drivers = TestDrivers.library).use { db ->
            RelationalContract(db, intType = "INT", textType = "STRING", keys = false, transactions = false).run()
            DialectSmoke(db).run()
        }
    }

    /** The emulator creates the instance and a database per run when the driver asks it to. */
    @Test
    fun spanner() = runBlocking<Unit> {
        val config =
            config("it.spanner", DatabaseType.Spanner, "test-project/test-instance/${run.replace('_', '-')}", "", mapOf("autoConfigEmulator" to "true"))
                ?: return@runBlocking
        Database.connect(config, null, drivers = TestDrivers.library).use { db ->
            RelationalContract(db, intType = "INT64", textType = "STRING(100)").run()
            // A literal without a zone is in America/Los_Angeles; buckets are in UTC, as stored timestamps are.
            DialectSmoke(db, at = "TIMESTAMP '2024-05-15 13:45:00+00'").run()
            db.openSession().use { session ->
                val rows = session.execute("SELECT COUNT(*) FROM `users`") as QueryResult.Rows
                assertTrue(rows.rows.single().single() == "3")
            }
        }
    }
}
