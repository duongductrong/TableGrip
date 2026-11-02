package dev.tablegrip.core.ddl

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.driver.InsertException
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.driver.ScriptException
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.schema.DiffItem
import dev.tablegrip.core.schema.SchemaDiff
import dev.tablegrip.core.schema.TableDiffKind
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The schema tools end to end against real SQLite databases: designer changes, compare and migrate, inserts. */
class SchemaToolsSqliteTest {
    private val dir = Files.createTempDirectory("tablegrip-schema").toFile()
    private val opened = mutableListOf<Database>()

    private fun open(name: String, readOnly: Boolean = false): Database = runBlocking {
        val config = ConnectionConfig(id = UUID.randomUUID().toString(), name = name, type = DatabaseType.SQLite, database = File(dir, "$name.db").path, readOnly = readOnly)
        Database.connect(config, password = null).also { opened += it }
    }

    @AfterTest fun close() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private suspend fun Database.run(vararg sql: String) = openSession().use { session -> sql.forEach { session.execute(it) } }

    private suspend fun Database.rows(sql: String): List<List<String?>> = openSession().use { (it.execute(sql) as QueryResult.Rows).rows }

    private suspend fun Database.design(table: String): TableDesign {
        val ref = TableRef(namespaces().single(), table)
        val structure = tableStructure(ref)
        return TableDesign.from(structure.details, dialect, structure.constraints, structure.triggers)
    }

    @Test
    fun `rebuilding a table keeps its rows, indexes, triggers and foreign keys`() = runBlocking {
        val db = open("rebuild")
        db.run(
            "CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE, team_id INTEGER REFERENCES teams(id), age INTEGER CHECK (age >= 0))",
            "CREATE INDEX users_team ON users(team_id)",
            "CREATE TABLE audit (msg TEXT)",
            "CREATE TRIGGER users_audit AFTER INSERT ON users BEGIN INSERT INTO audit VALUES (NEW.email); END",
            "INSERT INTO teams VALUES (1, 'core')",
            "INSERT INTO users (email, team_id, age) VALUES ('a@x.io', 1, 30), ('b@x.io', NULL, 41)",
        )
        val original = db.design("users")
        val target =
            original.copy(
                columns = original.columns.map { if (it.name == "age") it.copy(name = "years", type = "INTEGER", nullable = false, defaultValue = "0") else it },
                checks = listOf("CHECK (years >= 0)"),
            )
        val script = DdlGenerator(db.dialect).script(TableChange(original, target))
        assertTrue(script.warnings.any { "rebuilt" in it }, script.text)
        db.executeScript(script)

        assertEquals(listOf(listOf("1", "a@x.io", "1", "30"), listOf("2", "b@x.io", null, "41")), db.rows("SELECT * FROM users ORDER BY id"))
        val after = db.tableStructure(TableRef(db.namespaces().single(), "users"))
        assertEquals(listOf("id", "email", "team_id", "years"), after.details.columns.map { it.name })
        assertEquals(listOf("users_team"), after.details.indexes.map { it.name }.filterNot { it.startsWith("sqlite_autoindex") })
        assertEquals(listOf("users_audit"), after.triggers.map { it.name })
        assertEquals("teams", after.details.foreignKeys.single().referencedTable.name)
        // The UNIQUE constraint came back with the table.
        assertFailsWith<Exception> { db.run("INSERT INTO users (email) VALUES ('a@x.io')") }
    }

    @Test
    fun `unnamed foreign keys to different tables stay apart`() = runBlocking {
        val db = open("fks")
        db.run(
            "CREATE TABLE a (id INTEGER PRIMARY KEY)",
            "CREATE TABLE b (id INTEGER PRIMARY KEY)",
            "CREATE TABLE ab (a_id INTEGER REFERENCES a(id), b_id INTEGER REFERENCES b(id), PRIMARY KEY (a_id, b_id))",
        )
        val ref = TableRef(db.namespaces().single(), "ab")
        assertEquals(listOf("a" to listOf("a_id"), "b" to listOf("b_id")), db.tableDetails(ref).foreignKeys.map { it.referencedTable.name to it.columns }.sortedBy { it.first })
        assertEquals(2, db.relations(ref).size)
        // The composite key's own index isn't mistaken for a UNIQUE constraint.
        assertEquals(emptyList(), db.design("ab").uniqueConstraints)
    }

    @Test
    fun `a failing script changes nothing`() = runBlocking {
        val db = open("rollback")
        db.run("CREATE TABLE t (a INTEGER)", "INSERT INTO t VALUES (1)")
        val original = db.design("t")
        // An index can't take the table's own name, so the second statement fails after the first went through.
        val target = original.copy(columns = original.columns + DesignColumn("b", "TEXT"), indexes = listOf(dev.tablegrip.core.metadata.IndexInfo("t", listOf("a"), false)))
        val error = assertFailsWith<ScriptException> { db.executeScript(DdlGenerator(db.dialect).script(TableChange(original, target))) }
        assertTrue(error.rolledBack)
        assertEquals(listOf("a"), db.tableColumns(TableRef(db.namespaces().single(), "t")).columns.map { it.name })
    }

    @Test
    fun `read-only connections refuse scripts and inserts`() = runBlocking {
        open("ro").run("CREATE TABLE t (a INTEGER)")
        opened.removeLast().close()
        val db = open("ro", readOnly = true)
        val design = db.design("t")
        assertFailsWith<ReadOnlyViolationException> { db.executeScript(DdlGenerator(db.dialect).script(TableChange(null, design.copy(table = design.table.copy(name = "u"))))) }
        assertFailsWith<ReadOnlyViolationException> { db.insertRows(design.table, db.tableColumns(design.table).columns, sequenceOf(listOf("1")), { it }) }
    }

    @Test
    fun `compares two schemas and migrates the target to match`() = runBlocking {
        val source = open("source")
        source.run(
            "CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT NOT NULL, region TEXT DEFAULT 'eu')",
            "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, team_id INTEGER REFERENCES teams(id))",
            "CREATE INDEX users_email ON users(email)",
        )
        val target = open("target")
        target.run(
            "CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT, legacy TEXT)",
            "CREATE TABLE old_stuff (x INTEGER)",
            "INSERT INTO teams (id, name) VALUES (7, 'kept')",
        )
        suspend fun designs(db: Database) = db.schemaSnapshot(db.namespaces().single()).tables.map { TableDesign.from(it, db.dialect) }
        val comparison = SchemaDiff.compare(designs(source), designs(target), target.namespaces().single())
        assertEquals(
            listOf("users" to TableDiffKind.Added, "teams" to TableDiffKind.Changed, "old_stuff" to TableDiffKind.Removed),
            comparison.diffs.map { it.name to it.kind },
        )
        val teams = comparison.diffs.single { it.name == "teams" }
        assertEquals(
            setOf("column:region" to DiffItem.ColumnAdded::class, "column:legacy" to DiffItem.ColumnRemoved::class, "column:name" to DiffItem.ColumnChanged::class),
            teams.items.map { it.id to it::class }.toSet(),
        )

        // Drops start unselected: the legacy column and old_stuff survive the default migration.
        target.executeScript(SchemaDiff.migration(comparison, comparison.defaultSelection(), DdlGenerator(target.dialect)))
        assertEquals(listOf(listOf("7", "kept", null, "eu")), target.rows("SELECT id, name, legacy, region FROM teams"))
        assertEquals(setOf("teams", "users", "old_stuff"), target.tables(target.namespaces().single()).map { it.ref.name }.toSet())
        val again = SchemaDiff.compare(designs(source), designs(target), target.namespaces().single())
        assertEquals(listOf("old_stuff" to TableDiffKind.Removed, "teams" to TableDiffKind.Changed), again.diffs.map { it.name to it.kind }.sortedBy { it.first })
        assertEquals(listOf("column:legacy"), again.diffs.single { it.name == "teams" }.items.map { it.id })
    }

    @Test
    fun `inserts in one transaction and names the row that failed`() = runBlocking {
        val db = open("insert")
        db.run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL, born DATE)")
        val table = TableRef(db.namespaces().single(), "people")
        val columns = db.tableColumns(table).columns.filter { it.name != "id" }
        var progress = 0L
        val rows = (1..1_234).map { listOf("p$it", "2001-02-03") }
        assertEquals(1_234, db.insertRows(table, columns, rows.asSequence(), { it }, onProgress = { progress = it }))
        assertEquals(1_234, progress)

        val bad = (1..800).map { if (it == 650) listOf(null, null) else listOf("q$it", null) }
        val error = assertFailsWith<InsertException> { db.insertRows(table, columns, bad.asSequence(), { it }, describe = { i, _ -> "line ${i + 2}" }) }
        assertTrue("line 651" in error.row || "line 502" in error.row || "–" in error.row, error.message)
        assertEquals(listOf(listOf("1234")), db.rows("SELECT COUNT(*) FROM people"), "nothing of the failed import was kept")
    }
}
