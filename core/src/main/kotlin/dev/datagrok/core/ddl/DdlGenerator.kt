package dev.datagrok.core.ddl

import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.metadata.ForeignKeyInfo
import dev.datagrok.core.metadata.IndexInfo
import dev.datagrok.core.metadata.TableRef

/**
 * When a statement runs relative to the others. A script runs phase by phase, so foreign keys go before what they
 * depend on is dropped and come back after what they point at exists, whichever tables the changes touch.
 */
enum class DdlPhase {
    /** Outside the transaction, before it (SQLite's `PRAGMA foreign_keys`, a no-op inside one). */
    Prologue,
    DropForeignKeys,
    DropIndexes,
    Tables,
    CreateIndexes,
    AddForeignKeys,
    DropTables,
    /** Outside the transaction, after it. */
    Epilogue,
}

data class DdlStatement(val sql: String, val phase: DdlPhase)

/**
 * Statements in the order they must run, and what the user should know before running them: changes an engine can't
 * make, or makes by copying the table.
 */
data class DdlScript(val statements: List<DdlStatement>, val warnings: List<String>, val transactional: Boolean) {
    val isEmpty: Boolean
        get() = statements.isEmpty()

    /** The script as text, for the preview and consoles. */
    val text: String
        get() = statements.joinToString("\n\n") { it.sql.trimEnd().removeSuffix(";") + ";" }
}

/**
 * Writes DDL for table changes in a dialect's SQL. PostgreSQL, MySQL/MariaDB, SQLite and SQL Server get their own syntax;
 * DuckDB and ClickHouse what they support; other engines standard SQL, with a warning to check it.
 */
class DdlGenerator(private val dialect: SqlDialect) {
    private enum class Engine { PostgreSQL, MySQL, SQLite, SqlServer, DuckDB, ClickHouse, Generic }

    private val engine =
        when (dialect) {
            is SqlDialect.PostgreSQLDialect -> Engine.PostgreSQL
            is SqlDialect.MySQLDialect -> Engine.MySQL
            is SqlDialect.SQLiteDialect -> Engine.SQLite
            is SqlDialect.SqlServerDialect -> Engine.SqlServer
            is SqlDialect.DuckDBDialect -> Engine.DuckDB
            is SqlDialect.ClickHouseDialect -> Engine.ClickHouse
            else -> Engine.Generic
        }

    /** Whether the engine can roll DDL back: the script then runs in one transaction, all or nothing. */
    val transactionalDdl: Boolean
        get() = engine in setOf(Engine.PostgreSQL, Engine.SQLite, Engine.SqlServer, Engine.DuckDB)

    /** Engines whose foreign keys can only be declared in `CREATE TABLE`. */
    private val inlineForeignKeys: Boolean
        get() = engine == Engine.SQLite || engine == Engine.DuckDB

    fun script(changes: List<TableChange>): DdlScript {
        val out = Output()
        for (change in changes) {
            val original = change.original
            val target = change.target
            when {
                original == null -> createTable(target!!, out)
                target == null -> dropTable(original, out)
                else -> alterTable(original, target, out)
            }
        }
        if (engine == Engine.Generic && out.statements.isNotEmpty()) {
            out.warn("Written in standard SQL for ${dialect}: check it against your database's syntax before running it.")
        }
        val ordered = out.statements.withIndex().sortedWith(compareBy({ it.value.phase }, { it.index })).map { it.value }
        // SQLite rebuilds turn foreign keys off around the transaction; once is enough.
        return DdlScript(ordered.distinct(), out.warnings.distinct(), transactionalDdl)
    }

    fun script(change: TableChange): DdlScript = script(listOf(change))

    private class Output {
        val statements = mutableListOf<DdlStatement>()
        val warnings = mutableListOf<String>()

        fun add(phase: DdlPhase, sql: String) {
            statements += DdlStatement(sql, phase)
        }

        fun warn(message: String) {
            warnings += message
        }
    }

    // region Create and drop

    private fun createTable(design: TableDesign, out: Output, name: TableRef = design.table) {
        require(design.columns.isNotEmpty()) { "${design.table.name} has no columns" }
        val inlinePrimaryKey = engine == Engine.SQLite && design.primaryKey.size == 1 && design.column(design.primaryKey.single())?.autoIncrement == true
        val lines = mutableListOf<String>()
        design.columns.forEach { lines += columnDefinition(it, inlinePrimaryKey = inlinePrimaryKey && it.name == design.primaryKey.single()) }
        if (design.primaryKey.isNotEmpty() && !inlinePrimaryKey && engine != Engine.ClickHouse) {
            val constraint = design.primaryKeyName?.takeIf { engine == Engine.PostgreSQL || engine == Engine.SqlServer }?.let { "CONSTRAINT ${q(it)} " }.orEmpty()
            lines += "${constraint}PRIMARY KEY ${columns(design.primaryKey)}"
        }
        design.uniqueConstraints.forEach { unique ->
            val named = if (engine == Engine.SQLite || unique.name.startsWith("sqlite_autoindex")) "" else "CONSTRAINT ${q(unique.name)} "
            lines += "${named}UNIQUE ${columns(unique.columns)}"
        }
        design.checks.forEach { lines += it }
        if (inlineForeignKeys) design.foreignKeys.forEach { lines += foreignKeyClause(name, it, named = engine != Engine.SQLite) }
        val body = lines.joinToString(",\n    ", prefix = "(\n    ", postfix = "\n)")
        val suffix =
            if (engine == Engine.ClickHouse) {
                val order = design.primaryKey.takeIf { it.isNotEmpty() }?.let(::columns) ?: "tuple()"
                "\nENGINE = MergeTree\nORDER BY $order"
            } else {
                ""
            }
        out.add(DdlPhase.Tables, "CREATE TABLE ${qualified(name)} $body$suffix")
        design.indexes.forEach { out.add(DdlPhase.CreateIndexes, createIndex(name, it)) }
        if (!inlineForeignKeys) design.foreignKeys.forEach { addForeignKey(name, it, out) }
        if (engine == Engine.ClickHouse) {
            if (design.foreignKeys.isNotEmpty()) out.warn("ClickHouse has no foreign keys: those of ${name.name} are left out.")
            if (design.indexes.isNotEmpty()) out.warn("ClickHouse indexes are data-skipping indexes: those of ${name.name} are left out.")
        }
        if (engine == Engine.DuckDB && design.columns.any { it.autoIncrement }) {
            out.warn("DuckDB has no auto-increment columns: give ${name.name} a sequence default instead (DEFAULT nextval('seq')).")
        }
    }

    private fun dropTable(design: TableDesign, out: Output) {
        out.add(DdlPhase.DropTables, "DROP TABLE ${qualified(design.table)}")
    }

    // endregion

    // region Alter

    private fun alterTable(original: TableDesign, target: TableDesign, out: Output) {
        val renames = target.columns.mapNotNull { column -> column.originalName?.takeIf { it != column.name }?.let { it to column.name } }.toMap()
        fun renamed(names: List<String>) = names.map { renames[it] ?: it }
        val originalByName = original.columns.associateBy { it.name }
        val kept = target.columns.filter { it.originalName != null && it.originalName in originalByName }
        val added = target.columns.filter { it.originalName == null || it.originalName !in originalByName }
        val dropped = original.columns.filter { column -> target.columns.none { it.originalName == column.name } }
        val altered = kept.filter { columnChanged(originalByName.getValue(it.originalName!!), it) }

        val primaryKeyChanged = renamed(original.primaryKey) != target.primaryKey
        val originalIndexes = original.indexes.associate { it.name to it.copy(columns = renamed(it.columns)) }
        val targetIndexes = target.indexes.associateBy { it.name }
        val droppedIndexes = original.indexes.filter { targetIndexes[it.name] != originalIndexes[it.name] }
        val createdIndexes = target.indexes.filter { originalIndexes[it.name] != it }
        // Unnamed keys match by what they link, so renaming a column they use changes their identity: compare renamed.
        val originalForeignKeys = original.foreignKeys.map { it.copy(columns = renamed(it.columns)) }.associateBy { it.identity }
        val targetForeignKeys = target.foreignKeys.associateBy { it.identity }
        val droppedForeignKeys = original.foreignKeys.filter { fk -> fk.copy(columns = renamed(fk.columns)).identity.let { !sameForeignKey(targetForeignKeys[it], originalForeignKeys[it]) } }
        val addedForeignKeys = target.foreignKeys.filter { !sameForeignKey(originalForeignKeys[it.identity], it) }
        val tableRenamed = original.table.name != target.table.name

        if (engine == Engine.SQLite) {
            val needsRebuild =
                altered.isNotEmpty() || primaryKeyChanged || droppedForeignKeys.isNotEmpty() || addedForeignKeys.isNotEmpty() ||
                    dropped.any { column -> column.name in original.primaryKey || original.indexes.any { column.name in it.columns } || original.uniqueConstraints.any { column.name in it.columns } || original.foreignKeys.any { column.name in it.columns } } ||
                    added.any { !sqliteCanAdd(it, target) }
            if (needsRebuild) return rebuildSqliteTable(original, target, out)
        }

        val table = target.table
        val before = original.table
        droppedForeignKeys.forEach { dropForeignKey(before, it, out) }
        droppedIndexes.forEach { index -> dropIndex(before, index).forEach { out.add(DdlPhase.DropIndexes, it) } }
        if (primaryKeyChanged && original.primaryKey.isNotEmpty()) dropPrimaryKey(original, out)

        if (tableRenamed) out.add(DdlPhase.Tables, renameTable(before, table.name))
        dropped.forEach { out.add(DdlPhase.Tables, "ALTER TABLE ${qualified(table)} DROP COLUMN ${q(it.name)}") }
        for (column in kept) {
            val from = originalByName.getValue(column.originalName!!)
            alterColumn(table, from, column, out)
        }
        added.forEach { addColumn(table, it, out) }
        if (primaryKeyChanged && target.primaryKey.isNotEmpty()) addPrimaryKey(target, out)
        createdIndexes.forEach { out.add(DdlPhase.CreateIndexes, createIndex(table, it)) }
        addedForeignKeys.forEach { addForeignKey(table, it, out) }
    }

    private fun columnChanged(from: DesignColumn, to: DesignColumn): Boolean =
        ColumnTypes.normalize(from.type) != ColumnTypes.normalize(to.type) || from.nullable != to.nullable ||
            ColumnTypes.normalizeDefault(from.defaultValue) != ColumnTypes.normalizeDefault(to.defaultValue) || from.autoIncrement != to.autoIncrement

    private fun sameForeignKey(a: ForeignKeyInfo?, b: ForeignKeyInfo?): Boolean =
        a != null && b != null && a.columns == b.columns && a.referencedTable.name == b.referencedTable.name && a.referencedColumns == b.referencedColumns &&
            (a.onUpdate ?: "NO ACTION") == (b.onUpdate ?: "NO ACTION") && (a.onDelete ?: "NO ACTION") == (b.onDelete ?: "NO ACTION")

    private fun alterColumn(table: TableRef, from: DesignColumn, to: DesignColumn, out: Output) {
        val name = q(to.name)
        val target = qualified(table)
        val renamed = from.name != to.name
        val typeChanged = ColumnTypes.normalize(from.type) != ColumnTypes.normalize(to.type)
        val nullChanged = from.nullable != to.nullable
        val defaultChanged = ColumnTypes.normalizeDefault(from.defaultValue) != ColumnTypes.normalizeDefault(to.defaultValue)
        if (from.autoIncrement != to.autoIncrement && engine != Engine.MySQL) {
            out.warn("${table.name}.${to.name}: auto-increment can't be switched on an existing column here; recreate the column instead.")
        }
        when (engine) {
            Engine.MySQL -> {
                // MySQL restates the whole column: CHANGE renames too, MODIFY keeps the name.
                if (renamed) out.add(DdlPhase.Tables, "ALTER TABLE $target CHANGE COLUMN ${q(from.name)} ${columnDefinition(to)}")
                else if (typeChanged || nullChanged || defaultChanged || from.autoIncrement != to.autoIncrement) out.add(DdlPhase.Tables, "ALTER TABLE $target MODIFY COLUMN ${columnDefinition(to)}")
            }
            Engine.ClickHouse -> {
                if (renamed) out.add(DdlPhase.Tables, "ALTER TABLE $target RENAME COLUMN ${q(from.name)} TO $name")
                if (typeChanged || nullChanged || defaultChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target MODIFY COLUMN ${columnDefinition(to)}")
            }
            Engine.SqlServer -> {
                if (renamed) {
                    val path = listOfNotNull(table.namespace.schema, table.name, from.name).joinToString(".")
                    out.add(DdlPhase.Tables, "EXEC sp_rename ${dialect.literal(path, false)}, ${dialect.literal(to.name, false)}, 'COLUMN'")
                }
                if (typeChanged || nullChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name ${to.type} ${if (to.nullable) "NULL" else "NOT NULL"}")
                if (defaultChanged) {
                    if (from.defaultValue != null) {
                        out.warn("${table.name}.${to.name}: SQL Server keeps defaults as named constraints; drop the old default constraint before this runs.")
                    }
                    to.defaultValue?.let { out.add(DdlPhase.Tables, "ALTER TABLE $target ADD DEFAULT $it FOR $name") }
                }
            }
            Engine.SQLite -> {
                // Anything but a rename rebuilds the table, decided before we get here.
                if (renamed) out.add(DdlPhase.Tables, "ALTER TABLE $target RENAME COLUMN ${q(from.name)} TO $name")
            }
            Engine.PostgreSQL, Engine.DuckDB -> {
                if (renamed) out.add(DdlPhase.Tables, "ALTER TABLE $target RENAME COLUMN ${q(from.name)} TO $name")
                if (typeChanged) {
                    // USING converts existing values the way a cast would, e.g. text holding numbers to integer.
                    out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name TYPE ${to.type} USING $name::${to.type}")
                }
                if (nullChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name ${if (to.nullable) "DROP" else "SET"} NOT NULL")
                if (defaultChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name " + (to.defaultValue?.let { "SET DEFAULT $it" } ?: "DROP DEFAULT"))
            }
            Engine.Generic -> {
                if (renamed) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN ${q(from.name)} RENAME TO $name")
                if (typeChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name SET DATA TYPE ${to.type}")
                if (nullChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name ${if (to.nullable) "DROP" else "SET"} NOT NULL")
                if (defaultChanged) out.add(DdlPhase.Tables, "ALTER TABLE $target ALTER COLUMN $name " + (to.defaultValue?.let { "SET DEFAULT $it" } ?: "DROP DEFAULT"))
            }
        }
        if (nullChanged && !to.nullable && to.defaultValue == null) {
            out.warn("${table.name}.${to.name} becomes NOT NULL: this fails if any row has NULL there.")
        }
    }

    private fun addColumn(table: TableRef, column: DesignColumn, out: Output) {
        val keyword = if (engine == Engine.SqlServer) "ADD" else "ADD COLUMN"
        out.add(DdlPhase.Tables, "ALTER TABLE ${qualified(table)} $keyword ${columnDefinition(column)}")
        if (!column.nullable && column.defaultValue == null && !column.autoIncrement) {
            out.warn("New column ${table.name}.${column.name} is NOT NULL without a default: adding it fails if the table has rows.")
        }
    }

    /** What `ALTER TABLE ADD COLUMN` takes in SQLite; anything else needs the table rebuilt. */
    private fun sqliteCanAdd(column: DesignColumn, target: TableDesign): Boolean {
        if (column.name in target.primaryKey || column.autoIncrement) return false
        if (target.uniqueConstraints.any { column.name in it.columns }) return false
        if (target.foreignKeys.any { column.name in it.columns }) return false
        val default = column.defaultValue?.trim()
        // Expressions in parentheses and CURRENT_* aren't constant, which ADD COLUMN requires.
        if (default != null && (default.startsWith("(") || default.uppercase().startsWith("CURRENT_"))) return false
        return column.nullable || default != null
    }

    private fun renameTable(table: TableRef, newName: String): String =
        when (engine) {
            Engine.MySQL -> "RENAME TABLE ${qualified(table)} TO ${qualified(table.copy(name = newName))}"
            Engine.SqlServer -> "EXEC sp_rename ${dialect.literal(listOfNotNull(table.namespace.schema, table.name).joinToString("."), false)}, ${dialect.literal(newName, false)}"
            Engine.ClickHouse -> "RENAME TABLE ${qualified(table)} TO ${qualified(table.copy(name = newName))}"
            // PostgreSQL and SQLite keep the schema; naming it again is an error.
            else -> "ALTER TABLE ${qualified(table)} RENAME TO ${q(newName)}"
        }

    private fun dropPrimaryKey(original: TableDesign, out: Output) {
        val table = qualified(original.table)
        when (engine) {
            Engine.MySQL -> out.add(DdlPhase.DropIndexes, "ALTER TABLE $table DROP PRIMARY KEY")
            Engine.PostgreSQL -> out.add(DdlPhase.DropIndexes, "ALTER TABLE $table DROP CONSTRAINT ${q(original.primaryKeyName ?: "${original.table.name}_pkey")}")
            Engine.SqlServer ->
                original.primaryKeyName?.let { out.add(DdlPhase.DropIndexes, "ALTER TABLE $table DROP CONSTRAINT ${q(it)}") }
                    ?: out.warn("${original.table.name}: the primary key's constraint name is unknown; drop it by name before this runs.")
            Engine.Generic -> out.add(DdlPhase.DropIndexes, "ALTER TABLE $table DROP PRIMARY KEY")
            Engine.DuckDB, Engine.ClickHouse -> out.warn("$dialect can't change the primary key of ${original.table.name} in place; recreate the table instead.")
            Engine.SQLite -> Unit
        }
    }

    private fun addPrimaryKey(target: TableDesign, out: Output) {
        if (engine == Engine.DuckDB || engine == Engine.ClickHouse) return
        val name = target.primaryKeyName?.takeIf { engine == Engine.SqlServer || engine == Engine.PostgreSQL }
            ?: "PK_${target.table.name}".takeIf { engine == Engine.SqlServer }
        val constraint = name?.let { "CONSTRAINT ${q(it)} " }.orEmpty()
        out.add(DdlPhase.Tables, "ALTER TABLE ${qualified(target.table)} ADD ${constraint}PRIMARY KEY ${columns(target.primaryKey)}")
    }

    private fun createIndex(table: TableRef, index: IndexInfo): String =
        "CREATE ${if (index.unique) "UNIQUE " else ""}INDEX ${q(index.name)} ON ${qualified(table)} ${columns(index.columns)}"

    private fun dropIndex(table: TableRef, index: IndexInfo): List<String> =
        when (engine) {
            Engine.MySQL, Engine.SqlServer -> listOf("DROP INDEX ${q(index.name)} ON ${qualified(table)}")
            // A unique index can be a UNIQUE constraint's, which DROP INDEX refuses; dropping the constraint first takes it
            // with it, and IF EXISTS makes whichever statement doesn't apply a no-op.
            Engine.PostgreSQL ->
                listOf(
                    "ALTER TABLE ${qualified(table)} DROP CONSTRAINT IF EXISTS ${q(index.name)}",
                    "DROP INDEX IF EXISTS ${qualifiedIndex(table, index.name)}",
                )
            else -> listOf("DROP INDEX ${qualifiedIndex(table, index.name)}")
        }

    private fun addForeignKey(table: TableRef, fk: ForeignKeyInfo, out: Output) {
        when (engine) {
            Engine.ClickHouse -> out.warn("ClickHouse has no foreign keys: ${fk.name} is left out.")
            Engine.DuckDB -> out.warn("DuckDB can't add a foreign key to an existing table: ${fk.name} is left out.")
            else -> out.add(DdlPhase.AddForeignKeys, "ALTER TABLE ${qualified(table)} ADD ${foreignKeyClause(table, fk, named = true)}")
        }
    }

    private fun dropForeignKey(table: TableRef, fk: ForeignKeyInfo, out: Output) {
        when (engine) {
            Engine.MySQL -> out.add(DdlPhase.DropForeignKeys, "ALTER TABLE ${qualified(table)} DROP FOREIGN KEY ${q(fk.name)}")
            Engine.ClickHouse -> Unit
            Engine.DuckDB -> out.warn("DuckDB can't drop a foreign key from an existing table: ${fk.name} stays.")
            else -> out.add(DdlPhase.DropForeignKeys, "ALTER TABLE ${qualified(table)} DROP CONSTRAINT ${q(fk.name)}")
        }
    }

    /**
     * SQLite's ALTER TABLE only adds, renames and drops plain columns. Anything else follows its documented recipe: create
     * the new table under a temporary name, copy the rows, drop the old one, rename, and bring back indexes and triggers —
     * with foreign keys off, or dropping a referenced table would cascade.
     */
    private fun rebuildSqliteTable(original: TableDesign, target: TableDesign, out: Output) {
        val temporary = target.table.copy(name = "_${target.table.name}_new")
        out.add(DdlPhase.Prologue, "PRAGMA foreign_keys = OFF")
        createTable(target.copy(indexes = emptyList()), out, name = temporary)
        val originalNames = original.columns.map { it.name }.toSet()
        val copied = target.columns.filter { it.originalName != null && it.originalName in originalNames }
        if (copied.isNotEmpty()) {
            val into = copied.joinToString(", ") { q(it.name) }
            val from = copied.joinToString(", ") { q(it.originalName!!) }
            out.add(DdlPhase.Tables, "INSERT INTO ${qualified(temporary)} ($into) SELECT $from FROM ${qualified(original.table)}")
        }
        out.add(DdlPhase.Tables, "DROP TABLE ${qualified(original.table)}")
        out.add(DdlPhase.Tables, "ALTER TABLE ${qualified(temporary)} RENAME TO ${q(target.table.name)}")
        target.indexes.forEach { out.add(DdlPhase.CreateIndexes, createIndex(target.table, it)) }
        target.triggers.forEach { out.add(DdlPhase.CreateIndexes, it) }
        out.add(DdlPhase.Epilogue, "PRAGMA foreign_keys = ON")
        out.warn("SQLite can't make these changes to ${target.table.name} in place: the table is rebuilt and its rows copied. Views that use it may need recreating.")
        target.columns.filter { it.originalName == null && !it.nullable && it.defaultValue == null && !it.autoIncrement }.forEach {
            out.warn("New column ${target.table.name}.${it.name} is NOT NULL without a default: copying existing rows fails if there are any.")
        }
    }

    // endregion

    // region Pieces

    private fun columnDefinition(column: DesignColumn, inlinePrimaryKey: Boolean = false): String =
        buildString {
            append(q(column.name)).append(' ')
            val type = column.type.trim()
            when {
                engine == Engine.ClickHouse && column.nullable && !type.startsWith("Nullable(") -> append("Nullable($type)")
                else -> append(type)
            }
            if (inlinePrimaryKey) append(" PRIMARY KEY AUTOINCREMENT")
            if (column.autoIncrement && !inlinePrimaryKey) {
                when (engine) {
                    Engine.PostgreSQL -> if (!type.lowercase().contains("serial")) append(" GENERATED BY DEFAULT AS IDENTITY")
                    Engine.SqlServer -> append(" IDENTITY(1,1)")
                    Engine.Generic -> append(" GENERATED BY DEFAULT AS IDENTITY")
                    else -> Unit
                }
            }
            if (column.defaultValue != null && !(column.autoIncrement && engine == Engine.PostgreSQL)) append(" DEFAULT ").append(column.defaultValue.trim())
            if (engine != Engine.ClickHouse) {
                if (!column.nullable) append(" NOT NULL") else if (engine == Engine.MySQL || engine == Engine.SqlServer) append(" NULL")
            }
            if (column.autoIncrement && engine == Engine.MySQL) append(" AUTO_INCREMENT")
        }

    private fun foreignKeyClause(table: TableRef, fk: ForeignKeyInfo, named: Boolean): String =
        buildString {
            if (named && fk.name.isNotBlank()) append("CONSTRAINT ").append(q(fk.name)).append(' ')
            val target = fk.referencedTable
            val sameNamespace = target.namespace == table.namespace || target.namespace.let { it.catalog.isNullOrEmpty() && it.schema.isNullOrEmpty() }
            append("FOREIGN KEY ").append(columns(fk.columns)).append(" REFERENCES ")
            append(if (sameNamespace) qualified(target.copy(namespace = table.namespace)) else qualified(target))
            append(' ').append(columns(fk.referencedColumns))
            fk.onUpdate?.let { append(" ON UPDATE ").append(it) }
            fk.onDelete?.let { append(" ON DELETE ").append(it) }
        }

    private fun q(name: String) = dialect.quoteIdentifier(name)

    private fun columns(names: List<String>) = names.joinToString(", ", "(", ")", transform = ::q)

    private fun qualified(table: TableRef): String = dialect.qualifiedName(table)

    /** Index names live in the table's schema where engines have schemas. */
    private fun qualifiedIndex(table: TableRef, name: String): String =
        when (engine) {
            Engine.PostgreSQL, Engine.Generic, Engine.DuckDB -> table.namespace.schema?.let { "${q(it)}.${q(name)}" } ?: q(name)
            else -> q(name)
        }

    // endregion
}
