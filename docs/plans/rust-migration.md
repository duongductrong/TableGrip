# Migrate TableGrip sang Rust: kế hoạch toàn phần

Kế hoạch UI kit: [rust-ui-kit.md](rust-ui-kit.md). Tài liệu này đi tiếp từ chỗ UI kit dừng: port toàn bộ core, driver,
màn hình nghiệp vụ, đóng gói và phát hành, rồi gỡ hẳn mã Kotlin.

## Hiện trạng (nhánh `feat/rust-ui-kit-phase-0`, 10/11/2026)

| Phần | Kotlin | Rust | Ghi chú |
|---|---|---|---|
| UI kit (Jewel thay thế) | `app/ui`, `app/ui/theme` (~3,1k dòng) | 7 crate, ~20,6k dòng, phase 0–6 L+R xong | So ảnh 6 theme đạt ở M0 (336/336, 11 khác biệt đã duyệt); CI 3 OS chờ push |
| Core (`core/`) | 167 file, ~20k dòng | — | Chưa bắt đầu |
| Màn hình (`app/`) | 267 file, ~38k dòng | — | Chưa bắt đầu |
| Engine Redis / MongoDB | `engines/*` ~0,8k dòng | — | Chưa bắt đầu |
| Test | 187 file test Kotlin | test riêng UI kit | Test core/app chưa port |
| Đóng gói, cập nhật | Compose Desktop + jpackage, Sparkle (mac), WinSparkle (Win) | — | `release.yml` vẫn build bằng Gradle |

Kịch bản conformance và golden dùng runner Kotlin làm chuẩn đối chiếu cho đến M9.

## Nguyên tắc

1. **Tương thích dữ liệu người dùng là bắt buộc.** Bản Rust đọc nguyên `~/.tablegrip` (hoặc `tablegrip.home`): kết
   nối, settings, lịch sử, chat, workspace, backup schedule; và đọc đúng mục keychain (service/account) mà
   `SecretStore` đang ghi. Có test fixture lấy từ file bản Kotlin thật ghi ra.
2. **Kotlin là chuẩn đối chiếu đến phút cuối.** Logic thuần (lexer, splitter, formatter, dialect, filter, completion,
   chart suggest, type inference…) được port kèm *golden test*: chạy bản Kotlin trên cùng bộ input, ghi output ra JSON
   trong `conformance/core/`, bản Rust phải khớp. Golden được đóng băng trước khi xoá Kotlin.
3. **Tách logic khỏi GPUI** như UI kit: mỗi màn hình có crate/module state thuần Rust, test headless; phần vẽ mỏng.
4. **Mỗi PR một mảng nhỏ (≤ ~600 dòng)** kèm test, giống quy ước UI kit.
5. Bản Kotlin không thêm tính năng mới trong thời gian migrate (chỉ sửa lỗi nghiêm trọng).

## Kiến trúc đích

Workspace Cargo nằm ở gốc repo (đã làm ở M0): `crates/`, `conformance/`, `resources/` (theme, icon dùng chung).

```
crates/
  tg-theme, tg-text, tg-ui-logic, tg-ui, tg-gallery, tg-imgdiff   (đã có)
  tg-core        paths, settings, connection config/store, secret store (keyring), ssh tunnel (russh),
                 history, queries, session, activity, backup, theme prefs
  tg-sql         lexer, splitter, classifier, formatter, keywords, parameters, dialect, filter/projection,
                 DDL script, schema snapshot/diff, completion (SQL + document)
  tg-driver      trait RelationalConnection / KeyValueStore / DocumentStore, QueryResult, QuerySession, lỗi,
                 introspection model; mỗi engine sau một feature flag:
                   postgres (tokio-postgres + deadpool), mysql/mariadb (mysql_async), sqlite (rusqlite, bundled),
                   mssql (tiberius), clickhouse (HTTP, clickhouse crate), duckdb (duckdb crate, bundled),
                   redis (redis-rs), mongodb (mongodb crate), etcd (reqwest, v3 JSON API)
  tg-data        datafile (CSV/JSON/XLSX working copy + sync), importer + type inference, export/clipboard,
                 connection import (DBeaver, .env, IDE data sources, pgpass, plist, ssh hosts), test data
  tg-agent       agent registry, chat store, mentions, CLI adapter (spawn + decoder), provider adapter (SSE),
                 assist (SQL fix, chart assist, schema digest)
  tg-chart       chart spec/query/data/suggest, column profiler
  tg-app-logic   state của từng màn hình (grid model, edit history, filter builder, editor actions…)
  tg-app         binary GPUI: shell, explorer, các tab, dialog, settings, updater
```

Runtime: Tokio cho I/O, GPUI executor cho UI; cầu nối bằng channel. Huỷ query qua `CancellationToken` + cancel phía
server (pg cancel request, `KILL QUERY`, …), thay cho huỷ coroutine.

## Quyết định cần chốt trước M1

| # | Câu hỏi | Đề xuất |
|---|---|---|
| D1 | H2, HSQLDB, Derby và "Generic JDBC" chỉ có driver Java | **Bỏ** ở bản Rust 1.0; ghi rõ trong changelog. Phương án dự phòng: sidecar JVM nói JSON-RPC, làm sau nếu có người dùng yêu cầu |
| D2 | Cơ chế tải driver (`DriverLibrary`, lock file, BYO jar) | **Bỏ**: driver Rust biên dịch sẵn vào app. Hết vấn đề giấy phép Connector/J (GPL) |
| D3 | Cập nhật tự động | Giữ định dạng appcast + chữ ký Ed25519 hiện có. macOS: Sparkle.framework qua objc; Windows: WinSparkle qua FFI (DLL đã dùng). Linux: chỉ thông báo |
| D4 | Đóng gói | `cargo-packager`: `.app`/`.dmg` (ký + notarize), MSI (WiX), AppImage + `.deb` |
| D5 | Vị trí workspace Rust | **Đã chốt (M0):** `Cargo.toml` ở gốc repo, `crates/` ở gốc |

## Các mốc

Thời gian là ước lượng cho một người; nhiều mảng chạy song song được theo kiểu wave như UI kit.

Cách chia M1–M9 thành session, kèm prompt dán sẵn cho từng session: [sessions.md](rust-migration/sessions.md).

### M0 — Khép UI kit và dựng khung (1 tuần)
- Chạy Wave D của UI kit: so ảnh 6 theme, CI `ui-kit.yml` xanh trên macOS/Windows/Linux.
- Chuyển theme JSON sang `resources/themes/` dùng chung (Kotlin đọc qua `sourceSets`, Rust `include_str!` đường mới).
- Dọn 9 git worktree cũ trong `.claude/worktrees` (đã merge).
- Tạo crate rỗng theo kiến trúc đích, CI `cargo test --workspace` + `clippy` 3 OS.
- Viết harness golden phía Kotlin (`core/src/test/.../golden`) ghi JSON vào `conformance/core/`.

### M1 — Core thuần, không I/O (2–3 tuần)
`tg-sql` và phần thuần của `tg-chart`, `tg-data`: lexer, splitter, classifier, formatter, keywords, parameters,
`SqlDialect` (sinh SQL cho từng engine), filter/projection, DDL, schema diff, completion, type inference, chart
suggest/profiler, value formats. **Xong khi:** golden khớp 100% cho mọi engine.

### M2 — Lưu trữ và bảo mật (1–2 tuần)
`tg-core`: `TableGripPaths`, settings, `ConnectionStore`, `ConnectionString` (parse URL), `SecretStore` (crate
`keyring`, cùng service/account), history, saved queries, chat store, workspace, backup schedule, SSH tunnel (russh:
key, agent, password, known_hosts). **Xong khi:** đọc/ghi được bộ fixture `~/.tablegrip` do Kotlin tạo, round-trip
không đổi byte ngoài phần cố ý.

### M3 — Driver quan hệ (3–4 tuần)
`tg-driver` cho PostgreSQL, MySQL, MariaDB, SQLite, SQL Server, ClickHouse, DuckDB: pool, `QuerySession` riêng cho
console, `fetchPage`, export stream, introspection (`Introspector`, `StructureReader`), sample values, apply changes,
DDL, cancel, timeout, activity monitor, server identity, đọc binary/text. Port `RelationalContract` thành bộ test
chung chạy với mọi engine; integration test bằng Docker giống `ServerEnginesIntegrationTest`.
**Xong khi:** contract pass cho cả 7 engine, kết quả introspection khớp golden Kotlin trên schema `structure-it/*.sql`.

### M4 — Driver phi quan hệ (1–2 tuần)
Redis (key browser, scan, kiểu dữ liệu, command console), MongoDB (document store, query builder), etcd. Port test
của `engines/*` và `driver/etcd`.

### M5 — Data, agent, backup (2–3 tuần)
- `tg-data`: CSV/JSON/XLSX (csv, calamine, rust_xlsxwriter), data-file working copy + sync, import wizard backend,
  connection importers, test data generator, result formats/clipboard.
- `tg-agent`: CLI agent (spawn, shell env, decode output, model list), provider (HTTP streaming), mentions, assist.
- Backup/restore và scheduler.

### M6 — Màn hình lõi (5–7 tuần, phần khó nhất)
Theo thứ tự để sớm có bản dùng được nội bộ:
1. Shell `MainScreen`, kết nối (danh sách, dialog, test connection), explorer tree, status bar, workspace tabs,
   khôi phục workspace.
2. **Data grid** (`grid/*`, ~4,9k dòng): ảo hoá hai chiều, chọn ô/vùng, sửa ô, cell editors, paste, edit history,
   record editor, JSON tree, value panel, selection stats. Mục tiêu 1 triệu hàng mượt.
3. **SQL editor + console** (`console/*`): dựa trên `tg-text`, tô màu cú pháp, completion, chạy nhiều câu, kết quả
   nhiều tab, fix view, chart view.
4. Table view: filter builder, select builder, structure view, chỉnh sửa và commit.

### M7 — Màn hình còn lại (3–4 tuần)
Document browser + query builder, key browser + command console, schema tools (ER diagram + xuất ảnh, schema
compare, table designer, import dialog/wizard, test data dialog), chart, agent panel (markdown, model picker, mention,
history), settings (mọi trang), search, tools, files, backup UI, activity, shortcuts dialog, About.

### M8 — Đóng gói, cập nhật, phát hành (1–2 tuần)
`cargo-packager` cho 3 OS, ký/notarize macOS, Azure signing cho Windows, appcast (giữ `scripts/appcast.py`),
updater theo D3, `release.yml` chuyển sang cargo. Phát hành **beta Rust** song song với bản Kotlin cùng kênh beta để
người dùng thử, dữ liệu dùng chung nhờ M2.

### M9 — Parity và gỡ Kotlin
**Cổng vào (tất cả phải đúng):**
- Bảng parity theo từng file Kotlin trong `app/` và `core/` đều có dòng "đã port" hoặc "bỏ có chủ đích".
- Mọi golden và kịch bản conformance đã đóng băng, CI Rust xanh 3 OS.
- Một bản beta Rust đã phát hành và chạy ổn ít nhất 2 tuần.

**Cleanup (PR riêng, chỉ xoá):**
- Xoá `app/`, `core/`, `engines/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew*`,
  `gradle.properties`, `build/`, `build-dev/`.
- Xoá runner conformance Kotlin; giữ `conformance/` (scenarios, golden, reference) làm fixture đóng băng.
- Sửa `scripts/run.sh`, `run.cmd`, `test-update.sh`; workflow `release*.yml`, `appcast.yml`, đổi `ui-kit.yml` thành
  CI chính.
- Cập nhật `README.md`, `CONTRIBUTING.md`, `docs/development.md`, `docs/guide.md`, `NOTICE` (bỏ phần Jewel/JDBC, thêm
  giấy phép crate), `.gitignore`.
- `rg -g '*.kt' -g '*.kts' --files` phải rỗng; `rg 'gradle|Jewel|Compose'` chỉ còn trong CHANGELOG.

## Tổng ước lượng

M0–M8: khoảng 20–28 tuần cho một người, rút xuống còn khoảng một nửa nếu chạy song song theo wave (M1/M2 song song;
M3/M4/M5 song song; trong M6–M7 mỗi màn hình một session sau khi shell xong).

## Nhật ký

### M0 (10/11/2026)

- Xoá 9 git worktree cũ của UI kit trong `.claude/worktrees`. Mọi commit của chúng đã có trên nhánh này; các branch
  `claude/ui-kit-*` vẫn giữ nguyên.
- Workspace Cargo lên gốc repo: `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`, `crates/`, `conformance/`;
  `scripts/imgdiff.sh` và `scripts/extract-icons.py` chuyển từ `ui-kit/scripts`.
- Theme JSON và icon riêng của app chuyển sang `resources/themes`, `resources/icons`. Gradle thêm `resources/` vào
  resource của `:app` nên jar vẫn có `/themes/*.json` và `/icons/*.svg` như trước.
- Khung crate rỗng: `tg-core`, `tg-sql`, `tg-driver`, `tg-data`, `tg-agent`, `tg-chart`, `tg-app-logic`, `tg-app`.
- CI: `ui-kit.yml` đổi thành `rust.yml`, chạy ở gốc repo; job Kotlin chạy thêm `CoreGoldenTest`.
- Golden cho core: `CoreGoldenTest` đọc `conformance/core/inputs/*.json` và ghi `conformance/core/golden/*.json`
  (`-Pconformance.record=true`). Hiện có ba subject: `sql-lexer`, `sql-splitter`, `sql-classifier`. Phía Rust,
  `crates/tg-sql/tests/golden.rs` đã kiểm tra golden phủ đủ input và in ra các subject còn pending. M1 thêm một nhánh
  `run` cho mỗi subject port xong.
- Wave D: so ảnh cả 6 họ theme (12 biến thể), 336/336 specimen đạt. 11 khác biệt (10 ở Contrast do khử răng cưa, 1 ở
  thanh cuộn Catppuccin Frappé) được ghi vào `conformance/approved-image-diffs.json`, mỗi mục có trần riêng và lý do;
  `tg-imgdiff` có thêm `--approved`.
- Còn lại: CI `rust.yml` xanh trên Windows và Linux, chỉ xác nhận được sau khi push.

### S1 (10/11/2026)

Nhánh `rust/core-pure`. Xong cả 6 bước của M1: tg-sql (lexer → completion), tg-chart và tg-data (phần thuần). Mọi subject
trong `conformance/core/inputs` (29 subject) khớp golden Kotlin trên Rust, không còn subject pending.

- **Model dùng chung ở `tg_sql::model`** (DatabaseType, Namespace, TableRef, ColumnInfo, TableDetails, constraint,
  trigger, `jdbc_types`). Lý do: dialect, filter, DDL, schema diff và completion đều cần, còn tg-sql không phụ thuộc
  crate TableGrip nào. Vì vậy tg-core (S2) và tg-driver (S3) dùng được mà không phụ thuộc lẫn nhau. DatabaseType
  serialize theo `id` như Kotlin. Danh sách DriverId và defaultProperties của JDBC bị bỏ khỏi descriptor (D2), để
  tg-driver tự giữ theo engine. H2/HSQLDB/Derby/OtherJdbc vẫn còn trong danh sách để profile cũ vẫn đọc được (D1).
- `SqlDialect` là enum gồm 8 dialect. Phần bind JDBC chuyển cho driver. `list_namespaces` nhận tên schema/catalog mà
  driver đã đọc.
- Offset tính theo byte. Runner golden đổi sang UTF-16. Khác Kotlin ở ký tự ngoài BMP nằm ngoài chuỗi/comment
  (`tg_sql::kt`).
- FilterCompiler: giờ hiện tại và múi giờ là tham số, ngày đầu tuần là một setting (mặc định thứ Hai, Kotlin lấy theo
  locale).
- Harness golden dùng chung: `crates/tg-sql/tests/support/harness.rs` (mỗi crate include bằng `#[path]`). Subject
  `chart-*` thuộc về tg-chart, `data-*` thuộc về tg-data. Subject được ghi golden nhưng chưa có runner thì test fail.
- Float trong golden: ER layout dùng bit pattern. Golden chart đọc double bằng `serde_json/float_roundtrip` (dev-dep
  của tg-chart).
- **Golden (số ca)**: tg-chart: chart-values 118, chart-profile 25, chart-suggest 50, chart-spec 53, chart-data 61,
  chart-lttb 13, chart-query 31 (mỗi ca chạy trên mọi dialect). tg-data: data-inference 245, data-clipboard 57,
  data-formats 86. Input dùng `"gen": {"count", "cells": ["{i}", "{i%k}"]}` để sinh nhiều dòng (downsample, giới hạn
  scatter, cap distinct) mà không phình file; chart-data có `"summary": true` chỉ in số điểm, điểm đầu/cuối và tổng y.
  Input tránh series có tổng bằng nhau (Kotlin phá hòa theo thứ tự hash).
- **tg-chart**: các test Kotlin chạy SQLite thật (ChartQueryTest) không thành golden được; phần SQL sinh ra đã được
  golden phủ cho cả 8 dialect, còn việc chạy trên SQLite thuộc contract test của driver (S3). Đọc số từ text nay nhận
  cả hex float của Java (`0x1p3`), lệch này lộ ra nhờ golden. Kiểm tra checkpoint (đọc dài dừng theo callback) là test
  Rust riêng, `crates/tg-chart/tests/checkpoint.rs`.
- **tg-data** (chỉ phần thuần, chưa có reader/writer file, đó là S5):
  - `TypeInference` port nguyên; `is_json` mô phỏng `Json.parseToJsonElement` của kotlinx.serialization 1.11 (module
    `kotlinx_json`), vì nó rộng hơn JSON thật: giá trị không có nháy được đọc như chuỗi ký tự thô (`[abc]`, `{"a":1;}`,
    `[~]` đều hợp lệ), và sau `]` còn giá trị thì vẫn nuốt vào cùng mảng (`[1,2]x]`). Độ sâu lồng nhau tối đa 2000 (Kotlin
    tràn stack ở mảng, còn object chuyển sang đệ quy tường minh), nên khác Kotlin ở cỡ đó.
  - `ResultFormats`/`ResultWriter` ghi qua `io::Write`. Cột số khi xuất JSON không in lại đúng chuỗi gốc mà theo cách
    kotlinx in `BigDecimal`: đọc `toString()` thành long, rồi unsigned long, rồi double (`1.50` thành `1.5`, `1e3` thành
    `1000.0`, `1e400` làm lệnh ghi lỗi). Vì thế `tg_sql::Decimal::to_java_string` (`BigDecimal.toString`) và
    `Double.toString` kiểu Java (JDK 19+, gồm ca 4.9E-324) được thêm. `ResultColumn` tạm định nghĩa trong tg-data
    (giống `ChartColumn` của tg-chart); S3 nên gộp cả hai vào kiểu kết quả của tg-driver.
  - `ResultFormats::format` trả `io::Result<String>` vì ca `1e400` ở trên; golden ghi `"error"` cho ca đó.
- Gradle: golden test dùng build cache nên khi đổi file input mà không đổi code, chạy
  `./gradlew :core:cleanTest :core:test --tests '*CoreGoldenTest*' -Pconformance.record=true --no-build-cache`.

Còn dở / lưu ý cho session sau:
- Chưa làm: reader/writer CSV, JSON, XLSX, ImportReader, working copy của file dữ liệu (S5).
- Số Unicode ngoài ASCII trong `BigDecimal` (chữ số Ả Rập...) Java nhận, `Decimal::parse` thì không. Không có trong golden.

### S2 (10/11/2026)

Nhánh `rust/storage` (cắt từ `rust/core-pure` ở `bc0e3b8`, nên dùng được `tg_sql::model`). SshTunnel làm song song ở
nhánh `rust/storage-ssh` rồi đã merge vào `rust/storage`. ChatStore để S5. Đã thử merge thêm phần cuối của S1
(`rust/core-pure`): chỉ xung đột ở nhật ký, toàn workspace vẫn xanh với `preserve_order`.

Xong, trong `crates/tg-core`, mỗi mô-đun mirror một package Kotlin:
- `paths`: `TableGripPaths` là struct giữ `home`. `from_env()` đọc `TABLEGRIP_HOME` (thay `tablegrip.home`), `new(path)` cho
  test, nên test không chạm `~/.tablegrip` thật.
- `settings`, `theme`, `backup` (chỉ `BackupSchedule`: lịch nằm trong settings.json, Kotlin không lưu gì thêm).
- `connection::{config, store, string, files, ai_access, secret}`.
- `history`, `queries`, `session`, `activity` (xem "Khác kế hoạch").
- `json`: phần tương thích kotlinx. `omit_defaults!` ghi đúng thứ tự khai báo và bỏ field bằng mặc định (kotlinx
  `encodeDefaults = false`, mặc định của `Json {}`); `strip_lenient` đọc comment và dấu phẩy thừa (settings và theme);
  `to_pretty(indent)` (kotlinx mặc định thụt 4 dấu cách, settings/theme dùng 2). `serde_json` bật `preserve_order` để
  profile mà bản này không hiểu (engine của bản mới hơn) được ghi lại nguyên thứ tự key, như `ConnectionStore.write`.
- Dependency mới (đều MIT/Apache, ghim ở `[workspace.dependencies]`): `keyring` 3.6.3 (chỉ macOS/Windows), `indexmap`
  2.14.2 (map giữ thứ tự như `LinkedHashMap`), `tempfile` 3.27.0 (dev). Không cần sửa `NOTICE`.

**Fixture round-trip: JSON tương đương và byte giống hệt** trên cả 7 file (connections, settings, theme, history, session,
3 file queries). `StorageFixtureTest` (Kotlin) sinh `conformance/core/fixtures/home/` bằng chính các store Kotlin
(`-Pconformance.record=true`; không có cờ thì test so sánh và fail nếu format đổi): 33 profile (mọi DatabaseType, cộng SSH, URL thô, unicode), SSH
dạng key, agent (key mặc định) và password, profile "từ tương lai" (type `QuantumDB`) để kiểm tra giữ nguyên profile lạ,
settings không mặc định ở mọi field, theme file, history có unicode/xuống dòng, queries trong thư mục, session có
window/split. `crates/tg-core/tests/fixture.rs` đọc fixture, ghi lại qua store Rust vào thư mục tạm rồi so.

Test Rust đã port (đều pass; 57 test trong tg-core, 2 test keychain ignore):
- `tests/connection.rs`: ConnectionStoreTest (3), AiAccessTest (2), DatabaseFilesTest (2 phần đầu), cộng kiểm tra giữ
  profile lạ, thứ tự và field mặc định, `effective_driver`.
- `tests/connection_string.rs`: ConnectionStringTest (12) và phần "pasted paths" của DatabaseFilesTest, cộng test redis TLS và
  percent-decoding.
- `tests/settings.rs`: SettingsStoreTest (6), phần settings của StorageTest, ThemeFilesTest (3), BackupScheduleTest (2).
- `tests/storage.rs`: phần history của StorageTest, QueryLibraryTest (4), SessionStoreTest (2), ActivityTest.support, cộng
  `running_for`.
- `tests/fixture.rs`: round-trip fixture.

Khác kế hoạch / khác Kotlin:
- **Sửa một lỗi Kotlin (`core/src/main`, commit `fix(core): a plain redis:// URL no longer turns on TLS`)**:
  `redisUri` kiểm tra `scheme.endsWith("s")` để nhận `rediss://`, nhưng "redis" cũng kết thúc bằng "s", nên mọi URL
  `redis://` dán vào đều ra `ssl=true`. Đã sửa thành `rediss`/`valkeys` và thêm test; Rust làm theo hành vi đúng.
- `Activity.kt` không có phần lưu trữ nào (chỉ monitor đọc từ DB). Đã port kiểu dữ liệu, `SessionActivity::running_for` và
  `ActivitySupport::of` (pure, có test); phần query theo engine để S3. Thời điểm dùng `chrono::DateTime<Utc>` thay `Instant`.
  `Backup.kt` chỉ có `BackupSchedule` là phần lưu trữ; lệnh `pg_dump`... thuộc M5.
- Lỗi parse settings.json: thông điệp lấy từ serde, không trùng chữ với kotlinx (chỉ dùng để hiển thị). Lỗi đọc file (I/O)
  cũng được coi là `load_error` để không ghi đè, trong khi Kotlin ném exception.
- Số thực trong session (`window`, `sidebarSplit`...) dùng `f32` như Kotlin `Float`; serde_json in giống Java với dải giá trị
  thường dùng (không có dạng mũ).
- `ConnectionStore::load/save` trả `Result` (Kotlin ném exception khi file không phải mảng JSON hay JSON hỏng).

Kiểu model tạm định nghĩa ở tg-core, **cần gộp về sau** (tg-sql do S1 sở hữu nên không đụng):
- `connection::DriverId` (14 giá trị, serialize theo tên) và `DriverId::for_type(&DatabaseType)` (bảng `drivers` của từng
  engine, suy từ family và id). Profile vẫn lưu field `driver`, nên cần giữ để đọc/ghi. Gợi ý: chuyển sang tg-driver (S3) khi
  tg-driver có enum driver riêng; tg-core giữ một bản tối giản để serialize.
- `connection::SshConfig` / `SshAuth` là kiểu lưu trong profile; `ssh::SshTunnelSpec` / `ssh::SshAuth` là đầu vào của
  tunnel (có thêm secret và `Agent`). `SshTunnelSpec::from_config(&SshConfig, secret, paths.known_hosts())` nối hai bên.
- `connection::ConnectionConfig`, `Environment`, `DataFileOptions`, `AiAccess` ở tg-core đúng chỗ (Kotlin cũng ở package
  `connection`), nhưng S3 sẽ import từ `tg_core::connection`; không cần chuyển.

Keychain (SecretStore):
- Theo mã nguồn java-keyring 1.0.4: macOS = generic password (service `TableGrip`, account = connection id hoặc `<id>:ssh`,
  UTF-8); Windows = credential generic có target `TableGrip|<key>`, UserName = key, blob UTF-16LE; Linux = Secret Service với
  attribute `service`/`account`, label `service|account`. `keyring` đọc/ghi hai kiểu đầu đúng như vậy (Windows dùng
  `Entry::new_with_target("TableGrip|<key>", ...)` vì mặc định của crate là `<user>.<service>`).
- **Đã kiểm tay trên macOS (10/11/2026): đạt.** `KeychainProbeTest` (Kotlin, chỉ chạy khi có
  `-Pkeychain.probe=set|delete`) ghi item `service=TableGrip account=tablegrip-s2-probe`; test Rust
  `reads_the_item_java_keyring_wrote` đọc lại đúng giá trị, và `writes_an_item_java_keyring_can_read_and_removes_it`
  ghi rồi xoá được một item. Cả hai là `#[ignore]` vì macOS hỏi quyền truy cập (lần đầu agent chạy thì treo chờ hộp
  thoại). Cách chạy lại: `./gradlew :core:test --tests '*KeychainProbeTest*' -Pkeychain.probe=set`, rồi
  `cargo test -p tg-core --test keychain -- --ignored` (bấm "Allow"), rồi `-Pkeychain.probe=delete`. Windows chưa kiểm được (chỉ theo mã nguồn).
- **Quyết định còn mở: Linux.** `keyring` 3.x ghi Secret Service với attribute `service` + `username`, còn java-keyring ghi
  `service` + `account`, nên item cũ sẽ không tìm thấy; ngoài ra backend sync cần libdbus (CI Linux sẽ vỡ). Hiện
  `KeyringSecretStore` trên Linux là stub (get = None, set = lỗi). Phương án A: dùng trực tiếp crate `secret-service` (zbus,
  thuần Rust) với đúng attribute của java-keyring. Phương án B: dùng `keyring` rồi migrate item cũ lần chạy đầu. Đề xuất A,
  làm trong S6/S11 khi có máy Linux để thử.

SshTunnel (`tg_core::ssh`, russh 0.65.1, backend crypto `ring` nên không cần OpenSSL/aws-lc/CMake):
- API: `SshTunnel::open(&spec, host, port).await`, `open_blocking(&Handle, ...)` cho code sync, `local_port()`, `close()`
  (drop cũng dừng). Tunnel bind `127.0.0.1:0`, keepalive 30 s, timeout kết nối 10 s như Kotlin.
- Auth: password, key (OpenSSH, PKCS#1/SEC1 PEM, PKCS#8 mã hoá, có passphrase; `~/` được mở rộng; không có path thì thử
  `~/.ssh/id_rsa`, `id_ed25519`, `id_ecdsa` như SSHJ), ssh-agent (`SSH_AUTH_SOCK`; Windows: named pipe rồi Pageant, chưa
  build thử trên Windows). `pkcs5` với `sha1-insecure` chỉ để russh đọc được key PKCS#8 mã hoá kiểu cũ.
- known_hosts: TOFU ở `~/.tablegrip/known_hosts` như Kotlin. Host lạ thì ghi thêm, key đổi thì lỗi `HostKeyChanged` và
  không sửa file, đọc được dòng hash `|1|`, `[host]:port` khi port khác 22. Khác Kotlin: file chỉ được tạo khi pin lần
  đầu. Có thêm chế độ `Strict` (không ghi).
- Test: 2 test của SshTunnelTest port nguyên, cộng ~18 test (định dạng key, passphrase sai, agent thật qua
  `ssh-agent -D` (bỏ qua nếu máy không có), đóng/drop giải phóng port, 8 kết nối đồng thời, đích từ chối). Server test là
  russh chạy trong tiến trình, không cần docker. Fixture key chỉ dùng cho test ở `crates/tg-core/tests/fixtures/ssh/`.
- `ring` theo giấy phép ISC/OpenSSL-style, đã ghi vào `NOTICE`.
- Chưa làm: key PuTTY `.ppk`, chứng chỉ SSH (host và agent).

Còn dở:
- ChatStore (S5).
- `KeyringSecretStore::get` nuốt mọi lỗi thành None như Kotlin; nếu UI muốn phân biệt "chưa lưu" với "keychain từ chối" thì
  cần thêm `try_get`.

### S3 (10/11/2026, đang làm)

Nhánh `rust/drivers-core`. Bước 1 (thiết kế) viết trước khi code; hai điểm cuối mục cần người dùng chốt.

#### Thiết kế `tg-driver`

**Crate và phụ thuộc.**
- `tg-driver` phụ thuộc `tg-sql` (model, dialect, filter, DDL) và `tg-core` (ConnectionConfig, SshTunnel, activity
  model). Không phụ thuộc `tg-data`.
- `ResultColumn` chuyển về `tg_sql::model` (cùng `is_numeric`, `is_binary`). `tg-data` và `tg-chart` dùng lại kiểu
  này, bỏ bản tạm `tg_data::ResultColumn`, còn `ChartColumn` thì tạo từ nó.
- Port `edit/ChangeSet.kt` (`BoundValue`, `RowChange`, `ChangeStatement`, `ChangeSql`) và `edit/ColumnEdit.kt` vào
  `tg_sql::edit`, vì chúng thuần và chỉ cần dialect. Phần này kèm golden.
- Feature theo engine: `postgres`, `sqlite` (S3); `mysql`, `mssql`, `clickhouse`, `duckdb` (S4); `redis`, `mongodb`,
  `etcd` (S5). Mặc định bật hết. `cargo test -p tg-driver --no-default-features --features sqlite` vẫn chạy được.

**Bất đồng bộ và dispatch.**
- API là `async`, chạy trên tokio. Trait là object-safe qua `#[async_trait]` (crate `async-trait`, MIT/Apache), vì UI
  giữ `Arc<dyn RelationalConnection>` và mỗi engine thêm sau (S4/S5) chỉ là một struct mới, không phải sửa enum
  trung tâm.
- Một hàm vào duy nhất: `tg_driver::connect(&ConnectionConfig, Secrets) -> Result<Connection, DriverError>`, trong đó
  `enum Connection { Relational(Arc<dyn RelationalConnection>), KeyValue(Arc<dyn KeyValueConnection>),
  Document(Arc<dyn DocumentConnection>) }` (khớp `sealed interface DatabaseConnection`). Hàm này chọn engine theo
  `config.db_type.family` qua bảng `ENGINES: &[EngineEntry]` gồm các mục bật bằng `#[cfg(feature)]`. Engine nào
  không được build thì trả lỗi `DriverError::Unsupported`. `tg_driver::test(...)` trả về `ServerIdentity` gợi ý.
- `Secrets { password: Option<String>, ssh: Option<String> }`; tunnel mở trong `connect` bằng
  `tg_core::ssh::SshTunnel`, giữ trong connection, đóng khi drop.
- Engine đồng bộ (rusqlite, sau này duckdb) chạy trên một thread riêng cho mỗi kết nối, nhận job qua channel
  (`blocking::Worker`). Không dùng `spawn_blocking` cho từng lệnh, vì kết nối SQLite không `Sync` và phải giữ thứ tự.

**Trait** (rút từ `DatabaseConnection.kt`, `QuerySession.kt`, `KeyValueConnection.kt`, `DocumentConnection.kt`; tên
method giữ như Kotlin, viết theo snake_case):
- `RelationalConnection`: `config()`, `dialect()`, `namespaces`, `tables`, `table_details`, `table_columns`,
  `table_structure`, `columns_of`, `relations`, `sample_values`, `fetch_page(&PageRequest)`, `export_table`,
  `count_rows`, `apply_changes`, `schema_snapshot`, `execute_script`, `insert_rows`, `open_session`. Method có
  default "unsupported": `server_major_version`, `backup_into`, `open_activity_monitor`. Đầu vào dài như
  `fetch_page(table, filter, order_by, limit, offset, timeout, projection)` gom thành struct `PageRequest`.
- `QuerySession` là trait riêng, trả từ `open_session() -> Box<dyn QuerySession>`, gồm `execute(sql, ExecOptions)`,
  `set_auto_commit`, `commit`, `rollback`, `is_valid`, `auto_commit()`, `uncommitted_statements()`, `read_only()`.
  Kiểm read-only và đếm statement chưa commit nằm trong một wrapper chung, `GuardedSession`, dùng `SqlClassifier`,
  nên engine chỉ cần cài phần chạy thật (`RawSession`).
- `KeyValueConnection` và `DocumentConnection`: chép nguyên API Kotlin kèm các kiểu `KeyInfo`, `KeyValue`, `KeyEdit`,
  `CollectionInfo`, `DocumentPage`… S3 chỉ định nghĩa trait, kiểu dữ liệu và wrapper read-only (`Guarded`, gồm
  `split_arguments`, `READ_COMMANDS`, `is_etcd_read`, `DocumentStore::is_read`, có test port từ Kotlin). Engine thật
  để S5 làm.
- Export: `export_table(.., sink: &mut dyn RowSink, progress: &dyn Fn(u64))`, với `trait RowSink: Send { fn
  begin(&mut self, &[ResultColumn]); fn write(&mut self, &[Cell]) -> io::Result<()>; fn finish(&mut self) }`.
  `tg-data` có adapter từ `ResultWriter` (một module nhỏ, bật bằng feature `driver`).

**QueryResult, ô, lỗi.**
- `enum QueryResult { Rows(Rows), Update { affected_rows: u64, elapsed_ms: u64 } }`,
  `Rows { columns: Vec<ResultColumn>, rows: Vec<Vec<Cell>>, truncated: bool, elapsed_ms }`.
- `Cell`: xem điểm chốt A. Dù chọn phương án nào, giá trị binary trong grid đều hiện dạng `BinaryText` (hex, cắt ở
  16 KiB, có đuôi `… (N bytes)`); khi export thì thay bằng `<binary N bytes>` như Kotlin. Port `BinaryText.kt` kèm test.
- `QueryError { message, sql_state, vendor_code, position }` (position đếm từ 0, theo UTF-16 như Kotlin). Lỗi tổng là
  `DriverError` (thiserror): `Query(QueryError)`, `ReadOnly { verb }`, `Script { message, statement, completed,
  rolled_back }`, `Insert { message, row }`, `Cancelled`, `Timeout`, `Connect(String)`, `Tunnel(SshError)`,
  `Unsupported(String)`, `Io`. Thông điệp người dùng thấy giữ đúng chữ của Kotlin (ví dụ "Expected 1 row(s) but…",
  "This connection is read-only: …").

**Huỷ và timeout.**
- Huỷ bằng drop: mọi future đều an toàn khi bị drop. Khi đang có câu lệnh chạy trên server, một guard trong future
  gửi lệnh huỷ phía server lúc drop: Postgres dùng `CancelToken::cancel_query` (đi qua cùng tunnel), SQLite dùng
  `sqlite3_interrupt` (`InterruptHandle`). Kết nối pool đang dùng dở thì bị bỏ, không trả về pool, để không ai nhận
  lại một kết nối còn đang huỷ. UI giữ `tokio_util::sync::CancellationToken` và chạy
  `token.run_until_cancelled(fut)`. Như vậy API không phải thêm tham số token, giống cách coroutine Kotlin làm.
- Timeout: `ExecOptions { max_rows, timeout: Option<Duration> }`. Postgres thì `SET LOCAL statement_timeout` không
  dùng được ngoài transaction, nên bọc bằng `tokio::time::timeout`, hết giờ thì gửi cancel và trả `DriverError::Timeout`.
  SQLite dùng `progress_handler` để ngắt. `sample_values` cố định 3 s như Kotlin.

**Pool và session.**
- Pool cố định 4 kết nối cho explorer và table (Kotlin dùng Hikari với min = max = 4), có keepalive 120 s, timeout lấy
  kết nối 10 s. Postgres dùng `deadpool-postgres`. SQLite dùng pool riêng gồm 4 worker. Riêng `:memory:` và file tạm
  dùng chung một worker để dữ liệu nằm ở một chỗ.
- `open_session` mở một kết nối riêng, nằm ngoài pool. `open_activity_monitor` cũng vậy.
- `schema_snapshot` đọc song song tối đa `POOL_SIZE - 1` bảng (semaphore), giống Kotlin.

**Introspection (thay cho JDBC `DatabaseMetaData`).**
- `trait Catalog` theo engine, gồm `namespaces`, `tables`, `columns`, `primary_key`, `indexes`, `imported_keys`,
  `relations`, `structure`. Phần ghép kết quả (`Introspector.details`: sắp cột, nhóm index, gộp FK theo `KEY_SEQ`, tên
  FK mặc định, referential action) là code chung, port từ `Introspector.kt`.
- Golden được ghi từ JDBC, nên các field mà JDBC tự suy ra phải ra cùng giá trị: `type_name` và `jdbc_type`
  (pgjdbc trả `serial`/`bigserial` khi default là `nextval`, `int4`, `varchar`…; sqlite-jdbc lấy kiểu khai báo và
  map affinity), `size`, `scale`, `IS_AUTOINCREMENT`. Postgres chép logic từ câu catalog của pgjdbc
  (`PgDatabaseMetaData.getColumns`/`getIndexInfo`/`getImportedKeys`) và bảng `TypeInfoCache`. SQLite chép từ
  `JDBC3DatabaseMetaData` của xerial (PRAGMA `table_xinfo`, `index_list`, `foreign_key_list`).
- `StructureReader` (constraint, trigger, DDL) vốn đã chạy bằng SQL thuần trên kết nối, nên port gần nguyên.
- Golden: test Kotlin mới `IntrospectionGoldenTest` (ở `src/test`) kết nối vào Postgres đã seed `structure-it/postgres.sql`,
  và vào một file SQLite seed bằng `structure-it/sqlite.sql` (file mới, tương đương bản Postgres). Test dump
  `TableDetails` + `TableStructure` của mọi bảng ra `conformance/core/golden/introspection-{postgres,sqlite}.json`.
  Test Rust so khớp sau khi seed cùng file. Golden SQLite chạy mặc định; golden Postgres chạy khi có `TG_IT_POSTGRES`.

**Postgres.** `tokio-postgres` + `deadpool-postgres`, TLS bằng `tokio-postgres-rustls` + `rustls`.
`sslmode` (`disable/allow/prefer/require/verify-ca/verify-full`) đọc từ `properties`/URL như `JdbcUrls`; với
`require` thì mã hoá nhưng không kiểm chứng chỉ (như pgjdbc), còn `verify-*` dùng `rustls-platform-verifier` (kho CA
của hệ điều hành). Đọc ô và bind tham số theo điểm chốt B. ServerIdentity, activity (`pg_stat_activity`,
`pg_cancel_backend`/`pg_terminate_backend`), `server_major_version` (`server_version_num`), export qua `DECLARE
CURSOR` + `FETCH 1000` trong transaction như pgjdbc.

**SQLite.** `rusqlite` (bundled). Pragma, `VACUUM INTO` để backup, `PRAGMA foreign_keys` quanh script rebuild,
read-only mở bằng `SQLITE_OPEN_READ_ONLY`. Ô ra text đúng như `sqlite3_column_text` mà sqlite-jdbc dùng (ví dụ REAL
in `%!.15g`).

**Contract.** `tests/contract/mod.rs` port `RelationalContract.kt`: `async fn run(conn, ContractOptions { int_type,
text_type, table_suffix, foreign_keys, transactions })`, thêm các ca huỷ, timeout, read-only, binary/`BinaryText` và
script rollback. `tests/sqlite.rs` chạy mặc định. `tests/postgres.rs` tạo database `dg_<ts>` khi có
`TG_IT_POSTGRES=host:port` (user `postgres`, mật khẩu `TG_IT_POSTGRES_PASSWORD`, mặc định `pw`). Docker:
`docker run -d --rm --name dg-pg -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:17-alpine`. S4 thêm
`tests/<engine>.rs` gọi đúng hàm `run` đó.

**Dependency dự kiến** (ghim ở workspace): `async-trait`, `tokio` (thêm `rt-multi-thread`, `sync`, `time`, `macros`,
`net`), `tokio-util`, `tokio-postgres`, `deadpool-postgres`, `tokio-postgres-rustls`, `rustls`,
`rustls-platform-verifier`, `rusqlite` (`bundled`), `futures-util`. Đều là MIT/Apache. `ring` đã có trong NOTICE.

#### Đã chốt (10/11/2026): A1 + B1

Người dùng chọn A1 (ô là `Option<String>`) và B1 (Postgres đọc bằng simple query, tham số inline thành literal).
Literal text inline không kèm kiểu, nên server tự suy kiểu y như khi pgjdbc bind `setObject(…, Types.OTHER)`.

#### Phương án đã cân nhắc

**A. Kiểu giá trị ô.**
- A1: `type Cell = Option<String>`, giống Kotlin (`List<String?>`). Driver tự đổi mọi giá trị ra text, binary ra
  `BinaryText`. Grid, export, chart và tg-data (golden S1) đều đang nhận text, nên không phải đổi gì.
- A2: enum có kiểu (`Null | Bool | Int(i64) | Float(f64) | Decimal(String) | Text | Bytes | Date… | Json`), còn việc
  đổi ra text dời lên tầng hiển thị. Grid sắp xếp và căn số dễ hơn, nhưng mỗi engine phải định dạng giống nhau, và mọi
  chỗ đang ăn text phải sửa.
- **Đề xuất A1.** Parity dễ kiểm hơn, giá trị đã sửa thì ghi ngược lại bằng chính text đó, và S7 vẫn có `jdbc_type`
  trên cột để căn số.

**B. Lấy text từ Postgres.** tokio-postgres chỉ trả text ở simple query protocol; extended protocol luôn trả binary.
- B1: dùng simple query cho mọi câu đọc. Tham số của filter và edit được inline thành literal, escape an toàn bằng
  `E'…'` (nhân đôi `\` và `'`, từ chối NUL) cộng cast kiểu khi cần. Text giống hệt pgjdbc `getString` (cũng chính là
  output của server). Console giới hạn `max_rows` bằng cách đọc dạng stream, đủ hàng thì gửi cancel. Cùng chính sách
  này cho S4: MySQL dùng text protocol (`query_iter`) cho câu không có tham số.
- B2: extended protocol, tham số bind thật (gửi dạng text), nhưng kết quả binary, nên phải tự định dạng từng kiểu
  Postgres (numeric, timestamp/tz theo `TimeZone` của session, interval, mảng, range, kiểu extension như PostGIS…).
  Kiểu không biết thì phải chạy lại với `::text`.
- **Đề xuất B1.** Định dạng text là việc của server nên khớp pgjdbc tuyệt đối, ít code hơn nhiều. Đổi lại, tham số
  không còn được server bind. `ChangeStatement` vẫn dùng `?` và chỉ tầng Postgres mới inline, nên sau này muốn đổi sang
  B2 thì không phải sửa API.
