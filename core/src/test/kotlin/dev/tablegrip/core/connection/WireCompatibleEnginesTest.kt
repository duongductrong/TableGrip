package dev.tablegrip.core.connection

import dev.tablegrip.core.activity.ActivitySupport
import dev.tablegrip.core.backup.BackupSupport
import dev.tablegrip.core.tools.ToolFamily
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.SelectFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Engines that join an existing family over its wire protocol: Redshift and QuestDB (PostgreSQL), OceanBase, Doris
 * and StarRocks (MySQL), and Presto (Trino's SQL, its own driver). Without a server.
 */
class WireCompatibleEnginesTest {
    private val added =
        listOf(
            DatabaseType.Redshift, DatabaseType.QuestDB, DatabaseType.OceanBase, DatabaseType.Doris, DatabaseType.StarRocks, DatabaseType.Presto,
        )

    private fun config(type: DatabaseType, database: String) = ConnectionConfig(id = "id", name = "n", type = type, host = "db.local", port = type.defaultPort, database = database)

    @Test
    fun `descriptors join their family with the engine's port`() {
        assertEquals(
            listOf(EngineFamily.PostgreSQL, EngineFamily.PostgreSQL, EngineFamily.MySQL, EngineFamily.MySQL, EngineFamily.MySQL, EngineFamily.Trino),
            added.map { it.family },
        )
        assertEquals(listOf(5439, 8812, 2881, 9030, 9030, 8080), added.map { it.defaultPort })
        added.forEach { assertSame(it, DatabaseType.byId(it.id)) }
        assertSame(SqlDialect.Redshift, DatabaseType.Redshift.dialect)
        assertSame(SqlDialect.QuestDB, DatabaseType.QuestDB.dialect)
        assertSame(SqlDialect.MySQLAnalytics, DatabaseType.Doris.dialect)
        assertSame(SqlDialect.MySQLAnalytics, DatabaseType.StarRocks.dialect)
        assertSame(SqlDialect.MySQL, DatabaseType.OceanBase.dialect)
        assertSame(SqlDialect.Trino, DatabaseType.Presto.dialect)
        assertEquals(listOf(DriverId.Presto), DatabaseType.Presto.drivers)
        assertEquals("require", DatabaseType.Redshift.defaultProperties[DriverId.PostgreSQL]?.get("sslmode"))
    }

    @Test
    fun `analytics engines are query-only, OceanBase and Redshift edit rows`() {
        listOf(DatabaseType.QuestDB, DatabaseType.Doris, DatabaseType.StarRocks, DatabaseType.Presto).forEach {
            assertEquals(setOf(Capability.Explain), it.capabilities, it.id)
        }
        assertEquals(Capability.AllRelational, DatabaseType.OceanBase.capabilities)
        assertEquals(Capability.AllRelational, DatabaseType.Redshift.capabilities)
    }

    @Test
    fun `only engines with PostgreSQL's own catalog get pg_dump and the activity monitor`() {
        assertEquals(BackupSupport.WithTools(ToolFamily.PostgreSQL), BackupSupport.of(DatabaseType.PostgreSQL))
        assertEquals(BackupSupport.WithTools(ToolFamily.PostgreSQL), BackupSupport.of(DatabaseType.YugabyteDB))
        assertEquals(ActivitySupport.Supported, ActivitySupport.of(DatabaseType.TimescaleDB))
        (added + DatabaseType.CockroachDB).forEach {
            assertIs<BackupSupport.NotYet>(BackupSupport.of(it), it.id)
            assertIs<ActivitySupport.NotYet>(ActivitySupport.of(it), it.id)
        }
        assertTrue(SqlDialect.PostgreSQL.hasPostgresCatalog)
        assertFalse(SqlDialect.Redshift.hasPostgresCatalog || SqlDialect.QuestDB.hasPostgresCatalog || SqlDialect.CockroachDB.hasPostgresCatalog)
    }

    @Test
    fun `Presto URLs and properties follow Trino's, with Presto's own names`() {
        assertEquals("jdbc:presto://db.local:8080/hive/sales", JdbcUrls.build(config(DatabaseType.Presto, "hive/sales")))
        val props = JdbcUrls.properties(config(DatabaseType.Presto, "hive"), null)
        assertTrue(props.getProperty("user").isNotBlank(), "Presto always needs a user")
        assertEquals("TableGrip", props.getProperty("applicationNamePrefix"))
        assertEquals("offset_clause_enabled:true", props.getProperty("sessionProperties"))
        assertFalse(props.containsKey("source"), "Presto's driver rejects properties it doesn't know")
        assertEquals("TableGrip", JdbcUrls.properties(config(DatabaseType.Trino, "hive"), null).getProperty("source"))
        assertEquals("presto", DriverId.Presto.catalogId)
        assertEquals(DriverId.Presto, DriverId.forUrl("jdbc:presto://db:8080/hive"))
        val parsed = ConnectionString.parse("jdbc:presto://db:8080/hive?user=me&password=secret")
        assertEquals(DatabaseType.Presto, parsed.type)
        assertEquals("secret", parsed.password)
    }

    @Test
    fun `wire-compatible URLs use the family's driver`() {
        assertEquals("jdbc:postgresql://db.local:5439/dev", JdbcUrls.build(config(DatabaseType.Redshift, "dev")))
        assertEquals("jdbc:postgresql://db.local:8812/qdb", JdbcUrls.build(config(DatabaseType.QuestDB, "qdb")))
        assertEquals("jdbc:mariadb://db.local:9030/sales", JdbcUrls.build(config(DatabaseType.Doris, "sales")))
        assertEquals("jdbc:oceanbase://db.local:2881/test", JdbcUrls.build(config(DatabaseType.OceanBase, "test")))
        assertEquals(listOf(DriverId.OceanBase, DriverId.MySQL), DatabaseType.OceanBase.drivers)
        assertEquals("oceanbase", DriverId.OceanBase.catalogId)
    }

    @Test
    fun `Doris and StarRocks escape LIKE with a backslash`() {
        val d = SqlDialect.MySQLAnalytics
        assertEquals("x LIKE " to "", d.textMatch("x", caseSensitive = true, negated = false))
        assertEquals("LOWER(x) NOT LIKE LOWER(" to ")", d.textMatch("x", caseSensitive = false, negated = true))
        assertEquals("a\\_b%", d.samplePattern("a_b"))
        assertTrue(d is SqlDialect.MySQLDialect)
    }

    @Test
    fun `Redshift and QuestDB SQL`() {
        assertEquals("round(x, 2)", SqlDialect.QuestDB.selectFunction(SelectFunction.Round, "x", listOf("2")))
        assertEquals("CAST(x AS STRING)", SqlDialect.QuestDB.castToText("x"))
        // A backslash escapes LIKE wildcards; there's no ESCAPE clause.
        assertEquals("x ILIKE " to "", SqlDialect.QuestDB.textMatch("x", caseSensitive = false, negated = false))
        assertEquals("50\\% off\\!\\\\", SqlDialect.QuestDB.textPattern("50!% off!!\\", caseSensitive = true))
        assertEquals("a\\_b%", SqlDialect.QuestDB.samplePattern("a_b"))
        assertEquals("x !~ concat('(?i)', " to ")", SqlDialect.QuestDB.regexMatch("x", caseSensitive = false, negated = true))
        val trades = dev.tablegrip.core.metadata.TableRef(dev.tablegrip.core.metadata.Namespace(null, "public"), "trades")
        assertEquals("SELECT * FROM \"public\".\"trades\" LIMIT 20, 30", SqlDialect.QuestDB.selectPage(trades, null, emptyList(), 10, 20))
        assertTrue("getdate" in SqlDialect.Redshift.functions && "gen_random_uuid" !in SqlDialect.Redshift.functions)
        assertEquals("ROUND(CAST(x AS NUMERIC), 2)", SqlDialect.Redshift.selectFunction(SelectFunction.Round, "x", listOf("2")))
    }
}
