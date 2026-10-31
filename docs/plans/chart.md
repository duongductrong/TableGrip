# Implementation plan: Quick charts from results

Detailed plan for phase **P4** in [ai-assist.md](ai-assist.md) (section 7), based on the source code at commit
`db5c2d1`, dated 2026-10-31. Section 7 of ai-assist.md remains the source for product decisions; this document breaks
the work into steps, states which files need to change, and defines when each step counts as done.

> **Status (2026-10-31):** C0–C5 are done. Differences from the design:
>
> - **The renderer is drawn directly with Compose Canvas, not Lets-Plot** (result of the C0 spike, see section 3.3).
> - Server-side aggregation keeps the original statement (including its own `LIMIT`) as a subquery and does not strip
>   the outermost `LIMIT`: a truncated result is caused by `maxResultRows`, not by a `LIMIT` the user wrote.
> - The first version only switches the console result tab to `ResultView` (Grid/JSON/Chart). `TableDataTab` still uses
>   `showJson`, because it already has a `view` property (Data/Structure) and has no chart yet.

## 1. Current state

| Item | Where | Relation to charts |
| --- | --- | --- |
| `QueryResult.Rows` | `core/.../driver/QueryResult.kt` | Values are `String?`, columns have `jdbcType` and `isNumeric`. There is no concept of a time column yet |
| `ResultSetView` | `app/.../console/ConsoleTab.kt` | Has pagination (`page`, `pageSize`), `pinned`, `showJson: Boolean` |
| View switching | `ResultFooter` in `ConsoleView.kt`, `TableDataView.kt` | `ViewChipGroup` with two chips, Grid / JSON |
| `TableDataTab` | `app/.../table/` | Also has `showJson`, but its data is one page at a time fetched from the server |
| `AssistTask`, `SchemaDigest` | `core/.../assist/` | P0 is done and can be reused for the AI part |
| `SqlDialect` | `core/.../dialect/SqlDialect.kt` | Has `selectPage`, `countRows`, etc., but no function for grouping by time yet |
| License | `bundledLicenses` in `app/build.gradle.kts` | The build fails if a new library has not had its license reviewed |

## 2. Scope

**In this round:** charts for the console result tab, including line, bar, area, scatter, and pie. This includes
automatic axis selection, an axis editing panel, a banner when data is truncated, server-side aggregation, PNG/SVG/clipboard
export, and an AI-generated ChartSpec.

**Later:** charts in the Table viewer (its data is only one page, so it needs server-side aggregation from the start),
histogram, box plot, facets, multi-chart dashboards.

## 3. Design

### 3.1 Core (`dev.datagrok.core.chart`), pure Kotlin, with unit tests

```kotlin
enum class ColumnKind { Numeric, Temporal, Category, Other }

/** Result of scanning a column: the inferred kind and the number of distinct values (counting stops past a threshold). */
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

- `ColumnProfiler.profile(rows)`: infers the kind from `jdbcType` first (DATE/TIME/TIMESTAMP → Temporal, `isNumeric` →
  Numeric). For text columns it tries to parse a sample of about 200 values: if ≥ 95% parse, it accepts that kind. It
  never infers the kind from the column name.
- `ChartSuggest.suggest(profiles): List<ChartSpec>` implements the rules in ai-assist.md 7.1, and the first option is
  the default. If no option fits, it returns an empty list and the UI shows "Couldn't pick axes automatically".
- `ChartData.build(rows, spec): ChartSeries`: parses values, groups by `bucket`, aggregates, sorts, and splits into
  series (keeps the top 10 series and merges the rest into "Other"). When the number of points exceeds about 5k, it
  downsamples with LTTB. Rows that cannot be parsed are counted so the UI can show "N rows skipped".
- `ChartSpec.validate(columns)` returns a list of specific errors. The UI and the AI part both use this function.

### 3.2 Server-side aggregation

- `SqlDialect.timeBucket(expr, bucket)`: PG/DuckDB use `date_trunc`, MySQL/MariaDB `DATE_FORMAT`, SQLite `strftime`,
  SQL Server `DATETRUNC` (2022) with a `DATEADD/DATEDIFF` fallback, ClickHouse `toStartOfMonth`, etc. An engine that
  does not support it returns `null`, and the UI hides the button.
- `ChartQuery.aggregate(dialect, sql, spec)` generates `SELECT x, agg(y)… FROM (<original statement>) t GROUP BY … ORDER BY …`.
  Before wrapping, it removes the trailing `;` and the outermost `LIMIT` clause. The original statement must be a single
  `SELECT` statement (checked with `SqlClassifier`); otherwise the feature is not available.
- The generated statement runs through `ConsoleTab.run`, so it still goes through the read-only check and
  classification like any other statement. The result opens as a new result tab, already on the Chart view, with the
  spec switched to the aggregated columns (agg = None).

### 3.3 App (`app/.../chart`)

- `ChartRenderer`: an interface with `@Composable fun Chart(frame, colors, modifier)` and `fun export(frame, colors, format, w, h)`.
  The implementation is `CanvasChartRenderer`: `ChartPlot` computes the layout (axes, ticks, legend) once per size, then
  shares it between drawing, hover tooltips, and image export. PNG is drawn through a Skia `Surface` and SVG through
  `SVGCanvas`, so the exported image is identical to what is on screen.
- **C0 spike result:** Lets-Plot Compose 3.2.2 (MIT, about 9 MB jar) resolves and compiles, but **crashes when
  drawing** with Compose 1.12.1: `SkiaContext2d` calls the `Matrix33(float[])` constructor, which in the Skiko version
  bundled with Compose 1.12.1 has become private (`IllegalAccessError`). The Swing build (`platf-awt`) works, but it has
  to be embedded through `SwingPanel` and would paint over Compose popups. So we switched to the fallback recorded in
  ai-assist.md 7.3: drawing it ourselves. No dependency is added, so `bundledLicenses` stays unchanged.
- **Not blocking the UI** (added 2026-10-31): reading column kinds, building the `ChartFrame`, and building the
  `ChartPlot` layout all run on `ChartDispatcher` (`Dispatchers.Default.limitedParallelism(2)`), checking the
  cancellation flag every 4096 rows. On resize, the layout is rebuilt only after the size has been still for 120 ms;
  while waiting, the old chart stays visible, dimmed, with a spinner. Labels are cut to 80 characters before measuring
  text: with 50 JSON labels of 4 KB each, layout time dropped from about 96 ms to about 9 ms, and this step no longer
  runs on the UI thread. Hover is drawn on a separate layer, so it does not redraw the chart. Copying and saving the
  image also render on a background thread.
- `ResultSetView.showJson: Boolean` → `view: ResultView` (`Grid`, `Json`, `Chart`) and `chart: ChartSpec?`. `TableDataTab`
  is changed too so both places share the same type (the Table viewer has no Chart chip yet).
- `ResultChartView`: the chart area, with the right column being `ChartEditor` (choose type, X, Y + aggregation,
  series, bucket), and a banner at the top when `truncated` ("Chart from the first 500 rows" with a **Aggregate on server** button).
- The chart is drawn from **all loaded rows**, not the page currently being viewed. The banner says this explicitly.
- `ResultFooter`: adds a **Chart** chip. In the Chart view the pagination controls are hidden.
- When the statement is re-run: if the new tab has all the columns the old spec uses, the spec is kept; if any are
  missing, suggestions are generated again.

### 3.4 AI

- `ChartSpecTask : AssistTask<ChartSpec>` in `core/.../assist/`: the prompt contains column names, kinds, and `distinct`.
  A few sample values are sent only when `AiAccess = SchemaAndSamples`. `parse` calls `ChartSpec.validate`, so errors go
  into the existing repair loop of `AssistRunner`.
- UI: a ✨ box above `ChartEditor`. The result appears as a proposal (the new spec and its `assumptions`). The chart only
  changes when the user clicks Apply. If the spec needs grouping while the data is truncated, the proposal also includes
  an Aggregate on server button.
- When `AiAccess = Off` or no agent is available, the ✨ box is hidden and everything else keeps working in full.

## 4. Roadmap

| Step | Content | Estimate | Done when |
| --- | --- | --- | --- |
| **C0** | Spike Lets-Plot Compose on a separate branch, answer the 5 questions in ai-assist.md 7.3 | 1–2 days | Conclusion recorded in section 7.3 and renderer decided |
| **C1** | `ColumnProfiler`, `ChartSpec`, `ChartSuggest`, `ChartData` (bucket, agg, series, LTTB) | 2 days | Unit tests for each heuristic rule, for parsing dates in multiple formats, and for null/NaN |
| **C2** | `ResultView` enum, `ChartRenderer` + the chosen implementation, `ResultChartView`, `ChartEditor`, banner | 3 days (about 3 more days if it has to be drawn with Canvas) | Light/dark theme, hover tooltip, 100k rows without stutter |
| **C3** | `timeBucket` for each dialect, `ChartQuery.aggregate`, Aggregate on server button | 1.5 days | Unit tests for the generated SQL per dialect; IT running on PG/MySQL/SQLite/DuckDB |
| **C4** | Export PNG/SVG/clipboard, keep the spec on re-run | 1 day | Exported image matches the theme in use |
| **C5** | `ChartSpecTask` + ✨ box + proposal | 1.5 days | Test with a fake adapter: invalid JSON triggers repair, a made-up column is rejected |

About 10 days in total if Lets-Plot is used, about 13 days if we have to draw it ourselves. C1 does not depend on the
spike result, so it can run in parallel with C0.

## 5. Testing

- `core`: pure tests for the profiler, heuristics, grouping, LTTB, per-dialect aggregation SQL, and `ChartSpecTask`.
- IT (gated like the existing ITs): server-side aggregation with real seed data.
- Manual: empty result, a single row, all null, a date column stored as text, 100k rows, changing theme while a chart is open.

## 6. Open questions

1. Renderer: waiting for the C0 result.
2. Should server-side aggregation open the result in a **new tab** (proposed, because it keeps the original data) or
   replace the current tab?
3. Do we need a chart for the Table viewer in this round? Proposed for later, because it needs server-side aggregation from the start.
