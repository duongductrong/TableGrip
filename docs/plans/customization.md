# Customization — đề xuất

> **Trạng thái (2026-10-31): đã triển khai cả 4 đợt.** Các lựa chọn đã chốt: `Settings` giữ cấu trúc phẳng (không cần
> migration); keymap có preset Datagrok / DataGrip / VS Code. Những mục **chưa làm**, vì đụng vào phần lõi của editor
> hoặc grid: word wrap (editor dựa vào việc không soft-wrap), dấu phẩy đầu dòng khi format, UI density (khoảng cách
> island), tắt hỏi khi đóng tab chưa lưu (để tránh mất dữ liệu). Cũng chưa làm kéo-thả nút trên tool stripe; thay vào
> đó có nút lên/xuống trong Settings và menu chuột phải "Hide".

Mục tiêu: người dùng tự điều chỉnh app theo thói quen cá nhân. **Mọi default giữ nguyên như hiện tại**: một
`settings.json` cũ (hoặc không có) phải cho ra đúng giao diện và hành vi của bây giờ.

## Hiện trạng

`Settings` (core) có 18 field, Settings UI có 7 trang. Phần cá nhân hóa thực sự chỉ có **Theme** (System/Light/Dark) và
**Editor font size**. Còn lại là hard-code:

| Chỗ | Giá trị cứng | File |
| --- | --- | --- |
| Shortcuts | ~60 phím trong `object Shortcuts` | `ui/Shortcuts.kt` |
| Màu accent, frame, island | `DarkIslands` / `LightIslands` | `ui/DatagrokTheme.kt` |
| Màu syntax SQL | 2 bảng màu cố định | `console/SqlEditor.kt:603` |
| Màu environment (DEV/STG/PROD) | 4 màu cố định | `ui/DatagrokTheme.kt:150` |
| Indent | 4 dấu cách (editor + formatter) | `SqlEditor.kt:342`, `SqlFormatter.kt:8` |
| Keyword case khi format | luôn UPPERCASE | `SqlFormatter.kt:46` |
| Auto-pair ngoặc/quote | luôn bật | `SqlEditor.kt:128` |
| Font editor / UI | font mặc định của Jewel | `DatagrokTheme.kt` |
| Grid row height | 26dp | `grid/GridGeometry.kt:7` |
| Hiển thị NULL | `<null>` | `grid/DataGrid.kt:464` |
| Định dạng ngày giờ | `yyyy-MM-dd HH:mm:ss.SSS` | `grid/ValueFormats.kt:211` |
| Kích thước cửa sổ, tỉ lệ split | 1360×860, 0.24 / 0.68, không nhớ | `Main.kt:86`, `MainScreen.kt` |
| Tool window mở lúc khởi động | luôn Explorer | `AppState.kt:65` |

## Nguyên tắc

1. **Default = hiện tại.** Mỗi field mới có default đúng bằng giá trị cứng đang dùng; `ignoreUnknownKeys` đã có sẵn nên
   file cũ/mới đọc qua lại được.
2. **Khai báo một lần.** Tiếp tục mô hình `SettingsPages` (field khai báo → dialog tự render). Thêm các kiểu field còn
   thiếu: `BooleanField` (checkbox), `ColorField` (ô màu + hex), `FontField` (combo font hệ thống).
3. **Reset được.** Mỗi trang có "Restore defaults"; field khác default hiện chấm "modified" như JetBrains.
4. **Core giữ UI-free.** Màu lưu dạng hex string, phím lưu dạng chuỗi (`"meta+shift+E"`), app tự parse.

## Đề xuất theo đợt

### Đợt 1 — Editor & Grid (ít rủi ro, giá trị cao nhất)

**Settings › SQL Editor**

| Setting | Default | Tùy chọn |
| --- | --- | --- |
| Font family | font hiện tại | combo các font monospace trên máy (JetBrains Mono, Fira Code, Menlo…) |
| Font size | 13 | (đã có) |
| Line height | 1.0× | 1.0–2.0 |
| Tab size | 4 | 2 / 4 / 8 |
| Indent bằng | Spaces | Spaces / Tab |
| Auto-close brackets & quotes | bật | bật / tắt |
| Completion tự bật khi gõ | bật | bật / chỉ khi ⌃Space |
| Highlight dòng hiện tại | bật | bật / tắt |
| Word wrap | tắt | bật / tắt |

**Settings › SQL Formatter** (trang mới)

| Setting | Default | Tùy chọn |
| --- | --- | --- |
| Keyword case | UPPER | UPPER / lower / giữ nguyên |
| Indent | theo Tab size của editor | |
| Comma | cuối dòng | cuối dòng / đầu dòng |

**Settings › Data Editor**

| Setting | Default | Tùy chọn |
| --- | --- | --- |
| Row density | Comfortable (26dp) | Compact (22) / Comfortable (26) / Spacious (32) |
| Hiển thị NULL | `<null>` | text tùy ý (`NULL`, `∅`, rỗng…) |
| Định dạng ngày giờ | `yyyy-MM-dd HH:mm:ss.SSS` | preset ISO / bỏ milliseconds / pattern tùy chỉnh (có preview) |
| Múi giờ hiển thị | như hiện tại | UTC / Local / theo server |
| Số: dấu phân cách hàng nghìn | tắt | bật / tắt |
| Màu xen kẽ dòng (zebra) | tắt | bật / tắt |
| Page size | 200 | (đã có) |

### Đợt 2 — Giao diện

**Settings › Appearance**

| Setting | Default | Tùy chọn |
| --- | --- | --- |
| Theme | Sync with OS | (đã có) |
| Accent color | `#3574F0` | 8 preset + màu tùy chỉnh |
| UI font size | mặc định Jewel | 11–16 |
| UI density | Default | Compact / Default (khoảng cách island, stripe) |
| Bo góc island | 10dp | 0 / 6 / 10 |

**Settings › Color Scheme** (trang mới)

- Bảng màu syntax SQL cho Light và Dark riêng: keyword, string, number, comment, quoted identifier, parameter.
- Vài scheme có sẵn để chọn nhanh: **Datagrok (default)**, Darcula, GitHub, Solarized, Monokai.
- Màu environment: LOCAL / DEV / STG / PROD (một số team dùng màu khác cho PROD).

### Đợt 3 — Phím tắt

**Settings › Keymap** (trang mới)

- Bảng toàn bộ action (lấy từ `Shortcuts.groups`, đã có sẵn mô tả), tìm kiếm theo tên hoặc theo phím.
- Click vào một dòng → bấm tổ hợp mới để gán; báo **xung đột** khi phím đã dùng trong cùng ngữ cảnh.
- Preset: **Datagrok (default)**, JetBrains/DataGrip, VS Code, TablePlus.
- Chỉ lưu phần người dùng ghi đè (`keymapOverrides: Map<actionId, String>`), nên đổi default sau này vẫn tới được người
  dùng chưa chỉnh.
- Kỹ thuật: `Shortcuts` từ `object` các `val` → registry tra theo id, đọc qua `CompositionLocal`; tooltip và dialog
  Shortcuts tự cập nhật vì đã cùng nguồn.

### Đợt 4 — Bố cục & khởi động

**Settings › Behavior** (trang mới)

| Setting | Default | Tùy chọn |
| --- | --- | --- |
| Nhớ kích thước/vị trí cửa sổ | bật (mới) | bật / tắt |
| Nhớ độ rộng sidebar và panel Agent | bật (mới) | bật / tắt |
| Tool window khi mở app | Connections | Connections / History / Saved Queries / ẩn |
| Panel Agent khi mở app | đóng | mở / đóng / như lần trước |
| Khôi phục tab phiên trước | bật | (hiện luôn bật) bật / tắt |
| Xác nhận khi đóng tab console chưa lưu | như hiện tại | bật / tắt |
| Status bar | breadcrumbs + thống kê | bật/tắt từng phần |

Cùng đợt: **ẩn/hiện và sắp xếp nút trên tool stripe** (chuột phải → Hide; kéo để đổi thứ tự).

### Ngoài phạm vi (đề xuất để sau)

- Import/Export settings ra file (chia sẻ cấu hình trong team) — dễ làm khi đã có schema ổn định.
- Settings theo từng connection (page size, timeout riêng) — `ConnectionConfig` đã có một phần (`confirmWrites`, AI access).
- Plugin/theme bên ngoài dạng file `.json`.

## Thay đổi kỹ thuật chung

- `core/settings/Settings.kt`: thêm field mới, nhóm lại thành sub-object (`editor`, `grid`, `appearance`, `keymap`,
  `layout`) **với migration** từ field phẳng cũ (`editorFontSize`, `pageSize`…) để file cũ vẫn đọc đúng. Hoặc giữ phẳng
  cho đơn giản — cần chốt.
- `settings/SettingsForm.kt`: thêm `BooleanField`, `ColorField`, `FontField`, "Restore defaults", dấu modified.
- `ui/DatagrokTheme.kt`: `IslandsPalette`, syntax colors và font lấy từ settings thay vì hằng số.
- Test: mỗi setting có test "default = giá trị cũ" và round-trip JSON; test parse/format chuỗi phím.

## Cần anh/chị chốt

1. Thứ tự đợt: 1 → 2 → 3 → 4 như trên, hay ưu tiên Keymap sớm hơn?
2. `Settings` giữ cấu trúc phẳng hay tách nhóm (kèm migration)?
3. Có cần preset keymap cho DataGrip / VS Code / TablePlus ngay ở đợt 3, hay chỉ cho tự gán trước?
4. Các mục nhỏ nào muốn bỏ / thêm (ví dụ zebra rows, comma đầu dòng, bo góc island)?
