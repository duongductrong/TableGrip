package dev.tablegrip.core.connection

import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Oracle, Firebird, Trino and YugabyteDB: descriptors, URLs, properties, connection strings and SQL, without a server. */
class NewEnginesTest {
    private fun config(type: DatabaseType, database: String, host: String = "db.local", port: Int? = type.defaultPort) =
        ConnectionConfig(id = "id", name = "n", type = type, host = host, port = port, database = database)

    @Test
    fun `descriptors pick the right family, dialect, port and capabilities`() {
        assertSame(SqlDialect.Oracle, DatabaseType.Oracle.dialect)
        assertSame(SqlDialect.Firebird, DatabaseType.Firebird.dialect)
        assertSame(SqlDialect.Trino, DatabaseType.Trino.dialect)
        assertSame(SqlDialect.PostgreSQL, DatabaseType.YugabyteDB.dialect)
        assertEquals(listOf(1521, 3050, 8080, 5433), listOf(DatabaseType.Oracle, DatabaseType.Firebird, DatabaseType.Trino, DatabaseType.YugabyteDB).map { it.defaultPort })
        assertEquals(setOf(Capability.Explain), DatabaseType.Trino.capabilities)
        assertFalse(Capability.Explain in DatabaseType.Firebird.capabilities)
        assertEquals(Capability.AllRelational, DatabaseType.Oracle.capabilities)
        listOf("Oracle", "Firebird", "Trino", "YugabyteDB").forEach { assertSame(DatabaseType.byId(it), DatabaseType.entries.single { t -> t.id == it }) }
    }

    @Test
    fun `Oracle's driver is brought by the user, the others download`() {
        assertNull(DriverId.Oracle.catalogId)
        assertTrue(DriverId.Oracle.vendorUrl!!.startsWith("https://www.oracle.com/"))
        assertEquals("firebird", DriverId.Firebird.catalogId)
        assertEquals("trino", DriverId.Trino.catalogId)
    }

    @Test
    fun `URLs in each driver's syntax`() {
        assertEquals("jdbc:oracle:thin:@//db.local:1521/FREEPDB1", JdbcUrls.build(config(DatabaseType.Oracle, "FREEPDB1")))
        assertEquals("jdbc:oracle:thin:@//127.0.0.1:40000/ORCL", JdbcUrls.build(config(DatabaseType.Oracle, "ORCL"), via = "127.0.0.1" to 40000))
        assertEquals("jdbc:firebird://db.local:3050//var/lib/firebird/data/app.fdb", JdbcUrls.build(config(DatabaseType.Firebird, "/var/lib/firebird/data/app.fdb")))
        assertEquals("jdbc:firebird://db.local:3050/employee", JdbcUrls.build(config(DatabaseType.Firebird, "employee")))
        assertEquals("jdbc:trino://db.local:8080/hive/sales", JdbcUrls.build(config(DatabaseType.Trino, "hive/sales")))
        assertEquals("jdbc:trino://db.local:8080/memory", JdbcUrls.build(config(DatabaseType.Trino, "memory")))
        assertEquals("jdbc:trino://db.local:8080", JdbcUrls.build(config(DatabaseType.Trino, "")))
        assertEquals("jdbc:postgresql://db.local:5433/yugabyte", JdbcUrls.build(config(DatabaseType.YugabyteDB, "yugabyte")))
        // SSH tunnels redirect the host of each form.
        assertEquals("jdbc:trino://127.0.0.1:1/memory", JdbcUrls.redirect("jdbc:trino://db:8080/memory", "127.0.0.1", 1))
    }

    @Test
    fun `driver properties fill in what each driver needs`() {
        val firebird = JdbcUrls.properties(config(DatabaseType.Firebird, "x"), "pw")
        assertEquals("UTF8", firebird.getProperty("encoding"))
        val ownCharset = JdbcUrls.properties(config(DatabaseType.Firebird, "x").copy(properties = mapOf("charSet" to "Cp1252")), null)
        assertNull(ownCharset.getProperty("encoding"))
        val trino = JdbcUrls.properties(config(DatabaseType.Trino, "memory"), null)
        assertTrue(trino.getProperty("user").isNotBlank(), "Trino always needs a user")
        assertEquals("analyst", JdbcUrls.properties(config(DatabaseType.Trino, "memory").copy(user = "analyst"), null).getProperty("user"))
        assertEquals("TableGrip", JdbcUrls.properties(config(DatabaseType.Oracle, "ORCL"), null).getProperty("v\$session.program"))
    }

    @Test
    fun `connection strings name the engine`() {
        assertEquals(DatabaseType.Oracle, ConnectionString.parse("jdbc:oracle:thin:@//db:1521/ORCL").type)
        assertEquals(DatabaseType.Firebird, ConnectionString.parse("jdbc:firebirdsql://db:3050/employee").type)
        assertEquals(DatabaseType.Firebird, ConnectionString.parse("jdbc:firebird://db/employee").type)
        val trino = ConnectionString.parse("jdbc:trino://db:8080/hive?user=me&password=secret")
        assertEquals(DatabaseType.Trino, trino.type)
        assertEquals("secret", trino.password)
        assertFalse("secret" in trino.url!!)
    }

    private val users = TableRef(Namespace(null, "APP"), "users")
    private val byName = listOf(SortColumn("name", SortDirection.Asc))

    @Test
    fun `Oracle SQL`() {
        val d = SqlDialect.Oracle
        assertEquals("SELECT * FROM \"APP\".\"users\" ORDER BY \"name\" ASC OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY", d.selectPage(users, null, byName, 10, 20))
        assertEquals("TRUNC(d, 'IW')", d.timeBucket("d", TimeBucket.Week))
        assertEquals("NOT REGEXP_LIKE(x, " to ", 'i')", d.regexMatch("x", caseSensitive = false, negated = true))
        assertEquals("SUBSTR(x, 1, 2)", d.selectFunction(SelectFunction.Substring, "x", listOf("1", "2")))
        assertEquals(listOf("EXPLAIN PLAN FOR SELECT 1 FROM DUAL", "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())"), d.explain("SELECT 1 FROM DUAL"))
        assertTrue(d.plainIdentifier.matches("ORDER_ITEMS") && !d.plainIdentifier.matches("users"))
    }

    @Test
    fun `Firebird SQL`() {
        val d = SqlDialect.Firebird
        val table = TableRef(Namespace(null, null), "users")
        assertEquals("SELECT * FROM \"users\" OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY", d.selectPage(table, null, emptyList(), 5, 0))
        assertEquals("CHAR_LENGTH(x)", d.selectFunction(SelectFunction.Length, "x"))
        assertEquals("SUBSTRING(x FROM 2 FOR 3)", d.selectFunction(SelectFunction.Substring, "x", listOf("2", "3")))
        assertEquals("CAST(d AS DATE)", d.timeBucket("d", TimeBucket.Day))
        assertFalse(d.supportsRegex)
    }

    @Test
    fun `Trino SQL`() {
        val d = SqlDialect.Trino
        val table = TableRef(Namespace("hive", "sales"), "orders")
        assertEquals("SELECT * FROM \"hive\".\"sales\".\"orders\" ORDER BY \"name\" ASC OFFSET 5 LIMIT 10", d.selectPage(table, null, byName, 10, 5))
        assertEquals("date_trunc('month', d)", d.timeBucket("d", TimeBucket.Month))
        assertEquals("regexp_like(x, concat('(?i)', " to "))", d.regexMatch("x", caseSensitive = false, negated = false))
        assertEquals("sales", d.defaultNamespace(listOf("default", "sales"), "hive/sales"))
        assertEquals("default", d.defaultNamespace(listOf("default", "sales"), "hive"))
    }
}
