package dev.datagrok.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcUrlsTest {
    private fun config(type: DatabaseType, vararg changes: (ConnectionConfig) -> ConnectionConfig) =
        changes.fold(ConnectionConfig(id = "id", name = "n", type = type, host = "db.local", port = 5555, database = "app")) { c, f -> f(c) }

    @Test
    fun `builds URLs in each driver's syntax`() {
        assertEquals("jdbc:postgresql://db.local:5555/app", JdbcUrls.build(config(DatabaseType.PostgreSQL)))
        assertEquals("jdbc:mysql://db.local:5555/app", JdbcUrls.build(config(DatabaseType.MySQL)))
        assertEquals("jdbc:mariadb://db.local:5555/app", JdbcUrls.build(config(DatabaseType.MariaDB)))
        // MySQL servers through MariaDB Connector/J, and the reverse.
        assertEquals("jdbc:mariadb://db.local:5555/app", JdbcUrls.build(config(DatabaseType.MySQL, { it.copy(driver = DriverId.MariaDB) })))
        assertEquals("jdbc:mysql://db.local:5555/app", JdbcUrls.build(config(DatabaseType.MariaDB, { it.copy(driver = DriverId.MySQL) })))
        // A driver that can't serve the type falls back to the default.
        assertEquals("jdbc:postgresql://db.local:5555/app", JdbcUrls.build(config(DatabaseType.PostgreSQL, { it.copy(driver = DriverId.MySQL) })))
    }

    @Test
    fun `hosts, IPv6, multiple hosts and encoded database names`() {
        assertEquals("jdbc:postgresql://[::1]:5432/my%20db", JdbcUrls.build(config(DatabaseType.PostgreSQL, { it.copy(host = "::1", port = 5432, database = "my db") })))
        assertEquals("jdbc:postgresql://h1:1,h2/app", JdbcUrls.build(config(DatabaseType.PostgreSQL, { it.copy(host = "h1:1, h2", port = null) })))
        assertEquals("jdbc:mysql://127.0.0.1:40000/app", JdbcUrls.build(config(DatabaseType.MySQL), via = "127.0.0.1" to 40000))
    }

    @Test
    fun `sqlite paths and shared in-memory databases`() {
        assertEquals("jdbc:sqlite:/tmp/a.db", JdbcUrls.build(config(DatabaseType.SQLite, { it.copy(database = "/tmp/a.db") })))
        assertEquals("jdbc:sqlite:file:datagrok-id?mode=memory&cache=shared", JdbcUrls.build(config(DatabaseType.SQLite, { it.copy(database = ":memory:") })))
    }

    @Test
    fun `raw URLs are adapted to the chosen driver and can be redirected through a tunnel`() {
        val raw = config(DatabaseType.MySQL, { it.copy(url = "jdbc:mysql://user@db.local:3306/app?useSSL=false") })
        assertEquals("jdbc:mysql://user@db.local:3306/app?useSSL=false", JdbcUrls.build(raw))
        assertEquals("jdbc:mysql://user@127.0.0.1:41000/app?useSSL=false", JdbcUrls.build(raw, via = "127.0.0.1" to 41000))
        assertEquals("jdbc:mysql://user@db.local:3306/app?useSSL=false&permitMysqlScheme", JdbcUrls.build(raw.copy(driver = DriverId.MariaDB)))
        assertFailsWith<java.sql.SQLException> {
            JdbcUrls.build(raw.copy(url = "jdbc:mysql:replication://a:1,b:2/db"), via = "127.0.0.1" to 1)
        }
    }

    @Test
    fun `every generated URL is accepted by the driver that will receive it`() {
        val cases =
            listOf(
                config(DatabaseType.PostgreSQL),
                config(DatabaseType.MySQL),
                config(DatabaseType.MySQL, { it.copy(driver = DriverId.MariaDB) }),
                config(DatabaseType.MariaDB),
                config(DatabaseType.MariaDB, { it.copy(driver = DriverId.MySQL) }),
                config(DatabaseType.MySQL, { it.copy(driver = DriverId.MariaDB, url = "jdbc:mysql://db.local/app") }),
                config(DatabaseType.SQLite, { it.copy(database = "/tmp/x.db") }),
            )
        for (case in cases) {
            val url = JdbcUrls.build(case)
            assertTrue(case.effectiveDriver.load().acceptsURL(url), "${case.effectiveDriver} should accept $url")
        }
        // Which is why the adaptation matters: MariaDB Connector/J refuses plain jdbc:mysql URLs.
        assertFalse(DriverId.MariaDB.load().acceptsURL("jdbc:mysql://db.local/app"))
    }

    @Test
    fun `driver properties add credentials, timeouts and translate ssl modes`() {
        val mysql = JdbcUrls.properties(config(DatabaseType.MySQL, { it.copy(user = "me", properties = mapOf("sslMode" to "verify-full")) }), "pw")
        assertEquals("me", mysql.getProperty("user"))
        assertEquals("pw", mysql.getProperty("password"))
        assertEquals("10000", mysql.getProperty("connectTimeout"))
        assertEquals("VERIFY_IDENTITY", mysql.getProperty("sslMode"))

        val maria = JdbcUrls.properties(config(DatabaseType.MariaDB, { it.copy(properties = mapOf("sslMode" to "REQUIRED", "connectTimeout" to "5000")) }), null)
        assertEquals("trust", maria.getProperty("sslMode"))
        assertEquals("5000", maria.getProperty("connectTimeout"), "user settings win")
        assertEquals(null, maria.getProperty("password"))

        assertEquals(null, JdbcUrls.properties(config(DatabaseType.PostgreSQL), null).getProperty("connectTimeout"), "pgjdbc already defaults to 10 s")
    }

    @Test
    fun `all bundled drivers load`() {
        DriverId.entries.forEach { assertTrue(it.version != null, "$it should load") }
    }
}
