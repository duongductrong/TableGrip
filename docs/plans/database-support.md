# Kế hoạch hỗ trợ database: đuổi kịp và vượt TablePro

Khảo sát `/Applications/TablePro.app` (v0.82.0, PluginKit 39) và plugin registry
`https://updates.tablepro.app/plugins/v3.json`, ngày 2026-10-28.

## 1. TablePro hỗ trợ gì

TablePro là app Swift native, mỗi driver là một bundle `.tableplugin` khai báo
`TableProProvidesDatabaseTypeIds`. 5 driver đi kèm app, 21 driver tải thêm từ registry khi người dùng cần.

### Đi kèm app (bundled)

| Plugin | Database type | Ghi chú |
| --- | --- | --- |
| MySQLDriver | MySQL, MariaDB, TiDB, Databend, OceanBase | Một driver cho cả họ MySQL wire protocol |
| PostgreSQLDriver | PostgreSQL, Redshift, CockroachDB, PGlite | Một driver cho cả họ PG wire protocol |
| SQLiteDriver | SQLite | Kèm sẵn DB mẫu `Chinook.sqlite` |
| ClickHouseDriver | ClickHouse | HTTP, hỗ trợ cả server cũ (< 22.3) |
| RedisDriver | Redis | Standalone, **Cluster** (hash slot, hash tag, phát hiện topology, slot đang migrate), **Sentinel**, đổi DB 0–15, SCAN/HSCAN/SSCAN/ZSCAN, đủ kiểu string/hash/list/set/zset/stream, sửa TTL, MULTI/EXEC |

### Tải từ registry

| Nhóm | Database type |
| --- | --- |
| SQL / OLTP | SQL Server, Oracle, SAP HANA, Teradata, Dameng, libSQL / Turso, Cloudflare D1, Spanner |
| Analytics / warehouse | DuckDB, BigQuery, Snowflake, Trino, Presto, Cloudflare R2 SQL |
| Document | MongoDB, Firestore, Firebase Realtime Database, SurrealDB |
| Wide-column | Cassandra, ScyllaDB, Bigtable |
| Key-value | etcd, DynamoDB |
| Search / vector | Elasticsearch, Typesense, Weaviate |
| Stream | Kafka |
| Khác | Beancount (sổ kế toán dạng text) |

**Tổng: 37 database type** qua 26 driver plugin.

### Những gì kiến trúc TablePro cho thấy

Từ symbol của `TableProPluginKit.framework`:

- **Navigation model / paradigm** theo từng driver: `hierarchicalSchema` (SQL), `keyList` (Redis, etcd),
  `documentInspector` (Mongo, Firestore…). UI chọn màn hình theo paradigm, không theo tên DB.
- **EditorLanguage** theo driver: SQL, MQL, Redis command, CQL…
- **Capabilities** khai báo: transaction, rename database/schema, privileges, foreign keys, DDL…
- **Auth** nhiều kiểu: password, TLS client key (có passphrase), AWS access key / credential process / web identity,
  GCP service account key, MFA.
- Import/export cũng là plugin: CSV, JSON, SQL, XLSX, Markdown, MQL (Mongo), Parquet.

## 2. DataGrok hiện tại

| Thứ | Hiện trạng | Vấn đề khi mở rộng |
| --- | --- | --- |
| `DatabaseType` (`core/.../connection/ConnectionConfig.kt`) | enum 4 giá trị: PostgreSQL, MySQL, MariaDB, SQLite | Enum đóng, được serialize vào profile; thêm type phải sửa khắp nơi |
| `DriverId` (`JdbcDriver.kt`) + `core/build.gradle.kts` | 4 JDBC driver đóng gói kèm app (`runtimeOnly`), load bằng `Class.forName` | Đóng gói driver là **phân phối lại** driver. Trong đó MySQL Connector/J là GPLv2 (+ FOSS exception), tức app đang mang theo một thư viện GPL |
| `Database` (`driver/Database.kt`) | Gắn chặt HikariCP + `java.sql.Connection` | Không có chỗ cho Redis, Mongo, HTTP API |
| `SqlDialect` | `sealed class`, 3 dialect; `of()` là `when` đóng | Ổn cho họ SQL, chỉ cần mở ra |
| `StructureReader` | Truy vấn catalog riêng cho từng engine | Mỗi engine SQL mới cần một reader |
| Model metadata | `Namespace(catalog, schema)`, `TableRef`, `ColumnInfo` | Hoàn toàn theo quan hệ |
| Rẽ nhánh theo type | ~20 file ở `app` và `core` (`ConnectionDialog`, `ConsoleTab`, `GridController`, `SqlCompleter`, `ConnectionString`…) | Phải chuyển sang hỏi capability |

**Lợi thế lớn của DataGrok:** nằm trên JVM. Gần như mọi engine SQL đều có JDBC driver chính chủ trên Maven Central,
và các NoSQL đều có Java SDK chính chủ. TablePro phải viết driver Swift (hoặc nhúng helper Go) cho từng engine;
DataGrok chỉ cần dialect + introspection.

## 3. Mục tiêu

DataGrok là sản phẩm **đóng nguồn**. Mọi lựa chọn giấy phép bên dưới xuất phát từ điều này.

1. Phủ toàn bộ 37 type của TablePro, cộng thêm các engine phổ biến TablePro chưa có.
2. **App không đóng gói driver nào.** Bản cài chỉ có code của DataGrok và các thư viện nền có giấy phép permissive.
   Người dùng tải driver khi lần đầu cần đến.
3. **Không dính rủi ro giấy phép:** DataGrok không phân phối lại, không mirror, không tự động tải driver có giấy phép
   copyleft mạnh hoặc giấy phép độc quyền của vendor.
4. Vượt TablePro nhờ **Generic JDBC (bring your own driver)**: người dùng tự trỏ tới file `.jar` họ có.

## 4. Chính sách driver và giấy phép

### 4.1 Nguyên tắc

- **Không bundle.** Bỏ toàn bộ `runtimeOnly(libs.jdbc.*)` khỏi `core/build.gradle.kts`; driver chỉ còn ở
  `testImplementation` (test không được phân phối).
- **Người dùng tự tải, từ nguồn gốc.** Khi cần, app tải artifact thẳng từ Maven Central (`repo1.maven.org`), không qua
  server/mirror của DataGrok: tự host lại file là phân phối lại. Trước khi tải, hỏi người dùng, hiện tên giấy phép, link
  văn bản giấy phép và dung lượng.
- **Catalog chỉ chứa driver qua được kiểm tra giấy phép** (bảng 4.2). Driver không qua chỉ dùng được theo kiểu BYO:
  app có preset (dialect, mẫu URL, tên class) nhưng **không bao giờ tự tải**; người dùng chọn file jar họ tự lấy từ vendor
  và tự chấp nhận điều khoản của vendor.
- **Không sửa, không shade driver.** Load nguyên bản trong classloader riêng, người dùng có thể thay jar bất kỳ lúc nào.
  Điều này cũng giữ đúng điều kiện của LGPL.
- **Kiểm tra cả cây phụ thuộc.** Snowflake, BigQuery, Mongo… kéo theo hàng chục jar. Lockfile (4.3) liệt kê giấy phép
  của *từng* artifact trong cây; một artifact ngoài allowlist là cả driver bị loại khỏi catalog.
- **Client ≠ server.** Giấy phép của server (Redis RSAL/SSPL/AGPL, MongoDB SSPL, Elasticsearch ELv2/SSPL/AGPL) không
  áp lên client library hay lên DataGrok; chỉ xét giấy phép của thư viện client.

### 4.2 Phân loại

Giấy phép lấy từ POM trên Maven Central (bản mới nhất, 2026-10-28); "cần xác minh" là POM không ghi hoặc ghi bất thường.

| Nhóm | Điều kiện | Driver |
| --- | --- | --- |
| **A — Catalog, tải một chạm** | Apache-2.0, MIT, BSD, MPL-2.0, EPL | PostgreSQL JDBC (BSD-2), SQLite Xerial (Apache), SQL Server `mssql-jdbc` (MIT), ClickHouse (Apache), DuckDB (MIT), Redshift (Apache), Snowflake (Apache), BigQuery JDBC (Apache), Spanner JDBC (Apache), Databricks JDBC (Apache), H2 (MPL/EPL), Lettuce (MIT), MongoDB driver (Apache), jetcd (Apache), Kafka clients (Apache), Elasticsearch-java & OpenSearch-java (Apache), AWS SDK v2, Google Cloud libs (Apache) |
| **A\* — Catalog sau khi xác minh** | POM không khai báo, nguồn upstream ghi Apache-2.0 | Trino JDBC, Presto JDBC, Cassandra java-driver, Neo4j java-driver: xác minh giấy phép trong parent POM / repo, ghi vào lockfile |
| **B — Catalog, có điều kiện** | LGPL (weak copyleft), dùng nguyên bản, load động, user tải | MariaDB Connector/J (LGPL-2.1), Jaybird / Firebird (LGPL) |
| **C — Chỉ BYO, không bao giờ tự tải** | GPL, hoặc giấy phép độc quyền của vendor | MySQL Connector/J (GPLv2 + FOSS exception), Oracle `ojdbc` (FUTC), SAP HANA `ngdbc` (SAP Developer License), Teradata (Generic Download License), IBM Db2 `jcc` (IPLA), Exasol (EXAClient License), Vertica (Client License Agreement) |
| **C? — Coi như C đến khi xác minh** | POM ghi khác thực tế, hoặc giấy phép riêng | Dameng `DmJdbcDriver18` (POM ghi Apache nhưng là driver của vendor), Athena JDBC (POM không ghi), Weaviate Java client ("Weaviate B.V. License") |
| **D — Không cần driver bên thứ ba** | Code của DataGrok gọi HTTP/protocol trực tiếp | libSQL / Turso (Hrana HTTP), Cloudflare D1 & R2 SQL (REST), Firebase RTDB (REST), SurrealDB (HTTP `/sql`), Typesense (REST), Weaviate (REST/GraphQL, thay cho client ở hàng C?), Athena (qua AWS SDK v2 thay cho JDBC) |

### 4.2.1 Nhóm B (LGPL) với một app đóng nguồn

MariaDB Connector/J (LGPL) là **driver duy nhất** cho cả họ MySQL (MySQL, MariaDB, TiDB, OceanBase, Databend,
StarRocks, Doris, SingleStore…) khi bỏ Connector/J. **Đề xuất:** giữ trong catalog, người dùng tải theo nhu cầu, với
điều kiện:

1. DataGrok **không đóng gói, không mirror** jar LGPL; người dùng tải thẳng từ Maven Central.
2. **Không sửa, không shade, không gộp** jar; luôn là file riêng, load động, người dùng thay được bằng bản khác
   (BYO cho cùng engine).
3. Trước khi tải, hiện **LGPL-2.1**, link văn bản giấy phép và link mã nguồn upstream.
4. **EULA của DataGrok** không cấm người dùng thay thư viện này hoặc dịch ngược ở mức cần để gỡ lỗi việc thay thế đó
   (LGPL-2.1 §6).

Cần phía pháp lý xác nhận **một lần cho cả chính sách nhóm B** (áp chung cho MariaDB Connector/J và Jaybird). Nếu
không được chấp thuận, nhóm B chuyển sang C: họ MySQL và Firebird chỉ còn BYO.

**Không làm theo TablePro ở điểm này.** TablePro liên kết tĩnh MariaDB Connector/C và FreeTDS (LGPL, FreeTDS còn bị
sửa) vào bản cài. Họ làm được vì TablePro là mã nguồn mở AGPL-3.0, nên nghĩa vụ "cho phép liên kết lại" và "công bố phần
sửa" tự được đáp ứng. Một app đóng nguồn đóng gói thư viện LGPL thì phải tự gánh các nghĩa vụ đó; không đóng gói thì
tránh được hoàn toàn.

**Phương án đã cân nhắc, chưa chọn:** đóng gói sẵn hai driver permissive (PostgreSQL JDBC BSD-2, SQLite Xerial
Apache-2.0) để lần chạy đầu không cần mạng. Không có rủi ro pháp lý (TablePro, TablePlus đều đóng gói libpq/SQLite),
nhưng trái với quyết định "không đóng gói driver nào"; có thể xem lại nếu trải nghiệm lần đầu là vấn đề.

### 4.3 Driver manager

- **Lockfile sinh lúc build:** một task Gradle resolve cây phụ thuộc đầy đủ của từng driver trong catalog và ghi ra
  `drivers.lock.json`: coordinate, URL Maven Central, sha256, giấy phép của từng jar. Task fail nếu có giấy phép ngoài
  allowlist (A, A\* đã xác minh, B). Lockfile đóng gói trong app; lúc chạy chỉ việc tải đúng danh sách đó, không cần
  Maven resolver.
- **Lưu trữ:** `DatagrokPaths/drivers/<id>/<version>/`; xác minh sha256 trước khi load; cho xóa/đổi version trong Settings.
- **Classloader riêng mỗi driver.** Code hiện tại đã gọi thẳng `java.sql.Driver` thay vì `DriverManager`. Riêng HikariCP
  phải nhận `DataSource` bọc instance driver đó thay vì `driverClassName` (Hikari load class theo context classloader).
- **Adapter cho SDK non-JDBC** (Redis, Mongo, Kafka…): phần adapter là code DataGrok, nằm trong app, biên dịch với SDK
  ở dạng `compileOnly`; lúc chạy adapter được load cùng classloader với các jar SDK vừa tải, tìm qua `ServiceLoader`.
- **BYO:** chọn một hoặc nhiều jar (hoặc thư mục), app dò các class `java.sql.Driver` trong đó; lưu đường dẫn, không copy.
  Preset nhóm C chỉ điền sẵn tên class, mẫu URL và link trang tải của vendor (mở trình duyệt, người dùng tự tải).
- **Môi trường doanh nghiệp / offline:** cấu hình Maven mirror riêng (Artifactory, Nexus) hoặc import driver từ thư mục có
  sẵn; sha256 vẫn được kiểm tra với lockfile.
- **Trải nghiệm lần đầu:** xem 4.4.

### 4.4 Nhận diện driver và đề xuất tải khi người dùng nhập kết nối

Mục tiêu: người dùng không cần biết "driver" là gì. Họ nhập kết nối theo cách nào cũng được; DataGrok tự xác định engine,
chọn driver phù hợp và đề xuất tải ngay trong form, trước khi họ bấm Connect.

#### Nguồn tín hiệu (theo độ tin cậy giảm dần)

| Tín hiệu | Khi nào | Nhận diện được |
| --- | --- | --- |
| **Engine người dùng chọn** trong dialog | Luôn có | Driver mặc định của engine (`Engine.drivers.first()`) |
| **Connection string / URL dán vào** | Ô "Paste" và ô URL đang có | Mở rộng `ConnectionString.parse` (hiện chỉ PG/MySQL/MariaDB/SQLite) thành bảng scheme → engine: `postgres(ql)://`, `mysql://`, `mariadb://`, `sqlserver://`, `mongodb(+srv)://`, `redis(s)://`, `clickhouse://`, `libsql://`, `jdbc:<subprotocol>:` (sqlserver, oracle, clickhouse, duckdb, snowflake, bigquery, trino, databricks, db2, sap, teradata…). Subprotocol lạ → Generic JDBC |
| **File** (chọn, kéo thả, mở bằng DataGrok) | Engine dạng file | Đọc magic bytes, không tin đuôi file: `SQLite format 3\0` → SQLite; header DuckDB → DuckDB; `.parquet`/`.csv` → DuckDB; H2 `.mv.db`; Firebird `.fdb` |
| **Cổng** khi engine chưa rõ | Người dùng chỉ nhập host:port | Chỉ là gợi ý: 5432 PG, 3306 MySQL, 1433 SQL Server, 1521 Oracle, 6379 Redis, 27017 MongoDB, 8123/9000 ClickHouse, 9042 Cassandra, 9200 Elasticsearch, 2379 etcd, 9092 Kafka, 7687 Neo4j |
| **Thăm dò server** (handshake, chưa đăng nhập) | Bấm "Nhận diện" hoặc khi engine mới chỉ biết ở mức họ (family) | MySQL wire: server gửi greeting trước → chuỗi version phân biệt MySQL / MariaDB / TiDB / OceanBase. Redis wire: `HELLO` → Redis / Valkey. HTTP: `GET /` cổng 9200 → Elasticsearch / OpenSearch, cổng 8123 → ClickHouse |
| **Sau khi kết nối** | Lần Test/Connect đầu | `SELECT version()` và tương tự → CockroachDB, Redshift, YugabyteDB, TimescaleDB… trên PG wire; đề xuất đổi preset engine (dialect), giữ nguyên driver |

Thăm dò server chỉ gửi tới host người dùng đã nhập, qua SSH tunnel nếu có, timeout 3 giây, **không gửi thông tin đăng
nhập**; tắt được trong Settings.

#### Kết quả nhận diện

```kotlin
// core/driver/DriverResolution.kt
sealed interface DriverResolution {
    data class Ready(val driver: InstalledDriver) : DriverResolution
    data class Download(val entry: CatalogEntry, val license: String, val bytes: Long, val jarCount: Int) : DriverResolution // nhóm A, B
    data class UpdateAvailable(val installed: InstalledDriver, val entry: CatalogEntry) : DriverResolution
    data class BringYourOwn(val preset: ByoPreset, val vendorUrl: String) : DriverResolution                   // nhóm C
    data object GenericJdbc : DriverResolution                                                                  // không nhận ra
}

object DriverDetector {
    fun fromConnectionString(text: String): Detection?          // engine + độ tin cậy + trường đã điền
    fun fromFile(path: Path): Detection?
    suspend fun probe(host: String, port: Int, via: SshTunnel?): ServerHint?
    fun resolve(engine: Engine, drivers: DriverManager): DriverResolution
}
```

Nhận diện nằm trong `core`, không phụ thuộc UI; `ConnectionDialog` chỉ hiển thị `DriverResolution`.

#### Trải nghiệm trong dialog kết nối

- Một **banner nằm ngay dưới ô engine / URL**, cập nhật trực tiếp khi người dùng gõ (debounce ~300 ms), không bật hộp
  thoại chặn:
  - Nhóm A: "Cần driver **PostgreSQL JDBC 42.7.14** · BSD-2-Clause · 1.1 MB · [Tải về]"
  - Nhóm B: "Cần driver **MariaDB Connector/J** · LGPL-2.1 ([giấy phép], [mã nguồn]) · 0.7 MB · [Tải về]"
  - Nhóm C: "Oracle cần driver `ojdbc` do bạn tự cung cấp · [Chọn file .jar…] [Mở trang tải của Oracle]"
  - Đã có: "✓ Driver sẵn sàng" (mờ); có bản mới trong lockfile: "Có bản driver mới · [Cập nhật]"
  - Không nhận ra: "Dùng Generic JDBC · [Chọn file .jar…]"
- Bấm **Tải về** là tải nền ngay, người dùng vẫn điền tiếp form; banner hiện tiến độ. Test Connection / Connect bấm
  trong lúc đang tải sẽ chờ tải xong rồi chạy tiếp, nút hiện "Đang tải driver…".
- Bấm Connect khi chưa có driver: hiện đúng nội dung banner dưới dạng một bước xác nhận, không thêm màn hình mới.
- **Không bao giờ tự tải khi chưa có cú bấm của người dùng.** Settings có tùy chọn (mặc định tắt) "Tự tải driver có giấy
  phép permissive (nhóm A)"; nhóm B luôn hỏi; nhóm C không bao giờ tải.
- Lỗi: mất mạng → đề xuất "Nhập driver từ thư mục" hoặc cấu hình Maven mirror; sha256 không khớp → từ chối và báo rõ.

#### Các lối vào khác dùng chung luồng này

- Mở profile đã lưu trên máy chưa có driver (máy mới, đồng bộ cài đặt).
- Nhập nhiều profile cùng lúc: gom lại "3 kết nối cần 2 driver · [Tải tất cả]" (nhóm B vẫn liệt kê giấy phép).
- Kéo thả file database vào cửa sổ.
- Profile cũ chọn Connector/J: `BringYourOwn` cho Connector/J, kèm gợi ý chuyển sang MariaDB Connector/J.

#### Phân kỳ

- **Phase 0:** engine chọn trong dialog, connection string, file, banner + tải nền, các lối vào ở trên. Đây là điều
  kiện để bỏ đóng gói driver mà không làm xấu trải nghiệm.
- **Phase 1:** thăm dò server (MySQL greeting, Redis `HELLO`, HTTP) và tinh chỉnh engine sau khi kết nối; bảng scheme
  mở rộng dần theo từng engine được thêm vào.

#### Kiểm thử

- Bảng test cho mọi scheme / subprotocol JDBC → engine → `DriverResolution` (có cả nhóm C và subprotocol lạ).
- File mẫu cho magic bytes (SQLite, DuckDB, file rỗng, file đổi đuôi sai).
- Thăm dò bằng Testcontainers: MySQL, MariaDB, TiDB, Redis, Valkey, Elasticsearch, OpenSearch, ClickHouse.
- UI test cho banner: đổi engine giữa chừng khi đang tải, Connect trong lúc tải, mất mạng.

### 4.5 Các thư viện nền còn đóng gói trong app

Vẫn phải qua cùng allowlist: Compose, Jewel, kotlinx (Apache), HikariCP (Apache), sshj (Apache), java-keyring,
logback (dual EPL-1.0 / LGPL-2.1 → dùng theo EPL). Thêm bước kiểm tra giấy phép vào CI (vd. plugin
`com.github.jk1.dependency-license-report`) và sinh `THIRD-PARTY-NOTICES` vào bản cài.

## 5. Kiến trúc cần làm trước (Phase 0)

Không làm phase này thì mỗi engine thêm vào sẽ thêm một nhánh `when` ở 20 file.

### 5.1 Engine descriptor thay cho enum

```kotlin
// core/connection/Engine.kt
data class Engine(
    val id: String,                 // "postgresql", "redshift", "redis"… — lưu vào profile
    val displayName: String,
    val family: EngineFamily,       // PostgresWire, MySqlWire, SQLite, MSSQL, Oracle, Redis, Mongo, …
    val paradigm: Paradigm,         // Relational, KeyValue, Document, WideColumn, Search, Stream, Graph
    val defaultPort: Int?,
    val connectionFields: List<Field>,  // host/port/db/user, file path, account, project id, region…
    val authMethods: List<AuthMethod>,  // Password, TlsClientCert, AwsProfile/Keys/IAM, GcpServiceAccount, Token, OAuth
    val capabilities: Set<Capability>,  // Transactions, Schemas, ForeignKeys, Explain, EditRows, DDL, Ttl, Cluster…
    val drivers: List<DriverSpec>,  // Catalog(lockfile id) | Byo(className, urlTemplate, vendorUrl) | BuiltIn(http adapter)
)
```

- Profile lưu `engineId: String`. Đọc profile cũ: map `"PostgreSQL" → "postgresql"`… trong `ConnectionStore`, có test
  migration.
- Profile cũ đang chọn `driver = MySQL` (Connector/J): khi mở, đề nghị chuyển sang MariaDB Connector/J hoặc trỏ tới jar
  Connector/J người dùng tự có.
- UI hỏi `capabilities` / `paradigm`, không hỏi tên engine. `ConnectionDialog` vẽ form từ `connectionFields` +
  `authMethods`.

### 5.2 Tách `Database` thành interface theo paradigm

```kotlin
sealed interface Connection : AutoCloseable { val engine: Engine }
interface RelationalConnection : Connection { /* API hiện tại của Database: namespaces, tables, fetchPage, … */ }
interface KeyValueConnection : Connection { scanKeys, keyType, read/write value, ttl, databases }
interface DocumentConnection : Connection { databases, collections, find(filter, sort, page), insert/update/delete doc, indexes }
interface StreamConnection : Connection { topics, partitions, consume(offset), produce }
```

- `Database` hiện tại thành `JdbcConnection : RelationalConnection`, giữ nguyên hành vi.
- Engine non-JDBC có dạng bảng (Cassandra/CQL, DynamoDB PartiQL, D1/libSQL HTTP) vẫn implement `RelationalConnection`
  để dùng lại grid, filter, console.
- Mở rộng `ResultColumn` thêm kiểu JSON/document để grid render cell lồng nhau.

### 5.3 SQL dialect mở theo họ

- `SqlDialect` từ `sealed` thành `open`, kế thừa theo họ: `PostgreSQL → Redshift, CockroachDB, Greenplum…`;
  `MySQL → TiDB, OceanBase, StarRocks…`; `SQLite → libSQL, D1`; thêm `Generic` chỉ dựa vào `DatabaseMetaData`.
- Đưa vào dialect: paging (`OFFSET … FETCH`, `TOP`), quoting (`[x]`), splitter (`GO`, khối PL/SQL kết thúc bằng `/`),
  Explain, keyword cho lexer/completion.
- Logic đang gắn với Connector/J (`fetchSize = Int.MIN_VALUE` ở `Database.kt`, map `sslMode` ở `JdbcUrls`) chuyển sang
  hành vi của MariaDB driver làm mặc định.
- `StructureReader` có bản mặc định dùng `DatabaseMetaData` (cho BYO / generic), engine chính override.

### 5.4 Kết nối và xác thực

- SSH tunnel cho engine TCP; HTTP proxy cho engine HTTP.
- `SecretStore` thêm: TLS client cert/key, AWS (profile, access key, `credential_process`, SSO), GCP service account /
  ADC, API token, OAuth browser flow.
- Topology cho Redis/Mongo/Cassandra: seed node, Cluster, Sentinel, replica set, `mongodb+srv`.

**Ước lượng Phase 0:** 3–4 tuần (thêm ~1 tuần so với bản trước cho driver manager, lockfile, kiểm tra giấy phép và
chuyển họ MySQL sang MariaDB driver). Xong khi:
- bản cài không còn jar driver nào (kiểm tra bằng CI);
- PostgreSQL, MySQL, MariaDB, SQLite chạy qua kiến trúc mới với driver tải về, toàn bộ test hiện có pass;
- profile cũ đọc được, kể cả profile đang chọn Connector/J;
- nhập kết nối bằng engine, connection string hoặc file đều hiện đúng đề xuất tải driver (4.4).

**Trạng thái (2026-10-29, nhánh `feature/phase0-driver-library`):**
- Xong, phần driver: catalog + `drivers.lock.json` + kiểm tra giấy phép từng jar (`:core:verifyDriverLock`);
  `DriverLibrary` (tải từ Maven Central hoặc mirror, kiểm tra sha256, classloader riêng, jar của người dùng, nhập từ thư
  mục); bỏ đóng gói driver và chặn bằng `:app:verifyNoBundledDrivers`; họ MySQL mặc định dùng MariaDB Connector/J; banner
  driver trong dialog kết nối; hộp thoại "Driver Needed"; trang Settings → Drivers.
- Xong, phần kiến trúc:
  - `DatabaseType` từ enum thành bộ mô tả engine (`id`, `EngineFamily`, `Paradigm`, `Capability`, drivers, cổng); hồ sơ cũ
    đọc được vì `id` trùng tên enum cũ. Thêm engine cùng họ chỉ cần khai báo một `DatabaseType` (có test).
  - `SqlDialect` mở theo họ (`PostgreSQLDialect`, `MySQLDialect`, `SQLiteDialect`); logic riêng từng engine (namespace
    mặc định, EXPLAIN, hàm cho completion, quy tắc quote) chuyển vào dialect. UI hỏi `Capability` (Explain, Transactions).
  - `RelationalConnection` (con của `sealed interface DatabaseConnection`); `Database` là bản JDBC, app chỉ giữ interface.
  - Nhận diện file theo magic bytes (`DatabaseFiles`): SQLite; dán đường dẫn không đuôi vẫn nhận ra, file không phải
    database bị cảnh báo / chặn trong dialog.
  - `THIRD-PARTY-NOTICES.txt` sinh lúc build vào app.jar; mọi thư viện đóng gói phải có giấy phép đã duyệt
    (`:app:thirdPartyNotices`, chạy trong `check`).
- Chủ động dời sang lúc có engine cần đến (tránh làm trước khi có người dùng):
  - Interface `KeyValueConnection` / `DocumentConnection`… → Phase 2 cùng Redis / MongoDB.
  - Form kết nối sinh từ `connectionFields`, xác thực mở rộng (TLS client cert, AWS, GCP, token, OAuth — 5.4) → Phase 3–4.
  - Magic bytes cho DuckDB → Phase 1 cùng DuckDB.
- Cần người xem lại trước khi phát hành: `THIRD-PARTY-NOTICES.txt` hiện liệt kê thư viện + giấy phép + link SPDX; bản
  phát hành nên kèm toàn văn giấy phép, dòng bản quyền và file NOTICE của các thư viện Apache-2.0 — việc của pháp lý.
- Hồ sơ có `type` lạ (từ bản DataGrok mới hơn) hiện làm hỏng cả file `connections.json` khi đọc; nên bỏ qua riêng hồ sơ
  đó thay vì cả file — xử lý khi thêm engine đầu tiên ở Phase 1.

## 6. Lộ trình engine

Kích thước: **S** ≤ 2 ngày · **M** ~1 tuần · **L** 2–3 tuần. Cột "Nhóm" theo bảng 4.2.

### Phase 1: họ SQL (≈3–4 tuần)

**Trạng thái (2026-10-29):**
- Xong, đã chạy bộ `RelationalContract` (seed → namespace → bảng → khóa → phân trang → lọc → đếm → gợi ý giá trị → DDL →
  sửa dữ liệu → console) trên engine thật:
  - Trong tiến trình: H2, HSQLDB, Derby, DuckDB (bộ nhớ và file), "Other (JDBC)" với jar của người dùng.
  - Server Docker (`ServerEnginesIntegrationTest`, opt-in `-Pit.*`): SQL Server 2022, ClickHouse 25.3, CockroachDB 24.3,
    TimescaleDB (PG 17), TiDB 8.5; cùng PostgreSQL 17 và MySQL 8.4 có sẵn (MySQL giờ qua MariaDB Connector/J).
- Thêm vào nhưng chưa có server để chạy: Neon, Supabase (PostgreSQL + `sslmode=require`), PlanetScale (MySQL + TLS),
  Azure SQL (SQL Server + `encrypt`). Dùng chung driver/dialect với engine gốc đã test.
- Họ mới: `Generic` (SQL chuẩn qua JDBC metadata), `SqlServer` (`[]`, OFFSET/FETCH, `GO`, SHOWPLAN, `sys.*`), `ClickHouse`
  (không transaction, không sửa dòng, DDL từ `SHOW CREATE`), `DuckDB` (DDL từ `duckdb_tables()`); `CockroachDBDialect`
  con của PostgreSQL. Engine khai báo `Capability.EditRows`; ClickHouse tắt sửa dòng.
- Test Connection nhận ra engine thật trong cùng họ (CockroachDB/TimescaleDB sau PostgreSQL, TiDB/MariaDB/MySQL) và gợi ý
  đổi loại; dịch vụ host (Neon, Supabase, PlanetScale, Azure SQL) không bị gợi ý.
- URL `jdbc:` lạ khi dán giờ thành hồ sơ "Other (JDBC)" thay vì bị từ chối; `;password=` trong URL được tách ra keychain.
- Hồ sơ có engine lạ (từ bản mới hơn) được bỏ qua khi đọc và giữ nguyên khi ghi.
- Còn lại của Phase 1: Oracle (preset BYO, dialect PL/SQL), Redshift, YugabyteDB, Greenplum, OceanBase, Databend,
  StarRocks, Doris, SingleStore, Firebird; thăm dò server trước khi đăng nhập (MySQL greeting, HTTP) — hiện chỉ nhận
  diện sau khi kết nối.

| Engine | Driver | Nhóm | Size | Ghi chú |
| --- | --- | --- | --- | --- |
| Generic JDBC (BYO) | jar của người dùng | C | M | Ưu tiên số 1: là đường vào cho mọi driver nhóm C |
| SQL Server, Azure SQL | `mssql-jdbc` | A | M | T-SQL: `[]`, `OFFSET/FETCH`, `GO`, `SHOWPLAN_XML`, auth Entra ID |
| ClickHouse | `clickhouse-jdbc` | A | M | Không FK, DDL qua `SHOW CREATE` |
| DuckDB, MotherDuck | `duckdb_jdbc` | A | S | Engine file như SQLite; mở thẳng Parquet/CSV |
| Họ PG: Redshift, CockroachDB, Greenplum, YugabyteDB, TimescaleDB, Neon, Supabase, PGlite | PG JDBC (Redshift có driver riêng) | A | S mỗi cái | Dialect con + preset SSL |
| Họ MySQL: TiDB, OceanBase, Databend, StarRocks, Doris, SingleStore, PlanetScale | MariaDB Connector/J | B | S mỗi cái | Phụ thuộc quyết định ở 4.2 |
| H2, HSQLDB, Derby, Firebird | H2 / HSQLDB / Derby / Jaybird | A / B | S mỗi cái | Rẻ, đóng góp độ phủ |
| Oracle | `ojdbc` | **C (BYO)** | L | DataGrok lo dialect, splitter PL/SQL, `DBMS_METADATA`, TNS/wallet; người dùng tự lấy ojdbc |

### Phase 2: key-value và document (≈5–6 tuần)

| Engine | Driver | Nhóm | Size | Ghi chú |
| --- | --- | --- | --- | --- |
| Redis, Valkey, KeyDB, Dragonfly, Upstash | Lettuce | A | L | Paradigm KeyValue: cây key, SCAN phân trang, editor theo kiểu, TTL, chọn DB, Cluster + Sentinel, console lệnh |
| MongoDB | MongoDB Java driver | A | L | Paradigm Document: grid + inspector JSON, filter builder sinh MQL, console MQL, index, `mongodb+srv` |
| etcd | jetcd | A | M | Dùng lại UI KeyValue |

### Phase 3: cloud warehouse (≈4 tuần)

| Engine | Driver | Nhóm | Size | Ghi chú |
| --- | --- | --- | --- | --- |
| Snowflake | `snowflake-jdbc` | A | M | Warehouse/role picker, key-pair + SSO |
| BigQuery | BigQuery JDBC (Google, Apache) | A | M | Ước tính số byte quét trước khi chạy |
| Trino, Presto | `trino-jdbc`, `presto-jdbc` | A\* | S | Xác minh giấy phép trước |
| Databricks SQL | `databricks-jdbc` | A | M | |
| Athena | AWS SDK v2 (adapter riêng) | D | M | Tránh Athena JDBC chưa rõ giấy phép |
| Spanner | `google-cloud-spanner-jdbc` | A | M | GoogleSQL và PostgreSQL dialect |

### Phase 4: wide-column và NoSQL trên cloud (≈5 tuần)

| Engine | Driver | Nhóm | Size | Ghi chú |
| --- | --- | --- | --- | --- |
| Cassandra, ScyllaDB | Cassandra java-driver | A\* | M | CQL; tôn trọng partition key, paging bằng token |
| DynamoDB | AWS SDK v2 | A | M | Scan/Query + PartiQL, GSI/LSI |
| Firestore | `google-cloud-firestore` | A | M | Document paradigm |
| Firebase RTDB | REST | D | M | |
| Bigtable | `google-cloud-bigtable` | A | M | |
| libSQL / Turso, Cloudflare D1, R2 SQL | HTTP tự viết | D | M | Dialect SQLite, transport HTTP |

### Phase 5: search, stream, đa mô hình (≈5 tuần)

| Engine | Driver | Nhóm | Size | Ghi chú |
| --- | --- | --- | --- | --- |
| Elasticsearch, OpenSearch | elasticsearch-java / opensearch-java | A | L | index → grid, mapping, console Query DSL |
| Kafka | `kafka-clients` | A | L | Topic/partition/offset, decode JSON/Avro, produce, consumer lag |
| Neo4j | `neo4j-java-driver` | A\* | L | Cypher, grid trước, graph view sau |
| SurrealDB, Typesense, Weaviate | HTTP tự viết | D | M mỗi cái | |

### Phase 6: enterprise (chỉ preset BYO, không tải)

SAP HANA, Teradata, IBM Db2, Exasol, Vertica, Dameng (đến khi xác minh xong). Mỗi cái **S–M**: preset + dialect +
structure reader; người dùng tự cung cấp driver. Đây là nơi DataGrok vẫn "hỗ trợ" engine mà không đụng tới giấy phép.

**Bỏ qua:** Beancount (quá ngách).

## 7. Kiểm thử

- Testcontainers / `docker-compose` cho các engine có image (theo cách `-Pit.postgres=` đang có). Driver trong test là
  `testImplementation`, không vào bản cài.
- Contract test dùng chung cho mỗi paradigm; engine nào cũng phải pass phần ứng với capability nó khai báo.
- Engine nhóm C: chỉ chạy trên máy dev/CI nội bộ đã có driver hợp lệ (biến môi trường trỏ tới jar), không commit jar vào repo.
- Test cho driver manager: sha256 sai bị từ chối, giấy phép ngoài allowlist làm task lockfile fail, chạy offline với
  thư mục import, BYO dò đúng class driver.
- CI: kiểm tra artifact cài đặt không chứa `java.sql.Driver` của bên thứ ba và mọi jar trong đó nằm trong allowlist.

## 8. Mốc

| Mốc | Nội dung | Số engine (cộng dồn) |
| --- | --- | --- |
| M0 | Phase 0: kiến trúc + driver manager + chính sách giấy phép | 4 |
| M1 | Phase 1: BYO, SQL Server, ClickHouse, DuckDB, họ PG & MySQL, nhúng DB Java, Oracle (BYO) | ~28 + mọi JDBC qua BYO |
| M2 | Phase 2: Redis family, MongoDB, etcd | ~35 |
| M3 | Phase 3: warehouse | ~42 |
| M4 | Phase 4: wide-column & cloud NoSQL | ~50 |
| M5 | Phase 5: search, Kafka, Neo4j | ~56 |
| M6 | Phase 6: preset enterprise | ~62 |

Tổng ≈ 7 tháng cho một người; từ M1 trở đi song song được vì mỗi engine là một module độc lập.

## 9. Rủi ro

- **Phân loại giấy phép sai:** bảng 4.2 dựa trên POM, nhiều POM không đầy đủ. Mọi driver phải được người xem xét xác nhận
  trước khi vào lockfile; nên có ý kiến pháp lý một lần cho chính sách (đặc biệt nhóm B, mục 4.2.1) thay vì từng driver.
- **Thiếu bản mã nguồn mở thay thế trên JVM:** TablePro né driver độc quyền bằng thư viện tự cài đặt giao thức
  (`oracle-nio` cho Oracle, `go-hdb` cho SAP HANA, đều Apache-2.0). JVM không có bản tương đương đủ trưởng thành, nên
  Oracle và HANA ở DataGrok vẫn là BYO. Có thể xem xét chạy `go-hdb` thành tiến trình phụ như TablePro nếu cần HANA một chạm.
- **Vendor đổi giấy phép ở version mới:** lockfile khóa version và giấy phép; nâng version là chạy lại kiểm tra.
- **Trải nghiệm lần đầu chậm hơn** vì phải tải driver, kể cả PostgreSQL/SQLite. Giảm nhẹ: banner tải ngay trong dialog
  kết nối, tải nền song song khi người dùng đang điền form.
- **Không có mạng:** cần luồng import thư mục driver và Maven mirror nội bộ ngay từ Phase 0.
- **MariaDB driver với MySQL 8+:** khác Connector/J ở vài chỗ (`caching_sha2_password`, `sslMode`, streaming fetch);
  cần chạy integration test MySQL 5.7 / 8.x / 9.x bằng MariaDB driver trước khi bỏ Connector/J.
- **JDBC metadata không đáng tin:** BYO/generic là "best effort"; engine chính vẫn cần structure reader riêng.
- **Phạm vi UI:** KeyValue, Document, Stream là ba bộ màn hình mới; làm thật tốt Redis và Mongo trước rồi dùng lại.
