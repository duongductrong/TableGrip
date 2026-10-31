# Kế hoạch triển khai: Biểu đồ nhanh từ kết quả

Kế hoạch chi tiết cho giai đoạn **P4** trong [ai-assist.md](ai-assist.md) (mục 7), dựa trên mã nguồn tại commit
`db5c2d1`, ngày 2026-10-31. Mục 7 của ai-assist.md vẫn là nguồn cho các quyết định sản phẩm; tài liệu này chia việc
thành từng bước, chỉ rõ file nào cần sửa và khi nào thì coi là xong.

> **Trạng thái (2026-10-31):** C0–C5 đã làm xong. Những điểm khác thiết kế:
>
> - **Renderer tự vẽ bằng Compose Canvas, không dùng Lets-Plot** (kết quả spike C0, xem mục 3.3).
> - Tổng hợp trên server giữ nguyên câu gốc (kể cả `LIMIT` của nó) làm subquery, không bỏ `LIMIT` ngoài cùng: kết
>   quả bị cắt là do `maxResultRows`, không phải do `LIMIT` người dùng viết.
> - Bản đầu tiên chỉ đổi tab kết quả của console sang `ResultView` (Grid/JSON/Chart). `TableDataTab` vẫn dùng
>   `showJson`, vì nó đã có thuộc tính `view` (Data/Structure) và chưa có chart.

## 1. Hiện trạng

| Thứ | Ở đâu | Liên quan tới chart |
| --- | --- | --- |
| `QueryResult.Rows` | `core/.../driver/QueryResult.kt` | Giá trị là `String?`, cột có `jdbcType`, `isNumeric`. Chưa có khái niệm cột thời gian |
| `ResultSetView` | `app/.../console/ConsoleTab.kt` | Có phân trang (`page`, `pageSize`), `pinned`, `showJson: Boolean` |
| Chuyển view | `ResultFooter` trong `ConsoleView.kt`, `TableDataView.kt` | `ViewChipGroup` với hai chip Grid / JSON |
| `TableDataTab` | `app/.../table/` | Cũng có `showJson`, nhưng dữ liệu là từng trang lấy từ server |
| `AssistTask`, `SchemaDigest` | `core/.../assist/` | P0 đã xong, dùng lại được cho phần AI |
| `SqlDialect` | `core/.../dialect/SqlDialect.kt` | Có `selectPage`, `countRows`… nhưng chưa có hàm gom nhóm theo thời gian |
| License | `bundledLicenses` trong `app/build.gradle.kts` | Build sẽ fail nếu thư viện mới chưa được review license |

## 2. Phạm vi

**Làm trong đợt này:** chart cho tab kết quả của console, gồm line, bar, area, scatter, pie. Có tự chọn trục,
panel chỉnh trục, banner khi dữ liệu bị cắt, tổng hợp trên server, xuất PNG/SVG/clipboard, và ChartSpec do AI sinh.

**Để sau:** chart trong Table viewer (dữ liệu ở đó chỉ là một trang nên cần tổng hợp trên server ngay từ đầu),
histogram, box plot, facet, dashboard nhiều chart.

## 3. Thiết kế

### 3.1 Core (`dev.datagrok.core.chart`), Kotlin thuần, có unit test

```kotlin
enum class ColumnKind { Numeric, Temporal, Category, Other }

/** Kết quả quét một cột: kiểu suy ra và số giá trị khác nhau (dừng đếm khi vượt ngưỡng). */
data class ColumnProfile(val index: Int, val name: String, val kind: ColumnKind, val distinct: Int, val nulls: Int)

@Serializable
data class ChartSpec(
    val type: ChartType,              // Line, Bar, Area, Scatter, Pie
    val x: String,
    val y: List<Measure>,             // Measure(column, agg: None|Sum|Avg|Min|Max|Count)
    val series: String? = null,
    val bucket: TimeBucket? = null,   // Hour, Day, Week, Month, Quarter, Year
    val horizontal: Boolean = false,
)
```

- `ColumnProfiler.profile(rows)`: suy kiểu từ `jdbcType` trước (DATE/TIME/TIMESTAMP → Temporal, `isNumeric` →
  Numeric). Với cột text thì thử parse một mẫu khoảng 200 giá trị: ≥ 95% parse được thì nhận kiểu đó. Không suy kiểu
  từ tên cột.
- `ChartSuggest.suggest(profiles): List<ChartSpec>` cài đặt các luật của ai-assist.md 7.1, phương án đầu là mặc định.
  Không có phương án nào hợp thì trả danh sách rỗng, UI báo "Không tự chọn được trục".
- `ChartData.build(rows, spec): ChartSeries`: parse giá trị, gom theo `bucket`, tổng hợp, sắp xếp, tách series
  (giữ top 10 series, phần còn lại gộp vào "Khác"). Khi số điểm vượt khoảng 5k thì downsample bằng LTTB. Dòng nào không
  parse được thì đếm lại để hiển thị "N dòng bị bỏ qua".
- `ChartSpec.validate(columns)` trả danh sách lỗi cụ thể. UI và phần AI cùng dùng hàm này.

### 3.2 Tổng hợp trên server

- `SqlDialect.timeBucket(expr, bucket)`: PG/DuckDB dùng `date_trunc`, MySQL/MariaDB `DATE_FORMAT`, SQLite `strftime`,
  SQL Server `DATETRUNC` (2022) và fallback `DATEADD/DATEDIFF`, ClickHouse `toStartOfMonth`… Engine nào chưa hỗ trợ thì
  trả `null`, và UI ẩn nút.
- `ChartQuery.aggregate(dialect, sql, spec)` sinh `SELECT x, agg(y)… FROM (<câu gốc>) t GROUP BY … ORDER BY …`. Trước
  khi bọc thì bỏ dấu `;` cuối và mệnh đề `LIMIT` ngoài cùng. Câu gốc phải là một câu `SELECT` duy nhất (kiểm bằng
  `SqlClassifier`), nếu không thì không cho dùng.
- Câu sinh ra chạy qua `ConsoleTab.run`, nên vẫn đi qua read-only và phân loại như mọi câu khác. Kết quả mở ra như một
  tab kết quả mới, ở sẵn view Chart với spec đã đổi sang cột đã tổng hợp (agg = None).

### 3.3 App (`app/.../chart`)

- `ChartRenderer`: interface `@Composable fun Chart(frame, colors, modifier)` và `fun export(frame, colors, format, w, h)`.
  Bản cài đặt là `CanvasChartRenderer`: `ChartPlot` tính bố cục (trục, tick, legend) một lần cho mỗi kích thước, rồi
  dùng chung cho việc vẽ, hover tooltip và xuất ảnh. PNG vẽ qua `Surface` của Skia, SVG qua `SVGCanvas`, nên ảnh
  xuất ra giống hệt trên màn hình.
- **Kết quả spike C0:** Lets-Plot Compose 3.2.2 (MIT, khoảng 9 MB jar) resolve và compile được, nhưng **crash khi
  vẽ** với Compose 1.12.1: `SkiaContext2d` gọi constructor `Matrix33(float[])`, mà bản Skiko đi kèm Compose 1.12.1
  đã đổi thành private (`IllegalAccessError`). Bản Swing (`platf-awt`) thì chạy được, nhưng phải nhúng qua
  `SwingPanel` và sẽ vẽ đè lên các popup Compose. Vì vậy chuyển sang phương án dự phòng đã ghi trong ai-assist.md
  7.3: tự vẽ. Không thêm phụ thuộc nào, nên `bundledLicenses` giữ nguyên.
- **Không khoá UI** (bổ sung 2026-10-31): đọc kiểu cột, dựng `ChartFrame` và dựng bố cục `ChartPlot` đều chạy trên
  `ChartDispatcher` (`Dispatchers.Default.limitedParallelism(2)`), kiểm tra cờ huỷ sau mỗi 4096 dòng. Khi resize,
  bố cục chỉ dựng lại sau khi kích thước đứng yên 120 ms; trong lúc chờ, chart cũ vẫn hiện, làm mờ đi, kèm vòng quay.
  Nhãn được cắt còn 80 ký tự trước khi đo chữ: với 50 nhãn JSON dài 4 KB, thời gian dựng bố cục giảm từ khoảng 96 ms
  xuống khoảng 9 ms, và bước này không còn chạy trên UI thread. Hover vẽ trên một lớp riêng nên không vẽ lại chart.
  Copy và lưu ảnh cũng render ở luồng nền.
- `ResultSetView.showJson: Boolean` → `view: ResultView` (`Grid`, `Json`, `Chart`) và `chart: ChartSpec?`. Sửa cả
  `TableDataTab` để hai nơi dùng chung kiểu (Table viewer chưa có chip Chart).
- `ResultChartView`: vùng chart, cột phải là `ChartEditor` (chọn loại, X, Y + phép tổng hợp, series, bucket), banner
  trên cùng khi `truncated` ("Biểu đồ từ 500 dòng đầu" kèm nút **Tổng hợp trên server**).
- Chart vẽ từ **tất cả dòng đã tải**, không phải trang đang xem. Banner nói rõ điều này.
- `ResultFooter`: thêm chip **Chart**. Ở view Chart thì ẩn điều khiển phân trang.
- Khi chạy lại câu lệnh: nếu tab mới có đủ các cột mà spec cũ dùng thì giữ spec, nếu thiếu thì gợi ý lại.

### 3.4 AI

- `ChartSpecTask : AssistTask<ChartSpec>` trong `core/.../assist/`: prompt gồm tên cột, kiểu, `distinct`. Chỉ khi
  `AiAccess = SchemaAndSamples` mới gửi thêm vài giá trị mẫu. `parse` gọi `ChartSpec.validate`, nên lỗi sẽ đi vào vòng
  repair có sẵn của `AssistRunner`.
- UI: ô ✨ phía trên `ChartEditor`. Kết quả hiện như một proposal (spec mới và các `assumptions`). Người dùng bấm Áp dụng
  thì mới thay chart. Spec cần gom nhóm mà dữ liệu đang bị cắt thì proposal kèm luôn nút Tổng hợp trên server.
- Khi `AiAccess = Off` hoặc không có agent nào thì ẩn ô ✨, phần còn lại vẫn chạy đầy đủ.

## 4. Lộ trình

| Bước | Nội dung | Ước tính | Điều kiện xong |
| --- | --- | --- | --- |
| **C0** | Spike Lets-Plot Compose trên nhánh riêng, trả lời 5 câu ở ai-assist.md 7.3 | 1–2 ngày | Ghi kết luận vào mục 7.3 và chốt renderer |
| **C1** | `ColumnProfiler`, `ChartSpec`, `ChartSuggest`, `ChartData` (bucket, agg, series, LTTB) | 2 ngày | Unit test cho từng luật heuristic, cho parse ngày nhiều định dạng, cho null/NaN |
| **C2** | `ResultView` enum, `ChartRenderer` + bản cài đặt đã chọn, `ResultChartView`, `ChartEditor`, banner | 3 ngày (thêm khoảng 3 ngày nếu phải tự vẽ bằng Canvas) | Theme sáng/tối, tooltip khi hover, 100k dòng không giật |
| **C3** | `timeBucket` cho từng dialect, `ChartQuery.aggregate`, nút Tổng hợp trên server | 1,5 ngày | Unit test SQL sinh ra cho từng dialect; IT chạy trên PG/MySQL/SQLite/DuckDB |
| **C4** | Xuất PNG/SVG/clipboard, giữ spec khi chạy lại | 1 ngày | Ảnh xuất ra theo đúng theme đang dùng |
| **C5** | `ChartSpecTask` + ô ✨ + proposal | 1,5 ngày | Test với adapter giả: JSON sai thì repair, cột ảo thì bị từ chối |

Tổng khoảng 10 ngày nếu dùng Lets-Plot, khoảng 13 ngày nếu phải tự vẽ. C1 không phụ thuộc kết quả spike nên làm song
song với C0 được.

## 5. Kiểm thử

- `core`: test thuần cho profiler, heuristic, gom nhóm, LTTB, SQL tổng hợp theo dialect, `ChartSpecTask`.
- IT (gated như các IT hiện có): tổng hợp trên server với dữ liệu seed thật.
- Thủ công: kết quả rỗng, một dòng, toàn null, cột ngày dạng text, 100k dòng, đổi theme khi đang mở chart.

## 6. Câu hỏi mở

1. Renderer: chờ kết quả C0.
2. Tổng hợp trên server mở kết quả ra **tab mới** (đề xuất, vì giữ được dữ liệu gốc) hay thay vào tab hiện tại?
3. Có cần chart cho Table viewer trong đợt này không? Đề xuất để sau, vì ở đó cần tổng hợp trên server ngay từ đầu.
