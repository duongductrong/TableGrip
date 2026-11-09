# Phase 3 render: TextField và TextArea, báo cáo

Kế hoạch: [execution.md](execution.md) (brief B3). Logic: crate `tg-text` (Phase 3L).

## Đã làm

| Phần | Ở đâu | Kết quả |
|---|---|---|
| Component | `ui-kit/crates/tg-ui/src/text_input/` | `TextInput::field` / `TextInput::area` là một view GPUI bọc `tg_text::Editor`: fill, viền 1dp, vòng focus và outline lỗi 2dp (mép ngoài cách bounds 1dp), chữ hoặc placeholder, selection, marked text gạch chân, caret 2dp nhấp nháy 500/500 ms khi có focus. Field cuộn ngang theo caret; area tự xuống dòng và cuộn dọc. |
| Phím | `on_key_down` → `Editor::handle_key` | Phím editor không dùng (`KeyResult::Bubble`) được để lan lên cha. Up/Down trong TextArea (không kèm ⌘/⌥) tính theo pixel từ `WrappedLine` của GPUI và giữ x đích qua các dòng; ⌘/⌥+Up/Down vẫn đi qua `tg-text`. |
| IME | `impl EntityInputHandler for TextInput` | `replace_and_mark_text_in_range`, `replace_text_in_range`, `unmark_text`, `marked_text_range`, `bounds_for_range` (vị trí cửa sổ gợi ý), `character_index_for_point` nối thẳng vào mô hình composition của `tg-text`; đổi UTF‑16 ↔ byte, kể cả vùng chọn bên trong marked text. |
| Clipboard | `SystemClipboard` (trait `tg_text::Clipboard`) | Ô mật khẩu chép và cắt ra dấu `•`, như bản Kotlin. |
| Gallery | `TextFieldSpecimen.kt`, `tg-gallery/src/specimens/text_field.rs` | Field thường, placeholder, disabled, lỗi, mật khẩu, focus có chọn hết (không có caret, để ảnh không phụ thuộc nhịp nháy), TextArea hai dòng. Ảnh chuẩn cho cả 12 theme. Phần trong mỗi field là vùng `text:`. |
| Test headless | `ui-kit/crates/tg-ui/tests/text_input.rs` | 13 test: gõ và phím sửa, phím lan lên cha, Tab/Enter trong area, chuỗi Telex `tiếng` qua input handler (một bước undo), marked text có vùng chọn bên trong, clipboard mật khẩu, copy/paste, cuộn ngang, Up/Down theo pixel (cả dòng bị wrap), click và double-click, caret nhấp nháy, field disabled. |

## Kết quả so ảnh

`SPECIMEN=text-field ui-kit/scripts/imgdiff.sh --all`: **12/12 theme đạt**, 7/7 vùng chữ mỗi theme. Lệch lớn nhất 0,34 % pixel (Contrast), chỉ ở góc bo đã khử răng cưa; các theme khác dưới 0,12 %. Hộp mực chữ và selection lệch không quá 1 px.

## Số đo lấy từ ảnh Kotlin

- Field cao 28dp; chữ cách mép trái 9dp và đỉnh 6,5dp (hộp dòng đã cắt leading, 15,5dp). TextArea: 6dp ngang, 5dp dọc tính cả viền.
- Bo góc 4dp. Vòng focus/lỗi dày 2dp, đè lên viền và nhô ra ngoài 1dp.
- Màu selection là màu mặc định của Compose: `#4286F4` alpha 0,4, giống nhau ở mọi theme.
- Theme có `controls`: fill `input.background`, viền `input.border`, viền focus `focus`, placeholder `text.muted`, chữ disabled `text.disabled`. Graphite (Int UI): fill `#2B2D30`/`#FFFFFF`, viền `#4E5157`/`#C9CCD6` (disabled `#4E5157`/`#DFE1E5`), placeholder và chữ disabled `#6F737A`/`#A8ADBD`. Field disabled không có fill.

## Phát hiện

1. **Thứ tự phím của GPUI trên macOS:** phím in được (có `key_char`, không có ⌘/Ctrl) đi qua `on_key_down` của view trước, chỉ khi không ai xử lý mới tới IME. Vì thế field không được `stop_propagation` các phím đó, và cha sẽ thấy key-down của chữ đang gõ trước khi chữ vào field. Bản Kotlin thì field nuốt luôn. Cha nào gắn handler cho phím chữ không kèm modifier phải tự bỏ qua khi field đang focus.
2. **GPUI tự focus phần tử có `track_focus` khi nhấn chuột**, nên field disabled không được gắn `track_focus`.
3. Field một dòng vẫn giữ ký tự xuống dòng khi dán (theo `tg-text`). Lúc vẽ, chúng hiện thành dấu cách; độ dài byte giữ nguyên nên offset không đổi.

## Kiểm tay bằng Telex trên macOS

**Chưa làm.** Máy chạy session chỉ bật ABC, tiếng Hàn và Romaji tiếng Nhật. Bật bộ gõ tiếng Việt là đổi cài đặt hệ thống nên session không tự làm. Cách kiểm:

```bash
cd ui-kit && cargo run -p tg-gallery -- --theme "One Dark" --specimen text-field
```

Bật Vietnamese → Telex trong System Settings › Keyboard › Input Sources, click vào field đầu, gõ `tieengs Vieetj`. Kết quả đúng: hiện `tiếng Việt`, từ đang gõ có gạch chân, ⌘Z xóa cả từ một lần.

## Việc chưa làm

- Kiểm tay Telex (xem trên).
- Chưa có kịch bản conformance mới: hành vi phím đã nằm trong `text-field.json`/`text-area.json` của Phase 3L. Runner Kotlin không giả lập được IME nên IME chỉ kiểm trong Rust.
- Chưa có menu chuột phải (cut/copy/paste), chưa có icon trong field (`leadingIcon`/`trailingIcon`), chưa có thanh cuộn cho TextArea.
- Chưa vẽ trạng thái hover và pressed. Với theme có `controls`, `ThemedControls` đặt chúng cùng màu với lúc nghỉ; với Graphite (Int UI) chưa đo.
