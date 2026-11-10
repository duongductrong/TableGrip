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
