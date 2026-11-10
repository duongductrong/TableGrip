# Migrate sang Rust: chia M1–M9 thành session

Kế hoạch tổng: [../rust-migration.md](../rust-migration.md). M0 đã xong (commit `f0d6b46`). Tài liệu này chia phần
còn lại thành các session ngắn. Mỗi session mở bằng **một prompt dán sẵn** ở cuối tài liệu.

## Vì sao chia như thế này

Mục tiêu là tiết kiệm token: mỗi session ngắn, chỉ đọc đúng phần của mình, và không phải mang theo lịch sử dài.

- **Gom M khi chúng nhỏ và cùng một lớp.** M4 và M5 cùng là backend và không cần UI, nên đi chung một session.
- **Tách M khi một session ôm không nổi.** Ví dụ M3 có 7 engine; M6 gồm grid, editor và table view, mỗi phần cỡ một
  phase UI kit. Nếu gom, session sẽ dài, context phình to và chi phí đội lên nhiều hơn số session tăng thêm.
- **Mọi prompt đều tự đủ.** Không fork hội thoại. Phần quy ước chung nằm trong tài liệu này để prompt chỉ cần trỏ tới,
  không phải lặp lại.

## Sơ đồ session

| Wave | Session | Mốc | Model | Phụ thuộc | Chạy song song với |
|---|---|---|---|---|---|
| 1 | **S1** `core-pure` | M1 | Sonnet 5.5 | M0 | S2 |
| 1 | **S2** `storage` | M2 | Sonnet 5.5 | M0 | S1 |
| 2 | **S3** `drivers-core` | M3 phần 1: trait, Postgres, SQLite, contract | Opus 5.5 | S1, S2 | — |
| 3 | **S4** `drivers-rest` | M3 phần 2: MySQL/MariaDB, SQL Server, ClickHouse, DuckDB | Sonnet 5.5 | S3 | S5 |
| 3 | **S5** `nosql-data-agent` | M4 + M5 | Sonnet 5.5 | S3 | S4 |
| 4 | **S6** `app-shell` | M6.1: bootstrap app, shell, kết nối, explorer | Opus 5.5 | S4, S5 | — |
| 5 | **S7** `data-grid` | M6.2: data grid | Opus 5.5 | S6 | S8, S11 |
| 5 | **S8** `sql-console` | M6.3: SQL editor + console | Opus 5.5 | S6 | S7, S11 |
| 6 | **S9** `table-view` | M6.4 + M7 phần 1: table view, document/key browser | Sonnet 5.5 | S7 | S10 |
| 6 | **S10** `screens-rest` | M7 phần 2: schema tools, chart, agent panel, settings, phần còn lại | Sonnet 5.5 | S7, S8 | S9 |
| 5–6 | **S11** `packaging` | M8 | Sonnet 5.5 | S6 | S7–S10 |
| 7 | **S12** `parity-cleanup` | M9 | Sonnet 5.5 | S9, S10, S11 + beta chạy ổn 2 tuần | — |

**Cách chạy:**
- Mỗi lần chỉ chạy một wave, tối đa 2–3 session cùng lúc.
- Mỗi session làm trong worktree riêng, trên nhánh riêng.
- Merge kết quả vào `feat/rust-ui-kit-phase-0` (hoặc nhánh tích hợp bạn chọn) trước khi mở wave sau.
- Chọn Opus khi phần việc cần thiết kế: trait driver, kiến trúc app, grid, editor. Các phần còn lại là port có golden
  hoặc test đối chiếu, nên Sonnet đủ dùng.
- Nếu một session gần cạn context mà chưa xong, cho nó ghi tiến độ vào mục "Nhật ký" rồi mở session mới với **cùng
  prompt** cộng thêm một dòng: "Tiếp tục từ nhật ký của Sx".

## Quy ước chung (mọi session đọc mục này)

**Đọc gì:**
- Chỉ đọc mục của mình trong [../rust-migration.md](../rust-migration.md), mục này, và file Kotlin nguồn của phần mình
  port.
- Không duyệt toàn repo, không đọc lại tài liệu UI kit trừ khi prompt yêu cầu.

**Nhánh và commit:**
- Làm trong worktree riêng, nhánh `rust/<tên-session>` cắt từ `feat/rust-ui-kit-phase-0`.
- Mỗi commit một mảng nhỏ (khoảng 600 dòng trở xuống), kèm test của mảng đó. Message dạng
  `feat(<crate>): <việc>`.
- **Không thêm dòng `Co-Authored-By`** và không push. Người dùng tự merge và push.

**Port logic thuần thì phải có golden:**
1. Thêm input vào `conformance/core/inputs/<subject>.json`.
2. Thêm subject vào `core/src/test/kotlin/dev/tablegrip/core/golden/CoreGoldenTest.kt`.
3. Ghi golden bằng `./gradlew :core:test --tests '*CoreGoldenTest*' -Pconformance.record=true`.
4. Thêm một nhánh `run` trong test golden của crate Rust (mẫu: `crates/tg-sql/tests/golden.rs`). Crate khác có thể
   chép runner đó.
5. Input phải phủ được các ca trong test Kotlin tương ứng (`core/src/test/...`).

Offset trong golden tính theo UTF-16 như Kotlin; Rust tự quy đổi.

**Kotlin chỉ được sửa trong `src/test`.** Ngoại lệ duy nhất là sửa lỗi nghiêm trọng, và phải ghi rõ trong commit.

**Tương thích dữ liệu:**
- File trong `~/.tablegrip` và mục keychain mà Kotlin ghi ra, Rust phải đọc được.
- Fixture nằm ở `conformance/core/fixtures/`, do test Kotlin ghi ra, không viết tay.

**Kiểm tra trước mỗi commit:**
- `cargo fmt --all --check`
- `cargo clippy --workspace --all-targets --locked -- -D warnings`
- `cargo test -p <crate của mình>`
- Nếu có sửa test Kotlin: `./gradlew :core:test --tests '<test liên quan>'`.

**Dependency:**
- Thêm vào `[workspace.dependencies]` ở `Cargo.toml` gốc, ghim phiên bản.
- Chỉ dùng crate có giấy phép MIT, Apache-2.0, BSD hoặc tương đương. Ghi vào `NOTICE` nếu giấy phép yêu cầu.

**Khi kết thúc:**
- Thêm mục `### Sx (<ngày>)` vào phần "Nhật ký" của [../rust-migration.md](../rust-migration.md): đã làm gì, khác gì
  so với kế hoạch, còn gì dở.
- Báo lại tên nhánh và danh sách commit.

**Khi bị chặn:**
- Bị chặn vì một quyết định (ví dụ API trait, thư viện) thì nêu 2 phương án kèm đề xuất rồi dừng. Không tự chốt
  những quyết định sẽ ảnh hưởng tới session khác.

---

## Prompt cho từng session

Dán nguyên khối vào session mới. Bạn có thể đổi tên nhánh tích hợp nếu cần.

### S1 `core-pure`: M1

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0. Làm session S1 trong docs/plans/rust-migration/sessions.md:
đọc mục "Quy ước chung" ở đó và mục M1 trong docs/plans/rust-migration.md, rồi thực hiện.

Mục tiêu: port toàn bộ logic thuần (không I/O) của core sang Rust, khớp golden Kotlin 100%.

Phạm vi và thứ tự:
1. crates/tg-sql: SqlSyntax, SqlLexer, SqlSplitter, SqlClassifier (3 golden đã có sẵn, chỉ cần cắm runner), rồi
   SqlKeywords, SqlParameters, SqlFormatter (core/src/main/kotlin/dev/tablegrip/core/sql).
2. tg-sql: SqlDialect (core/.../dialect): mọi hàm sinh SQL, cho từng DatabaseType. Golden lấy mẫu theo engine × thao tác.
3. tg-sql: filter + projection (core/.../filter), DDL (core/.../ddl), schema snapshot/diff (core/.../schema).
4. tg-sql: SqlCompleter, DocumentCompleter (core/.../completion).
5. crates/tg-chart: ChartSpec, ChartSuggest, ColumnProfiler, ChartData (phần thuần của core/.../chart).
6. crates/tg-data: TypeInference (core/.../importer), ResultFormats/ClipboardTable (core/.../export): chỉ phần thuần.

Các kiểu dữ liệu mô hình mà nhiều phần dùng chung (DatabaseType, TableRef, ColumnInfo…) đặt ở tg-sql hoặc một module
model trong tg-sql. Ghi lý do chọn vào nhật ký, vì S2 và S3 sẽ dùng lại.

Xong khi: mọi subject trong conformance/core/inputs pass trên Rust (không còn subject pending), input phủ các ca của
test Kotlin tương ứng, clippy sạch. Mỗi bước trên ít nhất một commit.
```

### S2 `storage`: M2

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0. Làm session S2 trong docs/plans/rust-migration/sessions.md:
đọc mục "Quy ước chung" ở đó và mục M2 trong docs/plans/rust-migration.md, rồi thực hiện.
S1 chạy song song và sở hữu crates/tg-sql: không sửa tg-sql. Nếu cần một kiểu dữ liệu model, định nghĩa tạm ở tg-core
và ghi vào nhật ký để gộp sau.

Mục tiêu: crates/tg-core đọc và ghi đúng dữ liệu người dùng mà bản Kotlin đang dùng.

Phạm vi:
1. TableGripPaths (~/.tablegrip, override tablegrip.home → biến môi trường/argument tương ứng).
2. Settings + settings store, theme prefs (core/.../settings, core/.../theme).
3. ConnectionConfig, ConnectionStore, ConnectionString (parse mọi dạng URL như Kotlin), DatabaseFiles, AiAccess
   (core/.../connection).
4. SecretStore qua crate `keyring`, dùng đúng service/account như java-keyring đang ghi. Kiểm tay trên macOS bằng
   một mục do bản Kotlin tạo.
5. History, saved queries, session, activity store, backup schedule (core/.../{history,queries,session,activity,backup})
   chỉ phần lưu trữ. ChatStore để cho S5.
6. SshTunnel bằng russh: key (có passphrase), ssh-agent, password, known_hosts. Port SshTunnelTest (Kotlin dùng
   Apache SSHD làm server test; Rust dùng russh server hoặc container).

Fixture tương thích: viết một test Kotlin trong core/src/test để tạo một ~/.tablegrip mẫu (nhiều kết nối, đủ loại,
có SSH, settings không mặc định) vào conformance/core/fixtures/home/. Test Rust đọc fixture đó, ghi lại, rồi so: nội
dung JSON phải tương đương.

Xong khi: port đủ các test ConnectionStoreTest, ConnectionStringTest, SettingsStoreTest, SshTunnelTest, StorageTest
sang Rust và chúng pass; fixture round-trip pass.
```

### S3 `drivers-core`: M3 phần 1

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S1, S2). Làm session S3 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M3 trong docs/plans/rust-migration.md, và
nhật ký S1/S2, rồi thực hiện.

Mục tiêu: thiết kế crates/tg-driver và làm xong 2 engine đầu (PostgreSQL, SQLite) cùng bộ contract test. S4 và S5 sẽ
dựa trên đúng thiết kế này.

Phạm vi:
1. Thiết kế (viết ra trước khi code, ghi vào nhật ký):
   - Trait RelationalConnection / QuerySession / KeyValueStore / DocumentStore, rút từ core/.../driver/*.kt.
   - QueryResult và kiểu giá trị ô (gồm binary/text, BinaryText.kt), QueryError.
   - Runtime: tokio. Huỷ query: CancellationToken cộng cancel phía server. Timeout.
   - Pool cho explorer/table và session riêng cho console. Feature flag theo engine.
   Nêu 1–2 điểm cần người dùng chốt nếu có, rồi dừng chờ trả lời trước khi code phần trait.
2. Introspection: port Introspector + StructureReader. Kotlin dựa vào JDBC DatabaseMetaData, nên Rust phải tự viết
   query catalog cho từng engine. Golden: chạy bản Kotlin trên schema core/src/test/resources/structure-it/*.sql
   (và một schema SQLite tương đương), dump TableDetails/TableStructure ra conformance/core/golden.
3. PostgreSQL (tokio-postgres + deadpool-postgres, TLS bằng rustls), SQLite (rusqlite bundled).
   Phạm vi tính năng: fetchPage, export stream, sampleValues, apply changes (core/.../edit), DDL, activity monitor,
   server identity, đi qua SshTunnel của tg-core.
4. Contract: port RelationalContract.kt thành một bộ test generic chạy trên mọi engine. SQLite chạy mặc định; Postgres
   chạy khi có biến môi trường TG_IT_POSTGRES=host:port (lệnh docker lấy từ ServerEnginesIntegrationTest.kt).

Xong khi: contract pass cho SQLite và Postgres; introspection khớp golden; có tài liệu ngắn "thêm một engine mới" ở
đầu crates/tg-driver/src/lib.rs để S4 làm theo.
```

### S4 `drivers-rest`: M3 phần 2

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S3). Làm session S4 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, nhật ký S3 trong docs/plans/rust-migration.md,
và doc "thêm một engine mới" ở crates/tg-driver/src/lib.rs, rồi thực hiện.
S5 chạy song song: không sửa trait trong tg-driver. Nếu trait thiếu, thêm method có default và ghi vào nhật ký.

Mục tiêu: các engine quan hệ còn lại, mỗi engine sau một feature flag, pass contract của S3.

Thứ tự (mỗi engine một hoặc vài commit):
1. MySQL + MariaDB (mysql_async). Gồm cả biến thể TiDB.
2. SQL Server (tiberius, TLS bằng rustls).
3. ClickHouse (HTTP, crate clickhouse hoặc reqwest).
4. DuckDB (crate duckdb, bundled).
Mỗi engine: introspection khớp golden (ghi thêm golden từ Kotlin khi engine có schema test), contract chạy khi có
TG_IT_<ENGINE>=host:port. Cockroach và Timescale chạy qua engine Postgres; thêm vào ma trận contract.

Ghi vào nhật ký những engine/tính năng Kotlin có mà Rust chưa làm được. H2, HSQLDB, Derby và Generic JDBC bị bỏ có
chủ đích (D1); ghi rõ điều này.

Xong khi: contract pass cho cả 4 nhóm (chạy thật bằng docker trên máy này), cargo test mặc định (không docker) vẫn
xanh.
```

### S5 `nosql-data-agent`: M4 + M5

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S3). Làm session S5 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M4 và M5 trong docs/plans/rust-migration.md,
và nhật ký S1–S3, rồi thực hiện.
S4 chạy song song và sở hữu các engine quan hệ trong tg-driver; S5 chỉ thêm engine redis/mongodb/etcd. Không đổi trait
có sẵn; nếu cần, thêm method có default.

Phần A — M4, tg-driver:
1. Redis (redis-rs): key browser (SCAN, kiểu, TTL), đọc/sửa từng kiểu, command console. Port từ
   engines/redis và core/.../driver/KeyValue*.kt.
2. MongoDB (crate mongodb): document store, query, sửa document. Port từ engines/mongodb và DocumentStore.kt.
3. etcd: v3 JSON API qua reqwest, có auth. Port từ core/.../driver/etcd.
Port các test của engines/*/src/test và test etcd. Integration test chạy khi có TG_IT_REDIS / TG_IT_MONGO / TG_IT_ETCD.

Phần B — M5:
4. tg-data: CsvFormat/JsonFormat/XlsxFormat (csv, calamine, rust_xlsxwriter), DataFileStore, WorkingCopy, DataFileSync;
   ImportReader + CsvSource/JsonSource/XlsxSource; connection importers (DBeaver, .env, IDE data sources, pgpass, plist,
   ssh hosts); testdata; tools. Golden hoặc fixture cho mọi định dạng đọc/ghi.
5. tg-agent: AgentRegistry, ChatStore (tương thích file Kotlin), Mention, CLI adapter (spawn, ShellEnvironment,
   CliOutputDecoder, CliModelList), provider adapter (HTTP streaming SSE), assist (SqlFix, ChartAssist, SchemaDigest).
   Port test trong core/src/test/.../{agent,assist}.
6. Backup/restore + scheduler (core/.../backup).

Xong khi: test tương ứng của Kotlin đều có bản Rust và pass; fixture file của Kotlin (chat, data file) đọc được.
```

### S6 `app-shell`: M6.1

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S1–S5). Làm session S6 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M6 trong docs/plans/rust-migration.md, và
nhật ký S1–S5, rồi thực hiện.

Mục tiêu: crates/tg-app chạy được như một app thật: mở cửa sổ, quản lý kết nối, duyệt explorer, khôi phục workspace.
Các session S7, S8, S10, S11 sẽ cắm vào khung này.

Phạm vi:
1. Kiến trúc app (ghi vào nhật ký, có sơ đồ ngắn): cầu nối tokio ↔ GPUI executor, cách một tab/màn hình đăng ký với
   shell, state ở tg-app-logic, action/keymap theo Shortcuts.kt (đã có trong tg-ui-logic).
2. MainScreen: title bar, tool stripe, status bar, workspace tabs, split; dùng component tg-ui (xem
   crates/tg-gallery/src/specimens/main-screen*.rs).
3. Kết nối (app/.../connections): danh sách, dialog thêm/sửa cho mọi engine, test connection, nhập kết nối (dùng
   importer của S5), SSH.
4. Explorer (app/.../explorer): cây lazy, namespace → bảng → cột, refresh, menu ngữ cảnh. Mở tab là placeholder cho
   S7/S8.
5. Workspace (app/.../workspace): lưu/khôi phục tab, tương thích file Kotlin.
6. Theme, cỡ chữ, zoom từ settings của tg-core.
Logic và state đặt ở tg-app-logic, có test headless; phần vẽ để mỏng.

Xong khi: `cargo run -p tg-app` mở được kết nối SQLite và Postgres thật, duyệt được cây, đóng/mở lại app thì giữ
nguyên tab. Kèm ảnh chụp màn hình trong nhật ký.
```

### S7 `data-grid`: M6.2

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S6). Làm session S7 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M6 trong docs/plans/rust-migration.md, và
nhật ký S6 (kiến trúc app), rồi thực hiện.
S8 chạy song song (SQL console), S11 chạy song song (đóng gói). S7 sở hữu module grid trong tg-app-logic/tg-app.

Mục tiêu: data grid Rust thay app/src/main/kotlin/dev/tablegrip/app/grid (16 file, khoảng 4,9k dòng), dùng được cho
cả table view và kết quả console.

Phạm vi theo thứ tự:
1. GridModel/GridGeometry/DataGridState/SelectionStats → tg-app-logic, test headless (port test trong
   app/src/test/.../grid).
2. Vẽ: ảo hoá hai chiều, cột cố định, resize/kéo cột, header sort; mục tiêu cuộn mượt 1 triệu hàng (đo và ghi số).
3. Chọn ô, vùng, hàng, cột; copy (định dạng của ClipboardTable); paste (GridPaste).
4. Sửa ô: CellEditors theo kiểu dữ liệu (docs/plans/cell-editors.md), EditHistory undo/redo, RecordEditor + dialog,
   JSON tree editor, ValuePanel, ValueFormats.
5. ResultJsonView.
Mỗi bước có scenario hoặc test headless; dùng component tg-ui sẵn có, không vẽ lại control.

Xong khi: một tab bảng trong app (dùng khung S6) hiển thị, sửa và commit dữ liệu Postgres/SQLite; các test grid của
Kotlin đều có bản Rust; ghi số đo hiệu năng.
```

### S8 `sql-console`: M6.3

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S6). Làm session S8 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M6 trong docs/plans/rust-migration.md, và
nhật ký S6, rồi thực hiện.
S7 chạy song song và sở hữu grid. Kết quả console tạm hiển thị bằng một bảng đơn giản sau trait/props mà S7 sẽ cung
cấp; thống nhất giao diện đó qua nhật ký và chỉ đổi phần cắm vào khi S7 đã merge.

Mục tiêu: SQL editor và console thay app/src/main/kotlin/dev/tablegrip/app/console (+ completion, history, queries).

Phạm vi:
1. Editor dựa trên tg-text: nhiều dòng lớn (≥ 10k dòng mượt), số dòng, tô màu cú pháp bằng SqlLexer (tg-sql) theo
   SyntaxSchemes, IME (đã có ở tg-text), tìm/thay, EditorActions (comment, indent, format bằng SqlFormatter).
2. Completion popup dùng SqlCompleter + metadata (app/.../completion).
3. Chạy câu tại caret / vùng chọn / cả file (SqlSplitter, statementAt), tham số (SqlParameters), xác nhận câu nguy
   hiểm (SqlClassifier), huỷ query, nhiều tab kết quả, thông báo lỗi.
4. FixView (SqlFix của tg-agent), ChartView (tg-chart), history và saved queries.

Xong khi: chạy được script nhiều câu trên Postgres/MySQL/SQLite, có huỷ được, completion hoạt động; test console/
completion của Kotlin đều có bản Rust; gõ tiếng Việt Telex trong editor đã được kiểm tay.
```

### S9 `table-view`: M6.4 + M7 phần 1

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S7). Làm session S9 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M6 và M7 trong docs/plans/rust-migration.md,
và nhật ký S6–S7, rồi thực hiện.
S10 chạy song song: S9 chỉ làm các màn hình liệt kê dưới đây.

Phạm vi:
1. Table view (app/.../table): TableDataTab/View, FilterBuilder (+ completion), SelectBuilder, ColumnDrag,
   StructureView, TableEditModel; dùng grid của S7.
2. Document browser + query builder (app/.../documents) cho MongoDB.
3. Key browser + command console (app/.../keyvalue) cho Redis và etcd.
4. Data file tab (app/.../files) dùng tg-data.
State đặt ở tg-app-logic, port test tương ứng trong app/src/test.

Xong khi: lọc, sắp xếp, sửa bảng chạy được; duyệt và sửa được Mongo và Redis thật (docker); test Kotlin tương ứng có
bản Rust.
```

### S10 `screens-rest`: M7 phần 2

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S7, S8). Làm session S10 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M7 trong docs/plans/rust-migration.md, và
nhật ký S6–S8, rồi thực hiện.
S9 chạy song song (table view, document, key browser, files): không đụng các màn hình đó.

Phạm vi, mỗi mục ít nhất một commit:
1. Schema tools (app/.../schema): ER diagram + xuất ảnh, schema compare, table designer, import dialog/wizard, test
   data dialog.
2. Chart tab (app/.../chart).
3. Agent panel (app/.../agent): markdown, model picker, mention pills/suggestions, chat history, text reveal.
4. Settings: mọi trang trong app/.../settings, gồm tìm kiếm settings.
5. Search, tools, backup UI, activity, export dialog, shortcuts dialog, About (macOS About panel).
Port test tương ứng trong app/src/test.

Xong khi: mọi file trong app/src/main/kotlin (trừ phần của S9 và update/ của S11) đã có bản Rust; ghi bảng "file
Kotlin → module Rust" của phần mình vào nhật ký.
```

### S11 `packaging`: M8

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S6). Làm session S11 trong
docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó, mục M8 và các quyết định D3, D4 trong
docs/plans/rust-migration.md, rồi thực hiện.
S7–S10 chạy song song và sửa màn hình; S11 chỉ đụng tới đóng gói, updater, CI phát hành và scripts.

Phạm vi:
1. cargo-packager: .app + .dmg (macOS arm64/x64), MSI (Windows x64), AppImage + .deb (Linux x64); icon từ
   app/src/main/resources/icon và assets/branding (chuyển sang resources/ nếu cần).
2. Updater: macOS dùng Sparkle.framework qua objc; Windows dùng WinSparkle qua FFI (đọc app/.../update/*.kt để có
   đúng appcast URL, khoá Ed25519, kênh beta); Linux chỉ thông báo. Định dạng appcast và scripts/appcast.py giữ nguyên.
3. Workflow phát hành mới (release-rust.yml) dựa trên release.yml: ký và notarize macOS, Azure Trusted Signing cho
   Windows, ký bản cập nhật, chạy thử không ký (dry run) qua workflow_dispatch. Chưa thay release.yml cũ.
4. scripts/run.sh và run.cmd có chế độ chạy bản Rust.
5. Phiên bản lấy từ Cargo.toml và đồng bộ với scripts/bump-version.py.

Xong khi: build được bản cài trên cả 3 OS ở chế độ dry run (artifact CI); trên máy này mở được .dmg chưa ký, và
updater đọc được appcast beta. Không tạo release hay tag thật.
```

### S12 `parity-cleanup`: M9

```text
Repo TableGrip, nhánh gốc feat/rust-ui-kit-phase-0 (đã merge S1–S11, bản beta Rust đã chạy ổn ít nhất 2 tuần).
Làm session S12 trong docs/plans/rust-migration/sessions.md: đọc mục "Quy ước chung" ở đó và mục M9 trong
docs/plans/rust-migration.md, rồi thực hiện.

Bước 1 — Kiểm parity, chưa xoá gì:
- Lập docs/plans/rust-migration/parity.md: mỗi file .kt trong app/src/main, core/src/main, engines/*/src/main →
  module Rust tương ứng, hoặc "bỏ có chủ đích" kèm lý do (lấy từ nhật ký S1–S11).
- Chạy bản Kotlin lần cuối để ghi lại toàn bộ golden/reference, rồi đóng băng chúng (từ đây chỉ Rust đọc).
- Liệt kê mọi chỗ còn thiếu. Nếu thiếu, dừng và báo; không xoá Kotlin.

Bước 2 — Cleanup, chỉ khi bước 1 không còn gì thiếu và người dùng đã đồng ý trong session này:
- Xoá app/, core/, engines/, build.gradle.kts, settings.gradle.kts, gradle/, gradlew, gradlew.bat, gradle.properties,
  build/, build-dev/; runner conformance Kotlin. Giữ conformance/ làm fixture.
- Sửa scripts/ (run.sh, run.cmd, test-update.sh), workflow (release*.yml → bản Rust, appcast.yml, rust.yml bỏ job
  Kotlin), .gitignore.
- Cập nhật README.md, CONTRIBUTING.md, docs/development.md, docs/guide.md, NOTICE (bỏ Jewel/JDBC, thêm giấy phép
  crate, có thể dùng cargo-about).
- Kiểm: `rg --files -g '*.kt' -g '*.kts'` rỗng; `rg -i 'gradle|jewel|compose'` chỉ còn trong CHANGELOG và docs/plans.
- Cleanup là một PR riêng, chỉ chứa thao tác xoá và sửa đường dẫn.

Xong khi: CI xanh 3 OS không còn Gradle; parity.md hoàn chỉnh; nhật ký M9 đã ghi.
```
