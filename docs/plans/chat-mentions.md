# Kế hoạch Mention trong chat agent (⌘L, `@`)

Đánh giá và thiết kế tính năng mention kiểu Cursor cho chat agent, viết ngày 2026-10-30 trên commit `185e860`.

> **Trạng thái (2026-10-30):** P0 và P1 xong. P1 thêm popup `@` (tab đang mở, bảng, cột, saved query), ⌘L từ grid,
> "Add to Chat" ở Explorer, và cấu trúc bảng (cột, khoá, index) được đọc lúc gửi. Chi tiết ở mục 5.
>
> P0: Pill nằm ngay trong dòng chữ, ⌘L và menu chuột phải "Add to Chat" từ SQL
> console, popup xem trước, copy/cut/paste giữ pill, "Ask in chat" chèn pill lỗi. Gõ tiếng Việt qua IME đã được thử tay
> trên app thật và chạy ổn. Việc tiếp theo là P1.

## 1. Mục tiêu

Người dùng chỉ cho agent biết đúng thứ cần hỏi, thay vì dán SQL hay mô tả bằng lời:

- **⌘L / Ctrl+L** ở nơi đang làm việc: đoạn đang chọn, nếu không chọn thì câu lệnh tại caret. Thứ đó vào ô chat thành một
  pill, ví dụ `@Console:3-5`, và người dùng gõ tiếp câu hỏi.
- **Gõ `@`** để chọn bảng, cột, tab đang mở, saved query… (P1).
- Pill nằm **ngay trong câu**, nên viết được kiểu "join `@orders` với `@customers` theo `@Console:3-5`". Agent biết thứ
  nào được nhắc ở vị trí nào.

## 2. Hiện trạng trước khi làm

| Thứ | Hiện trạng | Vấn đề |
| --- | --- | --- |
| `AgentContext.systemPrompt` | Tự gửi cả console đang mở (≤ 4k ký tự) hoặc tên bảng đang xem | Ngữ cảnh ngầm; người dùng không chỉ được "hỏi về đoạn này" |
| Schema trong prompt | Cắt ở 40k ký tự | Với schema lớn, bảng cần hỏi có thể bị rớt |
| "Ask in chat" (FixView) | Ghi đè `draft` | Mất phần người dùng đang gõ dở |
| Composer | `rememberTextFieldState()` nằm trong composable | Bên ngoài (⌘L) không chèn được vào |
| ⌘L | Chưa dùng. Reformat là ⌘⌥L | Không xung đột |

## 3. Thiết kế (đã hiện thực trong spike)

### 3.1 Mỗi mention là đúng một ký tự

Mention được lưu trong text thành **một ký tự Private Use của Unicode** (U+E000…U+F8FF). Chính ký tự đó là id, trỏ tới
`Mention` trong store của composer. Nhờ vậy, mọi hành vi soạn thảo tự đúng mà không phải theo dõi khoảng ký tự:

- Backspace xoá trọn pill, vì pill chỉ là một ký tự.
- Caret không thể dừng giữa pill. Mũi tên trái/phải nhảy qua cả pill.
- Undo/redo, cắt, dán trong ô soạn đều giữ đúng pill, vì store giữ mention cho tới khi gửi.
- Ký tự Private Use dán từ ngoài vào mà store không biết thì bị `InputTransformation` lọc bỏ.

| Thành phần | Vị trí | Vai trò |
| --- | --- | --- |
| `Mention` (sealed): `Sql`, `Error` | `core/.../agent/Mention.kt` | Ảnh chụp thứ được nhắc, có `label` và `context`; ngữ cảnh bị cắt ở `Mention.Budget` |
| `MentionText` | như trên | Text kèm mentions: `plain` (`@label`) và `prompt` (đánh số, kèm phần "# Mentioned") |
| `ComposerState` | `app/.../agent/ComposerState.kt` | `TextFieldState` cùng store mention, do `AgentViewModel` giữ; `insert`, `remove`, `copy`, `paste`, `take`, `restore` |
| `PillLayout`, `drawPills`, `MentionMessageText`, `MentionPreview` | `app/.../agent/MentionPills.kt` | Vị trí pill trong text hiển thị, vẽ nền bo góc, pill trong tin nhắn đã gửi, popup xem trước |
| `ConsoleTab.mention()`, `mentionInChat()` | `app/.../console/ConsoleTab.kt` | Đoạn đang chọn hoặc câu lệnh tại caret, kèm số dòng |
| `Shortcuts.AddToChat` | `app/.../ui/Shortcuts.kt` | ⌘L, có trong dialog phím tắt và trong menu chuột phải của editor |
| `TextClipboard` | `app/.../ui/Clipboard.kt` | Clipboard hệ thống, thay được bằng bản giả trong test |

### 3.2 Hiển thị

- **Ô soạn:** `OutputTransformation` thay mỗi ký tự mention bằng `" @label "` (hai bên là thin space) và tô màu accent.
  `drawBehind` + `onTextLayout` vẽ nền bo góc, mỗi dòng một khối nếu pill bị xuống dòng. Đây là cùng kỹ thuật
  `SqlEditor` đang dùng để highlight.
- **Tin nhắn đã gửi:** `Text` chỉ đọc dùng `AnnotatedString` và cùng hàm `drawPills`. Nút Copy chép bản `plain`.
- **Xem trước:** bấm vào pill (trong ô soạn hoặc tin nhắn đã gửi) mở popup phía trên: nhãn, engine, số dòng, SQL, và
  thông báo lỗi với pill `Error`. Trong ô soạn, popup có nút gỡ pill khỏi tin nhắn. Esc hoặc bấm ra ngoài thì đóng.
- **Clipboard:** ⌘C/⌘X trên vùng chọn có pill sẽ chép ra `@label`, nên dán sang chỗ khác vẫn đọc được. Dán lại chính
  nội dung đó vào ô soạn thì pill trở lại, kể cả sau khi tin nhắn đã gửi (pill được cấp ký tự mới). Vùng chọn không có
  pill thì để text field tự xử lý như bình thường.

### 3.3 Gửi cho agent

`ChatMessage` lưu `text` (có ký tự mention) và `mentions`. Mọi chỗ dựng `AgentMessage` đều dùng `mentionText.prompt`:

```
@Console:3-5 [1] why is it slow?

# Mentioned

[1] SQLite SQL from Console, lines 3-5:
```sql
SELECT * FROM orders WHERE total > 10
```
```

- Một mention xuất hiện nhiều lần chỉ gửi một lần. Pill đã bị xoá thì không gửi.
- `AgentMessage` vẫn là `String`, nên adapter CLI và Provider không phải sửa.
- Rewind trả lại cả text lẫn pill vào ô soạn (`composer.restore`).
- **Ảnh chụp tại lúc mention:** người dùng sửa editor sau đó thì lịch sử chat vẫn đúng với lúc hỏi.

### 3.4 Quyền riêng tư

- ⌘L không làm gì khi kết nối ở mức `Off` (`ConsoleTab.canAskAgent`). Mức `SchemaOnly` vẫn cho gửi SQL, vì SQL vốn đã
  được gửi kèm console đang mở.
- **P1, bắt buộc trước khi mention ô grid:** ở `SchemaOnly` chỉ gửi tên/kiểu cột và số dòng, không gửi giá trị. Pill có
  dấu cảnh báo kèm gợi ý "Bật dữ liệu mẫu cho kết nối này". Mỗi mention theo `aiAccess` của **kết nối của chính nó**.

## 4. Kết quả spike

| Câu hỏi | Kết quả | Kiểm bằng |
| --- | --- | --- |
| `OutputTransformation` đổi 1 ký tự thành nhãn dài hơn | Đạt | `MentionComposerTest`, ảnh chụp |
| Mũi tên trái/phải nhảy qua cả pill | Đạt | `MentionComposerTest` |
| Backspace xoá trọn pill, ⌘Z khôi phục | Đạt | `MentionComposerTest` |
| Ký tự Private Use lạ khi dán bị loại | Đạt | `MentionComposerTest` |
| Không chèn trùng cùng một mention | Đạt | `MentionComposerTest` |
| Prompt đánh số và kèm ngữ cảnh | Đạt | `MentionTextTest` (core), `MentionComposerTest` |
| Số dòng của câu lệnh tại caret / đoạn chọn | Đạt | `MentionComposerTest` |
| Gõ tiếng Việt (Telex/VNI) ngay cạnh pill | Đạt | Thử tay trên app thật |
| Click vào giữa pill: caret dừng ở mép, popup mở | Đạt | `MentionComposerTest` |
| Copy/cut/paste giữ pill, chép ra `@label` | Đạt | `ComposerStateTest` |
| Menu chuột phải "Add to Chat" | Đạt | `MentionComposerTest` |
| "Ask in chat" chèn pill lỗi, giữ phần đang gõ | Đạt | `MentionComposerTest` |

Sau P0, toàn bộ test `app` (100) và `core` (190) qua. Trong lúc làm, `ConsoleFixTest` lộ ra một race có sẵn: test bấm
"Fix with AI" lần hai trước khi thẻ mới ổn định. Đã sửa bằng cách chờ link sẵn sàng rồi mới bấm.

## 5. Lộ trình tiếp theo

| Giai đoạn | Nội dung | Ước lượng |
| --- | --- | --- |
| **P0 — xong** | IME tiếng Việt và thao tác chuột; copy/cut/paste giữ pill; bấm pill để xem trước; "Add to Chat" trong menu chuột phải; "Ask in chat" chèn pill `Error`; giới hạn độ dài mỗi mention | — |
| **P1 — xong** | Popup `@`; ⌘L và menu chuột phải trên grid; "Add to Chat" cho bảng/cột ở Explorer; mention bảng gửi cấu trúc chi tiết; không gửi lại console đã được mention trọn vẹn | — |
| **P2** | Thêm provider: `@history`, `@result`, `@explain`, `@connection`; ⌘⇧L mở chat mới (khi có nhiều cuộc hội thoại) | tuỳ nhu cầu |

### P1 đã làm

| Phần | Cách làm |
| --- | --- |
| Loại mention mới | `Mention.Table`, `Column`, `Query`, `Cells` trong `core/.../agent/Mention.kt` |
| Popup `@` | `ComposerState.typing` nhận `@query` ngay trước caret (bỏ qua `@` giữa một từ, ví dụ email). `MentionSuggestions` hiện danh sách phía trên ô soạn, nhóm theo loại. ↑/↓ chọn, Enter/Tab chèn pill, Esc đóng. `bảng.` liệt kê cột của bảng đó |
| Nguồn | `MentionSources.search`: tab đang mở (console, bảng), bảng và cột của kết nối hiện tại, saved query. Xếp hạng bằng `fuzzyScore` của Go to Anything |
| Cấu trúc bảng | `Mention.Table` chỉ mang tên lúc chèn. Lúc gửi, `MentionSources.complete` đọc `tableDetails` và `SchemaDigest.table` viết ra cột, kiểu, PK, FK, index. Kết quả được lưu lại vào tin nhắn nên các lượt sau gửi y hệt |
| ⌘L trên grid | `GridActions.onMention`: ⌘L hoặc menu chuột phải "Add to Chat" gửi khối ô đang chọn. Trong table viewer, ⌘L khi không có ô nào được chọn thì mention cả bảng |
| Explorer | Mục "Add to Chat" trong menu chuột phải của node bảng và node cột |
| Quyền | Mọi nguồn đều kiểm `AiAccess` của kết nối của chính nó. `Off`: không có gì. `SchemaOnly`: ô chỉ gửi tên/kiểu cột và số dòng. `SchemaAndSamples`: gửi cả giá trị, tối đa 50 dòng × 200 ký tự |
| Gửi trùng | `AgentContext.systemPrompt` bỏ khối "console đang mở" khi tin nhắn đã mention toàn bộ console đó |

**Khác với thiết kế ban đầu:**
- `search` chạy đồng bộ, chỉ đọc cache, thay vì `suspend` như `MentionProvider` dự kiến. Bảng tải nền và danh sách tự cập nhật nhờ đọc `SchemaCache.version`. Chỉ phần đọc cấu trúc bảng lúc gửi là `suspend`.
- Explorer chỉ có mục trong menu chuột phải, chưa có ⌘L, vì cây Explorer chưa có trạng thái "node đang chọn" để phím tắt dựa vào.
- Dòng lỗi ở Output dùng lại "Ask in chat" có sẵn (đã chèn pill `Error` từ P0), không thêm đường riêng.

### Kiểm thử P1

`MentionSourcesTest` (UI, SQLite): `@ord` → Enter chèn bảng, `@orders.to` → cột, Esc đóng danh sách, prompt có cấu trúc
bảng; tab đang mở hiện khi chưa gõ gì; `Off` thì không còn gợi ý; giá trị ô chỉ gửi khi `SchemaAndSamples`; console
mention trọn vẹn không bị lặp trong system prompt. `ComposerStateTest`: nhận diện `@`, bỏ qua email, chọn thì thay bằng
pill. `DataGridTest`: ⌘L trên grid trả về đúng khối ô. Sau P1, test `app` (108) và `core` (194) đều qua.

## 6. Rủi ro còn lại

- **IME:** khi đang ghép chữ (composition), `OutputTransformation` vẫn áp dụng lên text. Nếu ghép chữ ngay sau pill bị
  lỗi, phương án lùi là bỏ qua transformation trong lúc đang composition, hoặc chuyển ⌘L sang hàng pill phía trên ô soạn.
  Model `Mention`/`MentionText` dùng được nguyên cho cả hai.
- **Nhãn trùng:** hai console cùng tên cho ra nhãn giống nhau. Agent vẫn phân biệt được nhờ số `[n]`, nhưng người dùng
  thì không. Cân nhắc thêm tên kết nối vào nhãn khi bị trùng.
- **Gửi trùng một phần:** khi chỉ mention một câu trong console đang mở, system prompt vẫn gửi cả console, nên câu đó
  xuất hiện hai lần. Chỉ trường hợp mention trọn console mới được bỏ.
- **Popup `@` không có danh sách bảng của kết nối khác:** chỉ kết nối hiện tại. Bảng của kết nối khác vẫn mention được
  qua Explorer hoặc tab đang mở.
- **Copy qua menu chuột phải trong ô soạn** vẫn đi theo đường mặc định của text field, nên chép ra ký tự Private Use.
  Chỉ phím tắt ⌘C/⌘X được chặn.
- **Tổng độ dài:** mỗi mention đã bị cắt ở 8.000 ký tự (thông báo lỗi ở 2.000), nhưng nhiều mention trong một tin nhắn
  thì chưa có giới hạn tổng.
