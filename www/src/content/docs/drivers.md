---
title: Drivers
description: How TableGrip gets the driver for each database, keeps it verified, and works offline.
---

TableGrip ships **without any database driver**. Drivers have their own licenses and release cycles, so instead of
bundling them, the app downloads the one you need the first time you connect, and checks it before using it.

## What happens on first connect

1. When you pick a database in the connection dialog, it tells you which driver that database needs and whether it is
   already installed.
2. If it is missing, TableGrip offers to download it from **Maven Central** (or the mirror you set in
   **Settings → Drivers**).
3. Every downloaded `.jar` is checked against a **SHA-256 hash pinned in the app**. A file that doesn't match is
   rejected.
4. The driver is stored in `~/.tablegrip/drivers/` and reused for every later connection.

Each driver runs in its own class loader, so two versions of a driver, or two drivers that would clash, never interfere
with each other.

## Kinds of drivers

| Kind | Used for | Where it comes from |
| --- | --- | --- |
| **Catalog drivers** | PostgreSQL, MariaDB Connector/J (also for MySQL), SQLite, SQL Server, ClickHouse, DuckDB, H2, HSQLDB, Derby, Jaybird (Firebird), Trino JDBC, Presto JDBC, OceanBase Connector/J, Kyuubi Hive JDBC (Hive, Spark SQL, Impala, Kyuubi), Cloud Spanner JDBC, UCanAccess (Microsoft Access) | Downloaded from Maven Central and verified |
| **Non-JDBC clients** | Redis (Lettuce), MongoDB (the MongoDB Java driver), Cassandra and ScyllaDB (the Apache Cassandra Java driver) | Downloaded the same way; TableGrip's own adapter for each ships inside the app |
| **Built in** | etcd, Elasticsearch, OpenSearch, Meilisearch, Solr, CouchDB, DynamoDB, InfluxDB 2 and 3, VictoriaMetrics, Prometheus, Qdrant, RabbitMQ, Neo4j | Nothing to download: TableGrip speaks their HTTP APIs itself |
| **Your own jars** | MySQL Connector/J, Oracle, Snowflake, Databricks, BigQuery, IBM Db2, SAP HANA, Vertica, Exasol, IBM Informix, InterSystems IRIS, any other JDBC driver | You choose the `.jar` files in the connection dialog; it links to each vendor's download page |

## MySQL and MariaDB

MySQL connections use **MariaDB Connector/J** by default: it is licensed so TableGrip can download it for you, and it
talks to MySQL servers well. If you prefer Oracle's **MySQL Connector/J** (GPL licensed, so it can't be distributed
with TableGrip), download it yourself and pick its jar.

Either way, TableGrip writes the connection URL in the chosen driver's own scheme, translates `sslMode` values
between the two drivers, and sets a 10-second connect timeout where the driver would otherwise wait forever.

## Your own jars

For databases TableGrip has no catalog entry for, or drivers it can't distribute:

1. Choose **Other (JDBC driver)** as the database type, or paste a `jdbc:` URL in **New Connection → Paste**.
2. Choose the driver's `.jar` file (and any jars it depends on).
3. Extra driver settings go in **Options → Driver properties**.

## Working offline

On a machine without internet access, use **Import from folder…** to install catalog drivers from jars that are
already on disk, for example copied from another computer's `~/.tablegrip/drivers/`. They are checked against the same
pinned hashes as a download.

> [!TIP]
> Behind a corporate proxy or with an internal artifact repository? Set a Maven mirror in **Settings → Drivers** and
> every catalog driver downloads from there instead.

## Why drivers aren't bundled

- **Licenses.** Some drivers can't be redistributed (MySQL Connector/J is GPL). Others must be a separate download
  under their license (LGPL). Keeping all of them out of the installer keeps the app's license simple.
- **Size.** You only download the drivers for the databases you actually use.
- **Trust.** Pinned hashes mean a driver can't be swapped on the way to your machine.

Developers can read how the catalog and its lockfile are maintained in [Driver catalog & licenses](/docs/driver-catalog).
