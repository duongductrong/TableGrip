# Thực thi Phase 1–6 song song

Kế hoạch tổng: [../rust-ui-kit.md](../rust-ui-kit.md). Phase 0 đã xong ([phase-0.md](phase-0.md)). Tài liệu này chia việc
còn lại thành các session chạy song song, với mục tiêu rút ngắn thời gian và giảm token.

## Ý chính: tách mỗi phase thành hai lớp

| Lớp | Nội dung | Phụ thuộc |
|---|---|---|
| **L: logic** | Kịch bản quan sát trên bản Kotlin + subject Kotlin + state machine trong `tg-ui-logic` (hoặc crate logic riêng) + subject Rust | Không cần GPUI. Chạy được ngay, song song |
| **R: vẽ** | Component GPUI trong `tg-ui`, khớp golden và ảnh chuẩn | Cần nền tảng Phase 1 (`tg-theme`, `tg-ui`, gallery, công cụ so ảnh) |

Lớp L của các phase 2–6 không phụ thuộc nhau, nên chạy song song được ngay. Lớp R phải đợi Phase 1.

## Các đợt (wave)

| Wave | Session | Phase | Model | Thời gian ước tính | Chi phí tương đối |
|---|---|---|---|---|---|
| **A** | A1 `ui-foundation` | 1 (L+R) | Opus 5.5 | 3–5 giờ | Lớn |
| **A** | A2 `controls-logic` | 2L + 4L | Sonnet 5.5 | 2–3 giờ | Vừa |
| **A** | A3 `text-logic` | 3L | Opus 5.5 | 3–4 giờ | Lớn |
| **B** | B1 `layout-shell-logic` | 5L + 6L | Sonnet 5.5 | 2–3 giờ | Vừa |
| **B** | B2 `controls-render` | 2R | Sonnet 5.5 | 2–3 giờ | Vừa |
| **B** | B3 `text-render` | 3R (gồm IME) | Opus 5.5 | 3–5 giờ | Lớn |
| **C** | C1 `popups-render` | 4R | Sonnet 5.5 | 2–3 giờ | Vừa |
| **C** | C2 `layout-render` | 5R (tree ảo hóa) | Opus 5.5 | 3–4 giờ | Lớn |
| **C** | C3 `shell-render` | 6R | Sonnet 5.5 | 2–3 giờ | Vừa |
| **D** | Session tích hợp (session này) | Gom, so ảnh 6 theme, CI 3 OS | Opus 5.5 | 1–2 giờ | Nhỏ–vừa |

**Thứ tự:**
- Wave A chạy ngay.
- Wave B chạy khi A1 đã được merge. B1 không cần A1, nhưng được xếp vào B để giữ tối đa 3 session cùng lúc.
- Wave C chạy khi B2 đã được merge, vì popup và tab dùng lại button, focus ring và tooltip.
- Wave D chạy khi C xong.

**Tổng thời gian:** khoảng 12–20 giờ chạy máy. Tính cả lúc chờ quota 5 giờ reset và thời gian bạn duyệt, thực tế là
3–5 ngày.

### Ngân sách token

Hiện tại: 5 giờ đã dùng 27%, tuần đã dùng 43%.

Các ước lượng dưới đây là thô. Session đầu tiên xong sẽ cho số thật để chỉnh lại.

- **Mỗi wave có 3 session**, trong đó 1–2 session Opus. Ước lượng tốn khoảng 10–15% quota tuần mỗi wave. Ba wave
  cộng phần tích hợp có thể dùng gần hết 57% quota tuần còn lại, nên **Wave C có thể phải sang tuần sau**.
- **Mỗi lần chỉ chạy một wave**, tối đa 3 session cùng lúc, để không cạn quota 5 giờ giữa chừng. Nếu quota 5 giờ còn
  dưới khoảng 30%, chỉ chạy 2 session.
- **Sau Wave A thì dừng lại** để xem quota thực tế đã dùng. Nếu một session Opus tốn quá nhiều, chuyển các session vẽ
  ở Wave B và C sang Sonnet, trừ B3 (IME) và C2 (tree ảo hóa).

## Cách giảm token, áp dụng cho mọi session

1. **Session mới với brief ngắn**, không fork hội thoại này (đã rất dài). Brief nằm ở cuối tài liệu.
2. **Chỉ đọc những gì cần:** phần liên quan trong [inventory.md](inventory.md) và file của component mình làm. Không
   đọc lại cả kế hoạch, không duyệt toàn repo.
3. **Chỉ chạy test của mình:**
   - `cargo test -p <crate>`
   - `./gradlew :app:test --tests '*ConformanceTest*'`

   Bộ test đầy đủ chỉ chạy lúc tích hợp.
4. **Không đọc mã nguồn Jewel hay IntelliJ.** Đây vừa là nguyên tắc không copy, vừa tiết kiệm token. Hành vi chỉ lấy
   bằng cách quan sát qua kịch bản.
5. **Hỏi thì gửi một tin ngắn cho session tích hợp**, không tự tìm hiểu lan man.

## Nhánh và commit

- **Nhánh tích hợp `feat/rust-ui-kit-phase-0` (PR #5) chứa toàn bộ migration.** Không merge vào master cho đến khi
  Phase 6 xong.
- **Mỗi session làm trong worktree riêng**, trên nhánh cắt từ đỉnh nhánh tích hợp lúc session bắt đầu.
- **Mỗi phase là một commit.** Session tạo đúng một commit cho mỗi phase/lớp nó làm, theo mẫu:
  - `feat(ui-kit): phase 2 logic — buttons, radio, tooltip, focus order`
  - `feat(ui-kit): phase 2 render — ...`

  Sửa thêm thì `--amend` vào commit của phase đó, không tạo commit lặt vặt.
- **Session tích hợp đưa các commit đó lên nhánh tích hợp bằng rebase (cherry-pick)**, giữ nguyên từng commit
  phase, chạy bộ test đầy đủ, rồi push. Không dùng merge commit, để lịch sử đọc được theo phase.
- **Không dòng ghi công Claude trong commit** (quy ước của repo).

### File dùng chung và cách tránh xung đột

| File | Quy tắc |
|---|---|
| `app/.../conformance/subjects/*.kt` | Mỗi component một file `object XxxSubject : ConformanceSubject`. Runner tự phát hiện, không có danh sách chung. |
| `ui-kit/conformance/scenarios/<component>.json` | Mỗi component một file. Chỉ thêm kịch bản đã pass trên Kotlin. |
| `tg-ui-logic/tests/conformance/subjects/mod.rs` | Chỉ thêm 1 dòng `mod` và 1 nhánh `match`, theo thứ tự chữ cái. |
| `tg-ui-logic/src/lib.rs` | Chỉ thêm dòng `pub mod` / `pub use`, theo thứ tự chữ cái. |
| `ui-kit/Cargo.toml` | Thành viên là `crates/*`. Crate mới không cần sửa file này; dependency mới thêm vào `[workspace.dependencies]` theo thứ tự chữ cái. |
| Runner: `ConformanceTest.kt`, `Scenario.kt`, `tests/conformance/{main,step}.rs` | **Không sửa.** Cần step hoặc key mới thì nhắn session tích hợp. |
| `GoldenTest.kt` | Chỉ A1 sửa ở Wave A. Ở Wave B và C, mỗi session thêm mẫu vào file gallery riêng (A1 tạo cơ chế này). |

Xung đột kiểu một dòng thêm ở cùng vị trí là bình thường; session tích hợp xử lý khi cherry-pick.

### Từ vựng kịch bản (đã có sẵn ở cả hai runner)

- **Step:**
  - Con trỏ: `click`, `doubleClick`, `rightClick`, `hover`, `unhover`. Mỗi loại có thể nhắm một tag, ví dụ
    `{ "click": "<tag>" }`.
  - Bàn phím và chữ: `focus`, `{ "key": "<Tên>", "mods": ["primary", "shift", "alt"] }`, `{ "type": "..." }`.
  - Thời gian: `{ "wait": <ms> }`.
- **Phím:**
  - `Space`, `Enter`, `Tab`, `Escape`, `Backspace`, `Delete`
  - `Home`, `End`, `Left`, `Right`, `Up`, `Down`, `PageUp`, `PageDown`
  - `F2`, `A`–`Z`
- **Expect dùng chung:** `focused`, `nextFocused`, `shown:<tag>` (có node mang tag đó trên màn hình). Mọi key khác do
  subject báo.
- **Setup dùng chung:** `next: true` thêm một control focus được sau subject (tag `next`).
- **Modifier `word`:** di chuyển theo từ, là Option trên macOS và Ctrl ở nơi khác. Hai runner tự quy đổi.
- **Hook cho subject Rust** (mặc định không làm gì):
  - `claims_key`: subject giữ phím trước khi focus chuyển, ví dụ Tab trong TextArea.
  - `tab(backwards)`: subject có nhiều điểm dừng Tab của riêng nó, ví dụ các dòng radio.
  - `pointer_focuses`: double-click hoặc click vào một phần của subject cũng làm nó nhận focus.
- **Giới hạn đã biết:**
  - Runner Kotlin chưa giả lập được IME composition, nên IME chỉ được kiểm trong Rust (`tg-text`).
  - Item trong popup của Jewel mất test tag do semantics bị gộp.

## Định nghĩa "xong" cho mỗi session

1. Mọi kịch bản mới đều pass trên Kotlin.
2. Cùng các kịch bản đó pass trên Rust, không còn pending với component của mình (trừ khi brief nói khác).
3. Không có kịch bản nào chỉ pass ở một bên.
4. `cargo fmt --check`, `cargo clippy -- -D warnings` và test của crate mình đều sạch.
5. Đúng một commit cho mỗi phase/lớp, đúng mẫu tên.
6. Báo cáo ngắn: số kịch bản, hành vi đáng chú ý quan sát được, việc chưa làm.

---

## Brief từng session

Mỗi brief được dùng nguyên văn làm prompt khi khởi động session. Phần nền chung cho mọi brief: TableGrip đang dựng bộ UI
kit Rust (GPUI 0.2.2, `ui-kit/`) thay cho Jewel. Bản Kotlin là chuẩn hành vi, kiểm qua kịch bản JSON chạy trên cả hai
runner. Đọc mục "Nhánh và commit" và "File dùng chung" của `docs/plans/rust-ui-kit/execution.md` trước khi làm. Không
đọc mã nguồn Jewel hay IntelliJ.

### A1 `ui-foundation`: Phase 1 (Opus)

**Mục tiêu:** dựng nền mà mọi phần vẽ sau này dùng chung.

**Việc cần làm:**
- **Crate `tg-theme`.** Đọc `app/src/main/resources/themes/*.json` (đọc thẳng, không chép), theme của người dùng,
  `themeOverrides` và `accentColor`, rồi resolve ra token. Kết quả phải khớp `ThemeRegistry` / `ColorTheme` của bản
  Kotlin trên cùng bộ fixture: thêm một test Kotlin dump token ra `ui-kit/conformance/golden/themes/*.json`, và test
  Rust so khớp từng giá trị. Graphite để trống key nào thì lấy giá trị đo được trong golden hiện có.
- **Crate `tg-ui`.** Gồm:
  - Island, Divider, Text (cỡ chữ UI và font editor từ settings), Link (hover, click, focus);
  - UI zoom theo `UiZoom.kt`: 13 mức, nhân density;
  - hệ thống icon.
- **Pipeline icon.** Script lấy SVG từ artifact `com.jetbrains.intellij.platform:icons` đã ghim phiên bản. Điều kiện:
  - chỉ lấy file có header Apache‑2.0, và chỉ những icon app đang dùng (danh sách ở `inventory.md`);
  - ghi manifest gồm tên file, phiên bản nguồn và sha256;
  - giữ nguyên header trong từng file;
  - thêm dòng ghi nguồn JetBrains vào `NOTICE`;
  - `toolWindowAskAI` và logo thì không lấy; để chỗ trống cho icon tự vẽ.
  
  Xem `docs/plans/rust-ui-kit/icon-licence.md`.
- **Crate `tg-gallery`.** Một binary hiển thị các mẫu theo từng file đăng ký riêng, để các wave sau thêm mẫu mà không
  sửa file chung. Phía Kotlin cũng làm cơ chế tương tự cho `GoldenTest`: mỗi file gallery là một object, được tự phát
  hiện.
- **Công cụ so ảnh.** Chụp gallery Rust, chuyển ICC về sRGB, so với `reference/<theme>/*.png` theo ngưỡng (ΔE và % pixel
  lệch), có vùng loại trừ cho icon và chữ, xuất trang HTML đặt hai ảnh cạnh nhau. Chạy được bằng một lệnh.
- **Golden và ảnh chuẩn** mở rộng cho cả 6 theme.

**Hoàn thành khi:**
- Token của 6 theme khớp.
- Island, Divider, Text, Link trong gallery Rust nằm trong ngưỡng so ảnh ở Graphite Dark, Graphite Light và One Dark.

**Commit:** `feat(ui-kit): phase 1 — theme, foundations, icons, gallery, image diff`.

### A2 `controls-logic`: Phase 2L + 4L (Sonnet)

**Mục tiêu:** viết kịch bản và state machine cho các control cơ bản và popup/list.

**Không làm:** không vẽ GPUI, không sửa runner.

**Phase 2L, việc cần làm:**
- RadioButtonRow; TriStateCheckbox (chỉ phần hành vi mà app tự quyết, xem `inventory.md`).
- Tooltip: hover rồi đợi bao lâu thì hiện; rời chuột thì ẩn; hiện khi focus bằng bàn phím hay không. Đo bằng `wait`.
- Thứ tự focus: Shift+Tab, Tab ở control cuối, bỏ qua control bị disable. Đây là điểm còn mở từ Phase 0; sửa
  `FocusOrder` cho đúng hành vi quan sát được.
- IconButton bọc trong Tooltip.

**Phase 4L, việc cần làm:**
- PopupMenu: mở, điều hướng bằng phím mũi tên, Enter chọn, Esc đóng, click ra ngoài thì đóng, separator. App không
  dùng menu con.
- ListComboBox (luôn không cho sửa): mở bằng click hoặc phím, điều hướng, chọn, đóng.
- Danh sách tự vẽ trong popup: selection, hover.
- Scrollbar: toán vị trí thumb, click vào track.

**Commit:** hai commit,
- `feat(ui-kit): phase 2 logic — ...`
- `feat(ui-kit): phase 4 logic — ...`

### A3 `text-logic`: Phase 3L (Opus)

**Mục tiêu:** crate `tg-text` lo mô hình soạn thảo chữ, không phụ thuộc GPUI.

**Việc cần làm:**
- **Mô hình soạn thảo:**
  - buffer UTF-8, caret và selection theo grapheme;
  - di chuyển theo từ: Option trên macOS, Ctrl trên Windows/Linux, lấy theo `Modifiers.primary` và `alt`;
  - Home/End, Shift để mở rộng vùng chọn, chọn tất cả;
  - cắt/chép/dán qua một trait clipboard;
  - undo/redo có gom nhóm.
- **IME:** mô hình composition (marked text). Có test bắt buộc cho chuỗi gõ Telex và VNI ra chữ có dấu, ví dụ
  `tieengs Vieetj` thành `tiếng Việt`. Giả lập ở mức sự kiện IME (set marked text, commit), giống cách hệ điều hành gửi.
- **Placeholder, chế độ password, single-line và multi-line** (TextArea).
- **Kịch bản:** mở rộng `text-field.json`, thêm `text-area.json`, kèm subject Kotlin và Rust. 6 kịch bản text field đang
  pending phải pass.
- **Hành vi app tự gắn quanh TextField** (18 chỗ có handler phím riêng, xem `inventory.md`): ghi vào kịch bản dưới dạng
  "phím nào được chuyển lên cho cha xử lý". Không làm lại các tính năng đó.

**Commit:** `feat(ui-kit): phase 3 logic — text editing model and IME`.

### B1 `layout-shell-logic`: Phase 5L + 6L (Sonnet)

**Phase 5L, việc cần làm:**
- Tab strip của Jewel: chọn, đóng, cuộn khi tràn.
- Tab workspace tự làm của app: kéo đổi thứ tự, tự cuộn ở mép, click chuột giữa để đóng. Đối chiếu
  `WorkspaceTabDragTest`.
- Split: kẹp tỉ lệ 0.1–0.6 (sidebar) và 0.3–0.9 (agent), lưu và khôi phục.
- Lazy tree: mở/đóng bằng phím mũi tên, selection, chọn nhiều, double-click, nạp con khi mở node, toán cửa sổ ảo hóa.

**Phase 6L, việc cần làm:**
- Keymap theo `Shortcuts.kt`: modifier phải khớp chính xác; ⌘ trên macOS tương ứng Ctrl ở nơi khác; Enter trên bàn phím
  số tính là Enter; tạm dừng phím tắt khi đang ghi phím tắt mới.
- Dialog: chỉ đóng khi nhấn Esc không kèm modifier, và chỉ khi bên trong chưa dùng phím đó.
- Mức zoom và phím tắt zoom.
- Pha preview (cha nhận trước) và pha bubble cho sự kiện phím: một mô hình dispatch thuần trong `tg-ui-logic`.

**Commit:** hai commit, một cho phase 5 logic, một cho phase 6 logic.

### B2 `controls-render`: Phase 2R (Sonnet)

**Mục tiêu:** vẽ các control của Phase 2 bằng GPUI trong `tg-ui`, dùng logic của A2 và token của A1.

**Việc cần làm:**
- Focus ring vẽ dạng lớp phủ ra ngoài bounds, không chiếm chỗ (phát hiện 1 của Phase 0).
- Thêm mẫu vào gallery: các trạng thái hover, pressed, focused, disabled, error.

**Hoàn thành khi:** golden và so ảnh đạt trên 3 theme; test GPUI headless cho mỗi control.

**Commit:** `feat(ui-kit): phase 2 render — ...`.

### B3 `text-render`: Phase 3R (Opus)

**Mục tiêu:** TextField và TextArea bằng GPUI, dùng `tg-text`.

**Việc cần làm:**
- Gắn input handler / IME của GPUI vào mô hình composition; kiểm tay bằng bộ gõ tiếng Việt trên macOS và ghi lại kết
  quả.
- Caret nhấp nháy, selection, cuộn ngang, placeholder, outline lỗi.

**Commit:** `feat(ui-kit): phase 3 render — text field and text area`.

### C1 `popups-render`: Phase 4R (Sonnet)

**Mục tiêu:** vẽ PopupMenu, ListComboBox, list item, scrollbar và scrollable container bằng GPUI, dùng logic của A2.

**Việc cần làm:**
- Vị trí popup và lật hướng ở mép cửa sổ.
- Bóng đổ.
- Thêm mẫu vào gallery.

**Commit:** `feat(ui-kit): phase 4 render — ...`.

### C2 `layout-render`: Phase 5R (Opus)

**Mục tiêu:** tab strip, tab workspace (kéo đổi thứ tự), split layout (khe 6dp giữa các island làm tay kéo) và lazy
tree ảo hóa bằng `uniform_list`.

**Yêu cầu hiệu năng:** cuộn mượt 10.000 node.

**Commit:** `feat(ui-kit): phase 5 render — ...`.

### C3 `shell-render`: Phase 6R (Sonnet)

**Mục tiêu:** khung cửa sổ và shell, dựng lại bộ khung rỗng của `MainScreen` trong gallery.

**Việc cần làm:**
- Decorated window và title bar theo từng OS: traffic lights và fullscreen trên macOS, nút caption trên Windows, title
  bar trên Linux.
- AppDialog và DialogTitleBar: vùng click tính theo pixel cửa sổ.
- ToolStripe, StatusBar.
- Keymap gắn vào action GPUI.

**Commit:** `feat(ui-kit): phase 6 render — window chrome and shell`.
