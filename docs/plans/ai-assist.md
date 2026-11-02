# AI Assist plan: error fixing, natural-language filters, visual EXPLAIN, quick charts

Assessment and production-ready implementation design for 4 AI features, based on the source code at commit `da09586`,
dated 2026-10-30.

> **Status (2026-10-31):** P0, P1 and P4 (chart, see [chart.md](chart.md)) are done. Differences from the design: the fix is shown as a diff in a card on the
> Output tab (not an inline diff in the editor), and there is no dedicated shortcut for Fix yet.

## 1. Summary

| Feature | User value | Part that needs no AI | Main risk | Effort |
| --- | --- | --- | --- | --- |
| **Fix with AI** | High: SQL errors happen every day | Suggesting near-match table/column names | AI changes the meaning of the statement, turns a SELECT into a write statement | ~1 week |
| **NL → filter** | Very high, a differentiator for marketing | — | Misreads the column or the time range, the user trusts a wrong result | ~2 weeks |
| **Visual EXPLAIN** | High for backend devs and DBAs | ~80%: parse the plan, draw the tree, highlight heavy nodes | `EXPLAIN ANALYZE` executes the real statement | ~2–2.5 weeks |
| **Quick chart** | Medium–high: avoids exporting to Excel | ~80%: auto-pick axes, draw the chart | The chart is built only from the first N rows, so it is easy to misread | ~2 weeks |

The overarching lesson: **AI only proposes, it never executes on its own**. Every AI output becomes a *proposal* for the
user to review: a statement diff, filter rows that are not yet applied, a suggested `CREATE INDEX` statement, a chart
spec. When the user accepts, the proposal goes through the existing execution path (`ConsoleTab.run`, `SqlClassifier`,
read-only, confirm writes). That way there is no shortcut around the safety layer built in M2.

Suggested order: **Fix with AI** (build the shared infrastructure, low risk) → **EXPLAIN** → **NL filter** → **Chart**.

## 2. Relevant current state

| Item | Current state | Consequence for the 4 features |
| --- | --- | --- |
| `AgentAdapter.send` (`core/.../agent/Agent.kt`) | Chat only: free-form text stream, no tools, no structured output | Need to add a *single-turn task layer that returns validated JSON* on top |
| CLI agent (`CliAgentAdapter`) | Spawns one process per turn, timeout set by preset | Latency around 3–20 seconds. Fine for Fix and Explain, a bit slow for inline filters |
| Provider (`ProviderAgentAdapter`) | Anthropic and OpenAI-compatible, SSE streaming, `MAX_TOKENS = 16_000` | Faster, structured output can be enabled later |
| `AgentContext` (app) | Sends the default schema (40k character budget), the open console, up to 50 result rows | The formatting part is pure logic, so it should move down to `core` for reuse and testing |
| `OutputLine(time, text, isError)` | An error is left as just the string `"$message\n  in: …"` | SQLState, vendor error code and error position are lost. Need to store structured errors |
| `SqlDialect.explain` | PG/MySQL: text `EXPLAIN …`, SQLite: `EXPLAIN QUERY PLAN`, SQL Server: `SHOWPLAN_TEXT` | Cannot be parsed into a tree. Need a JSON/XML variant for each engine |
| `SqlClassifier` | Already treats `EXPLAIN ANALYZE` and CTEs with DML as Write | Reusable for Explain Analyze |
| `FilterGroup` / `FilterCondition` / `FilterOperator.forKind` | The filter model is typed and validated | The ideal target for NL filter: AI produces the **model**, not SQL |
| `FilterValues.temporal` | `today-7d`, `3 months ago`… but **no start-of-week/month anchors** | "last week" or "last month" cannot yet be expressed as relative values. Need to add anchors |
| `QueryResult.Rows` | Values are `String?` with `jdbcType` | The chart has to parse numbers and dates itself. Data is capped by `maxResultRows` |
| Chart library | None. The build has a license allowlist (`bundledLicenses`) | Choosing a library must go through a license review step |

## 3. Shared infrastructure (build once, used by all 4 features)

### 3.1 `AssistTask`: a single AI turn that returns validated JSON

New package `dev.tablegrip.core.assist`, depending only on `AgentAdapter`, so it can be tested with a fake adapter:

```kotlin
interface AssistTask<O> {
    val name: String                       // "fix-sql", "nl-filter", "explain-advice", "chart-spec"
    fun system(): String                   // the task's role and its own rules
    fun prompt(): String                   // context + request + JSON format description
    fun parse(json: JsonElement): O        // throws AssistInvalid(reasons) if wrong
}

class AssistRunner(private val agent: () -> AgentAdapter?, private val timeout: Duration) {
    /** One turn; if the JSON is invalid, resend once with the validation errors, and only then report failure. */
    suspend fun <O> run(task: AssistTask<O>): AssistResult<O>
}
```

- **Reliable JSON extraction**: take the last ```` ```json ```` block; if there is none, take the last brace-balanced
  `{…}` object. CLI agents often add preambles, so do not trust the whole stdout.
- **One repair attempt**: if validation fails, resend with the specific list of errors (for example "column
  `total_amount` does not exist; available columns: …"). After more than 1 attempt, stop and report a clear error
  instead of looping forever.
- **Cancellable**: cancelling the coroutine stops the process or the HTTP request, using the adapter's existing
  mechanism. Every entry point in the UI has Esc or a Stop button.
- **Structured output**: in the first phase, use a single shared prompt format for all transports. Later, for the
  Provider, each API's JSON schema or tool-use can be enabled as an optimization, not a requirement.
- `AssistResult` = `Ok(value, agentName, elapsed)` | `Invalid(reasons)` | `Failed(message)` | `Unavailable`.

### 3.2 Policy for data sent to the AI (mandatory for production)

Decided (2026-10-30): **the user configures it in Settings, the default is the restricted level**, so the AI does not
read sensitive data until the user has actively allowed it.

| Level | What is sent |
| --- | --- |
| `Off` | Nothing, all AI buttons are hidden |
| `SchemaOnly` (**default**) | Table/column names/types, DDL, statements, error messages, plans. **No data values at all** |
| `SchemaAndSamples` | Adds sample values (distinct values of low-cardinality columns, a few result rows), only when the user enables it |

- **Settings → AI → "Data sent to AI"**: the global default level `Settings.aiAccess = SchemaOnly`.
- **Each connection can override it** (`ConnectionConfig.aiAccess: AiAccess?`; `null` means use the global level) in
  Connection → Options. A connection tagged with the Production environment **must not exceed** `SchemaOnly` unless the
  user changes it specifically for that connection and confirms the warning.
- Consequence at `SchemaOnly`: NL filter has no sample values, so the AI may ask follow-up questions more often ("which
  value is 'paid' status?"); the chart AI sees only column names and types. This is an acceptable trade-off, and the UI
  suggests "Enable sample data for this connection" right there.
- Applies to the existing chat agent as well. Currently, when the user allows a run request,
  `AgentContext.describe(sql, outcome)` sends up to 50 result rows. That is per-request consent, so it can stay at
  `SchemaOnly`, but the permission card must clearly state "the result will be sent to the AI". At `Off` it is
  disabled entirely.
- Every AI popover has a **"Data to be sent"** section (expandable to show the verbatim prompt), along with the agent
  name and its location (local CLI or provider URL).
- Settings has a global "AI assist" switch to turn everything off.
- Local log in `~/.tablegrip/logs/`: task name, agent, time, result (ok/invalid/failed), **no content is recorded**.

### 3.3 Scoped schema context

Split the pure part of `AgentContext.describe` out to `core` as `SchemaDigest`:

- `full(namespace, budget)`: as it is today, used for chat.
- `forTables(refs, budget)`: only the tables the statement uses, with primary keys, foreign keys, indexes, estimated
  row counts. These tables are prioritized **before** truncating to the budget. The table list is obtained with
  `SqlLexer` and the table-finding logic of `SqlCompleter`.
- `nearMisses(identifier)`: near-match table/column names (fuzzy, same algorithm as completion) for suggestions.

### 3.4 Proposal display

Every AI suggestion uses one shared frame: title, the change (diff, filter, SQL), a short explanation, an agent label,
and the buttons **Apply / Dismiss / Open in chat**. "Open in chat" moves the context to `AgentPanel` for follow-up
questions, so we do not have to build two conversation systems.

## 4. Fix with AI

### Flow

1. A failed statement → the error line in the output log has a **Fix with AI** button (and a shortcut, for example ⌥⏎
   when the caret is on the failed statement).
2. **Before calling the AI**, run the local fixer: if the error is "column/relation does not exist" (recognized by
   SQLState: PG `42703` and `42P01`, MySQL `1054` and `1146`…) and there is a near match, immediately suggest "Did you
   mean `user_id`?". It costs no tokens and answers instantly.
3. If there is no local fix, or the user still clicks AI: send an `AssistTask<SqlFix>` containing the statement,
   SQLState, error code, message, error position, dialect, `SchemaDigest.forTables`, `nearMisses`.
4. The result `SqlFix(sql, explanation, confidence)` is shown as an **inline diff** over the statement in the editor.
   Applying replaces exactly that segment, and ⌘Z can undo it. **It does not run automatically.**

### Checks before display

- Re-classify with `SqlClassifier`: if the original is Read and the fix is Write/DDL, show a **red warning** and remove
  the "Apply and run" button.
- The fix must parse through `SqlSplitter` into exactly **one** statement. If the AI returns several statements,
  reject it.
- Identifiers in the fix must exist in the known schema; if not, still show it but mark it "unverified".

### Code changes

- `QuerySession.execute` and `ConsoleTab.execute`: catch `SQLException` as
  `QueryError(message, sqlState, vendorCode, position?)`. Take `position` from
  `PSQLException.serverErrorMessage.position` for PG; leave it null for other engines.
- `OutputLine` gains `error: QueryError?` and `statement: String?`. `RunOutcome` carries them too so chat can use them.
- `ConsoleView`: render the button on the error line; the diff reuses the highlight mechanism of `SqlEditor`.

### Tests

- Unit: JSON extraction, repair, Read→Write classification, the local fixer driven by the SQLState table (real error
  fixtures from PG, MySQL, SQLite).
- Integration: use the existing `-Pit.postgres`/`-Pit.mysql` to verify that `QueryError` is obtained from a real driver.

## 5. Natural language → filter

Example: *"unpaid orders from last week over 1 million"*.

### Principle

The AI returns a **`FilterGroup` as JSON**, not SQL. This way:

- Values are still bound as parameters through `FilterCompiler`, so there is no SQL injection.
- Each condition can be validated with existing code: the column exists, the operator is in
  `FilterOperator.forKind(kind)`, the value can be parsed by `FilterValues`.
- The result appears in the Builder so the user can **re-read it in the Builder's own language** before applying.

### Format

```json
{
  "combinator": "all",
  "conditions": [
    {"column": "created_at", "operator": "Between", "value": "startOfWeek-1w", "value2": "startOfWeek-1d"},
    {"column": "total", "operator": "Greater", "value": "1000000"},
    {"column": "status", "operator": "NotEquals", "value": "paid"}
  ],
  "assumptions": ["“1 million” = 1000000 on the total column (VND)", "“unpaid” = status ≠ 'paid'"],
  "clarify": null
}
```

- `assumptions` is shown right below the new rows. This is where the user spots a misunderstanding by the AI.
- `clarify`: when the request is ambiguous (both `total` and `amount` exist), the AI asks one question with options
  instead of guessing.
- `RawCondition` is **banned outright** (decided 2026-10-30), with no option to enable it: verbatim SQL can call
  functions with side effects or run heavy subqueries, and cannot be validated like a structured row. The
  `AssistTask` parser rejects any node that is not a `FilterCondition`/`FilterGroup`. A question that cannot be
  expressed as structured rows (for example "customers with more than 3 orders") gets a `clarify` explaining why, and
  the UI offers an "Open in chat" button so the agent can write a query for the console. This could be reconsidered
  once the eval suite is stable.

### Context sent

Table name; columns with their `ValueKind` and list of valid operators; enum or distinct values of low-cardinality
columns (≤ 30, obtained with the existing value-suggestion mechanism, only at `SchemaAndSamples`); the current time,
time zone, locale-based first day of the week; TableGrip's relative date syntax.

### Gaps to fill first

- `FilterValues`: add the anchors `startOfWeek`, `startOfMonth`, `startOfYear` (start of week by locale or setting) so
  "last week" is still a **relative expression, recomputed on every reload**, in the spirit of the README. We could
  also add `LastWeek`/`LastMonth` operators for the manual Builder.
- Number units such as "1 million", "1tr", "1M", "2.5k": let the AI convert them, but **display the converted value**
  in `assumptions`.

### UI

A ✨ input at the top of the filter bar (⌘I when inside a table). The result **adds** new rows (color-marked, not yet
enabled) to the Builder. The user presses ⏎ to apply or Esc to discard. There is a history of past questions. In SQL
mode, the result is converted to SQL the same way as when switching modes today.

### Quality measurement (required before release)

- An eval suite `core/src/test/resources/assist/nl-filter/*.json`: about 60 questions (Vietnamese and English; dates,
  numbers, enums, negation, OR, NULL, ambiguous questions) with the expected `FilterGroup`, on the `structure-it`
  sample schema.
- Run conditionally with `-Pit.agent=anthropic` (like `-Pit.postgres`). Report the fully-correct rate and the rate of
  clarifying at the right moment. Suggested release threshold: ≥ 90% correct or reasonably clarified, 0% generating
  conditions for nonexistent columns.

## 6. Visual EXPLAIN and index suggestions

### 6.1 Deterministic part (no AI): a common plan model

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

A per-engine `PlanParser`, with a fallback to raw text if parsing fails:

| Engine | Statement | Format |
| --- | --- | --- |
| PostgreSQL, CockroachDB, DuckDB | `EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS])` | JSON |
| MySQL 8 | `EXPLAIN FORMAT=JSON`; Analyze: `EXPLAIN ANALYZE` (TREE) | JSON / tree text |
| MariaDB | `EXPLAIN FORMAT=JSON`; Analyze: `ANALYZE FORMAT=JSON` | JSON |
| SQLite | `EXPLAIN QUERY PLAN` (`id`, `parent`, `detail`) | Table, tree built by us |
| SQL Server | `SET SHOWPLAN_XML ON` / `STATISTICS XML` | XML |
| ClickHouse | `EXPLAIN json = 1, indexes = 1` | JSON |

Change `SqlDialect.explain(sql)` to `explain(sql, analyze: Boolean): ExplainPlan(statements, format)`.

### 6.2 Display

- A new result tab **Plan** with a collapsible tree (or a horizontal flame chart) and a detail table for the selected
  node. The raw text tab is kept.
- Color by **own cost** (node minus its children) as a percentage of the total; nodes ≥ 30% are highlighted in bold.
- **Deterministic insights**, no AI needed: estimate deviates from actual by more than 10x; Seq Scan on a large table
  with a Filter that removes many rows; Sort/Hash spilling to disk; Nested Loop with large `loops`; SQLite `SCAN`
  without an index. This is the main value, and it works even when there is no AI.

### 6.3 Explain Analyze: safety first

`EXPLAIN ANALYZE` **executes** the statement:

Decided (2026-10-30): **read statements only**.

- Read statements (`SqlClassifier` returns `Read`): run normally, with a timeout per `queryTimeoutSeconds`, cancellable.
- Write statements, DDL and CTEs with DML: the Explain Analyze button is disabled, with the tooltip "Only available for
  read statements". Only plain Explain (estimated plan) is available. Reason: even when wrapped in `BEGIN … ROLLBACK`,
  the write still really happens on the server while running (row locks, triggers or functions that call out,
  sequences that advance and cannot be rolled back).
- Check again right before sending to the server, not only based on the button state, because the statement at the
  caret may have changed.
- Could be reconsidered for write statements in a later phase, when there is real demand.

### 6.4 AI: explanation and index suggestions

`AssistTask<PlanAdvice>` takes the condensed plan (negligible nodes removed), the deterministic insights, the DDL and
existing indexes of the relevant tables, and estimated row counts. The result contains a short explanation in the
user's language and a list of `IndexSuggestion(table, columns, include?, where?, reason)`.

Checks before display: the columns exist; **no duplicate** of an existing index (compare by prefix); generate
`CREATE INDEX` in **code** per dialect from that structure (not taking the AI's SQL string), using `CONCURRENTLY` for
PG. An "Open in console" button, with no run-directly button.

Later phase: simulated verification with HypoPG if the extension is available ("what would the plan look like with
this index").

## 7. Quick chart from results

### 7.1 Deterministic part

- **Column types**: based on `jdbcType` and by trying to parse values (number, date). Not based on column names.
- **Auto-pick axes** (heuristic, no AI):
  - 1 time column + ≥ 1 numeric column → line;
  - 1 low-cardinality text column (≤ 30) + 1 numeric column → horizontal bar, sorted descending;
  - same as above but ≤ 8 values and all numbers positive → also offer pie/donut;
  - 2 numeric columns → scatter;
  - adding 1 low-cardinality text column → split into series.
- **Data**: by default, draw from the rows already loaded, with a clear banner "Chart from the first 500 rows, more
  results exist" when `truncated`. A **"Aggregate on server"** button: generates
  `SELECT x, agg(y) FROM (<original statement>) t GROUP BY x ORDER BY x` per dialect, runs it through
  `ConsoleTab.run` (classified as Read).
- **Export**: copy image to clipboard, save PNG/SVG.
- The chart spec is stored together with `ResultSetView`, so re-running the statement updates the chart.

### 7.2 AI

A ✨ input "revenue by month, split by region" → `AssistTask<ChartSpec>`:

```json
{"type": "line", "x": "created_at", "bucket": "month", "y": [{"column": "total", "agg": "sum"}], "series": "region"}
```

Validate columns, types and aggregation. If grouping is needed while the data is truncated, automatically suggest
"Aggregate on server". The AI receives only **column names, types and a few sample values**, not the entire result.

### 7.3 Library

Surveyed on 2026-10-30. Requirements: first-party (JetBrains/Kotlin) if available, a license that allows commercial
use, runs on Compose Desktop.

| Library | Owner | License | Desktop | Version / status | Notes |
| --- | --- | --- | --- | --- | --- |
| **[Lets-Plot Compose](https://github.com/JetBrains/lets-plot-compose)** | **JetBrains** | **MIT** | Yes (macOS, Windows, Linux) | 3.2.2, last updated ~4 months ago; labeled *Experimental*, *incubator* | Grammar of graphics (Lets-Plot core is also MIT, first-party JetBrains). Depends on Compose 1.11.1, Lets-Plot Multiplatform 4.11.0, kotlinx-datetime. Raster image export via `platf-awt` |
| [Koala Plot](https://github.com/KoalaPlot/koalaplot-core) | Community | MIT | Yes | 0.12.x, "developmental", the 0.x API may break between releases | Pure Compose so easy to theme; many chart types (line, bar, pie, heatmap…) |
| [Vico](https://github.com/patrykandpatrick/vico) | Community | Apache-2.0 (still usable commercially) | Has a JVM target | Very active, but the latest release is `-next` (pre-release); pie is still experimental | Android-oriented |

Kandy (JetBrains' Kotlin DSL) is built on Lets-Plot and aimed at notebooks, so it is not needed for the desktop app.

**Recommendation: a 1–2 day Lets-Plot Compose spike before deciding**, since it is the only option that is both
first-party JetBrains and MIT. The spike must answer:

1. Does it run with the repo's Compose `1.12.1`, Jewel `0.41.0` and JBR 25 (the library is built on Compose 1.11.1)?
2. How much does the installer size grow (Lets-Plot core is fairly large)?
3. Can it follow the Jewel light/dark theme, and are tooltip/hover good enough?
4. Performance with about 100k points (is LTTB downsampling needed before feeding it in)?
5. PNG and SVG export; do the `org.jetbrains.lets-plot` group and its transitive dependencies pass `bundledLicenses`?

The *Experimental* label is a risk: wrap the library behind a `ChartRenderer` interface in `app/.../chart`, so it can
be replaced with a hand-drawn version without touching `ChartSpec`, the heuristics or the AI part. If the spike fails
question 1 or 3, then **draw it ourselves with Compose `Canvas`** (line, bar, scatter, pie, about 1 week). Koala Plot
and Vico are fallback options, not prioritized because they are not first-party and their APIs are not yet stable.

One advantage of Lets-Plot: its spec is declarative data (ggplot style), close to the `ChartSpec` the AI produces, so
it can later be extended to histogram, box plot, facet without writing more renderers.

## 8. Roadmap

| Phase | Content | Estimate | Done when |
| --- | --- | --- | --- |
| **P0** | `AssistTask`/`AssistRunner`, per-connection `AiAccess`, `SchemaDigest` moved down to core, structured `QueryError` | 4–5 days | Unit tests with a fake adapter; the existing chat agent respects `AiAccess` |
| **P1** | Fix with AI + local fixer driven by SQLState | 3–4 days | Diff, undo, Read→Write warning; IT with PG/MySQL |
| **P2** | `PlanNode` + PG/MySQL/SQLite/DuckDB parsers, Plan tab, deterministic insights, Explain Analyze for read statements | 7–8 days | Real plan fixtures for each engine; text fallback |
| **P2b** | AI index suggestions + SQL Server/ClickHouse/MariaDB parsers | 3–4 days | No duplicate indexes; DDL generated by code |
| **P3** | `startOfWeek/Month/Year` anchors, NL → filter, eval suite | 8–10 days | Eval ≥ 90%, 0 phantom columns |
| **P4** | Lets-Plot Compose spike, then chart (Lets-Plot or hand-drawn) + heuristics + server aggregation + AI ChartSpec | 8–10 days | Light/dark theme, smooth with 100k points, PNG/SVG export |

About 7–8 weeks in total for one person; P2 and P4 can run in parallel since they do not touch the same files.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Sensitive data leaking to the cloud | Default `SchemaOnly` for every connection, overridable in Settings and per connection, preview of what is sent, support for local models via OpenAI-compatible (Ollama, LM Studio) |
| AI invents columns or tables | Validate against the schema; repair once; never display as verified |
| AI generates write or dangerous statements | Re-classify with `SqlClassifier`; index DDL generated by code; no run-directly button |
| CLI agent is slow or rambles | Timeout, Esc to cancel, reliable JSON extraction; recommend Provider mode for inline features |
| Users trust a wrong filter or chart | `assumptions` display is mandatory; "first N rows" banner; the Builder always shows the real conditions |
| Quality drifts when the model changes | Eval suite gated by `-Pit.agent`, run before every change of the default model |
| No agent available | Hide AI buttons, keep the deterministic parts (local fixer, plan tree, chart heuristics) fully working |

## 10. Open questions

1. ~~Default `AiAccess` level~~: decided, configured in Settings, default `SchemaOnly` (section 3.2).
2. ~~Explain Analyze for write statements~~: decided, read statements only (section 6.3).
3. ~~Chart~~: the Lets-Plot Compose spike failed question 1 (crashes with Compose 1.12.1), so draw it ourselves with Compose Canvas (details in [chart.md](chart.md) 3.3).
4. ~~`RawCondition` in NL filter~~: decided, banned outright (section 5).
