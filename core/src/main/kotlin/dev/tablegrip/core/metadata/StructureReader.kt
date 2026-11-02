package dev.tablegrip.core.metadata

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.TokenType
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/**
 * Reads what JDBC metadata doesn't cover — constraints, triggers and DDL — with each engine's own catalog queries.
 * Callers own threading and the connection.
 */
internal sealed class StructureReader(protected val dialect: SqlDialect) {

    fun read(connection: Connection, details: TableDetails): TableStructure {
        val constraints = constraints(connection, details)
        val triggers = triggers(connection, details.table)
        return TableStructure(details, constraints, triggers, ddl(connection, details, constraints, triggers))
    }

    protected abstract fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo>

    protected abstract fun triggers(connection: Connection, table: TableRef): List<TriggerInfo>

    protected abstract fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String

    protected fun columnList(columns: List<String>): String = columns.joinToString(", ", "(", ")", transform = dialect::quoteIdentifier)

    protected fun foreignKeyDefinition(table: TableRef, fk: ForeignKeyInfo): String =
        buildString {
            val target = fk.referencedTable
            val sameNamespace = target.namespace == table.namespace || target.namespace.let { it.catalog.isNullOrEmpty() && it.schema.isNullOrEmpty() }
            append("FOREIGN KEY ").append(columnList(fk.columns)).append(" REFERENCES ")
            append(if (sameNamespace) dialect.quoteIdentifier(target.name) else dialect.qualifiedName(target))
            append(' ').append(columnList(fk.referencedColumns))
            fk.onUpdate?.let { append(" ON UPDATE ").append(it) }
            fk.onDelete?.let { append(" ON DELETE ").append(it) }
        }

    protected fun <T> Connection.query(sql: String, vararg parameters: String?, map: ResultSet.() -> T): List<T> =
        prepareStatement(sql.trimIndent()).use { statement ->
            parameters.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.map()) } }
        }

    protected fun statements(vararg groups: List<String>): String = groups.flatMap { it }.joinToString(";\n\n", postfix = ";") { it.trim().removeSuffix(";") }

    companion object {
        fun of(dialect: SqlDialect): StructureReader =
            when (dialect) {
                is SqlDialect.CockroachDBDialect -> CockroachDBStructure
                is SqlDialect.PostgreSQLDialect -> PostgreSQLStructure
                is SqlDialect.MySQLDialect -> MySQLStructure
                is SqlDialect.SQLiteDialect -> SQLiteStructure
                is SqlDialect.SqlServerDialect -> SqlServerStructure
                is SqlDialect.ClickHouseDialect -> ClickHouseStructure
                is SqlDialect.DuckDBDialect -> DuckDBStructure
                else -> GenericStructure(dialect)
            }
    }
}

private data object PostgreSQLStructure : PostgreSQLCatalog()

/** CockroachDB answers PostgreSQL's constraint queries, but has no trigger catalog and writes its own DDL. */
private data object CockroachDBStructure : PostgreSQLCatalog() {
    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> = emptyList()

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW CREATE TABLE ${dialect.qualifiedName(details.table)}").use { rs -> rs.next(); rs.getString("create_statement") }
        }.trim().removeSuffix(";") + ";"
}

private open class PostgreSQLCatalog : StructureReader(SqlDialect.PostgreSQL) {
    private val kinds = mapOf("p" to ConstraintKind.PrimaryKey, "u" to ConstraintKind.Unique, "f" to ConstraintKind.ForeignKey, "c" to ConstraintKind.Check, "x" to ConstraintKind.Exclusion)

    // Relations are addressed as `?::regclass` with the quoted qualified name, so every query resolves them the same way.
    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> =
        connection.query(
            """
            SELECT c.conname, c.contype::text, pg_get_constraintdef(c.oid, true),
                   ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num, ord)
                         JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.num ORDER BY k.ord)
            FROM pg_constraint c
            WHERE c.conrelid = ?::regclass AND c.contype IN ('p', 'u', 'f', 'c', 'x')
            ORDER BY position(c.contype::text IN 'pufcx'), c.conname
            """,
            dialect.qualifiedName(details.table),
        ) {
            ConstraintInfo(getString(1), kinds.getValue(getString(2)), (getArray(4).array as Array<*>).map { it.toString() }, getString(3))
        }

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> =
        connection.query(
            """
            SELECT t.tgname,
                   CASE WHEN t.tgtype::int & 2 <> 0 THEN 'BEFORE' WHEN t.tgtype::int & 64 <> 0 THEN 'INSTEAD OF' ELSE 'AFTER' END,
                   concat_ws(' OR ', CASE WHEN t.tgtype::int & 4 <> 0 THEN 'INSERT' END, CASE WHEN t.tgtype::int & 16 <> 0 THEN 'UPDATE' END,
                             CASE WHEN t.tgtype::int & 8 <> 0 THEN 'DELETE' END, CASE WHEN t.tgtype::int & 32 <> 0 THEN 'TRUNCATE' END),
                   t.tgenabled <> 'D',
                   pg_get_triggerdef(t.oid, true)
            FROM pg_trigger t
            WHERE t.tgrelid = ?::regclass AND NOT t.tgisinternal
            ORDER BY t.tgname
            """,
            dialect.qualifiedName(table),
        ) {
            TriggerInfo(getString(1), getString(2), getString(3), getBoolean(4), getString(5))
        }

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String {
        val name = dialect.qualifiedName(details.table)
        val (kind, partitionKey) =
            connection.query(
                "SELECT c.relkind::text, CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END FROM pg_class c WHERE c.oid = ?::regclass",
                name,
            ) { getString(1) to getString(2) }.single()
        val create =
            when (kind) {
                "v" -> "CREATE OR REPLACE VIEW $name AS\n" + viewDefinition(connection, name)
                "m" -> "CREATE MATERIALIZED VIEW $name AS\n" + viewDefinition(connection, name)
                else -> createTable(connection, name, constraints, partitionKey)
            }
        // Indexes behind PRIMARY KEY / UNIQUE / EXCLUDE constraints are already part of CREATE TABLE.
        val indexes =
            connection.query(
                """
                SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i
                WHERE i.indrelid = ?::regclass
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.conrelid = i.indrelid AND c.contype IN ('p', 'u', 'x'))
                ORDER BY i.indexrelid::regclass::text
                """,
                name,
            ) { getString(1) }
        val objectType = when (kind) { "v" -> "VIEW"; "m" -> "MATERIALIZED VIEW"; else -> "TABLE" }
        val comments =
            connection.query(
                """
                SELECT NULL, obj_description(?::regclass, 'pg_class')
                UNION ALL
                SELECT a.attname, col_description(a.attrelid, a.attnum) FROM pg_attribute a
                WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
                """,
                name,
                name,
            ) { getString(1) to getString(2) }
                .filter { it.second != null }
                .map { (column, comment) ->
                    val target = if (column == null) "$objectType $name" else "COLUMN $name.${dialect.quoteIdentifier(column)}"
                    "COMMENT ON $target IS ${dialect.literal(comment, numeric = false)}"
                }
        val disabled = triggers.filterNot { it.enabled }.map { "ALTER TABLE $name DISABLE TRIGGER ${dialect.quoteIdentifier(it.name)}" }
        return statements(listOf(create), indexes, triggers.map { it.definition }, disabled, comments)
    }

    private fun viewDefinition(connection: Connection, name: String): String =
        connection.query("SELECT pg_get_viewdef(?::regclass, true)", name) { getString(1) }.single().trimEnd()

    private fun createTable(connection: Connection, name: String, constraints: List<ConstraintInfo>, partitionKey: String?): String {
        val columns =
            connection.query(
                """
                SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid),
                       a.attidentity::text, a.attgenerated::text
                FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """,
                name,
            ) {
                buildString {
                    append(dialect.quoteIdentifier(getString(1))).append(' ').append(getString(2))
                    val default = getString(4)
                    when {
                        getString(5) == "a" -> append(" GENERATED ALWAYS AS IDENTITY")
                        getString(5) == "d" -> append(" GENERATED BY DEFAULT AS IDENTITY")
                        getString(6) == "s" -> append(" GENERATED ALWAYS AS ($default) STORED")
                        default != null -> append(" DEFAULT ").append(default)
                    }
                    if (getBoolean(3)) append(" NOT NULL")
                }
            }
        val body = columns + constraints.map { "CONSTRAINT ${dialect.quoteIdentifier(it.name)} ${it.definition}" }
        return "CREATE TABLE $name (\n" + body.joinToString(",\n") { "    $it" } + "\n)" + (partitionKey?.let { " PARTITION BY $it" } ?: "")
    }
}

private data object MySQLStructure : StructureReader(SqlDialect.MySQL) {
    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> {
        val schema = details.table.namespace.catalog
        val table = details.table.name
        val columns =
            connection.query(
                """
                SELECT CONSTRAINT_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """,
                schema,
                table,
            ) { getString(1) to getString(2) }
                .groupBy({ it.first }, { it.second })
        // CHECK_CONSTRAINTS exists from MySQL 8.0.16 and MariaDB 10.2; only MariaDB scopes it by table.
        val checks =
            runCatching {
                connection.query(
                    "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = ?",
                    schema,
                    table,
                ) { getString(1) to getString(2) }
            }
                .recoverCatching {
                    connection.query("SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ?", schema) {
                        getString(1) to getString(2)
                    }
                }
                .getOrDefault(emptyList())
                .toMap()
        val order = listOf(ConstraintKind.PrimaryKey, ConstraintKind.Unique, ConstraintKind.ForeignKey, ConstraintKind.Check)
        return connection.query(
            "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
            schema,
            table,
        ) { getString(1) to getString(2) }
            .mapNotNull { (name, type) ->
                val cols = columns[name].orEmpty()
                when (type) {
                    "PRIMARY KEY" -> ConstraintInfo(name, ConstraintKind.PrimaryKey, cols, "PRIMARY KEY ${columnList(cols)}")
                    "UNIQUE" -> ConstraintInfo(name, ConstraintKind.Unique, cols, "UNIQUE ${columnList(cols)}")
                    "FOREIGN KEY" -> {
                        val definition = details.foreignKeys.firstOrNull { it.name == name }?.let { foreignKeyDefinition(details.table, it) }
                        ConstraintInfo(name, ConstraintKind.ForeignKey, cols, definition ?: "FOREIGN KEY ${columnList(cols)}")
                    }
                    "CHECK" -> {
                        // MySQL stores the clause with backslash-escaped quotes.
                        val clause = checks[name].orEmpty().replace("\\'", "'")
                        ConstraintInfo(name, ConstraintKind.Check, cols, "CHECK " + if (clause.startsWith("(")) clause else "($clause)")
                    }
                    else -> null
                }
            }
            .sortedWith(compareBy({ order.indexOf(it.kind) }, { it.name }))
    }

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> =
        connection.query(
            """
            SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT FROM information_schema.TRIGGERS
            WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?
            ORDER BY ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORDER
            """,
            table.namespace.catalog,
            table.name,
        ) {
            val name = getString(1)
            val timing = getString(2)
            val event = getString(3)
            val definition = "CREATE TRIGGER ${dialect.quoteIdentifier(name)} $timing $event ON ${dialect.quoteIdentifier(table.name)} FOR EACH ROW ${getString(4)}"
            TriggerInfo(name, timing, event, enabled = true, definition)
        }

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String {
        // SHOW CREATE TABLE also answers for views, with the CREATE VIEW statement.
        val create =
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW CREATE TABLE ${dialect.qualifiedName(details.table)}").use { rs -> rs.next(); rs.getString(2) }
            }
        return statements(listOf(create), triggers.map { it.definition })
    }
}

private data object SQLiteStructure : StructureReader(SqlDialect.SQLite) {
    private val lexer = SqlLexer(SqlDialect.SQLite.syntax)

    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> {
        val table = details.table
        val primaryKey =
            details.primaryKey.takeIf { it.isNotEmpty() }?.let { ConstraintInfo("", ConstraintKind.PrimaryKey, it, "PRIMARY KEY ${columnList(it)}") }
        // Only indexes created by a UNIQUE constraint (origin "u"), not CREATE UNIQUE INDEX.
        val uniqueIndexes =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list(${dialect.quoteIdentifier(table.name)})").use { rs ->
                    buildList { while (rs.next()) if (rs.getString("origin") == "u") add(rs.getString("name")) }
                }
            }
        val unique =
            details.indexes.filter { it.name in uniqueIndexes }.map { ConstraintInfo("", ConstraintKind.Unique, it.columns, "UNIQUE ${columnList(it.columns)}") }
        val foreignKeys =
            details.foreignKeys.map { fk ->
                // The driver invents names for unnamed foreign keys; SQLite itself has none.
                ConstraintInfo("", ConstraintKind.ForeignKey, fk.columns, foreignKeyDefinition(table, fk))
            }
        val checks = createSql(connection, table)?.let(::checkConstraints).orEmpty()
        return listOfNotNull(primaryKey) + unique + foreignKeys + checks
    }

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> =
        connection.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ? ORDER BY name", table.name) {
            val sql = getString(2)
            val header = sql.substringBefore(" BEGIN").substringBefore("\nBEGIN")
            val timing = Regex("""\b(BEFORE|AFTER|INSTEAD\s+OF)\b""", RegexOption.IGNORE_CASE).find(header)?.value?.uppercase() ?: "BEFORE"
            val event = Regex("""\b(INSERT|UPDATE|DELETE)\b""", RegexOption.IGNORE_CASE).find(header)?.value?.uppercase().orEmpty()
            TriggerInfo(getString(1), timing.replace(Regex("\\s+"), " "), event, enabled = true, sql)
        }

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String {
        val objects =
            connection.query(
                """
                SELECT sql FROM sqlite_master WHERE tbl_name = ? AND sql IS NOT NULL
                ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 0 WHEN 'index' THEN 1 ELSE 2 END, name
                """,
                details.table.name,
            ) { getString(1) }
        return statements(objects)
    }

    private fun createSql(connection: Connection, table: TableRef): String? =
        connection.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", table.name) { getString(1) }.firstOrNull()

    /** SQLite keeps CHECK constraints only in the CREATE TABLE text, so they are read back from it. */
    fun checkConstraints(createSql: String): List<ConstraintInfo> {
        val tokens = lexer.tokenize(createSql).filter { it.type != TokenType.Whitespace && it.type != TokenType.Comment }
        fun text(i: Int) = tokens.getOrNull(i)?.text(createSql)
        val checks = mutableListOf<ConstraintInfo>()
        for (i in tokens.indices) {
            if (!text(i).equals("CHECK", ignoreCase = true) || text(i + 1) != "(") continue
            var depth = 0
            var end = i + 1
            while (end < tokens.size) {
                when (text(end)) {
                    "(" -> depth++
                    ")" -> if (--depth == 0) break
                }
                end++
            }
            if (end >= tokens.size) continue
            val name = if (text(i - 2).equals("CONSTRAINT", ignoreCase = true)) text(i - 1)?.let(::unquote).orEmpty() else ""
            checks += ConstraintInfo(name, ConstraintKind.Check, emptyList(), "CHECK " + createSql.substring(tokens[i + 1].start, tokens[end].end))
        }
        return checks
    }

    private fun unquote(identifier: String): String =
        if (identifier.length >= 2 && identifier.first() in "\"`[") identifier.substring(1, identifier.length - 1) else identifier
}

/**
 * Structure from JDBC metadata alone, for engines without a reader of their own: keys come from metadata, there are no
 * triggers, and the DDL is rebuilt from the columns, so it may differ in detail from what created the table.
 */
private open class GenericStructure(dialect: SqlDialect) : StructureReader(dialect) {
    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> {
        val primaryKey =
            details.primaryKey.takeIf { it.isNotEmpty() }?.let { ConstraintInfo("", ConstraintKind.PrimaryKey, it, "PRIMARY KEY ${columnList(it)}") }
        val foreignKeys = details.foreignKeys.map { ConstraintInfo(it.name, ConstraintKind.ForeignKey, it.columns, foreignKeyDefinition(details.table, it)) }
        return listOfNotNull(primaryKey) + foreignKeys
    }

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> = emptyList()

    /** What the rebuilt DDL can't know; empty when the engine supplies what's missing. */
    protected open val ddlNote: String = "-- Rebuilt from JDBC metadata; details such as CHECK constraints and triggers aren't included.\n"

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String {
        val columns =
            details.columns.map { column ->
                buildString {
                    append(dialect.quoteIdentifier(column.name)).append(' ').append(column.typeName)
                    if (column.size != null && column.jdbcType in SizedTypes) append('(').append(column.size).append(')')
                    column.defaultValue?.takeIf { !column.autoIncrement }?.let { append(" DEFAULT ").append(it) }
                    if (!column.nullable) append(" NOT NULL")
                }
            }
        val keys = constraints.map { (if (it.name.isEmpty()) "" else "CONSTRAINT ${dialect.quoteIdentifier(it.name)} ") + it.definition }
        val create = "CREATE TABLE ${dialect.qualifiedName(details.table)} (\n" + (columns + keys).joinToString(",\n") { "    $it" } + "\n)"
        // Unique indexes that back the primary key are part of it already.
        val indexes =
            details.indexes.filterNot { it.columns == details.primaryKey }.map { index ->
                "CREATE ${if (index.unique) "UNIQUE " else ""}INDEX ${dialect.quoteIdentifier(index.name)} ON ${dialect.qualifiedName(details.table)} ${columnList(index.columns)}"
            }
        return ddlNote + statements(listOf(create), indexes, triggers.map { it.definition })
    }

    private companion object {
        val SizedTypes = setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY)
    }
}

/** SQL Server has no SHOW CREATE: the table is rebuilt from metadata, with CHECK constraints and triggers from `sys`. */
private object SqlServerStructure : GenericStructure(SqlDialect.SqlServer) {
    override val ddlNote = "-- Rebuilt from the catalog; storage options (filegroups, compression) aren't included.\n"

    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> {
        val checks =
            connection.query(
                "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID(?) ORDER BY name",
                dialect.qualifiedName(details.table),
            ) { ConstraintInfo(getString(1), ConstraintKind.Check, emptyList(), "CHECK " + getString(2)) }
        return super.constraints(connection, details) + checks
    }

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> =
        connection.query(
            """
            SELECT t.name, t.is_instead_of_trigger, t.is_disabled, OBJECT_DEFINITION(t.object_id),
                   STUFF((SELECT ' OR ' + e.type_desc FROM sys.trigger_events e WHERE e.object_id = t.object_id FOR XML PATH('')), 1, 4, '')
            FROM sys.triggers t WHERE t.parent_id = OBJECT_ID(?) ORDER BY t.name
            """,
            dialect.qualifiedName(table),
        ) { TriggerInfo(getString(1), if (getBoolean(2)) "INSTEAD OF" else "AFTER", getString(5).orEmpty(), enabled = !getBoolean(3), getString(4).orEmpty()) }
}

/** ClickHouse's own DDL carries the engine, ORDER BY and settings that metadata can't express. */
private object ClickHouseStructure : StructureReader(SqlDialect.ClickHouse) {
    override fun constraints(connection: Connection, details: TableDetails): List<ConstraintInfo> = emptyList()

    override fun triggers(connection: Connection, table: TableRef): List<TriggerInfo> = emptyList()

    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW CREATE TABLE ${dialect.qualifiedName(details.table)}").use { rs -> rs.next(); rs.getString(1) }
        }.trim().removeSuffix(";") + ";"
}

/** DuckDB keeps the SQL of tables, views and indexes in its catalog functions. */
private object DuckDBStructure : GenericStructure(SqlDialect.DuckDB) {
    override fun ddl(connection: Connection, details: TableDetails, constraints: List<ConstraintInfo>, triggers: List<TriggerInfo>): String {
        val schema = details.table.namespace.schema ?: "main"
        val name = details.table.name
        val objects =
            connection.query(
                """
                SELECT sql FROM duckdb_tables() WHERE schema_name = ? AND table_name = ?
                UNION ALL SELECT sql FROM duckdb_views() WHERE schema_name = ? AND view_name = ?
                UNION ALL SELECT sql FROM duckdb_indexes() WHERE schema_name = ? AND table_name = ? AND sql IS NOT NULL
                """,
                schema, name, schema, name, schema, name,
            ) { getString(1) }
        return if (objects.isEmpty()) super.ddl(connection, details, constraints, triggers) else statements(objects)
    }
}
