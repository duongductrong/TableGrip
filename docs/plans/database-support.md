# Database support plan: catch up with and surpass TablePro

Survey of `/Applications/TablePro.app` (v0.82.0, PluginKit 39) and the plugin registry
`https://updates.tablepro.app/plugins/v3.json`, dated 2026-10-28.

## 1. What TablePro supports

TablePro is a native Swift app. Each driver is a `.tableplugin` bundle that declares
`TableProProvidesDatabaseTypeIds`. 5 drivers ship with the app, and 21 more are downloaded from the registry when the user needs them.

### Bundled with the app

| Plugin | Database type | Notes |
| --- | --- | --- |
| MySQLDriver | MySQL, MariaDB, TiDB, Databend, OceanBase | One driver for the whole MySQL wire protocol family |
| PostgreSQLDriver | PostgreSQL, Redshift, CockroachDB, PGlite | One driver for the whole PG wire protocol family |
| SQLiteDriver | SQLite | Ships with the sample DB `Chinook.sqlite` |
| ClickHouseDriver | ClickHouse | HTTP; also supports old servers (< 22.3) |
| RedisDriver | Redis | Standalone, **Cluster** (hash slot, hash tag, topology detection, migrating slots), **Sentinel**, switching DB 0–15, SCAN/HSCAN/SSCAN/ZSCAN, all of string/hash/list/set/zset/stream, TTL editing, MULTI/EXEC |

### Downloaded from the registry

| Group | Database type |
| --- | --- |
| SQL / OLTP | SQL Server, Oracle, SAP HANA, Teradata, Dameng, libSQL / Turso, Cloudflare D1, Spanner |
| Analytics / warehouse | DuckDB, BigQuery, Snowflake, Trino, Presto, Cloudflare R2 SQL |
| Document | MongoDB, Firestore, Firebase Realtime Database, SurrealDB |
| Wide-column | Cassandra, ScyllaDB, Bigtable |
| Key-value | etcd, DynamoDB |
| Search / vector | Elasticsearch, Typesense, Weaviate |
| Stream | Kafka |
| Other | Beancount (plain-text accounting ledger) |

**Total: 37 database types** across 26 driver plugins.

### What TablePro's architecture shows

From the symbols of `TableProPluginKit.framework`:

- A **navigation model / paradigm** per driver: `hierarchicalSchema` (SQL), `keyList` (Redis, etcd),
  `documentInspector` (Mongo, Firestore...). The UI picks screens by paradigm, not by DB name.
- An **EditorLanguage** per driver: SQL, MQL, Redis command, CQL...
- Declared **capabilities**: transactions, rename database/schema, privileges, foreign keys, DDL...
- Several **auth** types: password, TLS client key (with passphrase), AWS access key / credential process / web identity,
  GCP service account key, MFA.
- Import/export is also plugin-based: CSV, JSON, SQL, XLSX, Markdown, MQL (Mongo), Parquet.

## 2. DataGrok today

| Item | Current state | Problem when expanding |
| --- | --- | --- |
| `DatabaseType` (`core/.../connection/ConnectionConfig.kt`) | Enum with 4 values: PostgreSQL, MySQL, MariaDB, SQLite | Closed enum, serialized into profiles; adding a type means editing code everywhere |
| `DriverId` (`JdbcDriver.kt`) + `core/build.gradle.kts` | 4 JDBC drivers packaged with the app (`runtimeOnly`), loaded with `Class.forName` | Packaging a driver is **redistributing** it. MySQL Connector/J is GPLv2 (+ FOSS exception), so the app currently ships a GPL library |
| `Database` (`driver/Database.kt`) | Tightly coupled to HikariCP + `java.sql.Connection` | No room for Redis, Mongo, HTTP APIs |
| `SqlDialect` | `sealed class`, 3 dialects; `of()` is a closed `when` | Fine for the SQL family, only needs to be opened up |
| `StructureReader` | Separate catalog queries for each engine | Each new SQL engine needs a reader |
| Metadata model | `Namespace(catalog, schema)`, `TableRef`, `ColumnInfo` | Purely relational |
| Branching on type | ~20 files in `app` and `core` (`ConnectionDialog`, `ConsoleTab`, `GridController`, `SqlCompleter`, `ConnectionString`...) | Must switch to asking about capabilities |

**DataGrok's big advantage:** it runs on the JVM. Almost every SQL engine has an official JDBC driver on Maven Central,
and the NoSQL engines have official Java SDKs. TablePro has to write a Swift driver (or embed a Go helper) for each engine;
DataGrok only needs a dialect + introspection.

## 3. Goals

DataGrok is a **closed-source** product. Every licensing choice below follows from this.

1. Cover all 37 TablePro types, plus popular engines TablePro does not have.
2. **The app bundles no drivers.** The installer contains only DataGrok code and base libraries with permissive licenses.
   Users download a driver the first time they need it.
3. **No license risk:** DataGrok does not redistribute, mirror, or automatically download drivers under a strong
   copyleft license or a vendor proprietary license.
4. Surpass TablePro with **Generic JDBC (bring your own driver)**: users point to a `.jar` file they already have.

## 4. Driver and license policy

### 4.1 Principles

- **No bundling.** Remove all `runtimeOnly(libs.jdbc.*)` from `core/build.gradle.kts`; drivers remain only in
  `testImplementation` (tests are not distributed).
- **Users download from the original source.** When needed, the app downloads the artifact directly from Maven Central (`repo1.maven.org`), not through
  a DataGrok server/mirror: re-hosting the file is redistribution. Before downloading, ask the user, showing the license name, the link to
  the license text, and the file size.
- **The catalog contains only drivers that pass the license check** (table 4.2). Drivers that fail can only be used the BYO way:
  the app has a preset (dialect, URL template, class name) but **never downloads automatically**; the user picks a jar file they got from the vendor
  and accepts the vendor's terms themselves.
- **No modifying or shading drivers.** Load them unmodified in a separate classloader; the user can swap the jar at any time.
  This also satisfies the LGPL conditions.
- **Check the whole dependency tree.** Snowflake, BigQuery, Mongo... pull in dozens of jars. The lockfile (4.3) lists the license
  of *each* artifact in the tree; one artifact outside the allowlist removes the whole driver from the catalog.
- **Client ≠ server.** Server licenses (Redis RSAL/SSPL/AGPL, MongoDB SSPL, Elasticsearch ELv2/SSPL/AGPL) do not
  apply to client libraries or to DataGrok; only the client library's license matters.

### 4.2 Classification

Licenses are taken from the POM on Maven Central (latest version, 2026-10-28); "needs verification" means the POM has no license or an unusual one.

| Group | Condition | Drivers |
| --- | --- | --- |
| **A: Catalog, one-click download** | Apache-2.0, MIT, BSD, MPL-2.0, EPL | PostgreSQL JDBC (BSD-2), SQLite Xerial (Apache), SQL Server `mssql-jdbc` (MIT), ClickHouse (Apache), DuckDB (MIT), Redshift (Apache), Snowflake (Apache), BigQuery JDBC (Apache), Spanner JDBC (Apache), Databricks JDBC (Apache), H2 (MPL/EPL), Lettuce (MIT), MongoDB driver (Apache), jetcd (Apache), Kafka clients (Apache), Elasticsearch-java & OpenSearch-java (Apache), AWS SDK v2, Google Cloud libs (Apache) |
| **A\*: Catalog after verification** | POM declares no license, upstream source says Apache-2.0 | Trino JDBC, Presto JDBC, Cassandra java-driver, Neo4j java-driver: verify the license in the parent POM / repo, record it in the lockfile |
| **B: Catalog, with conditions** | LGPL (weak copyleft), used unmodified, loaded dynamically, user downloads | MariaDB Connector/J (LGPL-2.1), Jaybird / Firebird (LGPL) |
| **C: BYO only, never auto-download** | GPL, or a vendor proprietary license | MySQL Connector/J (GPLv2 + FOSS exception), Oracle `ojdbc` (FUTC), SAP HANA `ngdbc` (SAP Developer License), Teradata (Generic Download License), IBM Db2 `jcc` (IPLA), Exasol (EXAClient License), Vertica (Client License Agreement) |
| **C?: Treat as C until verified** | POM differs from reality, or a separate license | Dameng `DmJdbcDriver18` (POM says Apache but it is a vendor driver), Athena JDBC (POM has no license), Weaviate Java client ("Weaviate B.V. License") |
| **D: No third-party driver needed** | DataGrok code calls HTTP/the protocol directly | libSQL / Turso (Hrana HTTP), Cloudflare D1 & R2 SQL (REST), Firebase RTDB (REST), SurrealDB (HTTP `/sql`), Typesense (REST), Weaviate (REST/GraphQL, replaces the client in row C?), Athena (via AWS SDK v2 instead of JDBC) |

### 4.2.1 Group B (LGPL) with a closed-source app

MariaDB Connector/J (LGPL) is the **only driver** for the whole MySQL family (MySQL, MariaDB, TiDB, OceanBase, Databend,
StarRocks, Doris, SingleStore...) once Connector/J is removed. **Proposal:** keep it in the catalog, downloaded by the user on demand, on
these conditions:

1. DataGrok **does not bundle or mirror** the LGPL jar; the user downloads it directly from Maven Central.
2. **No modifying, shading, or merging** the jar; it is always a separate file, loaded dynamically, and the user can replace it with another build
   (BYO for the same engine).
3. Before downloading, show **LGPL-2.1**, the link to the license text, and the link to the upstream source code.
4. The **DataGrok EULA** does not forbid users from replacing this library, or from reverse engineering to the extent needed to debug that replacement
   (LGPL-2.1 §6).

Legal needs to confirm **once for the whole group B policy** (applies to both MariaDB Connector/J and Jaybird). If it
is not approved, group B moves to C: the MySQL family and Firebird become BYO only.

**Do not follow TablePro on this point.** TablePro statically links MariaDB Connector/C and FreeTDS (LGPL; FreeTDS is even
modified) into the installer. They can do this because TablePro is open source under AGPL-3.0, so the obligations to "allow relinking" and "publish
modifications" are met automatically. A closed-source app that bundles an LGPL library must take on those obligations itself; not bundling
avoids them entirely.

**Alternative considered, not chosen:** bundle two permissive drivers (PostgreSQL JDBC BSD-2, SQLite Xerial
Apache-2.0) so the first run needs no network. There is no legal risk (TablePro and TablePlus both bundle libpq/SQLite),
but it goes against the decision "bundle no drivers"; it can be revisited if the first-run experience becomes a problem.

### 4.3 Driver manager

- **Lockfile generated at build time:** a Gradle task resolves the full dependency tree of each driver in the catalog and writes
  `drivers.lock.json`: coordinate, Maven Central URL, sha256, and license of each jar. The task fails if any license is outside the
  allowlist (A, verified A\*, B). The lockfile is packaged in the app; at runtime the app just downloads exactly that list, with no
  Maven resolver.
- **Storage:** `DatagrokPaths/drivers/<id>/<version>/`; verify sha256 before loading; users can delete or change versions in Settings.
- **A separate classloader per driver.** The current code already calls `java.sql.Driver` directly instead of `DriverManager`. HikariCP in particular
  must receive a `DataSource` wrapping that driver instance instead of `driverClassName` (Hikari loads classes through the context classloader).
- **Adapters for non-JDBC SDKs** (Redis, Mongo, Kafka...): the adapter is DataGrok code that lives in the app, compiled against the SDK
  as `compileOnly`; at runtime the adapter is loaded with the same classloader as the SDK jars just downloaded, found through `ServiceLoader`.
- **BYO:** pick one or more jars (or a folder), and the app scans them for `java.sql.Driver` classes; it stores the path and does not copy the files.
  Group C presets only prefill the class name, URL template, and the vendor's download page link (opens the browser, the user downloads it themselves).
- **Enterprise / offline environments:** configure a private Maven mirror (Artifactory, Nexus) or import drivers from an existing
  folder; sha256 is still checked against the lockfile.
- **First-run experience:** see 4.4.

### 4.4 Detecting the driver and suggesting a download when the user enters a connection

Goal: users do not need to know what a "driver" is. They can enter a connection any way they like; DataGrok identifies the engine,
picks a suitable driver, and offers to download it right in the form, before they click Connect.

#### Signal sources (in decreasing order of reliability)

| Signal | When | What it identifies |
| --- | --- | --- |
| **Engine chosen by the user** in the dialog | Always present | The engine's default driver (`Engine.drivers.first()`) |
| **Pasted connection string / URL** | The "Paste" field and URL field, if filled | Extend `ConnectionString.parse` (currently only PG/MySQL/MariaDB/SQLite) into a scheme → engine table: `postgres(ql)://`, `mysql://`, `mariadb://`, `sqlserver://`, `mongodb(+srv)://`, `redis(s)://`, `clickhouse://`, `libsql://`, `jdbc:<subprotocol>:` (sqlserver, oracle, clickhouse, duckdb, snowflake, bigquery, trino, databricks, db2, sap, teradata...). Unknown subprotocol → Generic JDBC |
| **File** (picked, dragged and dropped, opened with DataGrok) | File-based engines | Read magic bytes, do not trust the file extension: `SQLite format 3\0` → SQLite; DuckDB header → DuckDB; `.parquet`/`.csv` → DuckDB; H2 `.mv.db`; Firebird `.fdb` |
| **Port** when the engine is unknown | The user only entered host:port | A hint only: 5432 PG, 3306 MySQL, 1433 SQL Server, 1521 Oracle, 6379 Redis, 27017 MongoDB, 8123/9000 ClickHouse, 9042 Cassandra, 9200 Elasticsearch, 2379 etcd, 9092 Kafka, 7687 Neo4j |
| **Server probe** (handshake, before login) | Click "Detect", or when the engine is only known at the family level | MySQL wire: the server sends a greeting first → the version string distinguishes MySQL / MariaDB / TiDB / OceanBase. Redis wire: `HELLO` → Redis / Valkey. HTTP: `GET /` on port 9200 → Elasticsearch / OpenSearch, port 8123 → ClickHouse |
| **After connecting** | First Test/Connect | `SELECT version()` and similar → CockroachDB, Redshift, YugabyteDB, TimescaleDB... on PG wire; suggest switching the engine preset (dialect), keeping the same driver |

The server probe only sends to the host the user entered, through the SSH tunnel if there is one, with a 3-second timeout, and **does not send login
credentials**; it can be turned off in Settings.

#### Detection result

```kotlin
// core/driver/DriverResolution.kt
sealed interface DriverResolution {
    data class Ready(val driver: InstalledDriver) : DriverResolution
    data class Download(val entry: CatalogEntry, val license: String, val bytes: Long, val jarCount: Int) : DriverResolution // groups A, B
    data class UpdateAvailable(val installed: InstalledDriver, val entry: CatalogEntry) : DriverResolution
    data class BringYourOwn(val preset: ByoPreset, val vendorUrl: String) : DriverResolution                   // group C
    data object GenericJdbc : DriverResolution                                                                  // not recognized
}

object DriverDetector {
    fun fromConnectionString(text: String): Detection?          // engine + confidence + filled fields
    fun fromFile(path: Path): Detection?
    suspend fun probe(host: String, port: Int, via: SshTunnel?): ServerHint?
    fun resolve(engine: Engine, drivers: DriverManager): DriverResolution
}
```

Detection lives in `core` and does not depend on the UI; `ConnectionDialog` only displays the `DriverResolution`.

#### Experience in the connection dialog

- A **banner right below the engine / URL field**, updated live as the user types (debounce ~300 ms), with no blocking
  dialog:
  - Group A: "Driver **PostgreSQL JDBC 42.7.14** required · BSD-2-Clause · 1.1 MB · [Download]"
  - Group B: "Driver **MariaDB Connector/J** required · LGPL-2.1 ([license], [source code]) · 0.7 MB · [Download]"
  - Group C: "Oracle needs an `ojdbc` driver that you provide yourself · [Choose .jar file...] [Open Oracle download page]"
  - Already installed: "✓ Driver ready" (dimmed); newer version in the lockfile: "A newer driver version is available · [Update]"
  - Not recognized: "Using Generic JDBC · [Choose .jar file...]"
- Clicking **Download** starts a background download right away, and the user can keep filling in the form; the banner shows progress. If Test Connection / Connect is clicked
  during the download, it waits for the download to finish and then continues; the button shows "Downloading driver...".
- Clicking Connect with no driver installed: show the same content as the banner as a confirmation step, without adding a new screen.
- **Never download automatically without a user click.** Settings has an option (off by default) "Auto-download drivers with
  permissive licenses (group A)"; group B always asks; group C never downloads.
- Errors: network lost → suggest "Import driver from folder" or configuring a Maven mirror; sha256 mismatch → refuse and report it clearly.

#### Other entry points that share this flow

- Opening a saved profile on a machine without the driver (new machine, synced settings).
- Importing several profiles at once: group them as "3 connections need 2 drivers · [Download all]" (group B still lists licenses).
- Dragging and dropping a database file into the window.
- Old profile that selected Connector/J: `BringYourOwn` for Connector/J, with a suggestion to switch to MariaDB Connector/J.

#### Phasing

- **Phase 0:** engine chosen in the dialog, connection string, file, banner + background download, and the entry points above. This is the
  condition for removing driver bundling without hurting the experience.
- **Phase 1:** server probe (MySQL greeting, Redis `HELLO`, HTTP) and engine refinement after connecting; the scheme table
  grows as each engine is added.

#### Testing

- A test table for every scheme / JDBC subprotocol → engine → `DriverResolution` (including group C and unknown subprotocols).
- Sample files for magic bytes (SQLite, DuckDB, empty file, file with a wrong extension).
- Probing with Testcontainers: MySQL, MariaDB, TiDB, Redis, Valkey, Elasticsearch, OpenSearch, ClickHouse.
- UI tests for the banner: switching engine mid-download, Connect during download, network loss.

### 4.5 Base libraries still bundled in the app

They must still pass the same allowlist: Compose, Jewel, kotlinx (Apache), HikariCP (Apache), sshj (Apache), java-keyring,
logback (dual EPL-1.0 / LGPL-2.1 → use under EPL). Add a license check step to CI (e.g. the
`com.github.jk1.dependency-license-report` plugin) and generate `THIRD-PARTY-NOTICES` into the installer.

## 5. Architecture to build first (Phase 0)

Without this phase, every engine added will add another `when` branch in 20 files.

### 5.1 Engine descriptor instead of an enum

```kotlin
// core/connection/Engine.kt
data class Engine(
    val id: String,                 // "postgresql", "redshift", "redis"... — stored in the profile
    val displayName: String,
    val family: EngineFamily,       // PostgresWire, MySqlWire, SQLite, MSSQL, Oracle, Redis, Mongo, ...
    val paradigm: Paradigm,         // Relational, KeyValue, Document, WideColumn, Search, Stream, Graph
    val defaultPort: Int?,
    val connectionFields: List<Field>,  // host/port/db/user, file path, account, project id, region...
    val authMethods: List<AuthMethod>,  // Password, TlsClientCert, AwsProfile/Keys/IAM, GcpServiceAccount, Token, OAuth
    val capabilities: Set<Capability>,  // Transactions, Schemas, ForeignKeys, Explain, EditRows, DDL, Ttl, Cluster...
    val drivers: List<DriverSpec>,  // Catalog(lockfile id) | Byo(className, urlTemplate, vendorUrl) | BuiltIn(http adapter)
)
```

- Profiles store `engineId: String`. When reading an old profile, map `"PostgreSQL" → "postgresql"`... in `ConnectionStore`, with a migration
  test.
- Old profile that selected `driver = MySQL` (Connector/J): when opened, offer to switch to MariaDB Connector/J or point to the Connector/J
  jar the user already has.
- The UI asks about `capabilities` / `paradigm`, not the engine name. `ConnectionDialog` draws the form from `connectionFields` +
  `authMethods`.

### 5.2 Split `Database` into interfaces by paradigm

```kotlin
sealed interface Connection : AutoCloseable { val engine: Engine }
interface RelationalConnection : Connection { /* current Database API: namespaces, tables, fetchPage, ... */ }
interface KeyValueConnection : Connection { scanKeys, keyType, read/write value, ttl, databases }
interface DocumentConnection : Connection { databases, collections, find(filter, sort, page), insert/update/delete doc, indexes }
interface StreamConnection : Connection { topics, partitions, consume(offset), produce }
```

- The current `Database` becomes `JdbcConnection : RelationalConnection`, keeping its behavior unchanged.
- Non-JDBC engines that are table-shaped (Cassandra/CQL, DynamoDB PartiQL, D1/libSQL HTTP) still implement `RelationalConnection`
  to reuse the grid, filter, and console.
- Extend `ResultColumn` with a JSON/document type so the grid can render nested cells.

### 5.3 SQL dialect open by family

- `SqlDialect` goes from `sealed` to `open`, inherited by family: `PostgreSQL → Redshift, CockroachDB, Greenplum...`;
  `MySQL → TiDB, OceanBase, StarRocks...`; `SQLite → libSQL, D1`; add `Generic`, which relies only on `DatabaseMetaData`.
- Move into the dialect: paging (`OFFSET ... FETCH`, `TOP`), quoting (`[x]`), splitter (`GO`, PL/SQL blocks ending with `/`),
  Explain, keywords for the lexer/completion.
- Logic tied to Connector/J (`fetchSize = Int.MIN_VALUE` in `Database.kt`, the `sslMode` mapping in `JdbcUrls`) moves to
  the MariaDB driver's behavior as the default.
- `StructureReader` has a default implementation using `DatabaseMetaData` (for BYO / generic), and the main engines override it.

### 5.4 Connection and authentication

- SSH tunnel for TCP engines; HTTP proxy for HTTP engines.
- `SecretStore` additions: TLS client cert/key, AWS (profile, access key, `credential_process`, SSO), GCP service account /
  ADC, API token, OAuth browser flow.
- Topology for Redis/Mongo/Cassandra: seed node, Cluster, Sentinel, replica set, `mongodb+srv`.

**Phase 0 estimate:** 3–4 weeks (about 1 week more than the previous version, for the driver manager, lockfile, license checks, and
moving the MySQL family to the MariaDB driver). Done when:
- the installer contains no driver jars (checked by CI);
- PostgreSQL, MySQL, MariaDB, and SQLite run through the new architecture with downloaded drivers, and all existing tests pass;
- old profiles can be read, including profiles that selected Connector/J;
- entering a connection by engine, connection string, or file all show the correct driver download suggestion (4.4).

**Status (2026-10-29, branch `feature/phase0-driver-library`):**
- Done, driver part: catalog + `drivers.lock.json` + per-jar license check (`:core:verifyDriverLock`);
  `DriverLibrary` (download from Maven Central or a mirror, sha256 check, separate classloader, user-supplied jars, import from a
  folder); driver bundling removed and enforced by `:app:verifyNoBundledDrivers`; the MySQL family defaults to MariaDB Connector/J; driver
  banner in the connection dialog; "Driver Needed" dialog; Settings → Drivers page.
- Done, architecture part:
  - `DatabaseType` went from an enum to an engine descriptor (`id`, `EngineFamily`, `Paradigm`, `Capability`, drivers, port); old profiles
    can still be read because `id` matches the old enum names. Adding an engine in the same family only needs one `DatabaseType` declaration (with a test).
  - `SqlDialect` is open by family (`PostgreSQLDialect`, `MySQLDialect`, `SQLiteDialect`); per-engine logic (default
    namespace, EXPLAIN, completion functions, quoting rules) moved into the dialect. The UI asks about `Capability` (Explain, Transactions).
  - `RelationalConnection` (a child of `sealed interface DatabaseConnection`); `Database` is the JDBC version, and the app keeps only the interface.
  - File detection by magic bytes (`DatabaseFiles`): SQLite; a pasted path without an extension is still recognized, and a file that is not a
    database triggers a warning / is blocked in the dialog.
  - `THIRD-PARTY-NOTICES.txt` is generated at build time into app.jar; every bundled library must have an approved license
    (`:app:thirdPartyNotices`, run in `check`).
- Deliberately deferred until an engine needs them (to avoid building ahead of users):
  - `KeyValueConnection` / `DocumentConnection` interfaces... → Phase 2 together with Redis / MongoDB.
  - Connection form generated from `connectionFields`, extended authentication (TLS client cert, AWS, GCP, token, OAuth — 5.4) → Phase 3–4.
  - Magic bytes for DuckDB → Phase 1 together with DuckDB.
- Needs a human review before release: `THIRD-PARTY-NOTICES.txt` currently lists libraries + licenses + SPDX links; the
  release build should include the full license texts, copyright lines, and NOTICE files of Apache-2.0 libraries — a job for legal.
- A profile with an unknown `type` (from a newer DataGrok version) currently breaks the whole `connections.json` file on read; it should skip just that
  profile instead of the whole file — handle this when adding the first engine in Phase 1.

## 6. Engine roadmap

Sizes: **S** ≤ 2 days · **M** ~1 week · **L** 2–3 weeks. The "Group" column follows table 4.2.

### Phase 1: SQL family (≈3–4 weeks)

**Status (2026-10-29):**
- Done, the `RelationalContract` suite (seed → namespace → table → key → paging → filter → count → value suggestions → DDL →
  data editing → console) has been run on real engines:
  - In-process: H2, HSQLDB, Derby, DuckDB (memory and file), "Other (JDBC)" with a user-supplied jar.
  - Docker servers (`ServerEnginesIntegrationTest`, opt-in `-Pit.*`): SQL Server 2022, ClickHouse 25.3, CockroachDB 24.3,
    TimescaleDB (PG 17), TiDB 8.5; plus the existing PostgreSQL 17 and MySQL 8.4 (MySQL now goes through MariaDB Connector/J).
- Added but no server available to run against: Neon, Supabase (PostgreSQL + `sslmode=require`), PlanetScale (MySQL + TLS),
  Azure SQL (SQL Server + `encrypt`). They share the driver/dialect with the already-tested base engine.
- New families: `Generic` (standard SQL through JDBC metadata), `SqlServer` (`[]`, OFFSET/FETCH, `GO`, SHOWPLAN, `sys.*`), `ClickHouse`
  (no transactions, no row editing, DDL from `SHOW CREATE`), `DuckDB` (DDL from `duckdb_tables()`); `CockroachDBDialect` is a
  child of PostgreSQL. Engines declare `Capability.EditRows`; ClickHouse turns off row editing.
- Test Connection recognizes the real engine within the same family (CockroachDB/TimescaleDB behind PostgreSQL, TiDB/MariaDB/MySQL) and suggests
  changing the type; hosted services (Neon, Supabase, PlanetScale, Azure SQL) get no suggestion.
- An unknown `jdbc:` URL, when pasted, now becomes an "Other (JDBC)" profile instead of being rejected; `;password=` in the URL is moved out to the keychain.
- Profiles with an unknown engine (from a newer version) are skipped on read and kept unchanged on write.
- Remaining in Phase 1: Oracle (BYO preset, PL/SQL dialect), Redshift, YugabyteDB, Greenplum, OceanBase, Databend,
  StarRocks, Doris, SingleStore, Firebird; server probing before login (MySQL greeting, HTTP) — currently detection only happens
  after connecting.

| Engine | Driver | Group | Size | Notes |
| --- | --- | --- | --- | --- |
| Generic JDBC (BYO) | user's jar | C | M | Priority #1: it is the entry point for every group C driver |
| SQL Server, Azure SQL | `mssql-jdbc` | A | M | T-SQL: `[]`, `OFFSET/FETCH`, `GO`, `SHOWPLAN_XML`, Entra ID auth |
| ClickHouse | `clickhouse-jdbc` | A | M | No FKs, DDL via `SHOW CREATE` |
| DuckDB, MotherDuck | `duckdb_jdbc` | A | S | File engine like SQLite; opens Parquet/CSV directly |
| PG family: Redshift, CockroachDB, Greenplum, YugabyteDB, TimescaleDB, Neon, Supabase, PGlite | PG JDBC (Redshift has its own driver) | A | S each | Child dialect + SSL preset |
| MySQL family: TiDB, OceanBase, Databend, StarRocks, Doris, SingleStore, PlanetScale | MariaDB Connector/J | B | S each | Depends on the decision in 4.2 |
| H2, HSQLDB, Derby, Firebird | H2 / HSQLDB / Derby / Jaybird | A / B | S each | Cheap, adds coverage |
| Oracle | `ojdbc` | **C (BYO)** | L | DataGrok handles the dialect, PL/SQL splitter, `DBMS_METADATA`, TNS/wallet; users get ojdbc themselves |

### Phase 2: key-value and document (≈5–6 weeks)

**Status (2026-10-29): Phase 2 done — Redis, MongoDB, etcd.**
- Architecture for non-JDBC clients: a DataGrok adapter (module `engines/redis`, compiled against core + Lettuce as
  `compileOnly`) is packaged in the app at `engines/redis.jar`, not on the classpath; `DriverLibrary.loadClient` loads it
  with the same classloader as the Lettuce jars the user downloaded (15 jars, 7.7 MB, MIT/Apache/MIT-0). MongoDB and etcd will follow the same
  path.
- `KeyValueConnection` (a child of `DatabaseConnection`): keyspace, paged SCAN, read/edit 6 types (string, hash, list,
  set, zset, stream), TTL, rename, delete, console. `KeyValueStore` handles the SSH tunnel and blocks writes on read-only profiles (the console only
  runs commands from the read-command list).
- Engines: Redis, Valkey, KeyDB, Dragonfly. Standalone mode, Cluster (`mode=cluster`), Sentinel (`mode=sentinel` +
  `sentinelMaster`), TLS (`ssl=true`), ACL user.
- UI: the explorer shows db0…db15 (hiding empty dbs except db0) with key counts; Keys tab (glob filter, load more, type badge, TTL,
  type-specific editing, create key, rename, set/clear TTL, delete); redis-cli style Console tab (history ↑/↓, follows `SELECT`).
- Tested on real servers: Redis 7.4, Valkey 8.1, Redis Cluster 7.0 (6 nodes); the Lettuce download path from Maven Central + adapter loading from the app;
  UI tests with screenshots.
- MongoDB: adapter `engines/mongodb` on MongoDB Java driver 5.14 (4 jars, 2.8 MB, Apache-2.0), using the same download/load path
  as Redis. `DocumentConnection`: database, collection (estimated document count), `find` with shell-style filter/sort
  (Extended JSON, `ObjectId('…')`, `ISODate('…')`), paging, count, insert/replace/delete by `_id`, indexes, console.
  Connects by host/port (a single host gives `directConnection=true`) or a `mongodb://` / `mongodb+srv://` string (the password is moved
  out to the keychain). The console is a subset of the mongo shell that does not run JavaScript: `show dbs|collections`, `use`,
  `db.<coll>.find(…).sort(…).limit(…)`, `findOne`, `countDocuments`, `distinct`, `aggregate`, `insertOne/Many`,
  `updateOne/Many`, `replaceOne`, `deleteOne/Many`, indexes, `db.runCommand/adminCommand`. Read-only profiles can only run read
  commands (a pipeline with `$out`/`$merge` counts as a write).
- MongoDB UI: database → collection explorer; Documents tab (a grid where each top-level field is a column, types inferred from
  values, ObjectId/date shown by value; the JSON of the selected document for editing, with add and multi-row delete).
- Tested on a real MongoDB 8.0 (single-node replica set): adapter, console, read-only, connection string, driver download + adapter loading,
  UI tests with screenshots.
- Bugs found and fixed: the adapter now sets the thread context class loader to its own classloader when calling into the client (Lettuce
  looks up the JFR class through the TCCL and could pick the wrong copy from another classloader); the `lockDrivers` task declares the lockfile contents as an
  input (before, Gradle treated it as up to date when the catalog changed).
- etcd: the client is built into the app (`DriverId.Etcd`, `builtIn`), calling etcd's HTTP/JSON API v3 with `java.net.http` — nothing to
  download, no third-party library (group D), replacing `jetcd` (gRPC + Vert.x + Netty, dozens of jars). It reuses all of
  `KeyValueConnection` and the key-value UI: glob patterns become a prefix range + filtering of the rest; TTL is a lease (editing a
  value keeps the lease, clearing the TTL = detaching the lease); rename is a transaction (only if the new name does not exist yet); etcdctl-style console (`get
  --prefix`, `put`, `del`, `lease grant|revoke|timetolive|list`, `member list`, `endpoint status`). User/password authentication
  gets a token; TLS (`ssl=true`, `verifyPeer=false`). Read-only profiles only run etcdctl read commands.
- Real servers were run for every Phase 2 engine: Redis 7.4, Valkey 8.1, KeyDB 6.3, Dragonfly 2.0, Redis Sentinel, Redis
  Cluster 7.0; MongoDB 8.0 (replica set), FerretDB 2.7 (with authentication); etcd 3.5 (with and without authentication). KeyDB and
  FerretDB are identified by their correct names in `server()`.
- Remaining limitations (noted, not blocking the phase): the Redis console in Cluster mode sends commands without a key (e.g. `FLUSHALL`,
  `DBSIZE`) to a single node; MongoDB sharded, real Atlas, and AWS DocumentDB/Azure Cosmos have not been tried; etcd over TLS has not been tried.

| Engine | Driver | Group | Size | Notes |
| --- | --- | --- | --- | --- |
| Redis, Valkey, KeyDB, Dragonfly, Upstash | Lettuce | A | L | KeyValue paradigm: key tree, paged SCAN, type-specific editor, TTL, DB selection, Cluster + Sentinel, command console |
| MongoDB | MongoDB Java driver | A | L | Document paradigm: grid + JSON inspector, filter builder that generates MQL, MQL console, indexes, `mongodb+srv` |
| etcd | jetcd | A | M | Reuses the KeyValue UI |

### Phase 3: cloud warehouse (≈4 weeks)

| Engine | Driver | Group | Size | Notes |
| --- | --- | --- | --- | --- |
| Snowflake | `snowflake-jdbc` | A | M | Warehouse/role picker, key-pair + SSO |
| BigQuery | BigQuery JDBC (Google, Apache) | A | M | Estimate bytes scanned before running |
| Trino, Presto | `trino-jdbc`, `presto-jdbc` | A\* | S | Verify the license first |
| Databricks SQL | `databricks-jdbc` | A | M | |
| Athena | AWS SDK v2 (own adapter) | D | M | Avoids Athena JDBC, whose license is unclear |
| Spanner | `google-cloud-spanner-jdbc` | A | M | GoogleSQL and PostgreSQL dialect |

### Phase 4: wide-column and cloud NoSQL (≈5 weeks)

| Engine | Driver | Group | Size | Notes |
| --- | --- | --- | --- | --- |
| Cassandra, ScyllaDB | Cassandra java-driver | A\* | M | CQL; respect the partition key, token-based paging |
| DynamoDB | AWS SDK v2 | A | M | Scan/Query + PartiQL, GSI/LSI |
| Firestore | `google-cloud-firestore` | A | M | Document paradigm |
| Firebase RTDB | REST | D | M | |
| Bigtable | `google-cloud-bigtable` | A | M | |
| libSQL / Turso, Cloudflare D1, R2 SQL | Custom HTTP | D | M | SQLite dialect, HTTP transport |

### Phase 5: search, stream, multi-model (≈5 weeks)

| Engine | Driver | Group | Size | Notes |
| --- | --- | --- | --- | --- |
| Elasticsearch, OpenSearch | elasticsearch-java / opensearch-java | A | L | index → grid, mapping, Query DSL console |
| Kafka | `kafka-clients` | A | L | Topic/partition/offset, JSON/Avro decoding, produce, consumer lag |
| Neo4j | `neo4j-java-driver` | A\* | L | Cypher, grid first, graph view later |
| SurrealDB, Typesense, Weaviate | Custom HTTP | D | M each | |

### Phase 6: enterprise (BYO presets only, no download)

SAP HANA, Teradata, IBM Db2, Exasol, Vertica, Dameng (until verification is done). Each is **S–M**: preset + dialect +
structure reader; users supply the driver themselves. This is where DataGrok still "supports" an engine without touching the license.

**Skipped:** Beancount (too niche).

## 7. Testing

- Testcontainers / `docker-compose` for engines that have an image (the way `-Pit.postgres=` already works). Drivers in tests are
  `testImplementation` and do not go into the installer.
- Shared contract tests for each paradigm; every engine must pass the part matching the capabilities it declares.
- Group C engines: run only on dev machines/internal CI that already have a valid driver (an environment variable points to the jar); never commit jars to the repo.
- Tests for the driver manager: a wrong sha256 is rejected, a license outside the allowlist makes the lockfile task fail, running offline with
  an import folder, BYO finds the correct driver class.
- CI: check that the installer artifact contains no third-party `java.sql.Driver` and that every jar in it is on the allowlist.

## 8. Milestones

| Milestone | Content | Number of engines (cumulative) |
| --- | --- | --- |
| M0 | Phase 0: architecture + driver manager + license policy | 4 |
| M1 | Phase 1: BYO, SQL Server, ClickHouse, DuckDB, PG & MySQL families, embedded Java DBs, Oracle (BYO) | ~28 + any JDBC via BYO |
| M2 | Phase 2: Redis family, MongoDB, etcd | ~35 |
| M3 | Phase 3: warehouse | ~42 |
| M4 | Phase 4: wide-column & cloud NoSQL | ~50 |
| M5 | Phase 5: search, Kafka, Neo4j | ~56 |
| M6 | Phase 6: enterprise presets | ~62 |

Total ≈ 7 months for one person; from M1 onward the work can run in parallel because each engine is an independent module.

## 9. Risks

- **Wrong license classification:** table 4.2 is based on POMs, and many POMs are incomplete. Every driver must be confirmed by a human reviewer
  before it enters the lockfile; get legal's opinion once for the policy (especially group B, section 4.2.1) rather than per driver.
- **No open-source alternatives on the JVM:** TablePro avoids proprietary drivers with libraries that implement the protocol themselves
  (`oracle-nio` for Oracle, `go-hdb` for SAP HANA, both Apache-2.0). The JVM has no equivalent that is mature enough, so
  Oracle and HANA in DataGrok remain BYO. Running `go-hdb` as a sidecar process like TablePro could be considered if one-click HANA is needed.
- **Vendor changes license in a new version:** the lockfile pins the version and license; upgrading a version re-runs the check.
- **Slower first-run experience** because drivers must be downloaded, even PostgreSQL/SQLite. Mitigation: the download banner is right in the connection
  dialog, and the download runs in the background while the user fills in the form.
- **No network:** the driver folder import flow and an internal Maven mirror are needed from Phase 0.
- **MariaDB driver with MySQL 8+:** differs from Connector/J in a few places (`caching_sha2_password`, `sslMode`, streaming fetch);
  run integration tests on MySQL 5.7 / 8.x / 9.x with the MariaDB driver before removing Connector/J.
- **JDBC metadata is unreliable:** BYO/generic is "best effort"; the main engines still need their own structure reader.
- **UI scope:** KeyValue, Document, and Stream are three new sets of screens; do Redis and Mongo really well first, then reuse them.
