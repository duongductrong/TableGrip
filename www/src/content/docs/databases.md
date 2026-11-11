---
title: Supported databases
description: Every database TableGrip connects to, grouped by family, with what each one supports.
---

TableGrip speaks to relational databases through JDBC, to Redis, MongoDB and Cassandra through their own clients (Kafka and ZooKeeper too), and to
etcd, Consul, Elasticsearch, OpenSearch, Meilisearch, Solr, CouchDB, DynamoDB, InfluxDB, VictoriaMetrics, Prometheus, Qdrant,
RabbitMQ and Neo4j over their HTTP APIs. Hosted
services that are compatible with a family (Neon, Supabase, PlanetScale, Azure SQL…) have their own presets, which set
sensible defaults such as TLS.

In all, TableGrip connects to **67 databases** and opens **3 kinds of data file** out of the box, and to any other
database you have a JDBC driver for.

## At a glance

| Family | Databases | Good to know |
| --- | --- | --- |
| **PostgreSQL** | PostgreSQL · CockroachDB · TimescaleDB · YugabyteDB · Neon · Supabase · Amazon Redshift | Server activity with lock trees, backup and restore with `pg_dump`, all-or-nothing DDL. Hosted presets turn on TLS. Redshift connects with the PostgreSQL driver, without server activity or `pg_dump`. |
| **MySQL** | MySQL · MariaDB · TiDB · PlanetScale · OceanBase | Server activity, backup and restore with `mysqldump`. Uses MariaDB Connector/J, or your own MySQL Connector/J. OceanBase uses its own driver, OceanBase Connector/J. |
| **SQL Server & Oracle** | SQL Server · Azure SQL · Oracle · Firebird | All-or-nothing DDL on SQL Server. Azure SQL comes with encryption on. |
| **Analytics** | ClickHouse · DuckDB · Trino · Presto · Apache Doris · StarRocks · QuestDB | Query-only except DuckDB (no row editing or transactions). DuckDB opens a file or `:memory:`. Trino and Presto connect to one catalog. Doris and StarRocks speak the MySQL protocol, QuestDB the PostgreSQL one. |
| **Cloud warehouses** | Snowflake · Databricks · Google BigQuery · Cloud Spanner | Spanner edits rows in transactions and runs against its emulator too. Databricks is query-only; BigQuery has no row editing or `EXPLAIN`. Bring the Snowflake, Databricks and BigQuery JDBC jars: each bundles code under a license the catalog doesn't take. |
| **Enterprise** | IBM Db2 · SAP HANA · Vertica · Exasol · IBM Informix · InterSystems IRIS | Row editing and transactions. Bring the vendor's JDBC jar: their licenses don't allow TableGrip to download them. |
| **Hadoop & Spark** | Apache Hive · Spark SQL (Thrift server) · Apache Impala · Apache Kyuubi | Query-only, through Apache Kyuubi's Hive JDBC driver, which TableGrip downloads. |
| **Embedded** | SQLite · H2 · HSQLDB · Apache Derby · Microsoft Access | Open a file, or an in-memory database for H2 and HSQLDB. SQLite backs up with `VACUUM INTO`. Access `.accdb` and `.mdb` files open through UCanAccess, without Access or Windows. |
| **Key-value** | Redis · Valkey · KeyDB · Dragonfly · etcd · Consul · Apache ZooKeeper | Redis standalone, Cluster and Sentinel, TLS. etcd and Consul need no download. Consul keys and ZooKeeper znodes are browsed by folder and written with check-and-set. |
| **Document** | MongoDB · FerretDB · Apache CouchDB · Amazon DynamoDB | `mongodb://` and `mongodb+srv://` URLs. CouchDB browses with Mango queries and writes by revision; DynamoDB with PartiQL, signed with your AWS keys. Both need no download. |
| **Search** | Elasticsearch · OpenSearch · Meilisearch · Apache Solr | Indices, aliases and mappings; documents by query string or query DSL; a Dev Tools console. Edits check the version they were read at. No download. |
| **Wide-column** | Apache Cassandra · ScyllaDB | Keyspaces and tables, paged rows by `WHERE`, a CQL console. Rows round-trip as Cassandra's own JSON. |
| **Time series** | InfluxDB 2 · InfluxDB 3 · VictoriaMetrics · Prometheus | Buckets and measurements with Flux, InfluxDB 3 tables with SQL; metrics with PromQL. Read-only. No download. |
| **Vector** | Qdrant | Collections and points by filter; payloads edited, points added with their vector. No download. |
| **Messaging** | Apache Kafka · RabbitMQ | Kafka topics paged by partition and offset without a consumer group, messages produced, consumer group lag in the console. RabbitMQ queues through the management API: messages peeked (and requeued) and published, no download. |
| **Graph** | Neo4j | Labels and relationship types, a Cypher console. Edits set only the properties that changed. No download. |
| **Data files** | CSV / TSV · Excel `.xlsx` · JSON / NDJSON | Opened as tables. Committed changes are written back to the file. |
| **Anything else** | Any other JDBC database | Bring the driver's jar. |

## What works where

Not every feature makes sense for every database. This table shows the main differences.

| Feature | PostgreSQL family | MySQL family | SQL Server | SQLite | DuckDB | ClickHouse |
| --- | --- | --- | --- | --- | --- | --- |
| Edit rows in the grid | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| All-or-nothing schema changes | ✓ | one by one | ✓ | ✓ | ✓ | one by one |
| [Server activity](/docs/activity) | ✓ | ✓ | — | — | — | — |
| [Backup & restore](/docs/backup-and-restore) | `pg_dump` / `pg_restore` | `mysqldump` / `mysql` | — | `VACUUM INTO` | `EXPORT DATABASE` | — |

"One by one" means statements are applied in order and stop at the first failure; the dialog tells you this before you
run anything.

> [!NOTE]
> Rows of a table without a primary key can be inserted, but not updated or deleted from the grid, because TableGrip
> could not tell which row you meant.

## Databases TableGrip doesn't list

Any database with a JDBC driver works. Choose **Other (JDBC driver)**, or paste a `jdbc:` URL such as
`jdbc:teradata://…` or `jdbc:dm://…`, then pick the driver's `.jar` file. See
[Drivers](/docs/drivers#your-own-jars).
