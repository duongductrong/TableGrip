# Changelog

All notable changes to TableGrip are listed here, newest first. Each entry is written from the Conventional Commits
of its release and can be edited in the release PR before it is merged.

## [1.0.0-beta.7] - 2026-11-12

### 🐛 Bug Fixes
- Mark a table load as in progress before it starts (#16) (c49d665)
- Website follows the system theme by default instead of forcing light (f3067da)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/compare/v1.0.0-beta.6...v1.0.0-beta.7

## [1.0.0-beta.6] - 2026-11-12

### ✨ Features
- Implement ChartView and integrate with ConsoleTab and TableDataTab (71af32f)

### 🐛 Bug Fixes
- Run the update-signing jobs on Intel runners (#14) (c3210ff)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/compare/v1.0.0-beta.5...v1.0.0-beta.6

## [1.0.0-beta.5] - 2026-11-12

### ✨ Features
- Adjust ConnectionDialog size and improve driver selection layout (090403c)
- Implement password prompt for connections and add SSL options (7185f75)
- Added functionality to navigate to the last page in TableDataTab (c06029e)
- Website downloads follow the newest release in the feed (f8301c3)
- Introduce Welcome window with recent connections and enhance timeline structure (587c61a)
- Refactor BrandBackdrop for improved visual consistency and clarity (8a87974)
- Enhance session management and UI with recent connections and welcome window (12cf131)
- Added hover submenu for improved menu navigation (fb8da2a)
- Reopen closed tabs, and tree-wide actions beside the add menu (5810f5c)
- Visual EXPLAIN plans and Explain Analyze in the SQL console (#11) (02cf58e)
- Functions, procedures, sequences and materialized views in the explorer (#12) (ddc723a)

### 🧰 Maintenance
- test: Wait for the drivers page, not the library, after removing a driver (550e5eb)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/compare/v1.0.0-beta.4...v1.0.0-beta.5

## [1.0.0-beta.4] - 2026-11-11

### ✨ Features
- Complete in the record console's language (#9) (27a4d61)
- Script console with completion, and results as an editable grid, JSON or tree (b4a2888)
- Add macOS menu bar support with settings and functionality (2c06a64)
- Enhance UpdatesPanel with improved UI and functionality (a4bdb86)

### Contributors
[@duongductrong](https://github.com/duongductrong)

**Full Changelog**: https://github.com/duongductrong/TableGrip/compare/v1.0.0-beta.3...v1.0.0-beta.4

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
