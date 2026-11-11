package dev.tablegrip.core.connection

import dev.tablegrip.core.i18n.tr
import java.net.URLEncoder
import java.sql.SQLException
import java.util.Properties
import kotlinx.serialization.Serializable

/**
 * The JDBC drivers TableGrip knows. None ships with the app: [catalogId] names the entry in the driver catalog it can
 * download (see [dev.tablegrip.core.drivers.DriverLibrary]); drivers without one (GPL or a vendor's license) are only
 * used from jars the user supplies, which they get from [vendorUrl] under [license].
 */
@Serializable
enum class DriverId(
    val displayName: String,
    val className: String,
    val scheme: String,
    val catalogId: String? = null,
    val vendorUrl: String? = null,
    val license: String? = null,
    /** For non-JDBC clients: the name of TableGrip's adapter jar that drives this client library. */
    val adapter: String? = null,
    /** Part of TableGrip itself: always ready, never downloaded. */
    val builtIn: Boolean = false,
) {
    PostgreSQL("PostgreSQL JDBC Driver", "org.postgresql.Driver", "jdbc:postgresql:", catalogId = "postgresql"),
    MySQL(
        "MySQL Connector/J",
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql:",
        vendorUrl = "https://dev.mysql.com/downloads/connector/j/",
        license = "GPL-2.0 with Universal FOSS Exception",
    ),
    MariaDB("MariaDB Connector/J", "org.mariadb.jdbc.Driver", "jdbc:mariadb:", catalogId = "mariadb"),

    /** OceanBase's fork of MariaDB Connector/J: MariaDB's own 3.x driver rejects the character set OceanBase 4 reports. */
    OceanBase("OceanBase Connector/J", "com.oceanbase.jdbc.Driver", "jdbc:oceanbase:", catalogId = "oceanbase"),
    SQLite("SQLite JDBC (Xerial)", "org.sqlite.JDBC", "jdbc:sqlite:", catalogId = "sqlite"),
    H2("H2 Database Engine", "org.h2.Driver", "jdbc:h2:", catalogId = "h2"),
    HSQLDB("HyperSQL (HSQLDB)", "org.hsqldb.jdbc.JDBCDriver", "jdbc:hsqldb:", catalogId = "hsqldb"),
    Derby("Apache Derby (embedded)", "org.apache.derby.iapi.jdbc.AutoloadedDriver", "jdbc:derby:", catalogId = "derby"),
    SqlServer("Microsoft JDBC Driver for SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", catalogId = "sqlserver"),
    ClickHouse("ClickHouse JDBC", "com.clickhouse.jdbc.Driver", "jdbc:clickhouse:", catalogId = "clickhouse"),
    DuckDB("DuckDB JDBC", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", catalogId = "duckdb"),

    /** Under Oracle's own license (FUTC), so the user brings ojdbc11.jar (or ojdbc8.jar) themselves. */
    Oracle(
        "Oracle JDBC (ojdbc)",
        "oracle.jdbc.OracleDriver",
        "jdbc:oracle:",
        vendorUrl = "https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html",
        license = "Oracle Free Use Terms and Conditions",
    ),
    Firebird("Jaybird (Firebird JDBC)", "org.firebirdsql.jdbc.FBDriver", "jdbc:firebird:", catalogId = "firebird"),
    Trino("Trino JDBC", "io.trino.jdbc.TrinoDriver", "jdbc:trino:", catalogId = "trino"),
    Presto("Presto JDBC", "com.facebook.presto.jdbc.PrestoDriver", "jdbc:presto:", catalogId = "presto"),

    /** Apache Kyuubi's shaded Hive driver: HiveServer2's protocol, spoken by Hive, Spark's Thrift server, Impala and Kyuubi. */
    Hive("Kyuubi Hive JDBC", "org.apache.kyuubi.jdbc.KyuubiHiveDriver", "jdbc:hive2:", catalogId = "hive"),
    Spanner("Cloud Spanner JDBC", "com.google.cloud.spanner.jdbc.JdbcDriver", "jdbc:cloudspanner:", catalogId = "spanner"),
    UCanAccess("UCanAccess (Microsoft Access)", "net.ucanaccess.jdbc.UcanaccessDriver", "jdbc:ucanaccess:", catalogId = "ucanaccess"),

    // Drivers the user brings: under their vendors' own licenses, or bundling code the catalog doesn't take (see
    // artifactLicenses in core/build.gradle.kts).
    Snowflake(
        "Snowflake JDBC",
        "net.snowflake.client.api.driver.SnowflakeDriver",
        "jdbc:snowflake:",
        vendorUrl = "https://docs.snowflake.com/en/developer-guide/jdbc/jdbc-download",
        license = "Apache-2.0, bundling CDDL-1.1 Java EE APIs",
    ),
    Databricks(
        "Databricks JDBC",
        "com.databricks.client.jdbc.Driver",
        "jdbc:databricks:",
        vendorUrl = "https://www.databricks.com/spark/jdbc-drivers-download",
        license = "Apache-2.0, bundling public-domain org.json",
    ),
    BigQuery(
        "Google BigQuery JDBC",
        "com.google.cloud.bigquery.jdbc.BigQueryDriver",
        "jdbc:bigquery:",
        vendorUrl = "https://cloud.google.com/bigquery/docs/reference/odbc-jdbc-drivers",
        license = "Apache-2.0, bundling public-domain org.json",
    ),
    Db2(
        "IBM Db2 JDBC (JCC)",
        "com.ibm.db2.jcc.DB2Driver",
        "jdbc:db2:",
        vendorUrl = "https://www.ibm.com/support/pages/db2-jdbc-driver-versions-and-downloads",
        license = "IBM International Program License Agreement",
    ),
    Hana(
        "SAP HANA JDBC (ngdbc)",
        "com.sap.db.jdbc.Driver",
        "jdbc:sap:",
        vendorUrl = "https://tools.hana.ondemand.com/#hanatools",
        license = "SAP Developer License Agreement",
    ),
    Vertica(
        "Vertica JDBC",
        "com.vertica.jdbc.Driver",
        "jdbc:vertica:",
        vendorUrl = "https://www.vertica.com/download/vertica/client-drivers/",
        license = "Vertica Client License Agreement",
    ),
    Exasol(
        "Exasol JDBC",
        "com.exasol.jdbc.EXADriver",
        "jdbc:exa:",
        vendorUrl = "https://downloads.exasol.com/clients-and-drivers/jdbc",
        license = "EXAClient License",
    ),
    Informix(
        "IBM Informix JDBC",
        "com.informix.jdbc.IfxDriver",
        "jdbc:informix-sqli:",
        vendorUrl = "https://central.sonatype.com/artifact/com.ibm.informix/jdbc",
        license = "IBM Informix JDBC Software License Agreement",
    ),
    Iris(
        "InterSystems IRIS JDBC",
        "com.intersystems.jdbc.IRISDriver",
        "jdbc:IRIS:",
        vendorUrl = "https://central.sonatype.com/artifact/com.intersystems/intersystems-jdbc",
        license = "InterSystems terms of use",
    ),

    /**
     * Not JDBC: the Lettuce Redis client, driven by TableGrip's own adapter ([adapter], bundled with the app) loaded
     * together with it. [className] is the adapter's entry point.
     */
    Lettuce("Lettuce (Redis client)", "dev.tablegrip.engines.redis.RedisClient", "redis:", catalogId = "lettuce", adapter = "redis"),
    MongoDB("MongoDB Java Driver", "dev.tablegrip.engines.mongodb.MongoClient", "mongodb:", catalogId = "mongodb", adapter = "mongodb"),
    Cassandra("Apache Cassandra Java Driver", "dev.tablegrip.engines.cassandra.CassandraClient", "cassandra:", catalogId = "cassandra", adapter = "cassandra"),
    Kafka("Apache Kafka client", "dev.tablegrip.engines.kafka.KafkaClient", "kafka:", catalogId = "kafka", adapter = "kafka"),

    /** Built in: TableGrip talks to etcd's v3 HTTP/JSON API itself, so there's nothing to download. */
    Etcd(tr("etcd v3 API (built in)"), "dev.tablegrip.core.driver.etcd.EtcdClient", "http:", builtIn = true),

    /** Built in: the Elasticsearch REST API, which OpenSearch speaks too. */
    ElasticRest(tr("Elasticsearch REST API (built in)"), "dev.tablegrip.core.driver.search.ElasticClient", "http:", builtIn = true),
    Meilisearch(tr("Meilisearch REST API (built in)"), "dev.tablegrip.core.driver.search.MeiliClient", "http:", builtIn = true),
    CouchDB(tr("CouchDB HTTP API (built in)"), "dev.tablegrip.core.driver.document.CouchClient", "http:", builtIn = true),
    DynamoDB(tr("DynamoDB HTTP API (built in)"), "dev.tablegrip.core.driver.document.DynamoClient", "https:", builtIn = true),
    InfluxDB(tr("InfluxDB 2 HTTP API (built in)"), "dev.tablegrip.core.driver.timeseries.InfluxClient", "http:", builtIn = true),
    Prometheus(tr("Prometheus query API (built in)"), "dev.tablegrip.core.driver.timeseries.PromClient", "http:", builtIn = true),
    Neo4j(tr("Neo4j HTTP API (built in)"), "dev.tablegrip.core.driver.graph.Neo4jClient", "http:", builtIn = true),
    InfluxDB3(tr("InfluxDB 3 HTTP API (built in)"), "dev.tablegrip.core.driver.timeseries.Influx3Client", "http:", builtIn = true),
    Solr(tr("Solr HTTP API (built in)"), "dev.tablegrip.core.driver.search.SolrClient", "http:", builtIn = true),
    Qdrant(tr("Qdrant REST API (built in)"), "dev.tablegrip.core.driver.vector.QdrantClient", "http:", builtIn = true),
    RabbitMQ(tr("RabbitMQ management API (built in)"), "dev.tablegrip.core.driver.stream.RabbitClient", "http:", builtIn = true),

    /** Whatever driver the profile's own jars contain; its class is found in them. */
    Generic(tr("JDBC driver from your jars"), "", "jdbc:");

    /** A java.sql.Driver, as opposed to a non-JDBC client (adapter or built in). */
    val isJdbc: Boolean
        get() = adapter == null && !builtIn

    companion object {
        /** The driver whose own scheme a JDBC URL uses. */
        fun forUrl(url: String): DriverId? = entries.firstOrNull { it != Generic && url.startsWith(it.scheme) }
    }
}

/** Builds JDBC URLs and driver properties for a [ConnectionConfig], following each driver's documented syntax. */
object JdbcUrls {

    /**
     * The URL to connect with. [via] replaces the host and port, e.g. with an SSH tunnel's local end. For a profile
     * with a raw [ConnectionConfig.url], only a single `//host[:port]` URL can be redirected.
     */
    fun build(config: ConnectionConfig, via: Pair<String, Int>? = null): String {
        config.url?.let { raw -> return adaptToDriver(if (via == null) raw else redirect(raw, via.first, via.second), config.effectiveDriver) }
        val driver = config.effectiveDriver
        if (config.type.connection == ConnectionKind.Url) throw SQLException(tr("{0} connects by JDBC URL only.", config.type.displayName))
        if (config.type.isFile) return fileUrl(config, driver)
        val hosts =
            if (via != null) formatHost(via.first, via.second)
            else
                config.host
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf("localhost") }
                    .joinToString(",") { entry -> if (hasPort(entry)) entry else formatHost(entry, config.port) }
        return when (driver) {
            // Attributes follow `;`; the database is one of them.
            DriverId.SqlServer -> "${driver.scheme}//$hosts" + (config.database.takeIf { it.isNotBlank() }?.let { ";databaseName=${it.replace(";", "{;}")}" } ?: "")
            // EZConnect: the database is the service name. Several hosts would need a TNS descriptor, so only the first is used.
            DriverId.Oracle -> "${driver.scheme}thin:@//${hosts.substringBefore(',')}/${config.database.trim()}"
            // The database is a path on the server or an alias; a path keeps its slashes (`//host:port//var/db.fdb`).
            DriverId.Firebird -> "${driver.scheme}//${hosts.substringBefore(',')}/${config.database.trim()}"
            // `catalog` or `catalog/schema`, each a path segment.
            DriverId.Trino, DriverId.Presto ->
                "${driver.scheme}//${hosts.substringBefore(',')}" +
                    config.database.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("") { "/" + encodePathSegment(it) }
            // `database` or `database/schema`, as query parameters.
            DriverId.Snowflake -> {
                val (database, schema) = pathParts(config.database)
                "${driver.scheme}//${hosts.substringBefore(',')}/" +
                    listOfNotNull(database?.let { "db=" + encodeQuery(it) }, schema?.let { "schema=" + encodeQuery(it) }).joinToString("&", prefix = "?").removeSuffix("?")
            }
            // The database field is the warehouse's or cluster's HTTP path; attributes follow `;`.
            DriverId.Databricks -> "${driver.scheme}//${hosts.substringBefore(',')}/default;httpPath=${config.database.trim()}"
            // The endpoint is the API's URL, with the port after its path; then the project and the default dataset.
            DriverId.BigQuery -> {
                val (project, dataset) = pathParts(config.database)
                val (host, port) =
                    via ?: (config.host.trim().removePrefix("https://").substringBefore(',').substringBefore(':').ifEmpty { "www.googleapis.com" } to (config.port ?: 443))
                "${driver.scheme}//https://$host/bigquery/v2:$port" + (project?.let { ";ProjectId=$it" } ?: "") + (dataset?.let { ";DefaultDataset=$it" } ?: "")
            }
            // The tenant database, if any, is a parameter; HANA Cloud and single-container systems need none.
            DriverId.Hana -> "${driver.scheme}//${hosts.substringBefore(',')}/" + (config.database.trim().takeIf { it.isNotEmpty() }?.let { "?databaseName=" + encodeQuery(it) } ?: "")
            // No `//`; the schema to open is an attribute.
            DriverId.Exasol -> "${driver.scheme}${hosts.substringBefore(',')}" + (config.database.trim().takeIf { it.isNotEmpty() }?.let { ";schema=$it" } ?: "")
            // The database is the Spanner path, `projects/p/instances/i/databases/d`; without a host, Google's endpoint.
            DriverId.Spanner -> {
                val parts = config.database.trim().trim('/').split('/').filter { it.isNotBlank() }
                val path = if (parts.size == 3) "projects/${parts[0]}/instances/${parts[1]}/databases/${parts[2]}" else parts.joinToString("/")
                if (via == null && config.host.isBlank()) "${driver.scheme}/$path" else "${driver.scheme}//${hosts.substringBefore(',')}/$path"
            }
            else -> "${driver.scheme}//$hosts/${encodePathSegment(config.database)}"
        }
    }

    /**
     * Driver properties: the user's, plus credentials and sensible defaults the user didn't set. Translates SSL
     * settings between the MySQL and MariaDB vocabularies so a profile keeps working when its driver is switched.
     */
    fun properties(config: ConnectionConfig, password: String?): Properties {
        val driver = config.effectiveDriver
        val props = Properties()
        config.properties.forEach { (k, v) -> props.setProperty(k, v) }
        config.type.defaultProperties[driver]?.forEach { (k, v) -> props.putIfAbsent(k, v) }
        if (config.user.isNotBlank()) props.setProperty("user", config.user)
        if (!password.isNullOrEmpty()) props.setProperty("password", password)
        when (driver) {
            DriverId.MySQL -> {
                // Connector/J waits forever by default; fail like the other drivers do.
                props.putIfAbsent("connectTimeout", "10000")
                props.putIfAbsent("connectionAttributes", "program_name:TableGrip")
                props.getProperty("sslMode")?.let { props.setProperty("sslMode", mariaToMysqlSslMode[it.lowercase()] ?: it) }
            }
            DriverId.MariaDB -> {
                props.putIfAbsent("connectTimeout", "10000")
                // Names TableGrip's sessions in performance_schema, like ApplicationName on PostgreSQL.
                props.putIfAbsent("connectionAttributes", "program_name:TableGrip")
                props.getProperty("sslMode")?.let { props.setProperty("sslMode", mysqlToMariaSslMode[it.uppercase()] ?: it) }
            }
            DriverId.OceanBase -> props.putIfAbsent("connectTimeout", "10000")
            // Names TableGrip's sessions in pg_stat_activity, for DBAs and for the activity monitor.
            DriverId.PostgreSQL -> props.putIfAbsent("ApplicationName", "TableGrip")
            DriverId.Oracle -> {
                props.putIfAbsent("oracle.net.CONNECT_TIMEOUT", "10000")
                // Shows as PROGRAM in v$session.
                props.putIfAbsent("v\$session.program", "TableGrip")
            }
            // Without a character set Jaybird uses NONE, which mangles anything outside the JVM's default charset.
            DriverId.Firebird -> {
                if (props.getProperty("encoding") == null && props.getProperty("charSet") == null) props.setProperty("encoding", "UTF8")
                props.putIfAbsent("connectTimeout", "10")
            }
            // The user is `token` for personal access tokens, which go as the password.
            DriverId.Databricks -> {
                props.putIfAbsent("UID", props.getProperty("user") ?: "token")
                props.getProperty("password")?.let { props.putIfAbsent("PWD", it) }
            }
            DriverId.Db2 -> {
                // Without it, errors read "DB2 SQL Error: SQLCODE=-204, SQLSTATE=42704…" instead of the server's message.
                props.putIfAbsent("retrieveMessagesFromServerOnGetMessage", "true")
                props.putIfAbsent("clientProgramName", "TableGrip")
                props.putIfAbsent("loginTimeout", "10")
            }
            DriverId.Exasol -> props.putIfAbsent("clientname", "TableGrip")
            // Double quotes delimit identifiers only with DELIMIDENT; otherwise they are strings.
            DriverId.Informix -> props.putIfAbsent("DELIMIDENT", "y")
            // Trino and Presto need a user even without authentication; the OS user is what their CLIs send.
            DriverId.Trino, DriverId.Presto -> {
                props.putIfAbsent("user", System.getProperty("user.name") ?: "tablegrip")
                // Presto's driver takes `applicationNamePrefix`, not `source`, and rejects properties it doesn't know.
                props.putIfAbsent(if (driver == DriverId.Trino) "source" else "applicationNamePrefix", "TableGrip")
            }
            else -> Unit
        }
        return props
    }

    private val mysqlToMariaSslMode =
        mapOf("DISABLED" to "disable", "PREFERRED" to "trust", "REQUIRED" to "trust", "VERIFY_CA" to "verify-ca", "VERIFY_IDENTITY" to "verify-full")
    private val mariaToMysqlSslMode =
        mapOf("disable" to "DISABLED", "trust" to "REQUIRED", "verify-ca" to "VERIFY_CA", "verify-full" to "VERIFY_IDENTITY", "allow" to "PREFERRED")

    /**
     * Makes a raw URL acceptable to the chosen driver: MariaDB Connector/J 3.x only takes `jdbc:mysql:` URLs that carry
     * `permitMysqlScheme` (it checks the URL itself, as does HikariCP), and Connector/J only takes `jdbc:mysql:`.
     */
    private fun adaptToDriver(url: String, driver: DriverId): String =
        when {
            driver == DriverId.MariaDB && url.startsWith(DriverId.MySQL.scheme) && "permitMysqlScheme" !in url ->
                url + (if ('?' in url) "&" else "?") + "permitMysqlScheme"
            driver == DriverId.MySQL && url.startsWith("${DriverId.MariaDB.scheme}//") -> DriverId.MySQL.scheme + url.removePrefix(DriverId.MariaDB.scheme)
            else -> url
        }

    /**
     * Embedded databases. In-memory ones would vanish with each pooled connection, so they're kept alive for the
     * profile's lifetime: SQLite through a shared cache, H2 by not closing on the last connection.
     */
    private fun fileUrl(config: ConnectionConfig, driver: DriverId): String {
        val path = config.database.trim().let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
        val memory = path.isEmpty() || path == ":memory:" || path.startsWith("mem:")
        val memoryName = path.removePrefix("mem:").takeIf { it.isNotEmpty() && it != ":memory:" } ?: "tablegrip-${config.id}"
        return when (driver) {
            DriverId.SQLite -> if (memory) "jdbc:sqlite:file:tablegrip-${config.id}?mode=memory&cache=shared" else "jdbc:sqlite:$path"
            DriverId.H2 -> if (memory) "jdbc:h2:mem:$memoryName;DB_CLOSE_DELAY=-1" else "jdbc:h2:file:$path"
            DriverId.HSQLDB -> if (memory) "jdbc:hsqldb:mem:$memoryName" else "jdbc:hsqldb:file:$path"
            DriverId.Derby -> if (memory) "jdbc:derby:memory:$memoryName;create=true" else "jdbc:derby:$path"
            // A named in-memory database is shared by every connection of the process, so the pool sees one database.
            DriverId.DuckDB -> if (memory) "jdbc:duckdb::memory:${memoryName.replace(Regex("[^A-Za-z0-9_]"), "_")}" else "jdbc:duckdb:$path"
            DriverId.UCanAccess -> if (memory) throw SQLException(tr("Choose an Access database file (.accdb or .mdb).")) else "jdbc:ucanaccess://$path"
            else -> throw SQLException(tr("{0} has no file URL form; connect by JDBC URL.", driver.displayName))
        }
    }

    fun formatHost(host: String, port: Int?): String {
        val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return if (port == null) bracketed else "$bracketed:$port"
    }

    private fun hasPort(entry: String): Boolean =
        if (entry.startsWith("[")) entry.substringAfter(']').startsWith(":") else entry.count { it == ':' } == 1

    private fun encodePathSegment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun encodeQuery(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** `first/second`, either part blank or missing. */
    private fun pathParts(value: String): Pair<String?, String?> {
        val parts = value.split('/', limit = 2).map { it.trim().ifEmpty { null } }
        return parts[0] to parts.getOrNull(1)
    }

    /** Points a `scheme//host[:port]/...` URL at another host and port; rejects multi-host and key-value forms. */
    fun redirect(url: String, host: String, port: Int): String {
        val start = url.indexOf("//").takeIf { it >= 0 } ?: throw SQLException(tr("Can't tunnel this URL: no //host part."))
        val authorityStart = start + 2
        val end = url.indexOfAny(charArrayOf('/', '?', ';'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, end)
        val userInfo = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        val hostPart = authority.substringAfterLast('@')
        if (',' in hostPart || '(' in hostPart || hostPart.startsWith("address=")) {
            throw SQLException(tr("SSH tunnels need a single-host URL; this one lists several hosts."))
        }
        return url.substring(0, authorityStart) + userInfo + formatHost(host, port) + url.substring(end)
    }
}
