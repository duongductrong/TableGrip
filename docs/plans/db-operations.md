# Kế hoạch vận hành DB: Activity monitor, backup và restore

Ngày 2026-10-31. Chốt từ trao đổi về mục "Vận hành DB" trong đề xuất tính năng.

## 0. Quyết định đã chốt

| Câu hỏi | Quyết định |
| --- | --- |
| Phạm vi bản đầu | PostgreSQL và MySQL/MariaDB cho cả hai tính năng. Engine khác: ẩn hoặc disable, **luôn kèm lý do** |
| Công cụ dump/restore | Không đóng gói vào app. Tải lazy từ nguồn chính thức, chỉ sau khi người dùng xác nhận |
| Quản lý công cụ đã tải | Settings có trang riêng: xem, cập nhật, gỡ, đổi đường dẫn, mở thư mục |
| Backup định kỳ | Có, nhưng **tắt mặc định**. Chỉ chạy khi người dùng bật và cấu hình đủ |
| Vị trí Activity monitor | Tab trong workspace, một tab cho mỗi kết nối (xem §1.1) |

## 1. Activity monitor

### 1.1 Vị trí và cách mở

Activity là **tab trong workspace**, không phải tool window. Lý do: bảng session có nhiều cột, SQL dài và cây blocking
cần chỗ rộng, trong khi tool window bên cạnh (như History) hẹp. Tab cũng cho phép mở activity của hai kết nối cạnh
nhau, kéo thả và đóng như mọi tab khác.

Các đường mở (đều mở lại tab đã có, không tạo trùng):

- Explorer: chuột phải kết nối → **Activity**.
- Phím tắt **⌥⌘A** (Ctrl+Alt+A), áp dụng cho kết nối đang chọn. Thêm vào `Shortcuts.kt` và `ShortcutsDialog`.
- Status bar: khi kết nối đang mở có session bị chặn quá 5 s, hiện chip `⚠ 2 blocked`; click mở Activity, lọc sẵn
  "Blocked". Chip chỉ chạy khi tab Activity của kết nối đó đã từng mở hoặc người dùng bật trong Settings, để không
  tự ý query server.

Engine không hỗ trợ: mục menu vẫn hiện nhưng disable, tooltip "Activity chưa hỗ trợ SQLite — file DB không có session
server" hoặc "… chưa hỗ trợ ClickHouse, dự kiến bản sau".

### 1.2 Bố cục tab

```
┌ Toolbar: [⟳ 2s ▾] [⏸] | Lọc: [Active ▾] [User ▾] [DB ▾] [> 10s] | [Ẩn idle] [Ẩn session DataGrok]
├──────────────────────────────────────────────┬──────────────────────────────┐
│ Cây session (DataGrid, cột pid/user/db/      │ Chi tiết session đã chọn:    │
│ state/thời gian/wait/SQL rút gọn).           │ SQL đầy đủ (tô màu), client, │
│ Session đang giữ lock ở gốc, session chờ     │ app, bắt đầu tx/query, lock  │
│ nó thụt vào bên dưới.                        │ đang giữ/chờ.                │
│                                              │ [Mở trong console] [EXPLAIN] │
│                                              │ [Copy] [Cancel] [Terminate]  │
└──────────────────────────────────────────────┴──────────────────────────────┘
```

- Tô màu: chạy lâu hơn ngưỡng (vàng → đỏ), *idle in transaction* (cam), đang chờ lock (đỏ nhạt).
- Session của chính DataGrok có nhãn `DataGrok` và mặc định ẩn.
- Refresh: 1 / 2 / 5 / 10 s hoặc tạm dừng. Tab không hiển thị thì ngừng refresh.

### 1.3 Core

`core/.../activity/`:

```kotlin
data class SessionActivity(
    val id: String,            // pid (PG) / processlist id (MySQL)
    val user: String?, val database: String?, val client: String?, val application: String?,
    val state: SessionState,   // Active, Idle, IdleInTransaction, Waiting, Other
    val waitEvent: String?,
    val query: String?, val queryStart: Instant?, val transactionStart: Instant?,
    val blockedBy: List<String>,
    val own: Boolean,          // session của DataGrok
)

interface ActivityReader {
    suspend fun sessions(): List<SessionActivity>
    suspend fun cancel(id: String)     // chỉ hủy query đang chạy
    suspend fun terminate(id: String)  // ngắt kết nối
    suspend fun missingPrivileges(): List<String>
}
```

| | PostgreSQL | MySQL / MariaDB |
| --- | --- | --- |
| Session | `pg_stat_activity` (bỏ `backend_type <> 'client backend'`) | `information_schema.PROCESSLIST` |
| Blocking | `pg_blocking_pids(pid)` | MySQL 8: `performance_schema.data_lock_waits`; MariaDB / 5.7: `information_schema.INNODB_LOCK_WAITS` |
| Cancel / terminate | `pg_cancel_backend` / `pg_terminate_backend` | `KILL QUERY id` / `KILL id` |
| Quyền thiếu | không thấy query người khác nếu thiếu `pg_read_all_stats`; kill cần `pg_signal_backend` | `PROCESS`; kill người khác cần `CONNECTION_ADMIN` / `SUPER` |
| Nhận diện DataGrok | `application_name = 'DataGrok'` | `connection_id()` của các connection DataGrok |

Monitor dùng **một connection riêng**, không lấy từ pool console, để vẫn chạy được khi pool đã kẹt. Nếu thiếu quyền,
hiện banner nói rõ quyền nào và hệ quả (ví dụ: "Chỉ thấy session của user `app` — cần `pg_read_all_stats`").

### 1.4 An toàn

- Kết nối **read-only**: nút Cancel/Terminate disable, tooltip giải thích.
- Kết nối **Production** hoặc bật confirm writes: hỏi xác nhận, ghi rõ pid, user, câu SQL.
- Terminate session đang *trong transaction*: cảnh báo thêm "transaction sẽ bị rollback".
- Không cho kill session của chính tab Activity.

## 2. Công cụ ngoài (pg_dump, pg_restore, mysqldump…)

### 2.1 Nguồn và cách tải

| Công cụ | Nguồn | Ghi chú |
| --- | --- | --- |
| `pg_dump`, `pg_restore`, `psql` | Binary chính thức của PostgreSQL (EDB zip cho macOS/Windows; Linux: gói của distro) | Tải về `~/.datagrok/tools/postgresql/<version>/`, có SHA-256 pin giống `drivers.lock.json` |
| `mysqldump`, `mysql` | **Không tải**. Dùng bản người dùng đã cài | Binary MySQL là GPL; hướng dẫn cài (Homebrew, apt, installer) thay vì phân phối |
| `mariadb-dump` | Tương tự MySQL | |

Tìm công cụ có sẵn trước khi đề nghị tải: `PATH`, `/opt/homebrew/opt/libpq/bin`, `/opt/homebrew/bin`,
`/Applications/Postgres.app/Contents/Versions/*/bin`, `C:\Program Files\PostgreSQL\*\bin`, `C:\Program Files\MySQL\*\bin`.

### 2.2 Luồng xác nhận

Khi người dùng mở Backup mà chưa có công cụ phù hợp, dialog hiện:

- Cần gì: `pg_dump` / `pg_restore` 17.x (vì server là 17.2; công cụ phải cùng hoặc mới hơn server).
- Nguồn: URL đầy đủ, nhà phát hành, license.
- Dung lượng tải và dung lượng sau giải nén, thư mục cài.
- Nút: **Tải về** · **Dùng bản đã cài…** (chọn file) · **Hủy**.

Không có tải ngầm. `autoDownloadDrivers` **không** áp dụng cho công cụ.

### 2.3 Settings → Tools

Trang mới, cạnh trang Drivers, dùng lại phong cách `DriversPanel`:

- Danh sách công cụ: phiên bản, nguồn (Đã tải / Hệ thống / Người dùng chọn), đường dẫn, dung lượng.
- Hành động: Cập nhật, Gỡ, Gỡ phiên bản cũ, Mở thư mục, Đổi đường dẫn, Kiểm tra (chạy `--version`).
- Tổng dung lượng `~/.datagrok/tools/`.

## 3. Backup và restore

### 3.1 Backup

Mở từ: chuột phải kết nối/database/schema → **Backup…**.

- Định dạng PG: Custom `-Fc` (mặc định), Plain SQL, Directory `-Fd -j N`.
- Phạm vi: toàn DB · chọn schema · chọn bảng; schema only / data only.
- Tùy chọn PG: `--no-owner`, `--no-privileges`, `--clean --if-exists`, nén.
- MySQL: mặc định `--single-transaction --routines --triggers --events`, tùy chọn `--no-data`, chọn bảng.
- File đích mặc định `~/Backups/<connection>/<db>_<yyyyMMdd-HHmm>.dump`.
- Panel "Lệnh tương đương" (mật khẩu thay bằng biến), có nút Copy.

### 3.2 Restore

- Chọn file; định dạng custom/directory thì đọc `pg_restore --list` để tick chọn object.
- Mặc định đề xuất **database mới** (`<db>_restore_<date>`), không ghi đè.
- Production: bắt gõ lại tên database. Read-only: từ chối.
- Plain SQL: chạy bằng `psql` / `mysql` với `ON_ERROR_STOP`.

### 3.3 Thực thi

- Mật khẩu: PG qua `PGPASSWORD` trong env của process con; MySQL qua `--defaults-extra-file` tạm quyền 0600, xóa
  trong `finally`. Không bao giờ để mật khẩu trên dòng lệnh.
- SSH: mở tunnel như kết nối thường, trỏ công cụ vào `127.0.0.1:<port>`.
- Tiến trình: đọc stderr `--verbose`, hiện object đang xử lý. Cancel = kill process, xóa file dở.
- Log mỗi lần chạy vào `~/.datagrok/logs/backup/`.
- Kiểm tra phiên bản trước khi chạy (`ServerIdentity` so với `--version`).
- Engine không có công cụ ngoài: SQLite dùng `VACUUM INTO`, DuckDB dùng `EXPORT DATABASE` (không cần tải gì).
  Engine khác: menu disable kèm lý do.

### 3.4 Backup định kỳ (tắt mặc định)

- Bật trong Settings → Backups → Lịch. Mỗi lịch: kết nối, phạm vi, định dạng, thư mục, tần suất, giữ lại N bản.
- Chỉ bật được khi đủ điều kiện: công cụ có sẵn, mật khẩu trong keychain, thư mục ghi được. Thiếu gì thì nói rõ.
- Bản đầu chỉ chạy **khi app đang mở**; nếu lỡ lịch thì chạy bù lúc mở app (có thông báo). Chạy nền khi app đóng
  (launchd / Task Scheduler / systemd timer) để bản sau, cũng phải người dùng bật.
- Kết nối Production: lịch chạy được nhưng hiện cảnh báo tải lên server khi tạo lịch.

## 4. Thứ tự triển khai

1. ✅ `ActivityReader` cho PostgreSQL + tab Activity + cancel/terminate + an toàn. Test với `-Pit.postgres`.
2. ✅ MySQL/MariaDB reader. Chip "blocked" trên status bar.
3. ✅ `ToolLibrary` (tìm, xác minh, tải có xác nhận) + Settings → Tools.
4. ✅ Backup/restore PostgreSQL, rồi MySQL. SQLite/DuckDB.
5. ✅ Backup định kỳ.

## 5. Khác với thiết kế khi triển khai

- Chip "blocked" chỉ chạy cho kết nối đang mở tab Activity (refresh nền mỗi 15 s); chưa có tùy chọn Settings để theo dõi
  mọi kết nối.
- Engine "chưa hỗ trợ" vẫn hiện mục menu (Compose không disable được từng mục context menu); tab/dialog mở ra giải thích lý do.
- Gói tải PostgreSQL là EDB 18.6-2 (macOS universal, Windows x64). Bản Windows mới chỉ được kiểm bằng hash và cấu trúc zip,
  chưa chạy thử trên Windows. Linux: không có gói chính thức dạng zip, hướng dẫn cài từ distro.
- Restore cho SQLite/DuckDB không có: bản backup là một database riêng, mở bằng kết nối mới.
- Mục EXPLAIN trong chi tiết session chưa làm.
