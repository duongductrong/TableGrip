# Phase 5 render: tab strip, tab workspace, split, lazy tree, báo cáo

Kế hoạch: [execution.md](execution.md) (brief C2). Logic: `tg-ui-logic` (Phase 5L): `tab_strip`, `tab_drag`, `split`,
`lazy_tree`, `virtual_list`.

## Đã làm

| Phần | Ở đâu | Kết quả |
|---|---|---|
| Tab strip | `tg-ui/src/tab_strip.rs` | Tab 40dp, nền trắng ở theme sáng, nhãn tab thường mờ 0,9, đệm 8dp quanh nhãn, gạch chân 3dp bo tròn màu accent dưới tab đang chọn, nền hover phủ cả tab. Click chọn mà không lấy focus; có focus thì Left/Right (vòng), Home/End. Cuộn ngang bằng bánh xe, giữ tab đang chọn trong khung (`tg_ui_logic::TabStrip`). Chuột giữa đóng mọi tab, kể cả tab không có nút đóng. |
| Tab workspace | `tg-ui/src/workspace_tabs.rs` | Tab viên của `WorkspaceView.PillTab`: hàng 42dp, tab 30dp bo 8dp, icon bảng/console, dấu `•` khi chưa lưu, nút đóng chỉ hiện khi chọn hoặc hover (luôn giữ chỗ). Nhấn chọn và bắt đầu kéo; kéo qua giữa tab bên cạnh thì đổi chỗ (`on_move`), giữ ở mép thì hàng tự cuộn mỗi frame và mang tab theo (`TabRow`). Tab đang kéo nổi lên trên (bóng, nền island) và vẽ sau các tab khác. Chuột giữa đóng. |
| Split | `tg-ui/src/split.rs` | Hai pane, divider vô hình, khe 6dp giữa hai island là tay kéo (con trỏ ↔). Kéo trong giới hạn min của hai pane (`tg_ui_logic::Split`), báo tỉ lệ mới qua `on_ratio` để lưu. |
| Lazy tree | `tg-ui/src/tree.rs` | `uniform_list` của GPUI: chỉ dựng dòng trong khung nhìn. Dòng 26dp, nền chọn bo 4dp lùi 6dp hai bên, thụt 16dp mỗi cấp, chevron 16dp, `leaf_inset` như explorer. Màu chọn khác nhau khi có và không có focus. Click / Shift+click / ⌘(Ctrl)+click / double-click, phím mũi tên, Home/End, PageUp/PageDown (theo chiều cao thật), ⌘A; cuộn theo dòng active; scrollbar của Phase 4R. Danh sách dòng phẳng chỉ dựng lại khi mở/đóng node hoặc chủ đưa `items` mới (con lazy về). |
| Gallery | `TabStripSpecimen.kt`, `WorkspaceTabsSpecimen.kt`, `SplitSpecimen.kt`, `LazyTreeSpecimen.kt` và `tg-gallery/src/specimens/{tab_strip,workspace_tabs,split,lazy_tree}.rs` | Ảnh chuẩn cho cả 12 theme. Mẫu Kotlin dùng đúng `TabStrip`/`LazyTree`/`HorizontalSplitLayout` của Jewel với style của app; tab workspace chép `PillTab` (private). |
| Test headless | `tg-ui/tests/layout.rs` | 10 test: click/chuột giữa/phím trên tab strip; chọn, đóng, kéo phải, kéo trái, kéo ngắn, giữ ở mép trên tab workspace (đối chiếu `WorkspaceTabDragTest`); kéo khe split trong giới hạn; click kèm modifier và double-click trên tree; 10 000 dòng. |

## Kết quả so ảnh

`ui-kit/scripts/imgdiff.sh` (Graphite Dark, Graphite Light, One Dark): **72/72 mẫu đạt**, gồm 4 mẫu mới × 3 theme.

| Mẫu | Graphite Dark | Graphite Light | One Dark |
|---|---|---|---|
| tab-strip | 0,055 % · ΔE 6,7 | 0,022 % · ΔE 6,3 | 0,035 % · ΔE 4,5 |
| workspace-tabs | 0,257 % · ΔE 9,9 | 0,230 % · ΔE 10,5 | 0,225 % · ΔE 8,1 |
| split | 0,026 % · ΔE 2,5 | 0,000 % · ΔE 1,4 | 0,015 % · ΔE 2,5 |
| lazy-tree | 0,060 % · ΔE 8,1 | 0,020 % · ΔE 4,3 | 0,051 % · ΔE 7,6 |

(% pixel lệch, ΔE₀₀ lớn nhất; vùng chữ và icon đều đạt.)

`ui-kit/scripts/imgdiff.sh --all`: 4 mẫu mới đạt ở **cả 12 theme** (48/48). Lệch lớn nhất: workspace-tabs 0,48 %
(Contrast Dark, ΔE 13,1, ở mép bo của tab chọn), tab-strip 0,21 % (Contrast Dark). Các mẫu khác không đạt trong lượt
`--all` (checkbox, icon-button, list-combo-box, radio-pressed ở hai theme Contrast; scrollbar ở Catppuccin Frappé) là
mẫu của phase trước, không do phase này.

## Hiệu năng: cuộn 10 000 node

Test `ten_thousand_rows_build_only_what_is_in_view_and_scroll_cheaply` (`tg-ui/tests/layout.rs`): cây 100 node × 99 lá,
mở hết = 10 000 dòng, khung 520dp (20 dòng). Mỗi bước là một sự kiện bánh xe 78px rồi vẽ lại cả cửa sổ (layout,
prepaint, paint ra scene của GPUI; không rasterize trên GPU vì chạy headless).

```bash
cd ui-kit && cargo test -p tg-ui --release --test layout ten_thousand -- --nocapture
```

| Đo (release, Apple Silicon) | Thời gian |
|---|---|
| Khung đầu tiên (dựng 10 000 dòng phẳng + vẽ) | 2,4 ms |
| Một khung cuộn, trung bình / p95 / tối đa (400 bước) | 0,88 / 1,07 / 1,35 ms |
| Đóng rồi mở một node (dựng lại 10 000 dòng) | 1,28 ms mỗi lần |
| End: nhảy tới dòng cuối | 1,08 ms |

Test cũng kiểm dòng ngoài khung nhìn không bao giờ được dựng (`n99.98` không có bounds cho tới khi End). Bản debug: khoảng
10 ms một khung. Ngân sách 60 Hz là 16,7 ms, nên cuộn còn dư rất nhiều.

## Số đo lấy từ ảnh Kotlin

- **Tab strip (Int UI):** nền mỗi tab `#FFFFFF` ở theme sáng (lộ ra trên island không trắng như Alucard), trong suốt ở theme tối; nhãn tab không chọn, không hover có alpha 0,9. Tab cao 40dp, nhãn cách mép 8dp mỗi bên, không có khe giữa các tab; gạch chân 3dp sát đáy, bo tròn hai đầu, `#3574F0` (theme có `controls`: accent). Hover `#43454A`/`#EBECF0` (theme có `controls`: `hover`), phủ cả tab, gạch chân của tab hover không hiện.
- **Tree:** dòng 26dp bắt đầu sau 4dp đệm trên; nền chọn x 6dp…(rộng − 6dp), bo 4dp. Không focus: `#43454A`/`#DFE1E5` (theme: `selection.inactive`); có focus: `#2E436E`/`#D4E2FF` (theme: `selection`). Chữ node ở 30dp, lá thêm 20dp (`leaf_inset`), mỗi cấp 16dp.
- **Tab workspace:** viền 1dp của tab chọn không chiếm chỗ (Compose vẽ viền đè lên), nên trong GPUI phải vẽ dạng lớp phủ.

## Phát hiện

1. **`.whitespace_nowrap()` gọi thẳng trên `Text` xóa font, cỡ và màu chữ:** refine của `StyleRefinement` thay nguyên khối text style. Phải bọc `Text` trong một `div().whitespace_nowrap()`.
2. **Compose làm tròn bề rộng chữ lên nguyên pixel thiết bị.** Để tab khớp từng pixel, bề rộng tab được đặt tường minh từ `layout_line` làm tròn lên theo `scale_factor`; nếu để GPUI tự đo thì mỗi tab rộng hơn khoảng 1px.
3. **`window.refresh()` trong prepaint/paint bị bỏ qua** (cửa sổ đang vẽ). Component cần vẽ lại sau khi biết kích thước (tab strip, split, tree) dùng `window.request_animation_frame()`.
4. **Viền GPUI chiếm chỗ trong layout**, khác Compose. Viền của tab workspace đang chọn là lớp phủ tuyệt đối.
5. **Shift/⌘+click trên `LazyTree` của Jewel** (quan sát qua kịch bản, xem commit runner): Shift+click thêm khoảng từ dòng active tới dòng được click, không bỏ dòng nào; ⌘/Ctrl+click bật/tắt đúng một dòng; cả hai đặt dòng được click làm active, kể cả khi ⌘+click vừa bỏ chọn nó; Shift thắng khi giữ cả hai, Alt bị bỏ qua. Up ở dòng đầu và Down ở dòng cuối không làm gì (giữ nguyên selection nhiều dòng), còn Home/End/PageUp/PageDown vẫn thu về một dòng. Hai click liên tiếp lên cùng dòng thành double-click (mở node), nên kịch bản chèn `wait`.

## Lệch khỏi brief / chưa làm

- Tab đang kéo về chỗ ngay khi thả, chưa có hoạt ảnh lướt về (Kotlin dùng spring). `TabRow` đã có `end`/`settle` để thêm sau.
- Click lên chevron chưa mở/đóng node riêng (Jewel có); mở/đóng bằng double-click và phím. `LazyTree` chưa có hàm toggle công khai.
- Màu hover của tree và trạng thái nhấn của tab strip chưa đo (mẫu không có).
- So ảnh vẫn chỉ chạy trên macOS.
