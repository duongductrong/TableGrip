# Kế hoạch AI Assist: sửa lỗi, filter bằng ngôn ngữ tự nhiên, EXPLAIN trực quan, biểu đồ nhanh

Đánh giá và thiết kế triển khai 4 tính năng AI ở mức production-ready, dựa trên mã nguồn tại commit `da09586`,
ngày 2026-10-30.

> **Trạng thái (2026-10-30):** P0 và P1 đã làm xong. Khác với thiết kế: bản sửa hiển thị dạng diff trong thẻ ở tab
> Output (không phải diff inline trong editor), và chưa có phím tắt riêng cho Fix.

## 1. Tóm tắt

| Tính năng | Giá trị cho người dùng | Phần không cần AI | Rủi ro chính | Công sức |
| --- | --- | --- | --- | --- |
| **Fix with AI** | Cao: lỗi SQL xảy ra hằng ngày | Gợi ý tên cột/bảng gần đúng | AI đổi nghĩa câu lệnh, biến SELECT thành lệnh ghi | ~1 tuần |
| **NL → filter** | Rất cao, là điểm khác biệt khi marketing | — | Hiểu sai cột hoặc khoảng thời gian, user tin nhầm kết quả | ~2 tuần |
| **EXPLAIN trực quan** | Cao với dev backend và DBA | ~80%: parse plan, vẽ cây, đánh dấu node nặng | `EXPLAIN ANALYZE` thực thi câu lệnh thật | ~2–2,5 tuần |
| **Biểu đồ nhanh** | TB–cao: tránh phải xuất ra Excel | ~80%: tự chọn trục, vẽ chart | Biểu đồ chỉ dựng từ N dòng đầu nên dễ hiểu sai | ~2 tuần |

Bài học xuyên suốt: **AI chỉ đề xuất, không tự thực thi**. Mọi đầu ra AI đều thành một *proposal* để người dùng
duyệt: diff câu lệnh, các dòng filter chưa áp dụng, câu `CREATE INDEX` gợi ý, một chart spec. Khi người dùng chấp nhận,
proposal đi qua đúng đường chạy hiện có (`ConsoleTab.run`, `SqlClassifier`, read-only, confirm writes). Như vậy không
có lối tắt nào vượt qua phần an toàn đã xây ở M2.

Thứ tự đề xuất: **Fix with AI** (dựng hạ tầng chung, ít rủi ro) → **EXPLAIN** → **NL filter** → **Chart**.

## 2. Hiện trạng liên quan

| Thứ | Hiện trạng | Hệ quả cho 4 tính năng |
| --- | --- | --- |
| `AgentAdapter.send` (`core/.../agent/Agent.kt`) | Chỉ có chat: luồng text tự do, không tools, không structured output | Cần thêm lớp *task một lượt, trả JSON đã kiểm tra* bên trên |
| CLI agent (`CliAgentAdapter`) | Mỗi lượt sinh một process, timeout theo preset | Độ trễ cỡ 3–20 giây. Ổn cho Fix và Explain, hơi chậm cho filter inline |
| Provider (`ProviderAgentAdapter`) | Anthropic và OpenAI-compatible, streaming SSE, `MAX_TOKENS = 16_000` | Nhanh hơn, có thể bật structured output về sau |
| `AgentContext` (app) | Gửi schema mặc định (budget 40k ký tự), console đang mở, kết quả tối đa 50 dòng | Phần định dạng là logic thuần, nên tách xuống `core` để tái sử dụng và test |
| `OutputLine(time, text, isError)` | Lỗi chỉ còn là chuỗi `"$message\n  in: …"` | Mất SQLState, mã lỗi vendor và vị trí lỗi. Cần lưu lỗi có cấu trúc |
| `SqlDialect.explain` | PG/MySQL: `EXPLAIN …` dạng text, SQLite: `EXPLAIN QUERY PLAN`, SQL Server: `SHOWPLAN_TEXT` | Không parse được thành cây. Cần biến thể JSON/XML cho từng engine |
| `SqlClassifier` | Đã coi `EXPLAIN ANALYZE` và CTE có DML là Write | Tái dùng được cho Explain Analyze |
| `FilterGroup` / `FilterCondition` / `FilterOperator.forKind` | Model filter có kiểu, có validate | Đích lý tưởng cho NL filter: AI sinh **model**, không sinh SQL |
| `FilterValues.temporal` | `today-7d`, `3 months ago`… nhưng **không có mốc đầu tuần/tháng** | "tuần trước" hay "tháng trước" chưa diễn đạt được dưới dạng tương đối. Cần thêm mốc |
| `QueryResult.Rows` | Giá trị là `String?` kèm `jdbcType` | Chart phải tự parse số và ngày. Dữ liệu bị giới hạn bởi `maxResultRows` |
| Thư viện chart | Chưa có. Build có allowlist license (`bundledLicenses`) | Chọn thư viện phải qua bước review license |

## 3. Hạ tầng chung (làm một lần, cả 4 tính năng dùng)

### 3.1 `AssistTask`: một lượt AI trả JSON đã kiểm tra

Package mới `dev.datagrok.core.assist`, chỉ phụ thuộc `AgentAdapter` nên test được bằng adapter giả:

```kotlin
interface AssistTask<O> {
    val name: String                       // "fix-sql", "nl-filter", "explain-advice", "chart-spec"
    fun system(): String                   // vai trò và luật riêng của tác vụ
    fun prompt(): String                   // ngữ cảnh + yêu cầu + mô tả định dạng JSON
    fun parse(json: JsonElement): O        // ném AssistInvalid(reasons) nếu sai
}

class AssistRunner(private val agent: () -> AgentAdapter?, private val timeout: Duration) {
    /** Một lượt; nếu JSON sai thì gửi lại một lần kèm lỗi validate, sau đó mới báo thất bại. */
    suspend fun <O> run(task: AssistTask<O>): AssistResult<O>
}
```

- **Trích JSON chắc chắn**: lấy khối ```` ```json ```` cuối cùng, nếu không có thì lấy object `{…}` cân bằng ngoặc
  cuối cùng. CLI agent hay thêm lời dẫn, nên không tin vào toàn bộ stdout.
- **Một lần sửa (repair)**: validate thất bại thì gửi lại kèm danh sách lỗi cụ thể (ví dụ "cột `total_amount` không
  tồn tại; các cột có: …"). Quá 1 lần thì dừng và báo lỗi rõ ràng, không lặp mãi.
- **Huỷ được**: huỷ coroutine thì dừng process hoặc HTTP, dùng sẵn cơ chế của adapter. Mọi điểm vào trong UI đều có Esc
  hoặc nút Stop.
- **Structured output**: giai đoạn đầu dùng chung một định dạng prompt cho mọi transport. Sau đó với Provider có thể bật
  JSON schema hoặc tool-use của từng API như một tối ưu, không phải yêu cầu bắt buộc.
- `AssistResult` = `Ok(value, agentName, elapsed)` | `Invalid(reasons)` | `Failed(message)` | `Unavailable`.

### 3.2 Chính sách dữ liệu gửi cho AI (bắt buộc với production)

Đã chốt (2026-10-30): **người dùng tự cấu hình trong Settings, mặc định là mức hạn chế**, để AI không đọc dữ liệu
nhạy cảm khi người dùng chưa chủ động cho phép.

| Mức | Gửi gì |
| --- | --- |
| `Off` | Không gì cả, ẩn mọi nút AI |
| `SchemaOnly` (**mặc định**) | Tên bảng/cột/kiểu, DDL, câu lệnh, thông báo lỗi, plan. **Không gửi giá trị dữ liệu nào** |
| `SchemaAndSamples` | Thêm giá trị mẫu (distinct values của cột ít giá trị, vài dòng kết quả), chỉ khi người dùng bật |

- **Settings → AI → "Dữ liệu được gửi cho AI"**: mức mặc định toàn cục `Settings.aiAccess = SchemaOnly`.
- **Từng kết nối có thể ghi đè** (`ConnectionConfig.aiAccess: AiAccess?`; `null` thì dùng mức toàn cục) trong
  Connection → Options. Kết nối gắn environment Production **không được vượt quá** `SchemaOnly` trừ khi người dùng đổi
  riêng cho kết nối đó và xác nhận cảnh báo.
- Hệ quả khi ở `SchemaOnly`: NL filter không có giá trị mẫu nên AI có thể hỏi lại nhiều hơn ("trạng thái 'đã thanh toán'
  là giá trị nào?"); chart AI chỉ thấy tên và kiểu cột. Đây là đánh đổi chấp nhận được, và UI gợi ý "Bật dữ liệu mẫu cho
  kết nối này" ngay tại chỗ.
- Áp dụng cho cả chat agent hiện có. Hiện tại, khi người dùng cho phép một run request, `AgentContext.describe(sql,
  outcome)` gửi tới 50 dòng kết quả. Đó là đồng ý từng lần nên vẫn giữ được ở `SchemaOnly`, nhưng thẻ xin phép phải
  ghi rõ "kết quả sẽ được gửi cho AI". Ở `Off` thì tắt hẳn.
- Mỗi popover AI có mục **"Dữ liệu sẽ gửi"** (mở rộng ra xem được nguyên văn prompt), kèm tên agent và vị trí của nó
  (CLI local hay URL provider).
- Settings có công tắc toàn cục "AI assist" để tắt hết.
- Log cục bộ vào `~/.datagrok/logs/`: tên tác vụ, agent, thời gian, kết quả (ok/invalid/failed), **không ghi nội dung**.

### 3.3 Ngữ cảnh schema theo phạm vi

Tách phần thuần của `AgentContext.describe` sang `core` thành `SchemaDigest`:

- `full(namespace, budget)`: như hiện tại, dùng cho chat.
- `forTables(refs, budget)`: chỉ các bảng câu lệnh dùng tới, kèm khoá chính, khoá ngoại, index, số dòng ước lượng. Ưu
  tiên các bảng này **trước** khi cắt theo budget. Lấy danh sách bảng bằng `SqlLexer` và logic tìm bảng của
  `SqlCompleter`.
- `nearMisses(identifier)`: tên bảng/cột gần đúng (fuzzy, cùng thuật toán với completion) để gợi ý.

### 3.4 Hiển thị proposal

Mọi đề xuất từ AI dùng chung một khung: tiêu đề, phần thay đổi (diff, filter, SQL), lời giải thích ngắn, nhãn agent,
và các nút **Áp dụng / Bỏ qua / Mở trong chat**. "Mở trong chat" chuyển ngữ cảnh sang `AgentPanel` để hỏi tiếp, nên
không phải làm hai hệ thống hội thoại.

## 4. Fix with AI

### Luồng

1. Câu lệnh lỗi → dòng lỗi trong output log có nút **Fix with AI** (và phím tắt, ví dụ ⌥⏎ khi caret nằm trên câu lỗi).
2. **Trước khi gọi AI**, chạy bộ sửa cục bộ: nếu lỗi là "column/relation does not exist" (nhận theo SQLState: PG
   `42703` và `42P01`, MySQL `1054` và `1146`…) và có tên gần đúng thì gợi ý ngay "Ý bạn là `user_id`?". Không tốn
   token, trả lời tức thì.
3. Không có sửa cục bộ, hoặc người dùng vẫn bấm AI: gửi `AssistTask<SqlFix>` gồm câu lệnh, SQLState, mã lỗi, thông báo,
   vị trí lỗi, dialect, `SchemaDigest.forTables`, `nearMisses`.
4. Kết quả `SqlFix(sql, explanation, confidence)` hiện dạng **diff inline** trên đoạn câu lệnh trong editor. Áp dụng thì
   thay đúng đoạn đó, và ⌘Z hoàn tác được. **Không tự chạy.**

### Kiểm tra trước khi hiển thị

- Phân loại lại bằng `SqlClassifier`: nếu câu gốc là Read mà câu sửa là Write/DDL thì **cảnh báo đỏ** và bỏ nút "Áp dụng
  và chạy".
- Câu sửa phải parse được qua `SqlSplitter` thành đúng **một** câu. Nếu AI trả nhiều câu thì loại.
- Các định danh trong câu sửa phải có trong schema đã biết; nếu không thì vẫn hiển thị nhưng đánh dấu "chưa xác minh".

### Thay đổi mã

- `QuerySession.execute` và `ConsoleTab.execute`: bắt `SQLException` thành
  `QueryError(message, sqlState, vendorCode, position?)`. Lấy `position` từ `PSQLException.serverErrorMessage.position`
  với PG; các engine khác để null.
- `OutputLine` thêm `error: QueryError?` và `statement: String?`. `RunOutcome` cũng mang theo để chat dùng được.
- `ConsoleView`: render nút trên dòng lỗi; diff dùng lại cơ chế highlight của `SqlEditor`.

### Test

- Unit: trích JSON, repair, phân loại Read→Write, bộ sửa cục bộ theo bảng SQLState (fixture lỗi thật của PG, MySQL,
  SQLite).
- Integration: dùng `-Pit.postgres`/`-Pit.mysql` sẵn có để kiểm tra việc lấy `QueryError` từ driver thật.

## 5. Ngôn ngữ tự nhiên → filter

Ví dụ: *"đơn hàng tuần trước trên 1 triệu chưa thanh toán"*.

### Nguyên tắc

AI trả về **`FilterGroup` dạng JSON**, không trả SQL. Nhờ vậy:

- Giá trị vẫn bind thành tham số qua `FilterCompiler`, không có SQL injection.
- Có thể validate từng điều kiện bằng code sẵn có: cột tồn tại, toán tử nằm trong `FilterOperator.forKind(kind)`, giá
  trị parse được bằng `FilterValues`.
- Kết quả hiện trong Builder để người dùng **đọc lại bằng ngôn ngữ của Builder** trước khi áp dụng.

### Định dạng

```json
{
  "combinator": "all",
  "conditions": [
    {"column": "created_at", "operator": "Between", "value": "startOfWeek-1w", "value2": "startOfWeek-1d"},
    {"column": "total", "operator": "Greater", "value": "1000000"},
    {"column": "status", "operator": "NotEquals", "value": "paid"}
  ],
  "assumptions": ["“1 triệu” = 1000000 trên cột total (VND)", "“chưa thanh toán” = status ≠ 'paid'"],
  "clarify": null
}
```

- `assumptions` hiển thị ngay dưới các dòng mới. Đây là chỗ người dùng phát hiện AI hiểu sai.
- `clarify`: khi mơ hồ (có cả `total` và `amount`), AI hỏi lại một câu kèm lựa chọn, thay vì đoán.
- `RawCondition` **bị cấm hẳn** (đã chốt 2026-10-30), không có tuỳ chọn bật: SQL ghép nguyên văn có thể gọi hàm có
  side effect hoặc chạy subquery nặng, và không validate được như dòng có cấu trúc. Parser của `AssistTask` từ chối mọi
  node không phải `FilterCondition`/`FilterGroup`. Câu hỏi không diễn đạt được bằng dòng có cấu trúc (ví dụ "khách có hơn
  3 đơn hàng") thì AI trả `clarify` giải thích lý do, và UI đưa nút "Mở trong chat" để agent viết truy vấn cho console.
  Có thể xem xét mở lại sau khi bộ eval ổn định.

### Ngữ cảnh gửi đi

Tên bảng; các cột với `ValueKind` và danh sách toán tử hợp lệ; enum hoặc giá trị distinct của cột có ít giá trị (≤ 30,
lấy bằng cơ chế gợi ý giá trị đã có, chỉ khi `SchemaAndSamples`); thời điểm hiện tại, múi giờ, ngày đầu tuần theo locale;
cú pháp ngày tương đối của Datagrok.

### Khoảng trống cần lấp trước

- `FilterValues`: thêm mốc `startOfWeek`, `startOfMonth`, `startOfYear` (đầu tuần theo locale hoặc setting) để "tuần
  trước" vẫn là **biểu thức tương đối, tự tính lại mỗi lần reload**, đúng tinh thần README. Có thể thêm toán tử
  `LastWeek`/`LastMonth` cho Builder thủ công.
- Đơn vị số kiểu "1 triệu", "1tr", "1M", "2.5k": để AI quy đổi, nhưng **hiển thị giá trị đã quy đổi** trong
  `assumptions`.

### UI

Ô nhập ✨ ở đầu filter bar (⌘I khi đang trong bảng). Kết quả **thêm** các dòng mới (đánh dấu màu, chưa bật) vào Builder.
Người dùng bấm ⏎ để áp dụng hoặc Esc để bỏ. Có lịch sử các câu đã hỏi. Ở chế độ SQL thì chuyển kết quả sang SQL như
khi đổi chế độ hiện nay.

### Đo chất lượng (bắt buộc trước khi phát hành)

- Bộ eval `core/src/test/resources/assist/nl-filter/*.json`: khoảng 60 câu (tiếng Việt và tiếng Anh; ngày, số, enum,
  phủ định, OR, NULL, câu mơ hồ) với `FilterGroup` mong đợi, trên schema mẫu `structure-it`.
- Chạy có điều kiện `-Pit.agent=anthropic` (giống `-Pit.postgres`). Báo tỉ lệ đúng hoàn toàn và tỉ lệ hỏi lại đúng lúc.
  Ngưỡng phát hành gợi ý: ≥ 90% đúng hoặc hỏi lại hợp lý, 0% sinh điều kiện cho cột không tồn tại.

## 6. EXPLAIN trực quan và gợi ý index

### 6.1 Phần tất định (không AI): model plan chung

```kotlin
data class PlanNode(
    val operation: String,           // "Seq Scan", "Hash Join", "SEARCH", "Index Seek"
    val relation: String?, val index: String?,
    val estimatedRows: Double?, val actualRows: Double?, val loops: Long?,
    val estimatedCost: Double?, val actualMillis: Double?,
    val details: Map<String, String>, // Filter, Join Cond, Sort Key, Rows Removed by Filter…
    val children: List<PlanNode>,
)
```

Parser `PlanParser` theo engine, có fallback về text thô nếu không parse được:

| Engine | Câu lệnh | Định dạng |
| --- | --- | --- |
| PostgreSQL, CockroachDB, DuckDB | `EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS])` | JSON |
| MySQL 8 | `EXPLAIN FORMAT=JSON`; Analyze: `EXPLAIN ANALYZE` (TREE) | JSON / text cây |
| MariaDB | `EXPLAIN FORMAT=JSON`; Analyze: `ANALYZE FORMAT=JSON` | JSON |
| SQLite | `EXPLAIN QUERY PLAN` (`id`, `parent`, `detail`) | Bảng, tự dựng cây |
| SQL Server | `SET SHOWPLAN_XML ON` / `STATISTICS XML` | XML |
| ClickHouse | `EXPLAIN json = 1, indexes = 1` | JSON |

Đổi `SqlDialect.explain(sql)` thành `explain(sql, analyze: Boolean): ExplainPlan(statements, format)`.

### 6.2 Hiển thị

- Tab kết quả mới **Plan** gồm cây gập/mở (hoặc flame chart ngang) và bảng chi tiết node đang chọn. Tab text thô vẫn giữ.
- Màu theo **chi phí riêng** (node trừ con) tính theo phần trăm tổng; node ≥ 30% tô đậm.
- **Insight tất định**, chưa cần AI: ước lượng lệch thực tế > 10 lần; Seq Scan trên bảng lớn có Filter loại nhiều dòng;
  Sort/Hash tràn ra đĩa; Nested Loop có `loops` lớn; SQLite `SCAN` không index. Đây là giá trị chính, chạy được cả khi
  không có AI.

### 6.3 Explain Analyze: an toàn trước

`EXPLAIN ANALYZE` **thực thi** câu lệnh:

Đã chốt (2026-10-30): **chỉ cho câu đọc**.

- Câu đọc (`SqlClassifier` trả `Read`): chạy bình thường, có timeout theo `queryTimeoutSeconds`, huỷ được.
- Câu ghi, DDL và CTE có DML: nút Explain Analyze bị tắt, kèm tooltip "Chỉ chạy được với câu đọc". Chỉ có Explain thường
  (plan ước lượng). Lý do: kể cả khi bọc `BEGIN … ROLLBACK`, việc ghi vẫn thực sự xảy ra trên server trong lúc chạy
  (khoá dòng, trigger hay hàm gửi ra ngoài, sequence tăng không lùi lại được).
- Kiểm tra lần nữa ngay trước khi gửi lên server, không chỉ dựa vào trạng thái nút, vì câu lệnh tại caret có thể đã đổi.
- Có thể xem xét mở cho câu ghi ở giai đoạn sau, khi có nhu cầu thật.

### 6.4 AI: giải thích và gợi ý index

`AssistTask<PlanAdvice>` nhận plan đã rút gọn (bỏ các node không đáng kể), insight tất định, DDL và index hiện có của
các bảng liên quan, số dòng ước lượng. Kết quả gồm lời giải thích ngắn bằng ngôn ngữ người dùng và danh sách
`IndexSuggestion(table, columns, include?, where?, reason)`.

Kiểm tra trước khi hiển thị: cột tồn tại; **không trùng** index đã có (so prefix); sinh `CREATE INDEX` bằng **code** theo
dialect từ cấu trúc đó (không lấy chuỗi SQL của AI), với PG dùng `CONCURRENTLY`. Nút "Mở trong console", không có nút
chạy thẳng.

Giai đoạn sau: kiểm tra giả lập bằng HypoPG nếu extension có sẵn ("plan sẽ thế nào nếu có index này").

## 7. Biểu đồ nhanh từ kết quả

### 7.1 Phần tất định

- **Kiểu cột**: dựa vào `jdbcType` và thử parse giá trị (số, ngày). Không dựa vào tên cột.
- **Tự chọn trục** (heuristic, không AI):
  - 1 cột thời gian + ≥ 1 cột số → line;
  - 1 cột text ít giá trị (≤ 30) + 1 cột số → bar ngang, sắp giảm dần;
  - như trên nhưng ≤ 8 giá trị và số đều dương → cho chọn thêm pie/donut;
  - 2 cột số → scatter;
  - thêm 1 cột text ít giá trị → tách series.
- **Dữ liệu**: mặc định vẽ từ các dòng đã tải, kèm banner rõ ràng "Biểu đồ từ 500 dòng đầu, kết quả còn nữa" khi
  `truncated`. Nút **"Tổng hợp trên server"**: sinh `SELECT x, agg(y) FROM (<câu gốc>) t GROUP BY x ORDER BY x` theo
  dialect, chạy qua `ConsoleTab.run` (được phân loại Read).
- **Xuất**: copy ảnh vào clipboard, lưu PNG/SVG.
- Spec chart lưu cùng `ResultSetView`, nên chạy lại câu lệnh thì chart cập nhật.

### 7.2 AI

Ô ✨ "doanh thu theo tháng, tách theo vùng" → `AssistTask<ChartSpec>`:

```json
{"type": "line", "x": "created_at", "bucket": "month", "y": [{"column": "total", "agg": "sum"}], "series": "region"}
```

Validate cột, kiểu và phép tổng hợp. Nếu cần gom nhóm mà dữ liệu đang bị cắt thì tự đề xuất "Tổng hợp trên server". AI
chỉ nhận **tên cột, kiểu và vài giá trị mẫu**, không nhận toàn bộ kết quả.

### 7.3 Thư viện

Khảo sát ngày 2026-10-30. Yêu cầu: chính chủ (JetBrains/Kotlin) nếu có, license cho phép dùng thương mại, chạy trên
Compose Desktop.

| Thư viện | Chủ sở hữu | License | Desktop | Phiên bản / tình trạng | Ghi chú |
| --- | --- | --- | --- | --- | --- |
| **[Lets-Plot Compose](https://github.com/JetBrains/lets-plot-compose)** | **JetBrains** | **MIT** | Có (macOS, Windows, Linux) | 3.2.2, cập nhật gần nhất ~4 tháng trước; gắn nhãn *Experimental*, *incubator* | Grammar of graphics (Lets-Plot core cũng MIT, chính chủ JetBrains). Phụ thuộc Compose 1.11.1, Lets-Plot Multiplatform 4.11.0, kotlinx-datetime. Xuất ảnh raster qua `platf-awt` |
| [Koala Plot](https://github.com/KoalaPlot/koalaplot-core) | Cộng đồng | MIT | Có | 0.12.x, "developmental", API 0.x có thể vỡ giữa các bản | Compose thuần nên dễ theme; nhiều loại chart (line, bar, pie, heatmap…) |
| [Vico](https://github.com/patrykandpatrick/vico) | Cộng đồng | Apache-2.0 (vẫn dùng thương mại được) | Có target JVM | Hoạt động mạnh, nhưng bản mới nhất là `-next` (pre-release); pie còn experimental | Thiên về Android |

Kandy (DSL Kotlin của JetBrains) dựng trên Lets-Plot và hướng tới notebook, nên không cần thêm cho app desktop.

**Đề xuất: spike Lets-Plot Compose 1–2 ngày trước khi quyết định**, vì đây là lựa chọn duy nhất vừa chính chủ JetBrains
vừa MIT. Spike phải trả lời:

1. Chạy được với Compose `1.12.1`, Jewel `0.41.0` và JBR 25 của repo không (thư viện build trên Compose 1.11.1)?
2. Dung lượng installer tăng bao nhiêu (Lets-Plot core khá lớn)?
3. Theme theo Jewel sáng/tối được không, và tooltip/hover có đủ dùng không?
4. Hiệu năng với khoảng 100k điểm (có cần downsample LTTB trước khi đưa vào)?
5. Xuất PNG và SVG; nhóm `org.jetbrains.lets-plot` và các phụ thuộc kéo theo có qua được `bundledLicenses` không?

Nhãn *Experimental* là rủi ro: nên bọc thư viện sau một interface `ChartRenderer` trong `app/.../chart`, để có thể
thay bằng bản tự vẽ mà không đụng tới `ChartSpec`, heuristic hay phần AI. Nếu spike không đạt câu 1 hoặc 3 thì **tự vẽ
bằng Compose `Canvas`** (line, bar, scatter, pie, khoảng 1 tuần). Koala Plot và Vico là phương án dự phòng, không ưu
tiên vì không chính chủ và API chưa ổn định.

Một điểm cộng của Lets-Plot: spec của nó là dữ liệu khai báo (kiểu ggplot), gần với `ChartSpec` mà AI sinh ra, nên sau
này có thể mở rộng sang histogram, box plot, facet mà không phải viết thêm renderer.

## 8. Lộ trình

| Giai đoạn | Nội dung | Ước tính | Điều kiện xong |
| --- | --- | --- | --- |
| **P0** | `AssistTask`/`AssistRunner`, `AiAccess` cho từng kết nối, `SchemaDigest` tách xuống core, `QueryError` có cấu trúc | 4–5 ngày | Unit test với adapter giả; chat agent hiện có tôn trọng `AiAccess` |
| **P1** | Fix with AI + bộ sửa cục bộ theo SQLState | 3–4 ngày | Diff, hoàn tác, cảnh báo Read→Write; IT với PG/MySQL |
| **P2** | `PlanNode` + parser PG/MySQL/SQLite/DuckDB, tab Plan, insight tất định, Explain Analyze cho câu đọc | 7–8 ngày | Fixture plan thật cho mỗi engine; fallback text |
| **P2b** | Gợi ý index bằng AI + SQL Server/ClickHouse/MariaDB parser | 3–4 ngày | Không trùng index; DDL sinh bằng code |
| **P3** | Mốc `startOfWeek/Month/Year`, NL → filter, bộ eval | 8–10 ngày | Eval ≥ 90%, 0 cột ảo |
| **P4** | Spike Lets-Plot Compose, rồi chart (Lets-Plot hoặc tự vẽ) + heuristic + tổng hợp trên server + ChartSpec AI | 8–10 ngày | Theme sáng/tối, 100k điểm mượt, xuất PNG/SVG |

Tổng khoảng 7–8 tuần cho một người, có thể song song P2 và P4 vì không đụng cùng file.

## 9. Rủi ro và cách giảm

| Rủi ro | Giảm thiểu |
| --- | --- |
| Rò rỉ dữ liệu nhạy cảm lên cloud | Mặc định `SchemaOnly` cho mọi kết nối, ghi đè được trong Settings và từng kết nối, xem trước nội dung gửi, hỗ trợ model local qua OpenAI-compatible (Ollama, LM Studio) |
| AI bịa cột hoặc bảng | Validate với schema; repair 1 lần; không bao giờ hiển thị như đã xác minh |
| AI sinh lệnh ghi hoặc nguy hiểm | Phân loại lại bằng `SqlClassifier`; DDL index sinh bằng code; không có nút chạy thẳng |
| CLI agent chậm hoặc trả lời lan man | Timeout, Esc huỷ, trích JSON chắc chắn; gợi ý Provider mode cho tính năng inline |
| Người dùng tin nhầm filter hoặc chart | `assumptions` hiển thị bắt buộc; banner "N dòng đầu"; Builder luôn hiển thị điều kiện thật |
| Chất lượng trôi khi đổi model | Bộ eval gated `-Pit.agent`, chạy trước mỗi lần đổi model mặc định |
| Không có agent nào | Ẩn nút AI, giữ phần tất định (sửa cục bộ, plan tree, chart heuristic) chạy đầy đủ |

## 10. Câu hỏi mở

1. ~~Mức `AiAccess` mặc định~~: đã chốt, cấu hình trong Settings, mặc định `SchemaOnly` (mục 3.2).
2. ~~Explain Analyze cho câu ghi~~: đã chốt, chỉ cho câu đọc (mục 6.3).
3. Chart: chờ kết quả spike Lets-Plot Compose (mục 7.3).
4. ~~`RawCondition` trong NL filter~~: đã chốt, cấm hẳn (mục 5).
