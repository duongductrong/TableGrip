package dev.tablegrip.core.metadata

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.i18n.tr
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/**
 * Lists and reads the functions, procedures and sequences of a namespace, from each engine's own catalog where it has
 * one and JDBC metadata otherwise. Callers own threading and the connection.
 */
internal sealed class SchemaObjectReader(protected val dialect: SqlDialect) {

    abstract fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo>

    abstract fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition

    protected fun qualified(ref: SchemaObjectRef): String =
        listOfNotNull(ref.namespace.catalog, ref.namespace.schema, ref.name).joinToString(".", transform = dialect::quoteIdentifier)

    protected fun <T> Connection.query(sql: String, vararg parameters: String?, map: ResultSet.() -> T): List<T> =
        prepareStatement(sql.trimIndent()).use { statement ->
            parameters.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.map()) } }
        }

    protected fun Connection.single(sql: String, vararg parameters: String?): String? = query(sql, *parameters) { getString(1) }.firstOrNull()

    protected fun notFound(ref: SchemaObjectRef): Nothing = throw java.sql.SQLException(tr("{0} no longer exists", ref.name))

    protected fun kindLabel(kind: SchemaObjectKind): String =
        when (kind) {
            SchemaObjectKind.Function -> tr("function")
            SchemaObjectKind.Procedure -> tr("procedure")
            SchemaObjectKind.Sequence -> tr("sequence")
        }

    /** `(a int, b text) → int`, or without the arrow for procedures. */
    protected fun signature(arguments: String?, result: String?): String =
        "(${arguments.orEmpty()})" + (result?.takeIf { it.isNotBlank() }?.let { " → $it" } ?: "")

    /** `CREATE SEQUENCE` from its settings; null settings are left to the engine's defaults. */
    protected fun createSequence(
        ref: SchemaObjectRef,
        type: String?,
        start: String?,
        increment: String?,
        min: String?,
        max: String?,
        cache: String?,
        cycle: Boolean,
    ): String =
        buildString {
            append("CREATE SEQUENCE ").append(qualified(ref))
            type?.let { append("\n    AS ").append(it) }
            increment?.let { append("\n    INCREMENT BY ").append(it) }
            min?.let { append("\n    MINVALUE ").append(it) }
            max?.let { append("\n    MAXVALUE ").append(it) }
            start?.let { append("\n    START WITH ").append(it) }
            cache?.let { append("\n    CACHE ").append(it) }
            append(if (cycle) "\n    CYCLE" else "\n    NO CYCLE")
            append(';')
        }

    protected fun sequenceProperties(
        current: Pair<String, String?>,
        type: String?,
        start: String?,
        increment: String?,
        min: String?,
        max: String?,
        cache: String?,
        cycle: Boolean,
    ): List<Pair<String, String?>> =
        listOf(
            current,
            tr("increment") to increment,
            tr("minimum") to min,
            tr("maximum") to max,
            tr("start") to start,
            tr("cache") to cache,
            tr("cycle") to if (cycle) tr("yes") else tr("no"),
            tr("data type") to type,
        )

    companion object {
        fun of(dialect: SqlDialect): SchemaObjectReader =
            when (dialect) {
                // Only the wire protocol: no pg_proc to read, or one that lists the engine's own functions.
                is SqlDialect.PostgreSQLDialect if !dialect.hasPostgresCatalog -> NoSchemaObjects(dialect)
                is SqlDialect.PostgreSQLDialect -> PostgreSQLObjects
                is SqlDialect.MySQLDialect -> MySQLObjects
                is SqlDialect.SqlServerDialect -> SqlServerObjects
                // Their JDBC metadata lists every built-in function, or engines without user routines.
                is SqlDialect.SQLiteDialect,
                is SqlDialect.ClickHouseDialect,
                is SqlDialect.DuckDBDialect,
                is SqlDialect.TrinoDialect,
                is dev.tablegrip.core.dialect.SparkSqlDialect,
                is dev.tablegrip.core.dialect.GoogleSqlDialect -> NoSchemaObjects(dialect)
                else -> JdbcObjects(dialect)
            }
    }
}

private class NoSchemaObjects(dialect: SqlDialect) : SchemaObjectReader(dialect) {
    override fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo> = emptyList()

    override fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition = notFound(ref)
}

/**
 * PostgreSQL's `pg_proc` and `pg_sequences`. Overloads are told apart by their argument types (`oidvectortypes`), which
 * also address them as `regprocedure`. Functions an extension installed are left out: they aren't the user's, and
 * extensions such as PostGIS add hundreds.
 */
private data object PostgreSQLObjects : SchemaObjectReader(SqlDialect.PostgreSQL) {
    override fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo> {
        val schema = namespace.schema ?: return emptyList()
        // prokind is PostgreSQL 11+; before that there were no procedures, and aggregates and window functions were flags.
        val kind =
            runCatching { connection.single("SELECT 1 FROM pg_attribute WHERE attrelid = 'pg_proc'::regclass AND attname = 'prokind'") != null }
                .getOrDefault(true)
        val routines =
            connection.query(
                """
                SELECT p.proname, ${if (kind) "p.prokind::text" else "'f'"}, oidvectortypes(p.proargtypes),
                       pg_get_function_arguments(p.oid), pg_get_function_result(p.oid)
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ? AND ${if (kind) "p.prokind IN ('f', 'p')" else "NOT p.proisagg AND NOT p.proiswindow"}
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
                ORDER BY p.proname, 3
                """,
                schema,
            ) {
                val procedure = getString(2) == "p"
                val ref = SchemaObjectRef(namespace, getString(1), if (procedure) SchemaObjectKind.Procedure else SchemaObjectKind.Function, getString(3))
                SchemaObjectInfo(ref, signature(getString(4), getString(5).takeUnless { procedure }))
            }
        // pg_sequences is PostgreSQL 10+.
        val sequences =
            runCatching {
                connection.query("SELECT sequencename, data_type::text FROM pg_sequences WHERE schemaname = ? ORDER BY sequencename", schema) {
                    SchemaObjectInfo(SchemaObjectRef(namespace, getString(1), SchemaObjectKind.Sequence), getString(2))
                }
            }.getOrDefault(emptyList())
        return routines + sequences
    }

    override fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        if (ref.kind == SchemaObjectKind.Sequence) sequence(connection, ref) else routine(connection, ref)

    private fun routine(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition {
        val signature = qualified(ref) + "(" + ref.id + ")"
        val volatility = mapOf("i" to "IMMUTABLE", "s" to "STABLE", "v" to "VOLATILE")
        return connection.query(
            """
            SELECT pg_get_functiondef(p.oid), l.lanname, pg_get_function_arguments(p.oid), pg_get_function_result(p.oid),
                   p.provolatile::text, pg_get_userbyid(p.proowner), obj_description(p.oid, 'pg_proc')
            FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
            WHERE p.oid = to_regprocedure(?)
            """,
            signature,
        ) {
            val source = getString(1).trimEnd().removeSuffix(";") + ";"
            val comment = getString(7)
            val properties =
                listOf(
                    tr("kind") to kindLabel(ref.kind),
                    tr("language") to getString(2),
                    tr("arguments") to getString(3),
                    tr("returns") to getString(4),
                    tr("volatility") to volatility[getString(5)],
                    tr("owner") to getString(6),
                    tr("comment") to comment,
                ).filterNot { ref.kind == SchemaObjectKind.Procedure && it.first == tr("returns") }
            val withComment =
                if (comment == null) source
                else source + "\n\nCOMMENT ON ${ref.kind.name.uppercase()} $signature IS ${dialect.literal(comment, numeric = false)};"
            // pg_get_functiondef writes CREATE OR REPLACE already.
            SchemaObjectDefinition(ref, properties, withComment)
        }.firstOrNull() ?: notFound(ref)
    }

    private fun sequence(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        connection.query(
            """
            SELECT data_type::text, start_value::text, min_value::text, max_value::text, increment_by::text, cycle, cache_size::text,
                   last_value::text, sequenceowner
            FROM pg_sequences WHERE schemaname = ? AND sequencename = ?
            """,
            ref.namespace.schema,
            ref.name,
        ) {
            val (type, start, min, max, increment) = listOf(getString(1), getString(2), getString(3), getString(4), getString(5))
            val cycle = getBoolean(6)
            val cache = getString(7)
            // Null until nextval is first called, or when the user may not read the sequence.
            val current = tr("current value") to (getString(8) ?: tr("not used yet"))
            SchemaObjectDefinition(
                ref,
                sequenceProperties(current, type, start, increment, min, max, cache, cycle) + (tr("owner") to getString(9)),
                createSequence(ref, type, start, increment, min, max, cache, cycle),
            )
        }.firstOrNull() ?: notFound(ref)
}

/**
 * MySQL and MariaDB: `information_schema.ROUTINES` and `SHOW CREATE`. MariaDB 10.3+ also has sequences, listed as tables
 * of type `SEQUENCE`; MySQL has none.
 */
private data object MySQLObjects : SchemaObjectReader(SqlDialect.MySQL) {
    override fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo> {
        val schema = namespace.catalog ?: namespace.schema ?: return emptyList()
        // Arguments in order, with position 0 the function's result.
        val parameters =
            runCatching {
                connection.query(
                    """
                    SELECT SPECIFIC_NAME, ORDINAL_POSITION, PARAMETER_MODE, PARAMETER_NAME, DTD_IDENTIFIER FROM information_schema.PARAMETERS
                    WHERE SPECIFIC_SCHEMA = ? ORDER BY SPECIFIC_NAME, ORDINAL_POSITION
                    """,
                    schema,
                ) { getString(1) to Triple(getInt(2), getString(3), "${getString(4).orEmpty()} ${getString(5).orEmpty()}".trim()) }
                    .groupBy({ it.first }, { it.second })
            }.getOrDefault(emptyMap())
        val routines =
            runCatching {
                connection.query(
                    "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME",
                    schema,
                ) {
                    val name = getString(1)
                    val procedure = getString(2) == "PROCEDURE"
                    val params = parameters[name].orEmpty()
                    val arguments = params.filter { it.first > 0 }.joinToString(", ") { (_, mode, text) -> if (procedure && mode != null && mode != "IN") "$mode $text" else text }
                    val result = params.firstOrNull { it.first == 0 }?.third
                    SchemaObjectInfo(SchemaObjectRef(namespace, name, if (procedure) SchemaObjectKind.Procedure else SchemaObjectKind.Function), signature(arguments, result))
                }
            }.getOrDefault(emptyList())
        val sequences =
            runCatching {
                connection.query(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'SEQUENCE' ORDER BY TABLE_NAME",
                    schema,
                ) { SchemaObjectInfo(SchemaObjectRef(namespace, getString(1), SchemaObjectKind.Sequence)) }
            }.getOrDefault(emptyList())
        return routines + sequences
    }

    override fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        if (ref.kind == SchemaObjectKind.Sequence) sequence(connection, ref) else routine(connection, ref)

    private fun schema(ref: SchemaObjectRef) = ref.namespace.catalog ?: ref.namespace.schema

    private fun mariaDb(connection: Connection): Boolean = connection.single("SELECT VERSION()").orEmpty().contains("MariaDB", ignoreCase = true)

    private fun routine(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition {
        val type = if (ref.kind == SchemaObjectKind.Procedure) "PROCEDURE" else "FUNCTION"
        val (properties, body) =
            connection.query(
                """
                SELECT DTD_IDENTIFIER, IS_DETERMINISTIC, SQL_DATA_ACCESS, SECURITY_TYPE, DEFINER, ROUTINE_COMMENT, CREATED, LAST_ALTERED,
                       ROUTINE_DEFINITION
                FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_NAME = ? AND ROUTINE_TYPE = ?
                """,
                schema(ref),
                ref.name,
                type,
            ) {
                listOfNotNull(
                    tr("kind") to kindLabel(ref.kind),
                    (tr("returns") to getString(1)).takeIf { ref.kind == SchemaObjectKind.Function },
                    tr("deterministic") to if (getString(2) == "YES") tr("yes") else tr("no"),
                    tr("data access") to getString(3),
                    tr("security") to getString(4),
                    tr("definer") to getString(5),
                    tr("comment") to getString(6)?.ifBlank { null },
                    tr("created") to getString(7),
                    tr("modified") to getString(8),
                ) to getString(9)
            }.firstOrNull() ?: notFound(ref)
        // SHOW CREATE answers with a null statement to users who may run the routine but not see its source.
        val create =
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SHOW CREATE $type ${qualified(ref)}").use { rs -> if (rs.next()) rs.getString(3) else null }
                }
            }.getOrNull()
        val source =
            create?.trimEnd()?.removeSuffix(";")?.plus(";")
                ?: (tr("-- Only the body is visible to this user; SHOW CREATE {0} needs more privileges.", type) + "\n" + body.orEmpty())
        // MariaDB has CREATE OR REPLACE for routines; MySQL has only DROP and CREATE.
        val replace = if (create != null && mariaDb(connection)) source.replaceFirst(Regex("^CREATE\\s+", RegexOption.IGNORE_CASE), "CREATE OR REPLACE ") else source
        return SchemaObjectDefinition(ref, properties, source, replace)
    }

    private fun sequence(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition {
        val name = qualified(ref)
        val create = connection.createStatement().use { s -> s.executeQuery("SHOW CREATE SEQUENCE $name").use { rs -> rs.next(); rs.getString(2) } }
        return connection.createStatement().use { s ->
            s.executeQuery("SELECT next_not_cached_value, minimum_value, maximum_value, start_value, increment, cache_size, cycle_option FROM $name").use { rs ->
                rs.next()
                val current = tr("next value (not cached)") to rs.getString(1)
                SchemaObjectDefinition(
                    ref,
                    sequenceProperties(current, null, rs.getString(4), rs.getString(5), rs.getString(2), rs.getString(3), rs.getString(6), rs.getBoolean(7)),
                    create.trimEnd().removeSuffix(";") + ";",
                    create.trimEnd().removeSuffix(";").replaceFirst(Regex("^CREATE\\s+", RegexOption.IGNORE_CASE), "CREATE OR REPLACE ") + ";",
                )
            }
        }
    }
}

/** SQL Server's `sys` catalog views; sources come from `sys.sql_modules`, and recreate with `CREATE OR ALTER`. */
private data object SqlServerObjects : SchemaObjectReader(SqlDialect.SqlServer) {
    private val procedureTypes = setOf("P", "PC", "X")

    override fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo> {
        val schema = namespace.schema ?: return emptyList()
        val routines =
            connection.query(
                """
                SELECT o.name, RTRIM(o.type), p.parameter_id, p.name, TYPE_NAME(p.user_type_id), p.is_output
                FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
                LEFT JOIN sys.parameters p ON p.object_id = o.object_id
                WHERE s.name = ? AND o.type IN ('FN', 'IF', 'TF', 'FS', 'FT', 'P', 'PC', 'X') AND o.is_ms_shipped = 0
                ORDER BY o.name, p.parameter_id
                """,
                schema,
            ) { Triple(getString(1), getString(2), Triple(getInt(3).takeUnless { wasNull() }, "${getString(4).orEmpty()} ${getString(5).orEmpty()}".trim(), getBoolean(6))) }
                .groupBy({ it.first to it.second }, { it.third })
                .map { (key, params) ->
                    val (name, type) = key
                    val procedure = type in procedureTypes
                    val arguments = params.filter { (id, _, _) -> id != null && id > 0 }.joinToString(", ") { (_, text, output) -> if (output) "$text OUTPUT" else text }
                    val result = if (type in setOf("IF", "TF", "FT")) "TABLE" else params.firstOrNull { it.first == 0 }?.second
                    SchemaObjectInfo(SchemaObjectRef(namespace, name, if (procedure) SchemaObjectKind.Procedure else SchemaObjectKind.Function), signature(arguments, result))
                }
        val sequences =
            connection.query(
                "SELECT q.name, TYPE_NAME(q.user_type_id) FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id WHERE s.name = ? ORDER BY q.name",
                schema,
            ) { SchemaObjectInfo(SchemaObjectRef(namespace, getString(1), SchemaObjectKind.Sequence), getString(2)) }
        return routines + sequences
    }

    override fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        if (ref.kind == SchemaObjectKind.Sequence) sequence(connection, ref) else routine(connection, ref)

    private fun routine(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        connection.query(
            """
            SELECT m.definition, o.type_desc, o.create_date, o.modify_date
            FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id LEFT JOIN sys.sql_modules m ON m.object_id = o.object_id
            WHERE s.name = ? AND o.name = ?
            """,
            ref.namespace.schema,
            ref.name,
        ) {
            // CLR routines have no T-SQL source; encrypted ones show none either.
            val definition = getString(1)?.trim()
            val source = definition ?: tr("-- The source of {0} isn't available (CLR or encrypted).", ref.name)
            val replace = definition?.replaceFirst(Regex("\\bCREATE\\s+(?=(FUNCTION|PROC|PROCEDURE)\\b)", RegexOption.IGNORE_CASE), "CREATE OR ALTER ") ?: source
            val properties =
                listOf(
                    tr("kind") to kindLabel(ref.kind),
                    tr("type") to getString(2)?.lowercase()?.replace('_', ' '),
                    tr("created") to getString(3),
                    tr("modified") to getString(4),
                )
            SchemaObjectDefinition(ref, properties, source, replace)
        }.firstOrNull() ?: notFound(ref)

    private fun sequence(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition =
        connection.query(
            """
            SELECT TYPE_NAME(q.user_type_id), CAST(q.start_value AS nvarchar(64)), CAST(q.increment AS nvarchar(64)),
                   CAST(q.minimum_value AS nvarchar(64)), CAST(q.maximum_value AS nvarchar(64)), q.is_cycling, q.is_cached, q.cache_size,
                   CAST(q.current_value AS nvarchar(64)), q.last_used_value
            FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id WHERE s.name = ? AND q.name = ?
            """,
            ref.namespace.schema,
            ref.name,
        ) {
            val (type, start, increment, min, max) = listOf(getString(1), getString(2), getString(3), getString(4), getString(5))
            val cycle = getBoolean(6)
            val cached = getBoolean(7)
            val cache = getString(8)
            // current_value is the start value until first used; last_used_value (2017+) is null until then.
            val used = runCatching { getString(10) }.getOrNull()
            val current = tr("current value") to (getString(9).takeIf { used != null } ?: tr("not used yet"))
            val create =
                createSequence(ref, type, start, increment, min, max, null, cycle).removeSuffix(";") +
                    (if (!cached) "\n    NO CACHE" else cache?.let { "\n    CACHE $it" }.orEmpty()) + ";"
            SchemaObjectDefinition(ref, sequenceProperties(current, type, start, increment, min, max, if (cached) cache ?: tr("default") else tr("no"), cycle), create)
        }.firstOrNull() ?: notFound(ref)
}

/**
 * Engines without a reader of their own: routines from JDBC metadata, which has no sources. The SQL-standard
 * `INFORMATION_SCHEMA.ROUTINES` has them on some (H2, HSQLDB); elsewhere the source isn't available. No sequences:
 * JDBC has no metadata call for them.
 */
private class JdbcObjects(dialect: SqlDialect) : SchemaObjectReader(dialect) {
    override fun list(connection: Connection, namespace: Namespace): List<SchemaObjectInfo> {
        if (namespace.catalog == null && namespace.schema == null) return emptyList()
        val meta = connection.metaData
        val schemaPattern = namespace.schema?.let { meta.escape(it) }
        // (name, specific name) → whether it's a function.
        val functions =
            runCatching {
                meta.getFunctions(namespace.catalog, schemaPattern, "%").rows {
                    Row(getString("FUNCTION_SCHEM"), getString("FUNCTION_NAME"), getString("SPECIFIC_NAME"), getString("REMARKS"), function = true)
                }
            }.getOrDefault(emptyList())
        val procedures =
            runCatching {
                meta.getProcedures(namespace.catalog, schemaPattern, "%").rows {
                    val function = getShort("PROCEDURE_TYPE").toInt() == DatabaseMetaData.procedureReturnsResult
                    Row(getString("PROCEDURE_SCHEM"), getString("PROCEDURE_NAME"), runCatching { getString("SPECIFIC_NAME") }.getOrNull(), getString("REMARKS"), function)
                }
            }.getOrDefault(emptyList())
        // Drivers often report functions through both calls; a schema pattern may also match other schemas.
        val seen = functions.map { it.specific ?: it.name }.toSet()
        return (functions + procedures.filter { (it.specific ?: it.name) !in seen })
            .filter { namespace.schema == null || it.schema == null || it.schema == namespace.schema }
            .distinctBy { it.specific ?: it.name }
            .sortedBy { it.name.lowercase() }
            .map { row ->
                val kind = if (row.function) SchemaObjectKind.Function else SchemaObjectKind.Procedure
                SchemaObjectInfo(SchemaObjectRef(namespace, row.name, kind, row.specific ?: row.name), row.remarks?.ifBlank { null })
            }
    }

    override fun definition(connection: Connection, ref: SchemaObjectRef): SchemaObjectDefinition {
        val source =
            runCatching {
                connection.query(
                    """
                    SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES
                    WHERE ROUTINE_SCHEMA = ? AND ROUTINE_NAME = ? AND ROUTINE_DEFINITION IS NOT NULL
                    """,
                    ref.namespace.schema ?: ref.namespace.catalog,
                    ref.name,
                ) { getString(1) }.firstOrNull()
            }.getOrNull()
        return SchemaObjectDefinition(
            ref,
            listOf(tr("kind") to kindLabel(ref.kind), tr("specific name") to ref.id.takeIf { it != ref.name }),
            source?.trim() ?: tr("-- {0} doesn't make the source of routines available.", dialect.toString()),
        )
    }

    private data class Row(val schema: String?, val name: String, val specific: String?, val remarks: String?, val function: Boolean)

    private fun <T> ResultSet.rows(map: ResultSet.() -> T): List<T> = use { buildList { while (next()) add(map()) } }

    private fun DatabaseMetaData.escape(name: String): String {
        val escape = searchStringEscape.takeUnless { it.isNullOrEmpty() } ?: return name
        return name.replace(escape, escape + escape).replace("_", "${escape}_").replace("%", "$escape%")
    }
}
