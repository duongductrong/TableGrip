# Rust UI kit: kế hoạch theo phase

Bước đầu tiên của việc viết lại TableGrip bằng Rust: dựng một bộ component UI riêng, thay cho phần Jewel mà app đang
dùng. Bộ này phải giữ nguyên giao diện "Islands" hiện tại và hành xử giống bản Kotlin.

**Ngoài phạm vi:** các màn hình nghiệp vụ (data grid, SQL editor, explorer, agent…) và phần core DB. Chúng sẽ đến sau,
khi bộ UI kit đã đạt parity.

## Nguyên tắc "không copy"

- Không đọc rồi chuyển ngữ mã nguồn Jewel hay IntelliJ Platform sang Rust.
- Spec chỉ được rút ra từ ba nguồn:
  1. app TableGrip đang chạy: render offscreen, đo kích thước, sample màu;
  2. hành vi quan sát được qua test (xem "Conformance" bên dưới);
  3. mã của chính TableGrip, ví dụ `ColorTheme`, `ThemedControls`, `UiZoom`, `Shortcuts` và các file theme JSON.
- Màu mặc định của Graphite (những key mà theme file bỏ trống để Jewel tự lấp) được **đo từ ảnh render**, không chép từ
  bảng màu Int UI.
- Icon: dùng lại SVG của IntelliJ theo Apache‑2.0, có ghi nguồn; xem mục "Quyết định đã chốt".

## Component cần làm

Thứ tự ưu tiên dựa trên số file trong `app/src/main` đang import từng component:

| Nhóm | Component (số file dùng) |
|---|---|
| Nền tảng | `JewelTheme` (64), `Text` (61), `Icon` / `AllIconsKeys` (45), `Divider` (30), `Link` (26), Island |
| Điều khiển cơ bản | `OutlinedButton` (32), `DefaultButton` (31), `Tooltip` (30), `IconButton` (18), `CircularProgressIndicator` (12+2), `Checkbox` / `CheckboxRow` / `TriStateCheckbox` (22), `RadioButtonRow` (3), inline banner (7) |
| Nhập liệu | `TextField` (28), `TextArea` (8), `Outline` (lỗi/cảnh báo) |
| Popup và danh sách | `ListComboBox` (11), `PopupMenu` (9), simple list item, `VerticalScrollbar` / `HorizontalScrollbar` (10), `VerticallyScrollableContainer` |
| Bố cục | `TabStrip` (4) + kiểu editor tab, `HorizontalSplitLayout` / `VerticalSplitLayout` (7), lazy `Tree` (2 + style ở 10 file) |
| Khung cửa sổ | `DecoratedWindow` / `TitleBar` / fullscreen controls, `AppDialog`, `DialogTitleBar`, `ToolStripe`, `StatusBar`, UI zoom, keymap |

## Chọn stack: GPUI (chốt sau spike ở Phase 0)

| | GPUI (của Zed) | Slint | Tauri + web |
|---|---|---|---|
| Danh sách cực lớn, editor | Zed được xây trên chính nó | Khó (text editing còn hạn chế) | Được, nhưng UI không phải Rust |
| Hệ thống phím tắt / action | Có sẵn, gần với `Shortcuts.kt` | Tự làm | Tự làm |
| Test hành vi headless | `TestAppContext`, giả lập phím và chuột | Có testing backend | Playwright |
| Snapshot pixel | Không có sẵn | Software renderer, rất tốt | Có |
| Rủi ro | API còn thay đổi | Text editing, grid | WebView khác nhau theo OS |

**Đề xuất GPUI**, vì phần khó nhất về sau (grid và SQL editor) chính là thế mạnh của nó.

Hai cách giảm rủi ro:
- Ghim chặt phiên bản GPUI.
- Đặt toàn bộ logic component vào một crate không phụ thuộc framework (xem Kiến trúc). Nếu phải đổi framework, chỉ phải
  viết lại phần vẽ.

**Phương án dự phòng:** Slint, nếu spike cho thấy GPUI không đạt trên Windows/Linux hoặc không dựng được title bar tùy
biến.

## Kiến trúc

```
ui-kit/                     cargo workspace, nằm trong repo này
  crates/tg-theme/          đọc thẳng app/src/main/resources/themes/*.json + overrides + accent → token
  crates/tg-ui-logic/       state machine từng component, thuần Rust, không phụ thuộc GPUI
  crates/tg-ui/             phần vẽ bằng GPUI, mỏng, gọi tg-ui-logic
  crates/tg-gallery/        app hiển thị mọi component × mọi trạng thái, dùng để chụp ảnh so sánh
  conformance/
    scenarios/*.json        kịch bản hành vi, dùng chung cho Kotlin và Rust
    golden/<theme>.json     token và kích thước đo từ bản Kotlin
    reference/<theme>/*.png ảnh render của bản Kotlin
```

- **File theme dùng chung, không nhân bản.** Hai bản sẽ luôn đọc cùng một nguồn màu.
- **`tg-ui-logic` chứa mọi quyết định hành vi:** focus, hover, pressed, disabled, phím, selection, mở/đóng popup, kẹp
  tỉ lệ split, mở/đóng node tree, thứ tự tab. Nhờ vậy có thể unit test 100%, nhanh và tất định.

## Đảm bảo "hành xử như hiện tại": test ba lớp

### 1. Conformance: bản Kotlin là chuẩn đối chiếu

Mỗi kịch bản mô tả thao tác và kết quả mong đợi. Cùng một file chạy trên hai runner:
- **Runner Kotlin:** dùng `runComposeUiTest`, hạ tầng đã có trong `app/src/test`, chạy trên component Jewel thật.
- **Runner Rust:** chạy trên `tg-ui-logic`, và trên `tg-ui` qua `TestAppContext`.

**Luật:** một kịch bản chỉ được đưa vào bộ test sau khi đã pass trên bản Kotlin. Vì vậy mọi kỳ vọng đều là hành vi đã
quan sát được, không phải hành vi mình đoán.

Kịch bản viết bằng JSON để cả hai bên đọc được mà không cần thêm thư viện (Kotlin dùng kotlinx-serialization sẵn có,
Rust dùng serde_json):

```json
{
  "component": "checkbox",
  "cases": [
    { "name": "Space toggles the focused box", "setup": { "label": "Read-only", "checked": false },
      "steps": ["focus", { "key": "Space" }], "expect": { "checked": true, "focused": true } },
    { "name": "a disabled box ignores clicks", "setup": { "checked": false, "enabled": false },
      "steps": ["click"], "expect": { "checked": false } }
  ]
}
```

Cả hai runner đều có một test tự kiểm tra: đưa vào các kỳ vọng cố tình sai và bắt buộc phải fail, để runner không thể
"pass rỗng".

### 2. Golden cho token và kích thước

Một test Kotlin ghi ra `golden/<theme>.json`, gồm:
- màu của từng component × trạng thái, sample từ vùng phẳng (nền, viền, focus ring, error outline) của ảnh render ở
  density 2. Một cơ chế duy nhất cho mọi theme, kể cả các key theme bỏ trống;
- chiều cao, padding, bo góc, cỡ chữ, đo bằng semantics bounds.

Test Rust so khớp chính xác các giá trị này.

### 3. So sánh ảnh

- Gallery của bản Kotlin được render offscreen, giống cách `ThemeGallerySnapshotTest` đang làm, và lưu thành ảnh chuẩn.
- Gallery của bản Rust được chụp lại rồi so với ảnh chuẩn: ngưỡng ΔE cho mỗi pixel và tỉ lệ pixel lệch tối đa.
- Kết quả xuất ra một trang HTML đặt hai ảnh cạnh nhau để duyệt bằng mắt.
- Khác biệt không tránh được, như cách rasterize font của Skia so với CoreText, được ghi vào danh sách đã duyệt kèm lý
  do.

**Theme kiểm tra mỗi phase:** Graphite Dark, Graphite Light, One Dark. Chạy đủ 6 theme ở cuối Phase 1 và Phase 6.

## Các phase

Mỗi phase là một mốc. Trong mỗi phase, mỗi PR chỉ chứa một component hoặc một nhóm nhỏ (khoảng 600 dòng trở xuống),
kèm kịch bản và test của nó. Phase sau chỉ bắt đầu khi phase trước đạt tiêu chí hoàn thành. Bản Kotlin chỉ được thêm
code nằm trong `src/test`.

### Phase 0: Baseline và spike (1–2 tuần)

**Việc cần làm:**
- Lập danh mục cách app dùng từng component: biến thể nào, trạng thái nào, ở màn hình nào.
- Phía Kotlin, toàn bộ trong `src/test`:
  - một màn gallery;
  - runner đọc kịch bản JSON;
  - test ghi golden;
  - ảnh chuẩn cho 3 theme.
- Spike GPUI:
  - cửa sổ có title bar tùy biến;
  - chữ bằng font hệ thống;
  - một button có hover và focus ring;
  - một action gắn phím tắt;
  - một test headless.
- CI build spike trên macOS, Windows và Linux.

**Hoàn thành khi:**
- Có quyết định GPUI hoặc Slint, kèm bằng chứng.
- Runner Kotlin chạy được ít nhất 10 kịch bản mẫu.

### Phase 1: Nền tảng (khoảng 2 tuần)

**Việc cần làm:**
- `tg-theme` đọc cả 6 file theme có sẵn, theme người dùng, `themeOverrides` và `accentColor`. Kết quả phải khớp
  `ThemeRegistry` trên cùng bộ fixture.
- Typography: cỡ chữ UI và font editor lấy từ settings.
- UI zoom.
- Island, Divider, Text, Link, hệ thống icon.
- Gallery v1.

**Hoàn thành khi:** golden khớp cho cả 6 theme.

### Phase 2: Điều khiển cơ bản (2–3 tuần)

**Việc cần làm:**
- Default, Outlined và Icon button.
- Checkbox, TriState và CheckboxRow; RadioButtonRow.
- Tooltip (độ trễ hiện/ẩn, vị trí).
- Progress indicator, inline banner.
- Cơ chế focus ring và Tab/Shift+Tab dùng chung cho mọi component.

### Phase 3: Nhập liệu (3–4 tuần, tách riêng vì khó nhất)

**Việc cần làm:**
- TextField và TextArea: caret, chọn vùng, di chuyển theo từ (Option trên macOS, Ctrl trên Windows/Linux), clipboard,
  undo/redo.
- Outline báo lỗi và cảnh báo, placeholder.
- **Bộ gõ IME, gồm gõ tiếng Việt Telex/VNI có chuỗi composition, là tiêu chí bắt buộc.**

### Phase 4: Popup và danh sách (2–3 tuần)

**Việc cần làm:**
- PopupMenu: menu con, điều hướng bằng phím, đóng khi click ra ngoài hoặc nhấn Esc.
- ListComboBox.
- Simple list item với selection active và inactive.
- Scrollbar: kiểu overlay so với luôn hiện, hành vi khi click vào track.
- Scrollable container.

### Phase 5: Bố cục (3–4 tuần)

**Việc cần làm:**
- Tab strip và editor tab: đóng tab, cuộn khi tràn, kéo để đổi thứ tự (đối chiếu `WorkspaceTabDragTest`).
- Split layout: kích thước tối thiểu, lưu tỉ lệ.
- Lazy tree: mở/đóng bằng phím mũi tên, selection, ảo hóa ổn định với 10.000 node.

### Phase 6: Khung cửa sổ (2–3 tuần)

**Việc cần làm:**
- Decorated window và title bar theo từng OS: traffic lights và fullscreen trên macOS, nút caption trên Windows,
  title bar trên Linux.
- AppDialog và DialogTitleBar.
- ToolStripe và khung StatusBar.
- Keymap theo `Shortcuts.kt`.

**Hoàn thành khi:**
- Gallery dựng được bộ khung rỗng của `MainScreen` và khớp ảnh chuẩn trên cả 6 theme.
- CI xanh trên cả 3 OS.

**Tổng thời gian ước tính:** khoảng 4–5 tháng cho một người.

**Tiêu chí hoàn thành chung cho mọi phase:**
- Mọi kịch bản của các component trong phase pass trên cả Kotlin và Rust.
- Golden khớp.
- Ảnh so sánh nằm trong ngưỡng, hoặc khác biệt đã được duyệt.

## Quyết định đã chốt

1. **Stack: GPUI 0.2.2.** Spike ở Phase 0 đạt trên macOS (xem [phase-0.md](rust-ui-kit/phase-0.md)). Chốt hẳn khi CI
   Rust xanh trên Linux và Windows.
2. **Icon: dùng lại SVG của IntelliJ theo Apache‑2.0, có ghi nguồn.** Bằng chứng ở
   [icon-licence.md](rust-ui-kit/icon-licence.md): 3.505/3.662 file SVG trong artifact
   `com.jetbrains.intellij.platform:icons` tự ghi Apache‑2.0 ngay trong file, và JetBrains đã xác nhận công khai.
   POM thiếu `<licenses>` chỉ là thiếu metadata. Điều kiện khi dùng:
   - Chỉ lấy file **có header Apache‑2.0**, giữ nguyên comment header trong từng SVG.
   - Lấy từ đúng artifact đã ghim phiên bản, bằng script, kèm danh sách file và phiên bản nguồn.
   - Kèm bản Apache License 2.0 và thêm vào `NOTICE`: "This software includes code from IntelliJ IDEA, Copyright (C)
     JetBrains s.r.o." (theo `NOTICE.txt` của intellij-community). Ghi rõ nếu có chỉnh sửa file (Apache‑2.0 mục 4b).
   - **Không** dùng file không có header Apache (ví dụ `toolWindowAskAI`, app đang dùng ở 3 chỗ: tự vẽ thay thế) và
     **không** dùng logo hay ảnh sản phẩm JetBrains: Apache‑2.0 không cấp quyền nhãn hiệu (mục 6).
3. **Vị trí:** `ui-kit/` trong repo này, dùng chung file theme và kịch bản.
4. **Toolchain:** ghim Rust 1.97.1 (`ui-kit/rust-toolchain.toml`), `rust-version = "1.97"`.
