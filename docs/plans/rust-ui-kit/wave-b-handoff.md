# Prompt bàn giao: tiếp tục Wave B

Dán nguyên văn khối dưới đây vào một phiên Claude Code mới, mở tại thư mục repo TableGrip.

```text
Bạn là session tích hợp của dự án Rust UI kit trong TableGrip. Việc của bạn: khởi động Wave B, sau đó gom kết quả vào
nhánh tích hợp. Làm đúng các bước sau, không bỏ bước nào.

## Bối cảnh (đã xong, không làm lại)
- Nhánh tích hợp: `feat/rust-ui-kit-phase-0`, PR https://github.com/duongductrong/TableGrip/pull/5 ("Rust UI kit
  migration (phases 0–6)"). Nhánh này chứa toàn bộ migration, mỗi phase một commit. Không merge vào master trước khi
  Phase 6 xong.
- Đã có trên nhánh: phase 0 (baseline, runner, spike GPUI), phase 1 (tg-theme, tg-ui, icons, gallery, image diff),
  phase 2 logic, phase 3 logic (tg-text, IME Telex/VNI), phase 4 logic, và commit runner f19818f.
- Hiện có 231 kịch bản pass trên cả Kotlin và Rust, 539 test Kotlin pass, CI Rust xanh trên macOS, Linux và Windows.
- Kế hoạch và quy tắc: `docs/plans/rust-ui-kit/execution.md`. Đọc các mục "Nhánh và commit", "File dùng chung",
  "Từ vựng kịch bản", "Định nghĩa xong" và các brief B1, B2, B3. Báo cáo Phase 1 nằm ở
  `docs/plans/rust-ui-kit/phase-1.md`.

## Bước 1: kiểm tra trạng thái
1. `git checkout feat/rust-ui-kit-phase-0 && git pull`. Working tree phải sạch.
2. `cd ui-kit && cargo test --workspace --locked`, rồi `./gradlew :app:test --tests '*ConformanceTest*'`. Cả hai
   phải xanh trước khi khởi động session. Nếu không xanh, dừng lại và báo người dùng.

## Bước 2: khởi động Wave B
Tạo đúng 3 session song song. Mỗi session:
- là session mới (`context: fresh`), có worktree riêng (`use_worktree: true`);
- cắt nhánh từ `source_branch: feat/rust-ui-kit-phase-0`;
- `initiation: user_asked`.

Prompt của mỗi session là brief tương ứng trong `execution.md`, cộng phần "Bổ sung sau Wave A" bên dưới và các quy tắc
chung sau:
- Không đọc mã nguồn Jewel, IntelliJ hay Compose.
- Không sửa `ConformanceTest.kt`, `Scenario.kt`, `tests/conformance/{main,step}.rs`. Cần step mới thì ghi vào báo cáo.
- File dùng chung chỉ được thêm 1 dòng, theo thứ tự chữ cái.
- Chỉ chạy test của phần mình.
- Commit đúng tên trong brief, mỗi phase một commit, sửa thêm thì amend. Không dòng ghi công Claude.
- Không push, không mở PR. Kết thúc bằng báo cáo ngắn.

Ba session:
- **B1 `layout-shell-logic`**, model `claude-sonnet-5-5`: phase 5 logic + phase 6 logic. Hai commit:
  - `feat(ui-kit): phase 5 logic — …`
  - `feat(ui-kit): phase 6 logic — …`
- **B2 `controls-render`**, model `claude-sonnet-5-5`: phase 2 render. Một commit `feat(ui-kit): phase 2 render — …`.
- **B3 `text-render`**, model mặc định (Opus): phase 3 render. Một commit
  `feat(ui-kit): phase 3 render — text field and text area`.

### Bổ sung sau Wave A (thêm vào brief tương ứng)
- **Tất cả:**
  - `ui-kit/Cargo.lock` giữ `libc` ở 0.2.189; không chạy `cargo update` cho libc (lý do ghi trong `ui-kit/Cargo.toml`).
  - Workspace members là `crates/*`, crate mới không cần sửa `Cargo.toml`.
- **B1:**
  - Subject Rust có các hook `claims_key`, `tab(backwards)`, `pointer_focuses` (xem `subjects/mod.rs`). Dùng chúng cho
    tab strip, tree và các control có nhiều điểm dừng Tab, thay vì cố sửa runner.
  - Modifier `word` có sẵn ở cả hai runner.
  - Tab workspace đối chiếu với `WorkspaceTabDragTest`.
  - Keymap theo `app/src/main/kotlin/dev/tablegrip/app/ui/Shortcuts.kt`.
- **B2:**
  - Dùng `tg-theme` / `tg-ui` của Phase 1 và logic trong `tg-ui-logic` (button, checkbox, radio, tri_state, tooltip,
    icon_button, focus).
  - Thêm mẫu gallery theo cơ chế mỗi mẫu một file (cả Rust lẫn Kotlin, xem phase-1.md).
  - Kiểm bằng `ui-kit/scripts/imgdiff.sh` trên Graphite Dark, Graphite Light và One Dark.
  - Focus ring phải vẽ dạng lớp phủ ra ngoài bounds, không chiếm chỗ trong layout.
  - Tooltip hiện sau 500 ms, ẩn ngay khi rời chuột hoặc click, và không hiện khi chỉ focus bằng bàn phím.
- **B3:**
  - Dùng crate `tg-text`.
  - Up/Down trong TextArea phải tính theo vị trí pixel từ text layout của GPUI, không theo cột. `tg-text` chỉ có bản
    dự phòng theo cột grapheme.
  - Nối IME của GPUI (input handler, marked text) vào mô hình composition của `tg-text`. Kiểm tay với bộ gõ tiếng Việt
    Telex trên macOS và ghi kết quả vào báo cáo.
  - Ô mật khẩu đưa dấu `•` lên clipboard khi copy hoặc cut, giống bản Kotlin.

## Bước 3: khi từng session báo xong
1. Đọc báo cáo, kiểm commit trên nhánh của session: tên đúng, đúng số commit, không có dòng ghi công.
2. Trên `feat/rust-ui-kit-phase-0`, cherry-pick theo thứ tự: phase 2 render → phase 3 render → phase 5 logic → phase 6
   logic.
3. Xung đột thường chỉ là các dòng `mod` / `pub use` / nhánh `match` cùng thêm ở một chỗ: giữ cả hai phía, xóa dòng
   trùng, rồi chạy `cargo fmt --all`. Với `Cargo.lock`: lấy bản của nhánh tích hợp (`git checkout --ours`), chạy
   `cargo metadata` để thêm package mới, rồi kiểm lại libc vẫn là 0.2.189.
4. Chạy đủ bộ kiểm tra:
   - `cd ui-kit && cargo fmt --all --check`
   - `cargo clippy --workspace --all-targets --locked -- -D warnings`
   - `cargo test --workspace --locked`
   - `./gradlew :app:test` (toàn bộ, phải 0 lỗi)

   Sửa gì thì amend vào commit của phase đó.
5. Nếu session báo cần step hoặc hook mới cho runner: tự thêm ở cả hai runner (Kotlin `ConformanceTest.kt` /
   `Scenario.kt` và Rust `tests/conformance`), cập nhật mục "Từ vựng kịch bản" trong `execution.md`, và commit riêng
   với tiền tố `feat(ui-kit): conformance runner — …`.
6. `git push` (không force), rồi cập nhật checklist tiến độ trong phần mô tả PR #5.

## Bước 4: báo cáo người dùng
- Báo kết quả từng commit (số kịch bản, kết quả so ảnh, việc còn mở) và trạng thái CI.
- Hỏi trước khi chạy Wave C (C1 popups-render Sonnet, C2 layout-render Opus, C3 shell-render Sonnet), vì quota có
  hạn.

## Quyết định còn chờ người dùng
- Có đóng gói font Inter và JetBrains Mono (giấy phép OFL, kèm file license) vào bản Rust không? Đề xuất: có. Hiện
  `imgdiff.sh` lấy hai font này từ Gradle cache.

## Quy ước repo
- Commit không có dòng `Co-Authored-By` hay ghi công Claude.
- Trả lời người dùng bằng tiếng Việt.
```
