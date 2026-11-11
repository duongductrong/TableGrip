package dev.tablegrip.app.ui

import dev.tablegrip.core.connection.DatabaseType
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey

/** Icons of our own, beside the IntelliJ set; each has a `_dark` twin the dark theme picks up. */
internal object AppIcons {
    /** The agent's chat: a little robot. */
    val Agent: IconKey = PathIconKey("icons/robot.svg", AppIcons::class.java)

    /**
     * Engines with a logo under `icons/db`, by [DatabaseType.id]. The marks come from Simple Icons (CC0 SVG data) in
     * their official colours, exported by `assets/branding/tools/export_db_logos.py`; they are trademarks of their
     * owners (see NOTICE). Engines left out, such as SQL Server and Oracle, whose owners don't license their logos,
     * keep the initials badge.
     */
    private val EngineLogos: Set<String> =
        setOf(
            "PostgreSQL", "MySQL", "MariaDB", "SQLite", "ClickHouse", "DuckDB", "MongoDB", "Redis", "CockroachDB",
            "TimescaleDB", "Neon", "Supabase", "TiDB", "PlanetScale", "Trino", "Presto", "Doris", "OceanBase", "Etcd", "FerretDB", "H2", "JsonFile",
            "Snowflake", "Databricks", "BigQuery", "Spanner", "Hana", "Hive", "SparkSql",
            "Elasticsearch", "OpenSearch", "Meilisearch", "Cassandra", "ScyllaDB", "CouchDB", "InfluxDB", "VictoriaMetrics", "Neo4j",
            "Prometheus", "InfluxDB3", "Solr", "Qdrant", "RabbitMQ", "Kafka", "Consul",
        )

    private val engineLogoKeys = HashMap<String, IconKey>()

    /** The engine's logo, or null when it has none. */
    fun engineLogo(type: DatabaseType): IconKey? =
        if (type.id in EngineLogos) engineLogoKeys.getOrPut(type.id) { PathIconKey("icons/db/${type.id}.svg", AppIcons::class.java) } else null
}
