package dev.datagrok.core.backup

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.driver.Database
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.tools.Tool
import dev.datagrok.core.tools.ToolFamily
import dev.datagrok.core.tools.ToolLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BackupTest {
    private val server = ServerEndpoint("db.internal", 5432, "app", "s3cret 'quoted'", mapOf("sslmode" to "require"))

    @Test
    fun support() {
        assertEquals(BackupSupport.WithTools(ToolFamily.PostgreSQL), BackupSupport.of(DatabaseType.Supabase))
        assertEquals(BackupSupport.WithTools(ToolFamily.MySQL), BackupSupport.of(DatabaseType.MariaDB))
        assertEquals(BackupSupport.Native(BackupFormat.SqliteCopy), BackupSupport.of(DatabaseType.SQLite))
        assertIs<BackupSupport.NotYet>(BackupSupport.restoreOf(DatabaseType.SQLite))
        assertIs<BackupSupport.NotYet>(BackupSupport.of(DatabaseType.CockroachDB))
        assertIs<BackupSupport.NotYet>(BackupSupport.of(DatabaseType.SqlServer))
        assertEquals(BackupSupport.NotApplicable, BackupSupport.of(DatabaseType.Redis))
    }

    @Test
    fun secretsNeverReachTheCommandLine() {
        val dump = BackupCommands.pgDump(Path.of("/bin/pg_dump"), server, BackupOptions(BackupFormat.PgCustom, Path.of("/tmp/a.dump"), "shop", noOwner = true, schemas = listOf("public")))
        assertTrue(dump.arguments.none { "s3cret" in it })
        assertEquals("s3cret 'quoted'", dump.environment["PGPASSWORD"])
        assertEquals("require", dump.environment["PGSSLMODE"])
        assertEquals(
            "PGCONNECT_TIMEOUT=10 PGSSLMODE=require /bin/pg_dump --host=db.internal --port=5432 --username=app --dbname=shop --no-password --format=custom " +
                "--no-owner --schema=public --verbose --file=/tmp/a.dump",
            dump.display,
        )

        val secrets = Path.of("/tmp/opts.cnf")
        val mysql = BackupCommands.mysqlDump(Path.of("/bin/mysqldump"), server.copy(port = 3306), BackupOptions(BackupFormat.MySqlSql, Path.of("/tmp/a.sql"), "shop"), secrets)
        assertTrue(mysql.arguments.none { "s3cret" in it })
        assertEquals("--defaults-extra-file=/tmp/opts.cnf", mysql.arguments[1], "option files must come first")
        assertEquals("[client]\nuser=\"app\"\npassword=\"s3cret 'quoted'\"\n", mysql.secretFiles.getValue(secrets))
        assertFalse("s3cret" in mysql.display)
    }

    @Test
    fun parsesRestoreListings() {
        val listing =
            """
            ;
            ; Archive created at 2026-10-31 10:00:00 +07
            ;
            5; 2615 2200 SCHEMA - public pg_database_owner
            215; 1259 16386 TABLE public orders postgres
            3410; 0 16386 TABLE DATA public orders postgres
            3260; 2606 16392 CONSTRAINT public orders orders_pkey postgres
            3262; 2606 16400 FK CONSTRAINT public orders orders_customer_fkey postgres
            """.trimIndent()
        val entries = RestoreEntry.parse(listing)
        assertEquals(listOf("SCHEMA public", "TABLE public.orders", "TABLE DATA public.orders", "CONSTRAINT public.orders orders_pkey", "FK CONSTRAINT public.orders orders_customer_fkey"), entries.map { it.label })
    }

    private fun tools(): ToolLibrary = ToolLibrary(createTempDirectory("tools"))

    /** Skipped unless `-Pit.postgres=host:port` and pg_dump, pg_restore and psql are installed. */
    @Test
    fun postgresRoundTrip(): Unit = runBlocking {
        val (host, port) = System.getProperty("it.postgres")?.split(":") ?: return@runBlocking
        val library = tools()
        val dump = library.best(Tool.PgDump, 17)?.path ?: return@runBlocking
        val restore = library.best(Tool.PgRestore, 17)?.path ?: return@runBlocking
        val psql = library.best(Tool.Psql, 17)?.path ?: return@runBlocking
        val config = ConnectionConfig("pg", "pg", DatabaseType.PostgreSQL, host, port.toInt(), "postgres", "postgres")
        val endpoint = ServerEndpoint.of(config, "pw")
        Database.connect(config, "pw").use { db ->
            assertEquals(17, db.serverMajorVersion())
            db.openSession().use {
                it.execute("DROP DATABASE IF EXISTS backup_it_src")
                it.execute("DROP DATABASE IF EXISTS backup_it_dst")
                it.execute("DROP DATABASE IF EXISTS backup_it_sql")
                it.execute("CREATE DATABASE backup_it_src")
                it.execute("CREATE DATABASE backup_it_dst")
                it.execute("CREATE DATABASE backup_it_sql")
            }
        }
        val src = config.copy(database = "backup_it_src")
        Database.connect(src, "pw").use { db -> db.openSession().use { it.execute("CREATE TABLE t (id int PRIMARY KEY, name text); INSERT INTO t VALUES (1, 'a'), (2, 'b'); CREATE TABLE skipped (x int)") } }
        try {
            val dir = createTempDirectory("backup")
            val file = dir.resolve("src.dump")
            val lines = mutableListOf<String>()
            ToolRunner.runChecked(BackupCommands.pgDump(dump, endpoint, BackupOptions(BackupFormat.PgCustom, file, "backup_it_src")), "pg_dump", dir.resolve("log.txt")) { lines += it }
            assertTrue(file.exists() && Files.size(file) > 0)
            assertTrue(lines.any { "dumping contents of table" in it }, lines.joinToString("\n"))
            assertFalse("pw" in dir.resolve("log.txt").readText().substringBefore('\n'), "the log's command line has no password")

            val entries = RestoreEntry.parse(ToolRunner.runChecked(BackupCommands.pgRestoreList(restore, file), "pg_restore --list").log)
            val chosen = entries.filter { it.name != "skipped" }
            val listFile = dir.resolve("list.txt").also { Files.writeString(it, chosen.joinToString("\n") { e -> e.line }) }
            ToolRunner.runChecked(BackupCommands.pgRestore(restore, endpoint, RestoreOptions(file, BackupFormat.PgCustom, "backup_it_dst", entries = chosen), listFile), "pg_restore")
            Database.connect(config.copy(database = "backup_it_dst"), "pw").use { db ->
                assertEquals(listOf("t"), db.tables(Namespace(null, "public")).map { it.ref.name })
                assertEquals(2, db.countRows(db.tables(Namespace(null, "public")).single().ref, null))
            }

            val plain = dir.resolve("src.sql")
            ToolRunner.runChecked(BackupCommands.pgDump(dump, endpoint, BackupOptions(BackupFormat.PgPlain, plain, "backup_it_src", noOwner = true)), "pg_dump")
            ToolRunner.runChecked(BackupCommands.psqlRestore(psql, endpoint, RestoreOptions(plain, BackupFormat.PgPlain, "backup_it_sql")), "psql")
            Database.connect(config.copy(database = "backup_it_sql"), "pw").use { db -> assertEquals(setOf("t", "skipped"), db.tables(Namespace(null, "public")).map { it.ref.name }.toSet()) }

            val bad = ToolRunner.run(BackupCommands.pgDump(dump, endpoint.copy(password = "wrong"), BackupOptions(BackupFormat.PgCustom, dir.resolve("bad.dump"), "backup_it_src")))
            assertTrue(bad.exitCode != 0 && "password authentication failed" in bad.log, bad.log)
        } finally {
            Database.connect(config, "pw").use { db ->
                db.openSession().use { s -> listOf("src", "dst", "sql").forEach { s.execute("DROP DATABASE IF EXISTS backup_it_$it WITH (FORCE)") } }
            }
        }
    }

    /** Skipped unless `-Pit.mysql=host:port` and mysqldump and mysql are installed. */
    @Test
    fun mysqlRoundTrip(): Unit = runBlocking {
        val (host, port) = System.getProperty("it.mysql")?.split(":") ?: return@runBlocking
        val library = tools()
        val dump = library.best(Tool.MySqlDump)?.path ?: return@runBlocking
        val client = library.best(Tool.MySql)?.path ?: return@runBlocking
        val config = ConnectionConfig("my", "my", DatabaseType.MySQL, host, port.toInt(), "mysql", "root")
        Database.connect(config, "pw").use { db ->
            db.openSession().use {
                it.execute("DROP DATABASE IF EXISTS backup_it_src")
                it.execute("DROP DATABASE IF EXISTS backup_it_dst")
                it.execute("CREATE DATABASE backup_it_src")
                it.execute("CREATE DATABASE backup_it_dst")
                it.execute("CREATE TABLE backup_it_src.t (id int PRIMARY KEY, name text)")
                it.execute("INSERT INTO backup_it_src.t VALUES (1, 'a'), (2, 'b')")
            }
        }
        try {
            val dir = createTempDirectory("backup")
            val endpoint = ServerEndpoint.of(config, "pw")
            val file = dir.resolve("src.sql")
            ToolRunner.runChecked(BackupCommands.mysqlDump(dump, endpoint, BackupOptions(BackupFormat.MySqlSql, file, "backup_it_src"), dir.resolve("dump.cnf")), "mysqldump")
            assertFalse(dir.resolve("dump.cnf").exists(), "the option file with the password is deleted")
            assertTrue("INSERT INTO `t`" in file.readText())
            ToolRunner.runChecked(BackupCommands.mysqlRestore(client, endpoint, RestoreOptions(file, BackupFormat.MySqlSql, "backup_it_dst"), dir.resolve("restore.cnf")), "mysql")
            Database.connect(config.copy(database = "backup_it_dst"), "pw").use { db ->
                val table = db.tables(db.namespaces().first { it.catalog == "backup_it_dst" }).single().ref
                assertEquals(2, db.countRows(table, null))
            }
        } finally {
            Database.connect(config, "pw").use { db -> db.openSession().use { s -> listOf("src", "dst").forEach { s.execute("DROP DATABASE IF EXISTS backup_it_$it") } } }
        }
    }

    @Test
    fun sqliteBacksItselfUp(): Unit = runBlocking {
        val dir = createTempDirectory("sqlite")
        val config = ConnectionConfig("lite", "lite", DatabaseType.SQLite, database = dir.resolve("a.db").toString())
        Database.connect(config, null).use { db ->
            db.openSession().use { it.execute("CREATE TABLE t (x int); INSERT INTO t VALUES (1)") }
            db.backupInto(dir.resolve("copy.db"))
        }
        Database.connect(config.copy(database = dir.resolve("copy.db").toString()), null).use { copy ->
            assertEquals(listOf("t"), copy.tables(copy.namespaces().first()).map { it.ref.name })
        }
    }
}

class BackupScheduleTest {
    @Test
    fun dueAfterItsIntervalAndMissedRunsCatchUp() {
        val now = java.time.Instant.parse("2026-10-31T10:00:00Z")
        val daily = BackupSchedule("a", "c", frequency = BackupFrequency.Daily)
        assertTrue(daily.isDue(now), "never ran")
        assertFalse(daily.copy(lastRun = now.minusSeconds(3_600).toEpochMilli()).isDue(now))
        assertTrue(daily.copy(lastRun = now.minusSeconds(3 * 86_400).toEpochMilli()).isDue(now), "missed while closed")
        assertFalse(daily.copy(enabled = false).isDue(now))
    }

    @Test
    fun prunesOnlyItsOwnOldestBackups() {
        val folder = createTempDirectory("scheduled")
        val schedule = BackupSchedule("a", "c", keep = 2)
        listOf("scheduled-a-20261028", "scheduled-a-20261029", "scheduled-a-20261030", "scheduled-b-20261001", "manual.dump").forEach { Files.writeString(folder.resolve(it), "x") }
        assertEquals(listOf("scheduled-a-20261028"), BackupSchedule.prune(folder, schedule).map { it.fileName.toString() })
        assertEquals(setOf("scheduled-a-20261029", "scheduled-a-20261030", "scheduled-b-20261001", "manual.dump"), Files.list(folder).use { it.map { p -> p.fileName.toString() }.toList() }.toSet())
    }
}
