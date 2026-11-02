package dev.tablegrip.core.connection

import dev.tablegrip.core.dialect.SqlDialect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Engines that speak the same wire protocol and SQL. A family shares a driver and a dialect, so adding an engine to an
 * existing family (Redshift or CockroachDB to PostgreSQL, TiDB to MySQL) is a new [DatabaseType] and nothing else.
 */
enum class EngineFamily {
    PostgreSQL,
    MySQL,
    SQLite,

    /** Microsoft SQL Server and Azure SQL: T-SQL over TDS. */
    SqlServer,

    /** Column store over HTTP; no transactions, and changes are mutations. */
    ClickHouse,

    /** In-process analytics on a file, Parquet or CSV. */
    DuckDB,

    /** Standard SQL read through JDBC metadata alone: embedded Java databases, and any driver the user brings. */
    Generic,

    /** The Redis protocol: Redis, Valkey, KeyDB, Dragonfly. */
    Redis,

    /** The MongoDB wire protocol and query language. */
    MongoDB,

    /** etcd's v3 key-value API. */
    Etcd,
}

/** How a profile reaches its database, which decides the fields the connection dialog asks for. */
enum class ConnectionKind {
    /** Host, port, database. */
    Server,

    /** A database file. */
    File,

    /** Only a JDBC URL, for drivers whose URL syntax TableGrip doesn't model. */
    Url,

    /**
     * A data file (CSV, Excel, JSON…) opened as tables: read into a SQLite working copy, and every committed change
     * written back to the file. See [dev.tablegrip.core.datafile.DataFileStore].
     */
    DataFile,
}

/** How an engine's data is shaped, which decides the screens that browse it and the connection interface it has. */
enum class Paradigm { Relational, KeyValue, Document, WideColumn, Search, Stream, Graph }

/** Features the UI offers only where the engine has them. */
enum class Capability {
    /** Showing a statement's plan. */
    Explain,

    /** Manual commit and rollback in consoles. */
    Transactions,

    /** Editing rows in the table viewer: updates and deletes by primary key, in one transaction. */
    EditRows,
    ;

    companion object {
        val AllRelational: Set<Capability> = entries.toSet()
    }
}

/**
 * A database engine TableGrip connects to: what it's called, how it's reached, which drivers talk to it (default
 * first) and what it can do. Code asks these properties rather than which engine it is, so that new engines need
 * no changes elsewhere. The MySQL family defaults to MariaDB Connector/J: MySQL's own driver is GPL, so it's only
 * used from a jar the user supplies.
 */
@Serializable(with = DatabaseType.Serializer::class)
class DatabaseType(
    /** Stored in connection profiles: never change an existing one. */
    val id: String,
    val displayName: String,
    val family: EngineFamily,
    /** Null for engines that open a file rather than a server. */
    val defaultPort: Int?,
    val drivers: List<DriverId>,
    val paradigm: Paradigm = Paradigm.Relational,
    val capabilities: Set<Capability> = Capability.AllRelational,
    val connection: ConnectionKind = if (defaultPort == null) ConnectionKind.File else ConnectionKind.Server,
    /** Placeholder for the database field, e.g. `/path/to/app.mv.db`. */
    val databaseHint: String? = null,
    /** Driver properties the engine needs unless the user sets them, e.g. TLS for hosted services. */
    val defaultProperties: Map<DriverId, Map<String, String>> = emptyMap(),
    /** The family's SQL dialect, unless the engine strays from it; null for engines without SQL. */
    val dialect: SqlDialect? = SqlDialect.of(family),
) {
    init {
        require(drivers.isNotEmpty()) { "$id needs at least one driver" }
    }

    /** Opens a file rather than a server: a database file or a [data file][isDataFile]. */
    val isFile: Boolean
        get() = connection == ConnectionKind.File || connection == ConnectionKind.DataFile

    /** A spreadsheet-like file of tables, not a database: see [ConnectionKind.DataFile]. */
    val isDataFile: Boolean
        get() = connection == ConnectionKind.DataFile

    val isServer: Boolean
        get() = connection == ConnectionKind.Server

    override fun toString(): String = id

    companion object {
        val PostgreSQL = DatabaseType("PostgreSQL", "PostgreSQL", EngineFamily.PostgreSQL, 5432, listOf(DriverId.PostgreSQL))
        val MySQL = DatabaseType("MySQL", "MySQL", EngineFamily.MySQL, 3306, listOf(DriverId.MariaDB, DriverId.MySQL))
        val MariaDB = DatabaseType("MariaDB", "MariaDB", EngineFamily.MySQL, 3306, listOf(DriverId.MariaDB, DriverId.MySQL))
        val CockroachDB =
            DatabaseType("CockroachDB", "CockroachDB", EngineFamily.PostgreSQL, 26257, listOf(DriverId.PostgreSQL), dialect = SqlDialect.CockroachDB)
        val TimescaleDB = DatabaseType("TimescaleDB", "TimescaleDB", EngineFamily.PostgreSQL, 5432, listOf(DriverId.PostgreSQL))
        val Neon = DatabaseType("Neon", "Neon", EngineFamily.PostgreSQL, 5432, listOf(DriverId.PostgreSQL), defaultProperties = mapOf(DriverId.PostgreSQL to mapOf("sslmode" to "require")))
        val Supabase =
            DatabaseType("Supabase", "Supabase", EngineFamily.PostgreSQL, 5432, listOf(DriverId.PostgreSQL), defaultProperties = mapOf(DriverId.PostgreSQL to mapOf("sslmode" to "require")))
        val TiDB = DatabaseType("TiDB", "TiDB", EngineFamily.MySQL, 4000, listOf(DriverId.MariaDB, DriverId.MySQL))
        val PlanetScale =
            DatabaseType(
                "PlanetScale",
                "PlanetScale",
                EngineFamily.MySQL,
                3306,
                listOf(DriverId.MariaDB, DriverId.MySQL),
                defaultProperties = mapOf(DriverId.MariaDB to mapOf("sslMode" to "verify-full"), DriverId.MySQL to mapOf("sslMode" to "VERIFY_IDENTITY")),
            )
        val SQLite = DatabaseType("SQLite", "SQLite", EngineFamily.SQLite, null, listOf(DriverId.SQLite))
        val SqlServer = DatabaseType("SqlServer", "SQL Server", EngineFamily.SqlServer, 1433, listOf(DriverId.SqlServer))
        val AzureSql =
            DatabaseType(
                "AzureSql",
                "Azure SQL",
                EngineFamily.SqlServer,
                1433,
                listOf(DriverId.SqlServer),
                defaultProperties = mapOf(DriverId.SqlServer to mapOf("encrypt" to "true", "hostNameInCertificate" to "*.database.windows.net")),
            )
        val ClickHouse =
            DatabaseType("ClickHouse", "ClickHouse", EngineFamily.ClickHouse, 8123, listOf(DriverId.ClickHouse), capabilities = setOf(Capability.Explain))
        val DuckDB = DatabaseType("DuckDB", "DuckDB", EngineFamily.DuckDB, null, listOf(DriverId.DuckDB), databaseHint = "/path/to/file.duckdb or :memory:")
        val Redis = keyValue("Redis", "Redis")
        val Valkey = keyValue("Valkey", "Valkey")
        val KeyDB = keyValue("KeyDB", "KeyDB")
        val Dragonfly = keyValue("Dragonfly", "Dragonfly")

        val Etcd =
            DatabaseType("Etcd", "etcd", EngineFamily.Etcd, 2379, listOf(DriverId.Etcd), paradigm = Paradigm.KeyValue, capabilities = emptySet())
        val FerretDB =
            DatabaseType("FerretDB", "FerretDB", EngineFamily.MongoDB, 27017, listOf(DriverId.MongoDB), paradigm = Paradigm.Document, capabilities = emptySet())
        val MongoDB =
            DatabaseType("MongoDB", "MongoDB", EngineFamily.MongoDB, 27017, listOf(DriverId.MongoDB), paradigm = Paradigm.Document, capabilities = emptySet())

        private fun keyValue(id: String, name: String) =
            DatabaseType(id, name, EngineFamily.Redis, 6379, listOf(DriverId.Lettuce), paradigm = Paradigm.KeyValue, capabilities = emptySet())
        val H2 = DatabaseType("H2", "H2", EngineFamily.Generic, null, listOf(DriverId.H2), databaseHint = "/path/to/data (without .mv.db) or mem:test")
        val HSQLDB = DatabaseType("HSQLDB", "HSQLDB", EngineFamily.Generic, null, listOf(DriverId.HSQLDB), databaseHint = "/path/to/data (without .script) or mem:test")
        val Derby = DatabaseType("Derby", "Apache Derby", EngineFamily.Generic, null, listOf(DriverId.Derby), databaseHint = "/path/to/database directory")

        /**
         * Data files. They speak SQLite's SQL, because that's the engine their working copy runs on; the SQLite driver is
         * what a profile needs on this machine. Each one's reader and writer is a [dev.tablegrip.core.datafile.DataFileFormat].
         */
        val CsvFile = dataFile("CsvFile", "CSV / TSV file", "/path/to/data.csv")
        val ExcelFile = dataFile("ExcelFile", "Excel workbook", "/path/to/workbook.xlsx")
        val JsonFile = dataFile("JsonFile", "JSON / NDJSON file", "/path/to/records.json")

        private fun dataFile(id: String, name: String, hint: String) =
            DatabaseType(id, name, EngineFamily.SQLite, null, listOf(DriverId.SQLite), connection = ConnectionKind.DataFile, databaseHint = hint)

        /** Any JDBC driver: the user brings its jar and the URL. */
        val OtherJdbc =
            DatabaseType("OtherJdbc", "Other (JDBC driver)", EngineFamily.Generic, null, listOf(DriverId.Generic), connection = ConnectionKind.Url)

        /** Every engine, in the order the connection dialog lists them. */
        val entries: List<DatabaseType> =
            listOf(
                PostgreSQL, MySQL, MariaDB, SQLite, SqlServer, ClickHouse, DuckDB, MongoDB, Redis, Valkey,
                CockroachDB, TimescaleDB, Neon, Supabase, TiDB, PlanetScale, AzureSql,
                KeyDB, Dragonfly, Etcd, FerretDB, H2, HSQLDB, Derby, OtherJdbc,
                CsvFile, ExcelFile, JsonFile,
            )

        fun byId(id: String): DatabaseType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /** Writes the [id]; profiles saved before engines were descriptors used the same strings. */
    object Serializer : KSerializer<DatabaseType> {
        override val descriptor = PrimitiveSerialDescriptor("dev.tablegrip.DatabaseType", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DatabaseType) = encoder.encodeString(value.id)

        override fun deserialize(decoder: Decoder): DatabaseType {
            val id = decoder.decodeString()
            return byId(id) ?: throw SerializationException("Unknown database type \"$id\". The profile may come from a newer version of TableGrip.")
        }
    }
}
