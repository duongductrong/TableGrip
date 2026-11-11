# Changelog

All notable changes to TableGrip are listed here, newest first. Each entry is written from the Conventional Commits
of its release and can be edited in the release PR before it is merged.

## [1.0.0-beta.3] - 2026-11-11

### ✨ Features
- Browse Apache HBase through its REST server (4a8b46f)
- Browse Nacos configurations and TDengine (fdc205b)
- Browse Weaviate, Milvus and Chroma (aa55404)
- Browse rqlite and libSQL / Turso, SQLite over HTTP (ca463f9)
- Browse Consul's key-value store and ZooKeeper znodes (8962977)
- Browse Apache Kafka topics (b62d63b)
- Add Solr, Qdrant, RabbitMQ, InfluxDB 3 and Prometheus (75cfd36)
- Browse search, wide-column, document, time-series and graph engines (35d48b5)
- Support 15 more databases through their own JDBC drivers (c78f73c)
- Support Redshift, QuestDB, OceanBase, Doris, StarRocks and Presto (1ab4442)
- **www:** Show how many databases TableGrip supports (bd92a3a)
- Review pending changes before submitting, and show database logos (#7) (e9f4c56)
- Introduce the MCP server on the website, with its docs (f939bf4)
- **mcp:** Let AI apps use your connections over MCP (d0f2c41)
- **updates:** Open the updater window as soon as Check for Updates is clicked (2a27842)
- Translate the interface into Vietnamese and Chinese (8e48314)
- **www:** Replace hero screenshot with an animated product demo (3719021)
- **www:** Refresh landing page sections, header and theme toggle (93d11f7)
- Support Oracle, Firebird, Trino and YugabyteDB (e8ad8e3)
- Introduce custom AppIcons and replace existing icons in ChartView and FixView (d93a967)

### 🐛 Bug Fixes
- Name ZooKeeper and Consul tabs by path, and make re-runs of the server tests repeatable (94d4354)
- Size SQL editor caret to the glyphs and centre it in the line (6087413)

### 🧰 Maintenance
- test: **mcp:** Split the test PATH with the platform's separator, so the setup test passes on Windows (0873e02)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/compare/v1.0.0-beta.2...v1.0.0-beta.3

## [1.0.0-beta.2] - 2026-11-05

### ✨ Features
- TableGrip, a desktop database client (e5749db)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/commits/v1.0.0-beta.2
