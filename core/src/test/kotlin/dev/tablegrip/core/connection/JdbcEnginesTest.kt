package dev.tablegrip.core.connection

import dev.tablegrip.core.activity.ActivitySupport
import dev.tablegrip.core.backup.BackupSupport
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Engines reached through a JDBC driver of their own: cloud warehouses (Snowflake, Databricks, BigQuery, Spanner),
 * enterprise databases (Db2, SAP HANA, Vertica, Exasol, Informix, InterSystems IRIS), HiveServer2's engines and
 * Microsoft Access. Without a server.
 */
class JdbcEnginesTest {
    private val added =
        listOf(
            DatabaseType.Snowflake, DatabaseType.Databricks, DatabaseType.BigQuery, DatabaseType.Spanner, DatabaseType.Db2, DatabaseType.Hana,
            DatabaseType.Vertica, DatabaseType.Exasol, DatabaseType.Informix, DatabaseType.Iris, DatabaseType.Hive, DatabaseType.SparkSql,
            DatabaseType.Impala, DatabaseType.Kyuubi, DatabaseType.Access,
        )

    private fun config(type: DatabaseType, database: String, host: String = "db.local", properties: Map<String, String> = emptyMap()) =
        ConnectionConfig(id = "id", name = "n", type = type, host = host, port = type.defaultPort, database = database, properties = properties)

    private val users = TableRef(Namespace(null, "app"), "users")

    @Test
    fun `descriptors have their driver, port and dialect`() {
        assertEquals(
            listOf(443, 443, 443, 443, 50000, 30015, 5433, 8563, 9088, 1972, 10000, 10000, 21050, 10009, null),
            added.map { it.defaultPort },
        )
        added.forEach {
            assertSame(it, DatabaseType.byId(it.id))
            assertEquals(1, it.drivers.size, it.id)
            assertTrue(it.dialect != null && it.dialect !== SqlDialect.Generic, "${it.id} has a dialect of its own")
        }
        assertEquals(setOf(EngineFamily.Hive), listOf(DatabaseType.Hive, DatabaseType.SparkSql, DatabaseType.Impala, DatabaseType.Kyuubi).map { it.family }.toSet())
        assertEquals(DriverId.Hive, DatabaseType.Impala.drivers.single())
        assertSame(SqlDialect.SparkSql, DatabaseType.Databricks.dialect)
        assertSame(SqlDialect.Hive, SqlDialect.of(EngineFamily.Hive))
        assertTrue(DatabaseType.Access.isFile)
        assertEquals(added.size, added.map { it.id }.toSet().size)
    }

    @Test
    fun `capabilities are what each engine really has`() {
        listOf(DatabaseType.Databricks, DatabaseType.Hive, DatabaseType.SparkSql, DatabaseType.Impala, DatabaseType.Kyuubi).forEach {
            assertEquals(setOf(Capability.Explain), it.capabilities, it.id)
        }
        assertEquals(emptySet(), DatabaseType.BigQuery.capabilities, "BigQuery has no EXPLAIN and no keys to edit by")
        listOf(DatabaseType.Spanner, DatabaseType.Db2, DatabaseType.Hana, DatabaseType.Exasol, DatabaseType.Informix, DatabaseType.Iris, DatabaseType.Access).forEach {
            assertEquals(setOf(Capability.Transactions, Capability.EditRows), it.capabilities, it.id)
            // Without Explain, the dialect never has to write a plan statement.
            assertFailsWith<UnsupportedOperationException>(it.id) { it.dialect!!.explain("SELECT 1") }
        }
        assertEquals(Capability.AllRelational, DatabaseType.Snowflake.capabilities)
        assertEquals(Capability.AllRelational, DatabaseType.Vertica.capabilities)
    }

    @Test
    fun `server tools aren't offered yet, and Access has no server`() {
        added.filter { it.isServer }.forEach {
            assertIs<ActivitySupport.NotYet>(ActivitySupport.of(it), it.id)
            assertIs<BackupSupport.NotYet>(BackupSupport.of(it), it.id)
        }
        assertEquals(ActivitySupport.NotApplicable, ActivitySupport.of(DatabaseType.Access))
        assertEquals(ActivitySupport.NotApplicable, ActivitySupport.of(DatabaseType.H2))
    }

    @Test
    fun `drivers download only where every bundled license allows it`() {
        assertEquals(listOf("hive", "spanner", "ucanaccess"), listOf(DriverId.Hive, DriverId.Spanner, DriverId.UCanAccess).map { it.catalogId })
        listOf(
            DriverId.Snowflake, DriverId.Databricks, DriverId.BigQuery, DriverId.Db2, DriverId.Hana, DriverId.Vertica, DriverId.Exasol, DriverId.Informix,
            DriverId.Iris,
        ).forEach {
            assertNull(it.catalogId, it.name)
            assertNotNull(it.vendorUrl, it.name)
            assertNotNull(it.license, it.name)
        }
    }

    @Test
    fun `URLs follow each driver's syntax`() {
        assertEquals("jdbc:snowflake://acme.snowflakecomputing.com:443/?db=SALES&schema=PUBLIC", JdbcUrls.build(config(DatabaseType.Snowflake, "SALES/PUBLIC", "acme.snowflakecomputing.com")))
        assertEquals("jdbc:snowflake://acme.snowflakecomputing.com:443/", JdbcUrls.build(config(DatabaseType.Snowflake, "", "acme.snowflakecomputing.com")))
        assertEquals(
            "jdbc:databricks://adb-1.azuredatabricks.net:443/default;httpPath=/sql/1.0/warehouses/abc",
            JdbcUrls.build(config(DatabaseType.Databricks, "/sql/1.0/warehouses/abc", "adb-1.azuredatabricks.net")),
        )
        assertEquals("jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=acme;DefaultDataset=sales", JdbcUrls.build(config(DatabaseType.BigQuery, "acme/sales", "")))
        assertEquals("jdbc:cloudspanner:/projects/p/instances/i/databases/d", JdbcUrls.build(config(DatabaseType.Spanner, "p/i/d", "")))
        assertEquals(
            "jdbc:cloudspanner://localhost:9010/projects/p/instances/i/databases/d",
            JdbcUrls.build(config(DatabaseType.Spanner, "projects/p/instances/i/databases/d", "localhost:9010")),
        )
        assertEquals("jdbc:db2://db.local:50000/SAMPLE", JdbcUrls.build(config(DatabaseType.Db2, "SAMPLE")))
        assertEquals("jdbc:sap://db.local:30015/?databaseName=HXE", JdbcUrls.build(config(DatabaseType.Hana, "HXE")))
        assertEquals("jdbc:sap://db.local:30015/", JdbcUrls.build(config(DatabaseType.Hana, "")))
        assertEquals("jdbc:vertica://db.local:5433/analytics", JdbcUrls.build(config(DatabaseType.Vertica, "analytics")))
        assertEquals("jdbc:exa:db.local:8563;schema=RETAIL", JdbcUrls.build(config(DatabaseType.Exasol, "RETAIL")))
        assertEquals("jdbc:exa:127.0.0.1:40000", JdbcUrls.build(config(DatabaseType.Exasol, ""), via = "127.0.0.1" to 40000))
        assertEquals("jdbc:informix-sqli://db.local:9088/stores", JdbcUrls.build(config(DatabaseType.Informix, "stores")))
        assertEquals("jdbc:IRIS://db.local:1972/USER", JdbcUrls.build(config(DatabaseType.Iris, "USER")))
        assertEquals("jdbc:hive2://db.local:10000/default", JdbcUrls.build(config(DatabaseType.Hive, "default")))
        assertEquals("jdbc:hive2://db.local:21050/sales", JdbcUrls.build(config(DatabaseType.Impala, "sales")))
        assertEquals("jdbc:ucanaccess:///data/shop.accdb", JdbcUrls.build(config(DatabaseType.Access, "/data/shop.accdb")))
        assertFailsWith<java.sql.SQLException> { JdbcUrls.build(config(DatabaseType.Access, "")) }
        // Each scheme names its driver, for profiles that keep a raw URL.
        assertEquals(DriverId.Iris, DriverId.forUrl("jdbc:IRIS://h:1972/USER"))
        assertEquals(DriverId.Hive, DriverId.forUrl("jdbc:hive2://h:10000"))
    }

    @Test
    fun `driver properties carry each engine's defaults`() {
        val databricks = JdbcUrls.properties(config(DatabaseType.Databricks, "/sql/1.0/warehouses/abc"), "dapi123")
        assertEquals("token", databricks.getProperty("UID"))
        assertEquals("dapi123", databricks.getProperty("PWD"))
        assertEquals("3", databricks.getProperty("AuthMech"))
        assertEquals("1", databricks.getProperty("ssl"))
        val snowflake = JdbcUrls.properties(config(DatabaseType.Snowflake, "SALES"), null)
        assertEquals("JSON", snowflake.getProperty("JDBC_QUERY_RESULT_FORMAT"))
        assertEquals("true", snowflake.getProperty("CLIENT_METADATA_REQUEST_USE_CONNECTION_CTX"))
        assertEquals("3", JdbcUrls.properties(config(DatabaseType.BigQuery, "acme"), null).getProperty("OAuthType"))
        assertEquals("true", JdbcUrls.properties(config(DatabaseType.Db2, "SAMPLE"), null).getProperty("retrieveMessagesFromServerOnGetMessage"))
        assertEquals("y", JdbcUrls.properties(config(DatabaseType.Informix, "stores"), null).getProperty("DELIMIDENT"))
        assertEquals("n", JdbcUrls.properties(config(DatabaseType.Informix, "stores", properties = mapOf("DELIMIDENT" to "n")), null).getProperty("DELIMIDENT"), "user settings win")
        assertEquals("TableGrip", JdbcUrls.properties(config(DatabaseType.Exasol, ""), null).getProperty("clientname"))
    }

    @Test
    fun `connection strings name the engine and keep the password out of the URL`() {
        val expected =
            mapOf(
                "jdbc:snowflake://acme.snowflakecomputing.com/?db=SALES" to DatabaseType.Snowflake,
                "jdbc:databricks://adb.net:443/default;httpPath=/sql/1;AuthMech=3;UID=token;PWD=dapi123" to DatabaseType.Databricks,
                "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=acme" to DatabaseType.BigQuery,
                "jdbc:cloudspanner:/projects/p/instances/i/databases/d" to DatabaseType.Spanner,
                "jdbc:db2://h:50000/SAMPLE" to DatabaseType.Db2,
                "jdbc:sap://h:30015/?databaseName=HXE" to DatabaseType.Hana,
                "jdbc:vertica://h:5433/db" to DatabaseType.Vertica,
                "jdbc:exa:h:8563;schema=S" to DatabaseType.Exasol,
                "jdbc:informix-sqli://h:9088/stores:INFORMIXSERVER=ol" to DatabaseType.Informix,
                "jdbc:IRIS://h:1972/USER" to DatabaseType.Iris,
                "jdbc:ucanaccess:///data/shop.accdb" to DatabaseType.Access,
                "jdbc:hive2://h:10000/default" to DatabaseType.Hive,
                "jdbc:kyuubi://h:10009/" to DatabaseType.Kyuubi,
            )
        for ((url, type) in expected) {
            val parsed = ConnectionString.parse(url)
            assertEquals(type, parsed.type, url)
            assertEquals(type.drivers.single(), parsed.driver, url)
        }
        val databricks = ConnectionString.parse("jdbc:databricks://adb.net:443/default;httpPath=/sql/1;PWD=dapi123;AuthMech=3")
        assertEquals("dapi123", databricks.password)
        assertEquals("jdbc:databricks://adb.net:443/default;httpPath=/sql/1;AuthMech=3", databricks.url)
    }

    @Test
    fun `paging follows each engine's syntax`() {
        val byName = listOf(SortColumn("name", SortDirection.Asc))
        assertEquals("SELECT * FROM \"app\".\"users\" ORDER BY \"name\" ASC LIMIT 10 OFFSET 20", SqlDialect.Snowflake.selectPage(users, null, byName, 10, 20))
        assertEquals("SELECT SKIP 20 FIRST 10 * FROM \"app\".\"users\" WHERE x > 1", SqlDialect.Informix.selectPage(users, "x > 1", emptyList(), 10, 20))
        assertEquals("SELECT * FROM `app`.`users` LIMIT 20, 10", SqlDialect.Hive.selectPage(users, null, emptyList(), 10, 20))
        assertEquals("SELECT * FROM `app`.`users` LIMIT 10 OFFSET 20", SqlDialect.SparkSql.selectPage(users, null, emptyList(), 10, 20))
        assertEquals("SELECT * FROM `app`.`users` ORDER BY 1 LIMIT 10 OFFSET 20", SqlDialect.Impala.selectPage(users, null, emptyList(), 10, 20))
        assertEquals("SELECT * FROM \"app\".\"users\" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY", SqlDialect.Db2.selectPage(users, null, emptyList(), 10, 20))
        assertEquals("SELECT * FROM [app].[users] LIMIT 10 OFFSET 20", SqlDialect.Access.selectPage(users, null, emptyList(), 10, 20))
        // Unity Catalog's three-part names; Spanner's default schema has no name.
        assertEquals("`main`.`sales`.`orders`", SqlDialect.SparkSql.qualifiedName(TableRef(Namespace("main", "sales"), "orders")))
        assertEquals("`orders`", SqlDialect.Spanner.qualifiedName(TableRef(Namespace(null, ""), "orders")))
        assertEquals("main", Namespace(null, "").displayName)
    }

    @Test
    fun `text and regex filters use each engine's operators`() {
        assertEquals("x ILIKE " to " ESCAPE '!'", SqlDialect.Snowflake.textMatch("x", caseSensitive = false, negated = false))
        assertEquals("REGEXP_INSTR(x, " to ", 1, 1, 0, 'i') > 0", SqlDialect.Snowflake.regexMatch("x", caseSensitive = false, negated = false))
        assertEquals("NOT x RLIKE concat('(?i)', " to ")", SqlDialect.SparkSql.regexMatch("x", caseSensitive = false, negated = true))
        assertEquals("REGEXP_CONTAINS(x, " to ")", SqlDialect.BigQuery.regexMatch("x", caseSensitive = true, negated = false))
        assertEquals("x NOT LIKE_REGEXPR " to " FLAG 'i'", SqlDialect.Hana.regexMatch("x", caseSensitive = false, negated = true))
        assertEquals("NOT REGEXP_INSTR(x, " to ") > 0", SqlDialect.Exasol.regexMatch("x", caseSensitive = true, negated = true))
        // No ESCAPE clause on Hive or GoogleSQL: a backslash escapes wildcards.
        assertEquals("lower(x) LIKE lower(" to ")", SqlDialect.Hive.textMatch("x", caseSensitive = false, negated = false))
        assertEquals("50\\% off", SqlDialect.BigQuery.textPattern("50!% off", caseSensitive = true))
        assertEquals("a\\_b%", SqlDialect.Spanner.samplePattern("a_b"))
        listOf(SqlDialect.Informix, SqlDialect.Iris, SqlDialect.Access).forEach { assertTrue(!it.supportsRegex, "$it") }
    }

    @Test
    fun `every dialect rounds dates and has its own text cast`() {
        val dialects = added.mapNotNull { it.dialect }.distinct()
        for (dialect in dialects) for (bucket in TimeBucket.entries) {
            assertEquals(true, dialect.timeBucket("x", bucket)?.contains("x"), "$dialect $bucket")
        }
        assertEquals("DATE_TRUNC(x, WEEK(MONDAY))", SqlDialect.BigQuery.timeBucket("x", TimeBucket.Week))
        assertEquals("TIMESTAMP_TRUNC(x, MONTH, 'UTC')", SqlDialect.Spanner.timeBucket("x", TimeBucket.Month))
        assertEquals("TRUNC_TIMESTAMP(x, 'IW')", SqlDialect.Db2.timeBucket("x", TimeBucket.Week))
        assertEquals("CAST(x AS STRING)", SqlDialect.SparkSql.castToText("x"))
        assertEquals("CHARACTER_LENGTH(x, CODEUNITS32)", SqlDialect.Db2.selectFunction(SelectFunction.Length, "x"))
        assertEquals("LEN(x)", SqlDialect.Access.selectFunction(SelectFunction.Length, "x"))
        assertEquals("[odd name]", SqlDialect.Access.quoteIdentifier("odd name"))
        assertEquals("`it``s`", SqlDialect.SparkSql.quoteIdentifier("it`s"))
        assertEquals("`it\\`s`", SqlDialect.BigQuery.quoteIdentifier("it`s"))
        assertEquals("'it\\'s'", SqlDialect.BigQuery.literal("it's", numeric = false))
    }

    @Test
    fun `namespaces leave out each engine's system schemas`() {
        assertEquals("public", SqlDialect.Vertica.defaultNamespace(listOf("public", "store"), "db"))
        assertEquals("SALES", SqlDialect.Snowflake.defaultNamespace(listOf("PUBLIC", "SALES"), "DB/SALES"))
        assertEquals("PUBLIC", SqlDialect.Snowflake.defaultNamespace(listOf("PUBLIC", "SALES"), "DB"))
        assertEquals("analytics", SqlDialect.Hive.defaultNamespace(listOf("default", "analytics"), "analytics"))
        assertEquals("SQLUser", SqlDialect.Iris.defaultNamespace(listOf("Ens_Config", "SQLUser"), "USER"))
        assertEquals("sales", SqlDialect.BigQuery.defaultNamespace(listOf("logs", "sales"), "acme/sales"))
    }
}
