package dev.tablegrip.core

import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.RelationalConnection
import dev.tablegrip.core.edit.BoundValue
import dev.tablegrip.core.edit.ChangeSql
import dev.tablegrip.core.edit.RowChange
import dev.tablegrip.core.filter.SqlFilter
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.TableRef
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every relational engine must do for the explorer, table viewer, structure view and consoles to work. Each engine
 * test seeds the same two tables in its own SQL types and runs this; [keys], [foreignKeys] and [transactions] are off
 * for engines that have none.
 */
class RelationalContract(
    private val connection: RelationalConnection,
    private val intType: String = "INTEGER",
    private val textType: String = "VARCHAR(100)",
    /** Appended to each CREATE TABLE, e.g. an engine clause. */
    private val tableSuffix: String = "",
    /** Primary keys and NOT NULL: engines without them (Trino) declare plain columns. */
    private val keys: Boolean = true,
    private val foreignKeys: Boolean = keys,
    private val transactions: Boolean = true,
) {
    private val dialect = connection.dialect

    private fun q(name: String) = dialect.quoteIdentifier(name)

    suspend fun run() {
        seed()
        val namespaces = connection.namespaces()
        val namespace =
            namespaces.firstOrNull { it.displayName == dialect.defaultNamespace(namespaces.map { n -> n.displayName }, connection.config.database) }
                ?: error("No default namespace among $namespaces")
        val tables = connection.tables(namespace).map { it.ref.name }
        assertTrue("users" in tables && "teams" in tables, "tables in $namespace: $tables")
        val users = TableRef(namespace, "users")

        val details = connection.tableDetails(users)
        assertEquals(listOf("id", "name", "team_id"), details.columns.map { it.name })
        assertEquals(if (keys) listOf("id") else emptyList(), details.primaryKey)
        if (foreignKeys) assertEquals("teams", details.foreignKeys.single().referencedTable.name)

        val byName = listOf(SortColumn("name", SortDirection.Asc))
        val page = connection.fetchPage(users, filter = null, orderBy = byName, limit = 2, offset = 1)
        assertEquals(listOf("bob", "carol"), page.rows.map { it[1] })
        assertEquals(false, page.truncated)
        assertEquals(true, connection.fetchPage(users, null, byName, limit = 1, offset = 0).truncated)

        val startsWithA = SqlFilter.raw("${q("name")} LIKE 'a%'")
        assertEquals(listOf("alice"), connection.fetchPage(users, startsWithA, emptyList(), 10, 0).rows.map { it[1] })
        assertEquals(2L, connection.countRows(users, SqlFilter.raw("${q("id")} > 1")))
        assertEquals(listOf("alice"), connection.sampleValues(users, "name", "a"))

        val structure = connection.tableStructure(users)
        assertTrue(structure.ddl.isNotBlank())
        if (keys) assertTrue(ConstraintKind.PrimaryKey in structure.constraints.map { it.kind } || structure.details.primaryKey.isNotEmpty())

        if (transactions) {
            fun int(v: String) = BoundValue(v, Types.INTEGER, numeric = true)
            fun text(v: String) = BoundValue(v, Types.VARCHAR, numeric = false)
            val changes =
                listOf(
                    RowChange.Update(key = mapOf("id" to int("1")), values = mapOf("name" to text("carol2"))),
                    RowChange.Delete(key = mapOf("id" to int("2"))),
                    RowChange.Insert(values = mapOf("id" to int("4"), "name" to text("dave"))),
                )
            assertEquals(3, connection.applyChanges(ChangeSql.statements(dialect, users, changes)))
            val ids = listOf(SortColumn("id", SortDirection.Asc))
            assertEquals(listOf("carol2", "bob", "dave"), connection.fetchPage(users, null, ids, 10, 0).rows.map { it[1] })
        }

        connection.openSession().use { session ->
            val result = session.execute("SELECT COUNT(*) FROM ${dialect.qualifiedName(users)}")
            assertTrue(result is QueryResult.Rows && result.rows.single().single() != null)
        }
    }

    private suspend fun seed() =
        connection.openSession().use { session ->
            val primaryKey = if (keys) " NOT NULL PRIMARY KEY" else ""
            val notNull = if (keys) " NOT NULL" else ""
            session.execute("CREATE TABLE ${q("teams")} (${q("id")} $intType$primaryKey, ${q("name")} $textType)$tableSuffix")
            // Table-level: MySQL and TiDB parse a column's own REFERENCES clause but ignore it.
            val reference = if (foreignKeys) ", FOREIGN KEY (${q("team_id")}) REFERENCES ${q("teams")} (${q("id")})" else ""
            session.execute(
                "CREATE TABLE ${q("users")} (${q("id")} $intType$primaryKey, ${q("name")} $textType$notNull, ${q("team_id")} $intType$reference)$tableSuffix"
            )
            session.execute("INSERT INTO ${q("teams")} (${q("id")}, ${q("name")}) VALUES (1, 'red')")
            session.execute("INSERT INTO ${q("teams")} (${q("id")}, ${q("name")}) VALUES (2, 'blue')")
            session.execute("INSERT INTO ${q("users")} (${q("id")}, ${q("name")}, ${q("team_id")}) VALUES (1, 'carol', 1)")
            session.execute("INSERT INTO ${q("users")} (${q("id")}, ${q("name")}, ${q("team_id")}) VALUES (2, 'alice', 2)")
            session.execute("INSERT INTO ${q("users")} (${q("id")}, ${q("name")}, ${q("team_id")}) VALUES (3, 'bob', NULL)")
        }
}
