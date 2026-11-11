package dev.tablegrip.core.connection

import dev.tablegrip.core.i18n.tr
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

    /** Oracle Database: schemas are users, unquoted names fold to upper case, paging with OFFSET/FETCH (12c and later). */
    Oracle,

    /** Firebird 3 and later: one database per file, no schemas, OFFSET/FETCH paging. */
    Firebird,

    /** Trino (and Starburst): federated queries over catalogs; no transactions, keys or row editing. */
    Trino,

    /** HiveServer2's Thrift protocol: Hive, Spark's Thrift server, Impala and Kyuubi; query-only. */
    Hive,

    /**
     * Engines with a JDBC driver of their own and nothing else in common (Db2, Snowflake, BigQuery…), the embedded Java
     * databases, and any driver the user brings: structure comes from JDBC metadata, and each engine's SQL from its
     * dialect.
     */
    Generic,

    /** The Redis protocol: Redis, Valkey, KeyDB, Dragonfly. */
    Redis,

    /** The MongoDB wire protocol and query language. */
    MongoDB,

    /** etcd's v3 key-value API. */
    Etcd,

    /** Elasticsearch's REST API, which OpenSearch kept: indices of JSON documents, the query DSL. */
    Elasticsearch,

    /** Meilisearch's REST API. */
    Meilisearch,

    /** CQL over Cassandra's native protocol: Apache Cassandra and ScyllaDB. */
    Cassandra,

    /** CouchDB's HTTP API: databases of JSON documents with revisions, Mango queries. */
    CouchDB,

    /** DynamoDB's HTTP API, signed with AWS Signature Version 4; PartiQL. */
    DynamoDB,

    /** InfluxDB 2's HTTP API and Flux. */
    InfluxDB,

    /** The Prometheus query API and PromQL, as VictoriaMetrics serves it too. */
    Prometheus,

    /** Neo4j's HTTP API and Cypher. */
    Neo4j,

    /** InfluxDB 3's HTTP API: SQL and InfluxQL. */
    InfluxDB3,

    /** Solr's HTTP API. */
    Solr,

    /** Qdrant's REST API. */
    Qdrant,

    /** RabbitMQ's management HTTP API. */
    RabbitMQ,

    /** Kafka's protocol, through the Apache Kafka client. */
    Kafka,

    /** ZooKeeper's protocol, through the Apache ZooKeeper client. */
    ZooKeeper,
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
enum class Paradigm { Relational, KeyValue, Document, WideColumn, Search, Stream, Graph, TimeSeries, Vector }

/**
 * The connection interface an engine's client speaks, which decides the screens that browse it: tables and SQL, the key
 * browser, the MongoDB document browser, or the record browser that search, wide-column and graph engines share (see
 * [dev.tablegrip.core.driver.RecordConnection]).
 */
enum class ClientApi {
    Relational,
    KeyValue,
    Document,
    Records,
    ;

    companion object {
        fun of(paradigm: Paradigm): ClientApi =
            when (paradigm) {
                Paradigm.Relational -> Relational
                Paradigm.KeyValue -> KeyValue
                Paradigm.Document -> Document
                Paradigm.WideColumn, Paradigm.Search, Paradigm.Stream, Paradigm.Graph, Paradigm.TimeSeries, Paradigm.Vector -> Records
            }
    }
}

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
    /** The connection interface its client speaks; documents other than MongoDB's are browsed as records. */
    val api: ClientApi = ClientApi.of(paradigm),
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
        val DuckDB = DatabaseType("DuckDB", "DuckDB", EngineFamily.DuckDB, null, listOf(DriverId.DuckDB), databaseHint = tr("/path/to/file.duckdb or :memory:"))

        /** PostgreSQL-compatible distributed SQL (YSQL); its default port is 5433. */
        val YugabyteDB = DatabaseType("YugabyteDB", "YugabyteDB", EngineFamily.PostgreSQL, 5433, listOf(DriverId.PostgreSQL), databaseHint = "yugabyte")
        val Oracle = DatabaseType("Oracle", "Oracle", EngineFamily.Oracle, 1521, listOf(DriverId.Oracle), databaseHint = tr("Service name, e.g. FREEPDB1 or ORCLPDB1"))

        /** No EXPLAIN statement: Firebird shows plans only through its own API. */
        val Firebird =
            DatabaseType(
                "Firebird",
                "Firebird",
                EngineFamily.Firebird,
                3050,
                listOf(DriverId.Firebird),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("/path/on/server/app.fdb or an alias"),
            )
        val Trino =
            DatabaseType("Trino", "Trino", EngineFamily.Trino, 8080, listOf(DriverId.Trino), capabilities = setOf(Capability.Explain), databaseHint = tr("catalog or catalog/schema"))

        /**
         * PrestoDB, Trino's ancestor: the same SQL, but its own driver, since Trino's sends headers Presto rejects. OFFSET,
         * which pages the grid, is off unless the session turns it on.
         */
        val Presto =
            DatabaseType(
                "Presto",
                "Presto",
                EngineFamily.Trino,
                8080,
                listOf(DriverId.Presto),
                capabilities = setOf(Capability.Explain),
                databaseHint = tr("catalog or catalog/schema"),
                defaultProperties = mapOf(DriverId.Presto to mapOf("sessionProperties" to "offset_clause_enabled:true")),
            )

        /**
         * Amazon Redshift through the PostgreSQL driver. Its catalog is its own, so there's no activity monitor and no
         * pg_dump; keys are informational, but rows are still edited by them.
         */
        val Redshift =
            DatabaseType(
                "Redshift",
                "Amazon Redshift",
                EngineFamily.PostgreSQL,
                5439,
                listOf(DriverId.PostgreSQL),
                databaseHint = "dev",
                defaultProperties = mapOf(DriverId.PostgreSQL to mapOf("sslmode" to "require")),
                dialect = SqlDialect.Redshift,
            )

        /** QuestDB's PostgreSQL wire endpoint: query-only, since its tables have no keys and it has no transactions. */
        val QuestDB =
            DatabaseType("QuestDB", "QuestDB", EngineFamily.PostgreSQL, 8812, listOf(DriverId.PostgreSQL), capabilities = setOf(Capability.Explain), databaseHint = "qdb", dialect = SqlDialect.QuestDB)

        /** OceanBase in MySQL mode (the Community Edition's only mode), through its own driver; its SQL port is 2881. */
        val OceanBase = DatabaseType("OceanBase", "OceanBase", EngineFamily.MySQL, 2881, listOf(DriverId.OceanBase, DriverId.MySQL))

        /**
         * MySQL-wire analytics engines, query-only like ClickHouse: they update rows only in their unique or primary-key
         * table models and take nothing but inserts in an explicit transaction.
         */
        val Doris = mysqlAnalytics("Doris", "Apache Doris", 9030)
        val StarRocks = mysqlAnalytics("StarRocks", "StarRocks", 9030)

        private fun mysqlAnalytics(id: String, name: String, port: Int) =
            DatabaseType(id, name, EngineFamily.MySQL, port, listOf(DriverId.MariaDB, DriverId.MySQL), capabilities = setOf(Capability.Explain), dialect = SqlDialect.MySQLAnalytics)

        // Engines with a JDBC driver of their own. Cloud warehouses take the account's host (or none, for Google's).

        /** Snowflake: the host is the account's (`<account>.snowflakecomputing.com`); the database field `DATABASE/SCHEMA`. */
        val Snowflake =
            DatabaseType(
                "Snowflake",
                "Snowflake",
                EngineFamily.Generic,
                443,
                listOf(DriverId.Snowflake),
                databaseHint = tr("DATABASE or DATABASE/SCHEMA"),
                defaultProperties =
                    mapOf(
                        DriverId.Snowflake to
                            mapOf(
                                // Arrow results need --add-opens on Java 17 and later; JSON results don't.
                                "JDBC_QUERY_RESULT_FORMAT" to "JSON",
                                // Metadata calls list the profile's database only, not every one the role can see.
                                "CLIENT_METADATA_REQUEST_USE_CONNECTION_CTX" to "true",
                            ),
                    ),
                dialect = SqlDialect.Snowflake,
            )

        /**
         * Databricks SQL warehouses and clusters: the host is the workspace's, the database field the HTTP path, and a
         * personal access token the password. Query-only: Delta tables update, but not in transactions.
         */
        val Databricks =
            DatabaseType(
                "Databricks",
                "Databricks",
                EngineFamily.Generic,
                443,
                listOf(DriverId.Databricks),
                capabilities = setOf(Capability.Explain),
                databaseHint = tr("HTTP path, e.g. /sql/1.0/warehouses/…"),
                defaultProperties = mapOf(DriverId.Databricks to mapOf("transportMode" to "http", "ssl" to "1", "AuthMech" to "3")),
                dialect = SqlDialect.SparkSql,
            )

        /**
         * Google BigQuery: the database field is the project (and default dataset); credentials are Application Default
         * Credentials unless the driver properties name a service account key. No EXPLAIN, no row editing.
         */
        val BigQuery =
            DatabaseType(
                "BigQuery",
                "Google BigQuery",
                EngineFamily.Generic,
                443,
                listOf(DriverId.BigQuery),
                capabilities = emptySet(),
                databaseHint = tr("project or project/dataset"),
                defaultProperties = mapOf(DriverId.BigQuery to mapOf("OAuthType" to "3")),
                dialect = SqlDialect.BigQuery,
            )

        /** Cloud Spanner (GoogleSQL): no host for Google's endpoint, or the emulator's with `autoConfigEmulator=true`. */
        val Spanner =
            DatabaseType(
                "Spanner",
                "Cloud Spanner",
                EngineFamily.Generic,
                443,
                listOf(DriverId.Spanner),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("project/instance/database"),
                dialect = SqlDialect.Spanner,
            )

        /** IBM Db2 for Linux, Unix and Windows; plans need explain tables, so there's no EXPLAIN. */
        val Db2 =
            DatabaseType(
                "Db2",
                "IBM Db2",
                EngineFamily.Generic,
                50000,
                listOf(DriverId.Db2),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("Database, e.g. SAMPLE"),
                dialect = SqlDialect.Db2,
            )

        /** SAP HANA: 3<instance>15 on-premises (30015 for instance 00), 443 with `encrypt=true` on HANA Cloud. */
        val Hana =
            DatabaseType(
                "Hana",
                "SAP HANA",
                EngineFamily.Generic,
                30015,
                listOf(DriverId.Hana),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("Tenant database (optional)"),
                dialect = SqlDialect.Hana,
            )
        val Vertica = DatabaseType("Vertica", "Vertica", EngineFamily.Generic, 5433, listOf(DriverId.Vertica), dialect = SqlDialect.Vertica)

        /** Exasol: the database field is the schema to open. Self-signed certificates need a `fingerprint` property. */
        val Exasol =
            DatabaseType(
                "Exasol",
                "Exasol",
                EngineFamily.Generic,
                8563,
                listOf(DriverId.Exasol),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("Schema (optional)"),
                dialect = SqlDialect.Exasol,
            )

        /** IBM Informix; the server name, if the driver needs one, is the `INFORMIXSERVER` property. */
        val Informix =
            DatabaseType(
                "Informix",
                "IBM Informix",
                EngineFamily.Generic,
                9088,
                listOf(DriverId.Informix),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                dialect = SqlDialect.Informix,
            )

        /** InterSystems IRIS; the database field is the namespace. */
        val Iris =
            DatabaseType(
                "Iris",
                "InterSystems IRIS",
                EngineFamily.Generic,
                1972,
                listOf(DriverId.Iris),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("Namespace, e.g. USER"),
                dialect = SqlDialect.Iris,
            )

        /** Microsoft Access files, through UCanAccess (no Access or Windows needed). */
        val Access =
            DatabaseType(
                "Access",
                "Microsoft Access",
                EngineFamily.Generic,
                null,
                listOf(DriverId.UCanAccess),
                capabilities = setOf(Capability.Transactions, Capability.EditRows),
                databaseHint = tr("/path/to/database.accdb"),
                dialect = SqlDialect.Access,
            )

        /** HiveServer2 engines: query-only, through Kyuubi's Hive driver. The database field is the database to open. */
        val Hive = hiveServer("Hive", "Apache Hive", 10000, SqlDialect.Hive)
        val SparkSql = hiveServer("SparkSql", tr("Spark SQL (Thrift server)"), 10000, SqlDialect.SparkSql)
        val Kyuubi = hiveServer("Kyuubi", "Apache Kyuubi", 10009, SqlDialect.SparkSql)

        /** Impala's HiveServer2 port; without authentication the driver needs `auth=noSasl`. */
        val Impala = hiveServer("Impala", "Apache Impala", 21050, SqlDialect.Impala)

        private fun hiveServer(id: String, name: String, port: Int, dialect: SqlDialect) =
            DatabaseType(id, name, EngineFamily.Hive, port, listOf(DriverId.Hive), capabilities = setOf(Capability.Explain), databaseHint = "default", dialect = dialect)

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

        /** Elasticsearch and OpenSearch over their REST API, built in. The user and password are HTTP basic auth. */
        val Elasticsearch = search("Elasticsearch", "Elasticsearch", EngineFamily.Elasticsearch, 9200, DriverId.ElasticRest)
        val OpenSearch = search("OpenSearch", "OpenSearch", EngineFamily.Elasticsearch, 9200, DriverId.ElasticRest)

        /** Meilisearch over its REST API, built in; the password is the API key. */
        val Meilisearch = search("Meilisearch", "Meilisearch", EngineFamily.Meilisearch, 7700, DriverId.Meilisearch)

        /** Wide-column stores over CQL; the database field is a keyspace to open first. */
        val Cassandra = wideColumn("Cassandra", "Apache Cassandra")
        val ScyllaDB = wideColumn("ScyllaDB", "ScyllaDB")

        /** Apache CouchDB over its HTTP API, built in; browsed as records, not with the MongoDB shell. */
        val CouchDB =
            DatabaseType("CouchDB", "Apache CouchDB", EngineFamily.CouchDB, 5984, listOf(DriverId.CouchDB), paradigm = Paradigm.Document, capabilities = emptySet(), api = ClientApi.Records)

        /**
         * Amazon DynamoDB over its HTTP API, built in: the host is the endpoint (`dynamodb.<region>.amazonaws.com`, or
         * DynamoDB Local's), the user the access key id and the password the secret key.
         */
        val DynamoDB =
            DatabaseType(
                "DynamoDB", "Amazon DynamoDB", EngineFamily.DynamoDB, 443, listOf(DriverId.DynamoDB), paradigm = Paradigm.Document, capabilities = emptySet(),
                api = ClientApi.Records,
            )

        /** InfluxDB 2 over its HTTP API, built in: the password is an API token, the database field the organization. */
        val InfluxDB =
            DatabaseType(
                "InfluxDB", "InfluxDB 2", EngineFamily.InfluxDB, 8086, listOf(DriverId.InfluxDB), paradigm = Paradigm.TimeSeries, capabilities = emptySet(),
                databaseHint = tr("Organization"),
            )

        /** VictoriaMetrics over its Prometheus-compatible API, built in; read-only. */
        val VictoriaMetrics =
            DatabaseType("VictoriaMetrics", "VictoriaMetrics", EngineFamily.Prometheus, 8428, listOf(DriverId.Prometheus), paradigm = Paradigm.TimeSeries, capabilities = emptySet())

        /** Neo4j over its HTTP API (port 7474), built in; the database field picks the database, `neo4j` when blank. */
        val Neo4j =
            DatabaseType("Neo4j", "Neo4j", EngineFamily.Neo4j, 7474, listOf(DriverId.Neo4j), paradigm = Paradigm.Graph, capabilities = emptySet(), databaseHint = "neo4j")

        /** Prometheus itself, through the same query API; read-only. */
        val Prometheus =
            DatabaseType("Prometheus", "Prometheus", EngineFamily.Prometheus, 9090, listOf(DriverId.Prometheus), paradigm = Paradigm.TimeSeries, capabilities = emptySet())

        /** InfluxDB 3 Core and Enterprise over their HTTP API, built in; read-only. The password is a token. */
        val InfluxDB3 =
            DatabaseType("InfluxDB3", "InfluxDB 3", EngineFamily.InfluxDB3, 8181, listOf(DriverId.InfluxDB3), paradigm = Paradigm.TimeSeries, capabilities = emptySet())

        /** Apache Solr over its HTTP API, built in: cores, or SolrCloud collections. */
        val Solr = search("Solr", "Apache Solr", EngineFamily.Solr, 8983, DriverId.Solr)

        /** Qdrant over its REST API, built in; the password is the API key. */
        val Qdrant = DatabaseType("Qdrant", "Qdrant", EngineFamily.Qdrant, 6333, listOf(DriverId.Qdrant), paradigm = Paradigm.Vector, capabilities = emptySet())

        /** RabbitMQ over its management API (the management plugin's port), built in. */
        val RabbitMQ = DatabaseType("RabbitMQ", "RabbitMQ", EngineFamily.RabbitMQ, 15672, listOf(DriverId.RabbitMQ), paradigm = Paradigm.Stream, capabilities = emptySet())

        /** Apache Kafka through the Kafka client: the host field takes the bootstrap servers. */
        val Kafka = DatabaseType("Kafka", "Apache Kafka", EngineFamily.Kafka, 9092, listOf(DriverId.Kafka), paradigm = Paradigm.Stream, capabilities = emptySet())

        private fun wideColumn(id: String, name: String) =
            DatabaseType(
                id, name, EngineFamily.Cassandra, 9042, listOf(DriverId.Cassandra), paradigm = Paradigm.WideColumn, capabilities = emptySet(),
                databaseHint = tr("Keyspace (optional)"),
            )

        private fun search(id: String, name: String, family: EngineFamily, port: Int, driver: DriverId) =
            DatabaseType(id, name, family, port, listOf(driver), paradigm = Paradigm.Search, capabilities = emptySet())

        private fun keyValue(id: String, name: String) =
            DatabaseType(id, name, EngineFamily.Redis, 6379, listOf(DriverId.Lettuce), paradigm = Paradigm.KeyValue, capabilities = emptySet())
        val H2 = DatabaseType("H2", "H2", EngineFamily.Generic, null, listOf(DriverId.H2), databaseHint = tr("/path/to/data (without .mv.db) or mem:test"))
        val HSQLDB = DatabaseType("HSQLDB", "HSQLDB", EngineFamily.Generic, null, listOf(DriverId.HSQLDB), databaseHint = tr("/path/to/data (without .script) or mem:test"))
        val Derby = DatabaseType("Derby", "Apache Derby", EngineFamily.Generic, null, listOf(DriverId.Derby), databaseHint = tr("/path/to/database directory"))

        /**
         * Data files. They speak SQLite's SQL, because that's the engine their working copy runs on; the SQLite driver is
         * what a profile needs on this machine. Each one's reader and writer is a [dev.tablegrip.core.datafile.DataFileFormat].
         */
        val CsvFile = dataFile("CsvFile", tr("CSV / TSV file"), "/path/to/data.csv")
        val ExcelFile = dataFile("ExcelFile", tr("Excel workbook"), "/path/to/workbook.xlsx")
        val JsonFile = dataFile("JsonFile", tr("JSON / NDJSON file"), "/path/to/records.json")

        private fun dataFile(id: String, name: String, hint: String) =
            DatabaseType(id, name, EngineFamily.SQLite, null, listOf(DriverId.SQLite), connection = ConnectionKind.DataFile, databaseHint = hint)

        /** Any JDBC driver: the user brings its jar and the URL. */
        val OtherJdbc =
            DatabaseType("OtherJdbc", tr("Other (JDBC driver)"), EngineFamily.Generic, null, listOf(DriverId.Generic), connection = ConnectionKind.Url)

        /** Every engine, in the order the connection dialog lists them. */
        val entries: List<DatabaseType> =
            listOf(
                PostgreSQL, MySQL, MariaDB, SQLite, SqlServer, Oracle, ClickHouse, DuckDB, MongoDB, Redis, Valkey,
                CockroachDB, TimescaleDB, YugabyteDB, Neon, Supabase, Redshift, QuestDB, TiDB, PlanetScale, OceanBase,
                Doris, StarRocks, AzureSql, Firebird, Trino, Presto,
                Snowflake, Databricks, BigQuery, Spanner, Db2, Hana, Vertica, Exasol, Informix, Iris,
                Hive, SparkSql, Impala, Kyuubi,
                KeyDB, Dragonfly, Etcd, FerretDB, Elasticsearch, OpenSearch, Meilisearch, Cassandra, ScyllaDB, CouchDB, DynamoDB, InfluxDB, InfluxDB3, VictoriaMetrics, Prometheus, Neo4j, Solr, Qdrant, RabbitMQ, Kafka, H2, HSQLDB, Derby, Access, OtherJdbc,
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
            return byId(id) ?: throw SerializationException(tr("Unknown database type \"{0}\". The profile may come from a newer version of TableGrip.", id))
        }
    }
}
