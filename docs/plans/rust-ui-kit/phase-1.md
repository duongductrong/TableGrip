# Phase 1: nền tảng UI, báo cáo

Kế hoạch: [execution.md](execution.md) (brief A1). Phase trước: [phase-0.md](phase-0.md).

## Đã làm

| Phần | Ở đâu | Kết quả |
|---|---|---|
| Theme | `ui-kit/crates/tg-theme` | Đọc thẳng `app/src/main/resources/themes/*.json` lúc biên dịch (`include_str!`, không chép), theme người dùng (JSON lỏng: comment, dấu phẩy thừa), `themeOverrides`, `accentColor`, cỡ chữ UI, bán kính island, zoom. |
| Golden token | `ThemeTokensGoldenTest.kt` → `ui-kit/conformance/golden/themes/*.json` | 12 biến thể built-in (6 họ theme) + 14 ca fixture (`conformance/fixtures/themes`). **2756/2756 giá trị khớp** trong `cargo test -p tg-theme`. |
| Component | `ui-kit/crates/tg-ui` | Island, Divider, Text (UI/editor, 5 màu, cỡ, đậm, ellipsis), Link (hover, click, focus, disabled), Icon, zoom 13 mức nhân mọi kích thước dp. |
| Hành vi Link | `conformance/scenarios/link.json`, `LinkSubject.kt`, `tg-ui-logic/src/link.rs` | 10 kịch bản, pass cả Kotlin và Rust. |
| Icon | `ui-kit/scripts/extract-icons.py` → `tg-ui/assets/icons/intellij/` | 79/79 key app dùng (157 file sáng/tối), đều có header Apache‑2.0, giữ nguyên byte; `manifest.json` ghi key, đường dẫn nguồn, phiên bản, sha256. Icon agent lấy thẳng từ `app/src/main/resources/icons`. Dòng ghi nguồn JetBrains đã thêm vào `NOTICE`. |
| Gallery | `ui-kit/crates/tg-gallery`, `app/.../conformance/gallery/*Specimen.kt` | Mỗi mẫu một file ở cả hai bên, tự phát hiện (Kotlin: object trong package `gallery`; Rust: `build.rs` quét `src/specimens/`). Mẫu: controls (Phase 0), island, divider, text, link, icon. |
| Golden và ảnh chuẩn | `golden/<theme>.json`, `reference/<theme>/<mẫu>.png` + `.json` | Cả 12 biến thể. Giá trị cũ của 3 theme Phase 0 không đổi; `gallery.png` đổi tên thành `controls.png`. File `.json` cạnh ảnh ghi vùng chữ/icon/bỏ qua lấy từ test tag (`text:`, `icon:`, `skip:`). |
| So ảnh | `ui-kit/crates/tg-imgdiff`, `ui-kit/scripts/imgdiff.sh` | Chụp cửa sổ gallery, chuyển ICC về sRGB (`sips`), cắt đúng mẫu, so ΔE₀₀ từng pixel + ngưỡng % pixel lệch, vùng chữ so theo "mực" (hộp bao ±3 px, màu ΔE ≤ 6), xuất `target/imgdiff/report.html`. |

## Kết quả so ảnh

`ui-kit/scripts/imgdiff.sh --all`: **48/48 mẫu đạt** (4 mẫu nền tảng × 12 theme), thêm `icon` đạt ở 3 theme bắt buộc. Lệch lớn nhất: Link 0,25 % pixel (Contrast), phần còn lại dưới 0,2 %.

Chạy lại:

```bash
ui-kit/scripts/imgdiff.sh
```

```bash
./gradlew :app:test --tests '*GoldenTest*' --tests '*ConformanceTest*'
```

```bash
cd ui-kit && cargo test --workspace
```

## Thêm mẫu ở Wave B/C

1. Kotlin: `app/src/test/kotlin/dev/tablegrip/app/conformance/gallery/XxxSpecimen.kt`, một `object : GallerySpecimen` (tên, kích thước canvas, `Content()`, tùy chọn `prepare()` để hover/focus, `measure()` để ghi golden). Đặt vị trí bằng `Modifier.offset`, gắn tag `text:`/`icon:` cho vùng chữ và icon.
2. Ghi lại: `./gradlew :app:test --tests '*GoldenTest*' -Pconformance.record=true`.
3. Rust: `ui-kit/crates/tg-gallery/src/specimens/xxx.rs` với `pub fn specimen() -> Specimen`, cùng tên, cùng kích thước, cùng tọa độ (`at(cx, x, y)`). Trạng thái hover/focus vẽ bằng một "look" cố định (xem `LinkLook`).
4. `tg-gallery` có test kiểm mỗi mẫu Rust có ảnh chuẩn Kotlin cùng kích thước.

## Phát hiện

1. **`lerp` của Compose phải chép đúng từng bit.** Compose trộn màu trong Oklab qua XYZ D50, dùng `fastCbrt`, lưu giá trị trung gian dạng half float (làm tròn ngang ra xa số 0) và alpha 10 bit. Oklab chuẩn lệch 1 mức ở 3/2756 giá trị. `tg-theme/src/color.rs` giờ chép đúng các bước đó.
2. **Màu Int UI mà Graphite để trống không suy ra được**, nên lấy từ giá trị Kotlin vẽ thật (khối `ui` trong `golden/themes/graphite-*.json`, đối chiếu với màu đo pixel). Chúng là hằng theo sáng/tối, không phụ thuộc màu island.
3. **Compose cắt phần leading của line height** ở dòng đầu và dòng cuối: chữ 13 sp một dòng cao 15,5 dp chứ không phải 17 dp. `tg-ui` làm giống bằng margin âm tính từ ascent/descent thật của font; vòng focus của Link bám theo hộp đã cắt.
4. **Link:** Enter và Space kích hoạt cả khi có modifier (⌘/Ctrl, Shift), khác button; double-click tính 2 lần; link bị disable không nhận focus. Hover gạch chân 1 dp; focus vẽ vòng 2 dp màu `outline.focused` nằm hoàn toàn ngoài hộp chữ, bo khoảng 3,5 dp.
5. **Kotlin vẽ chữ bằng Inter và JetBrains Mono** (đi kèm Jewel). Phải có cùng font thì so ảnh chữ mới khớp.
6. **`serde_json` bật `preserve_order` khi build cả workspace** (do dependency của GPUI), nên thứ tự key JSON khác khi build riêng một crate. Test không được phụ thuộc thứ tự key.

## Lệch khỏi brief

- "6 theme" được hiểu là 6 họ theme, tức cả 12 biến thể. Token, golden, ảnh chuẩn và so ảnh đều làm cho cả 12.
- `GoldenTest.kt` được viết lại quanh cơ chế gallery; số liệu cũ giữ nguyên, chỉ thêm key mới.
- Thêm `link.json` và Link vào `tg-ui-logic`: mỗi file chung chỉ thêm 1 dòng, theo quy tắc.

## Còn mở

- **Font chưa được đóng gói.** `tg-ui` dùng Inter/JetBrains Mono nếu có, nếu không thì dùng font hệ thống. Script so ảnh lấy font từ jar của Jewel trong cache Gradle, đặt vào `target/` (không commit). Cần quyết định có đóng gói hai font OFL này vào app không, kèm file license.
- **Chụp ảnh chỉ chạy trên macOS** (`screencapture`, `sips`, quyền Screen Recording), nên chưa đưa so ảnh vào CI. Công cụ so ảnh tự nó chạy được trên mọi OS.
- **Icon:** màu icon vẽ theo SVG gốc. Việc đổi màu theo bảng màu Int UI (như checkbox của Phase 0) chưa làm; chưa có chỗ nào cần ở Phase 1.
- CI Rust trên Linux/Windows vẫn chưa chạy lần nào (từ Phase 0).
