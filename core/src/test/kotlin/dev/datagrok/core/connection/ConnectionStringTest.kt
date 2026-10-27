package dev.datagrok.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cases follow the examples in the libpq, pgjdbc, Connector/J and MariaDB Connector/J documentation. */
class ConnectionStringTest {
    private fun parse(text: String) = ConnectionString.parse(text)

    @Test
    fun `libpq URIs with user info, port, database and parameters`() {
        val parsed = parse("postgresql://other:s%40cret@localhost:5433/otherdb?connect_timeout=10&application_name=myapp")
        assertEquals(DatabaseType.PostgreSQL, parsed.type)
        assertEquals("localhost", parsed.host)
        assertEquals(5433, parsed.port)
        assertEquals("otherdb", parsed.database)
        assertEquals("other", parsed.user)
        assertEquals("s@cret", parsed.password)
        assertEquals(mapOf("connectTimeout" to "10", "ApplicationName" to "myapp"), parsed.properties)
        assertNull(parsed.url)
    }

    @Test
    fun `libpq URIs accept the postgres scheme, bare forms and named parameters`() {
        assertEquals("localhost", parse("postgres://localhost").host)
        assertEquals(null, parse("postgresql://").host)
        val named = parse("postgresql:///mydb?host=localhost&port=5433")
        assertEquals("localhost", named.host)
        assertEquals(5433, named.port)
        assertEquals("mydb", named.database)
    }

    @Test
    fun `libpq multiple hosts, IPv6 and percent-encoded options`() {
        val multi = parse("postgresql://host1:123,host2:456/somedb?target_session_attrs=any&application_name=myapp")
        assertEquals("host1:123,host2:456", multi.host)
        assertNull(multi.port)
        assertEquals("any", multi.properties["targetServerType"])

        val ipv6 = parse("postgresql://[2001:db8::1234]/database")
        assertEquals("2001:db8::1234", ipv6.host)

        val options = parse("postgresql://user@localhost:5433/mydb?options=-c%20synchronous_commit%3Doff")
        assertEquals("-c synchronous_commit=off", options.properties["options"])
    }

    @Test
    fun `libpq ssl=true means sslmode=require, sockets fall back to localhost`() {
        assertEquals("require", parse("postgresql://db.example.com/app?ssl=true").properties["sslmode"])
        val socket = parse("postgresql://%2Fvar%2Flib%2Fpostgresql/dbname")
        assertEquals("localhost", socket.host)
        assertTrue(socket.warnings.any { "Unix-domain" in it })
    }

    @Test
    fun `libpq keyword-value strings with quoting and escapes`() {
        val parsed = parse("""host=localhost port=5432 dbname='my db' user=me password='it\'s \\ ok' connect_timeout=10 sslmode=verify-full""")
        assertEquals("localhost", parsed.host)
        assertEquals(5432, parsed.port)
        assertEquals("my db", parsed.database)
        assertEquals("it's \\ ok", parsed.password)
        assertEquals(mapOf("connectTimeout" to "10", "sslmode" to "verify-full"), parsed.properties)
    }

    @Test
    fun `pgjdbc URL forms`() {
        assertEquals("accounting", parse("jdbc:postgresql:accounting").database)
        val full = parse("jdbc:postgresql://[::1]:5740/accounting?user=fred&password=p+w&sslmode=require&currentSchema=app")
        assertEquals(DriverId.PostgreSQL, full.driver)
        assertEquals("::1", full.host)
        assertEquals(5740, full.port)
        assertEquals("fred", full.user)
        assertEquals("p+w", full.password, "a plus is not a space outside form encoding")
        assertEquals(mapOf("sslmode" to "require", "currentSchema" to "app"), full.properties)
        assertEquals("host1:5432,host2:5433", parse("jdbc:postgresql://host1:5432,host2:5433/db").host)
    }

    @Test
    fun `mysql and mariadb URIs`() {
        val mysql = parse("mysql://sandy:secret@db.example.com:3307/sakila?ssl-mode=required&serverTimezone=UTC")
        assertEquals(DatabaseType.MySQL, mysql.type)
        assertNull(mysql.driver)
        assertEquals("db.example.com", mysql.host)
        assertEquals(3307, mysql.port)
        assertEquals("sakila", mysql.database)
        assertEquals("sandy", mysql.user)
        assertEquals("secret", mysql.password)
        assertEquals(mapOf("sslMode" to "REQUIRED", "serverTimezone" to "UTC"), mysql.properties)

        val jdbc = parse("jdbc:mariadb://localhost:3306/shop?user=root&sslMode=verify-full")
        assertEquals(DatabaseType.MariaDB, jdbc.type)
        assertEquals(DriverId.MariaDB, jdbc.driver)
        assertEquals("root", jdbc.user)
        assertEquals(mapOf("sslMode" to "verify-full"), jdbc.properties)

        assertEquals(DriverId.MySQL, parse("jdbc:mysql://host1:33060/sakila").driver)
    }

    @Test
    fun `advanced Connector-J and MariaDB forms are kept as URLs without the password`() {
        val replication = parse("jdbc:mysql:replication://sandy:secret@primary:3306,replica:3306/db?password=other&useSSL=false")
        assertEquals("jdbc:mysql:replication://sandy@primary:3306,replica:3306/db?useSSL=false", replication.url)
        assertEquals("sandy", replication.user)
        assertEquals("other", replication.password, "the query password is the last one removed")

        val keyValue = parse("jdbc:mysql://address=(host=myhost)(port=1111)(key1=value1)/db")
        assertEquals("jdbc:mysql://address=(host=myhost)(port=1111)(key1=value1)/db", keyValue.url)

        val sequential = parse("jdbc:mariadb:sequential://host1,host2/db")
        assertEquals(DatabaseType.MariaDB, sequential.type)
        assertEquals("jdbc:mariadb:sequential://host1,host2/db", sequential.url)
    }

    @Test
    fun `sqlite URLs and paths`() {
        assertEquals("/Users/me/app.db", parse("jdbc:sqlite:/Users/me/app.db").database)
        assertEquals(":memory:", parse("jdbc:sqlite::memory:").database)
        assertEquals("/abs/data.sqlite", parse("sqlite:///abs/data.sqlite").database)
        assertEquals("/abs/data.sqlite", parse("sqlite:////abs/data.sqlite").database)
        assertEquals(":memory:", parse("sqlite://").database)
        assertEquals("/tmp/my db.db", parse("file:///tmp/my%20db.db").database)
        assertEquals(System.getProperty("user.home") + "/data/app.sqlite3", parse("~/data/app.sqlite3").database)
        assertEquals(DatabaseType.SQLite, parse("./local.db").type)
    }

    @Test
    fun `rejects unsupported strings with a helpful message`() {
        assertFailsWith<ConnectionStringException> { parse("mysqlx://host/db") }
        assertFailsWith<ConnectionStringException> { parse("jdbc:oracle:thin:@host:1521:orcl") }
        assertFailsWith<ConnectionStringException> { parse("hello world") }
        assertFailsWith<ConnectionStringException> { parse("postgresql://host:99999/db") }
    }

    @Test
    fun `strips passwords from user info and query`() {
        assertEquals("jdbc:postgresql://u@h/db?ssl=true" to "p@ss", ConnectionString.stripSecrets("jdbc:postgresql://u:p%40ss@h/db?ssl=true"))
        assertEquals("jdbc:mysql://h/db" to "x", ConnectionString.stripSecrets("jdbc:mysql://h/db?password=x"))
        assertEquals("jdbc:mysql://h/db" to null, ConnectionString.stripSecrets("jdbc:mysql://h/db"))
    }
}
