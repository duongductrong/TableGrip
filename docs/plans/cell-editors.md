# Plan: type-specific editors for DataGrid

Code survey dated 2026-10-31 (commit `8e6b169`).

## 0. Status (2026-10-31)

**Done: foundation + P1.**

| Part | Where |
| --- | --- |
| `ColumnEdit`, `EditKind`, `EditCheck`, `BooleanStyle`, `Uuids` (v4/v7) | `core/edit/ColumnEdit.kt`, test `core/.../edit/ColumnEditTest.kt` |
| `UPDATE … SET c = DEFAULT` (`BoundValue.isDefault`) | `core/edit/ChangeSet.kt` |
| `GridEditor.columnEdit/canSetNull/canSetDefault/setDefault`, `GridModel.problem` | `app/grid/GridModel.kt` |
| `TableEditModel` takes `TableDetails` + dialect; compares by meaning (`t` = `true`); `firstProblem()` blocks Save | `app/table/TableEditModel.kt`, `TableDataTab.submit/submitNow` |
| Commit: Enter/Tab block invalid values (red border + hint under the cell); clicking outside keeps the typed text, marks the cell red and blocks Save; an empty cell in a non-text column becomes NULL (new row → default) | `GridController.commitEdit/editCheck` |
| Boolean: checkbox drawn in the cell, click or Space to toggle (true → false → NULL) | `CellEditors.CheckBoxGlyph`, `GridController.toggleBoolean` |
| Date / DateTime / Time: calendar + time field (⌥↓ or the ▾ button), accepts `today-7d`, `now`; `timestamptz` is written with the machine's offset | `CellEditors.TemporalPicker` |
| Numbers: range check, precision/scale (rounding warning), NaN/Infinity; ⌥↑/↓ to increment/decrement | `ColumnEdit.check`, `stepNumber` |
| UUID: validation + normalization; "Generate UUID" / "v7" menu for the selected cells | `GridController.generateUuids` |
| JSON: Enter opens the large editor directly, reports parse errors, Format / Minify | `ExpandedCellEditor` |
| Text: `12/255` counter near the limit, blocks when exceeded (SQLite only warns); multi-line text opens the large editor | `ColumnEdit.checkText` |
| `<auto>`, `<default: now()>`, `<required>` in new rows; Set DEFAULT; Set NULL hidden on NOT NULL columns | `ColumnEdit.placeholder`, cell menu |
| Value panel: True/False/NULL radio, calendar, New UUID / v7, Minify, **Empty** button (empty string ≠ NULL), Set DEFAULT, Apply is disabled when the value is invalid | `app/grid/ValuePanel.kt` |

Differences from the original proposal:
- Auto-increment columns in existing rows can still be edited (no "Allow editing" switch was added); new rows show `<auto>` but can still be overtyped.
- The large JSON editor has no syntax highlighting yet (`BasicTextField` has no place to attach `jsonTokens`); Tree is still view-only.
- The console result grid is still read-only, so it does not draw checkboxes.

**Not done:** P2 (enum/set, FK lookup), P3 (binary via file, array, PostgreSQL-specific types).

## 1. Current state

| Item | Where | Notes |
| --- | --- | --- |
| Inline editing | `CellEditor` in `grid/DataGrid.kt` | A single-line `BasicTextField` for **every** type. ⇧Enter opens `ExpandedCellEditor` (multi-line text, 420×180). |
| Value panel | `grid/ValuePanel.kt` | Views: Text / Tree (JSON) / Hex / Image, but only **Text** is editable. Date/time has a timestamp conversion table, view-only. |
| Model | `GridModel` / `GridEditor` (`grid/GridModel.kt`) | Values are `String?`. The grid only knows `ResultColumn(name, typeName, jdbcType)`: it does not know nullable, default, length, scale, enum, FK. |
| Table model | `table/TableEditModel.kt` | `canEdit` excludes binary columns. Values are not validated; the server reports errors at Save time. |
| Writing to the DB | `core/edit/ChangeSet.kt`, `SqlDialect.bind` | `BoundValue(value: String?, jdbcType, numeric)` → `setString`/`setNull`. PostgreSQL sends an untyped literal so the server coerces it. |
| Type classification | `ValueKind.of()` in `core/filter/Filter.kt` | Already exists: Text, Number, Boolean, Date, Time, DateTime, Uuid, Json, Binary, Other. Can be reused. |
| Parser | `FilterValues` (`core/filter/FilterValues.kt`) | `number`, `uuid`, `boolean`, `temporal` (including `today-7d`, `now-2h`). Can be reused for validation and normalization. |
| Metadata | `TableDataTab.details: TableDetails` | Loaded together with the first page (`ColumnInfo` has nullable, default, size, scale, autoIncrement, PK; there is `foreignKeys`) but is **not** passed to `TableEditModel`. |

### Specific bugs and annoyances today

1. **A NULL cell becomes an empty string.** `startEditing` takes `value.orEmpty()`; typing and then clearing everything in a number/date cell commits `""` → a cast error at Save (`invalid input syntax for type integer: ""`).
2. **No validation before Save.** One wrong date cell among 50 changes stops the whole batch at that statement.
3. **Booleans must be typed by hand** as `true`/`t`/`1`, and each engine accepts different forms.
4. **Dates and times must be typed in the exact server format**, even though the filter builder already understands `today-7d`.
5. **Enum / FK values must be remembered**, even though the app already knows the FK (there is `linkedColumns`, "follow link").
6. **Binary cannot be edited at all**, not even "load from file".
7. **Auto-increment / default columns** in new rows only show `<default>` without saying what the default is; for UPDATE there is no way to reset to `DEFAULT`.

## 2. Proposed architecture

Keep the `String?` contract down to `ChangeSet` for every type except binary: an editor only needs to **produce canonical text that the server can parse**. Most of the work is in the app; core only adds a column description and a normalization function.

```
core/edit/ColumnEdit.kt        (new, no UI)
  data class ColumnEdit(
      kind: EditKind,            // from ValueKind + typeName, more detailed: Integer/Decimal/Float, Enum, Set, Array…
      nullable, defaultValue, generated (autoIncrement/identity/computed),
      maxLength, precision, scale, unsigned,
      allowedValues: List<String>?,   // enum, set, CHECK IN (...)
      reference: ForeignKeyInfo?,     // column belongs to a single-column FK
      dialect                          // to pick the canonical form: true/1, 'T' or ' '…
  )
  fun ColumnEdit.normalize(text): EditResult   // Ok(canonical) | Invalid(message) | Null
```

- `GridEditor` gets `fun columnEdit(column: Int): ColumnEdit` (by default built from `ResultColumn` + `ValueKind`, so the console grid can use it later too).
- `TableEditModel` also receives `TableDetails` (already available in `TableDataTab` line 372).
- `app/grid/editors/`: one `CellEditorKind` per type group, with 3 surfaces:
  - **inline** (in the cell, keyboard-first, always allows typing text),
  - **popup** (a picker floating under the cell, opened with ⌥↓ or a ▾ button at the end of the cell, replacing `ExpandedCellEditor` when the type has a picker),
  - **panel** (in `ValuePanel`, the full editor).
- `commitEdit` goes through `normalize`: an **Invalid** value gets a red border + tooltip, Enter does not commit (Esc cancels); an empty cell in a non-text column becomes **NULL** (or `<default>` in a new row). The "was it changed" comparison happens after normalization (`1.50` = `1.5` does not count as a change).
- The context menu gets **Set DEFAULT** (needs `RowChange.Update` to support the special value `DEFAULT`; hidden for SQLite, which has none).

Text remains the escape hatch for every type: a picker is only a helper and never blocks someone who types the value directly.

## 3. Proposals by DataType

Priority: **P1** do now (needs only `ColumnEdit` + `FilterValues`), **P2** needs more introspection, **P3** needs a binding change in core or is rarely used.

| Group (`EditKind`) | Actual types | Inline | Popup / Panel | Validation & normalization | Priority |
| --- | --- | --- | --- | --- | --- |
| **Boolean** | `bool`, `boolean`, `bit(1)`, MySQL `tinyint(1)` | Checkbox drawn in the cell; Space toggles; single click. Nullable: three-state cycle true → false → NULL | Panel: True / False / NULL radio | `FilterValues.boolean`; write `true`/`false` (PG, CockroachDB, H2) or `1`/`0` (MySQL, SQLite, SQL Server `bit`) | P1 |
| **Integer** | `int2/4/8`, `smallint`, `bigint`, `tinyint`, `serial`, `year` | Right-aligned text, rejects stray characters; ⌥↑/↓ increments/decrements by 1 | Panel: also shows hex form, valid range | Range by type (tinyint −128…127, `unsigned`, int32/int64); strip `_` and spaces | P1 |
| **Decimal** | `numeric(p,s)`, `decimal`, `money` | Same as Integer | Panel: rounding warning | Digit count ≤ p, fraction digits ≤ s (warn when rounding is needed, never silently truncate) | P1 |
| **Float** | `real`, `float`, `double` | Same as Integer | — | Allow `NaN`, `Infinity` for PG; `1e-5` form | P1 |
| **Short text** | `varchar(n)`, `char(n)`, `name` | Text field + `12/255` counter at ≥ 80% of the limit, red when exceeded | Multi-line popup as today | Length ≤ n; `char(n)` warns that it will be space-padded | P1 |
| **Long text** | `text`, `clob`, `longtext`, `citext` | Text field; if the value contains line breaks, Enter opens the popup instead of single-line editing | Panel: toggle "show whitespace", word wrap, and clearly distinguish **empty string** from **NULL** (a separate "Empty string" button) | — | P1 |
| **Date** | `date` | Text, accepts `2026-10-31`, `today`, `today-7d` (hint "= Fri 31 Oct 2026" like the filter builder) | Month calendar popup; Today / Yesterday; ←↑→↓ pick a day, PgUp/PgDn change month | `FilterValues.temporal` → ISO `yyyy-MM-dd` | P1 |
| **DateTime** | `timestamp`, `timestamptz`, `datetime`, `datetime2` | Same as Date, accepts `now`, `now-2h` | Calendar popup + time field `HH:mm:ss.SSS`; Now button; for `timestamptz` shows the time zone (machine zone by default, changeable) and the UTC value | Outputs `yyyy-MM-dd HH:mm:ss[.f][±hh:mm]`; uses a space (MySQL and SQLite do not accept `T` everywhere); trims fractional seconds to the column precision | P1 |
| **Time** | `time`, `timetz` | Text `13:45`, `13:45:30` | Popup: hour/minute/second spinner | → `HH:mm:ss` | P1 |
| **UUID** | `uuid`, `uniqueidentifier` | Text with validation | **Generate** button (v4, v7) in the popup/panel; "Generate UUID" context menu for multiple selected cells | `FilterValues.uuid` → lowercase with hyphens; also accepts `{…}` and hyphen-less forms | P1 |
| **JSON** | `json`, `jsonb` (and text columns whose value starts with `{`/`[`) | No single-line editing: Enter opens the popup | Popup/panel: editor with syntax highlighting (`jsonTokens` already exists), parse errors with line/column, Format / Minify; later, edit directly on the Tree (edit leaves, add/remove keys) | Parse with kotlinx; `jsonb` is written minified | P1 (editor), P3 (editing on Tree) |
| **Generated** | `autoIncrement`, identity, `serial` PK, computed columns | New row: dimmed `<auto>`, not editable; existing row: read-only unless the user turns on "Allow editing" | Panel shows the default (`nextval(...)`, `now()`) | — | P1 |
| **NOT NULL / default** | every column | Hide/lock **Set NULL** when `nullable = false`; `<default: now()>` instead of `<default>` | **Set DEFAULT** | Warn before Save about a new row missing a NOT NULL column that has no default | P1 |
| **Enum** | PG `CREATE TYPE … AS ENUM`, MySQL `enum('a','b')`, `CHECK (c IN (…))` | Combo box: type to filter, ↑↓ to select | Panel: radio list | Value must be in the list | P2 — needs reading `pg_enum`, MySQL `COLUMN_TYPE`, parsing CHECK |
| **Set** | MySQL `set('a','b')` | Shown as chips | Popup: checkbox list | Join as `a,b` in declaration order | P2 |
| **Foreign key** | column in a single-column FK | Text + ▾ button | Search popup on the referenced table: `SELECT pk, <display column> … WHERE … LIKE ? LIMIT 50`, shows pk + the first text column (`name`, `title`, `email`…); Enter selects | Warn (do not block) when the value is missing from the parent table | P2 |
| **Array** | PG `int[]`, `text[]` (`_int4`…) | Shows `{1,2,3}`, Enter opens the popup | Popup/panel: list of elements, add/remove/drag to reorder, each element uses the editor of the element type | Build the PG literal `{a,"b c",NULL}` with escaping | P3 |
| **Binary** | `bytea`, `blob`, `varbinary` | Read-only (as today) | Panel: **Load from file…**, **Save to file…**, Set NULL; then edit as hex | Needs `BoundValue` to carry a `ByteArray` and `dialect.bind` to call `setBytes` | P3 |
| **Other (PG)** | `interval`, `inet`/`cidr`/`macaddr`, `bit(n)`, `hstore`, range (`tsrange`…), `xml`, `geometry` | Text as today | In turn: interval → year/month/day/hour fields; `bit(n)` → row of toggles; `hstore` → key/value table; range → two bounds + `[`/`(`; `xml` → editor with well-formedness check; geometry → WKT | Light syntax check, leave the rest to the server | P3 |

Redis, MongoDB and etcd do not go through `TableEditModel`; see section 7, to be done as a separate part.

## 4. Roadmap

1. **Foundation** (P1): `ColumnEdit` + `normalize` in core (pure tests like `FilterValuesTest`); pass `TableDetails` into `TableEditModel`; fix the `commitEdit` flow (empty → NULL, invalid blocks commit, compare after normalization). This step alone fixes bugs 1 and 2 in section 1.
2. **P1 editors**: Boolean (checkbox in the cell), Date/DateTime/Time (calendar + time), Number, UUID, JSON popup, Text length counter, Generated/NOT NULL/Set DEFAULT. One snapshot test per editor in `GridSnapshotTest`, and a round-trip check through SQLite and PostgreSQL in `RelationalContract`.
3. **P2 introspection**: add `allowedValues` to `ColumnInfo` (PG `pg_enum`, MySQL `COLUMN_TYPE`, CHECK IN), then the Enum/Set combo; FK lookup popup.
4. **P3**: binary via file (change `BoundValue`), array, then PostgreSQL-specific types as needed.

## 5. Open questions

- Invalid values: **block** the commit (proposed, because Save of the whole batch would fail) or only warn? The `Other` type always only warns, because the app does not know all the syntax the server accepts.
- `timestamptz`: enter in the machine time zone or UTC? Proposed: machine time zone, with the UTC conversion shown right below the cell, like `TimestampInfo`.
- Which display column does the FK popup use when the parent table has no obvious text column: pk only, or let the user choose and remember it per table?

## 7. Redis, MongoDB, etcd (separate part)

These three engines do not use `DataGrid` + `TableEditModel`, so the sections above do not apply automatically. Current state and work needed:

| Engine | Screen | How editing works now | Proposal |
| --- | --- | --- | --- |
| **Redis** (Valkey, KeyDB, Dragonfly) | `keyvalue/KeyBrowserView.kt`, core `KeyValueConnection.edit(KeyEdit)` | String: `TextArea`; hash/list/set/zset: list of elements with text fields; TTL: seconds field | String: detect JSON → reuse the JSON editor (Format/Minify/error reporting); numbers → validate like `EditKind.Integer/Float` (for `INCRBY`); zset score: number field with ⌥↑/↓; TTL: quick choices (1 minute, 1 hour, 1 day) and show the expiry time; stream: form to add an entry as field/value; binary-safe value: Hex view + load from file |
| **MongoDB** (FerretDB) | `documents/DocumentBrowserView.kt`, `DocumentConnection.replace/insert` | The whole document is one JSON block in a `TextArea`, saved with `replaceOne` | Edit per field on the Tree: each leaf picks an editor by BSON type (`ObjectId` → check 24 hex + Generate; `ISODate` → reuse `TemporalPickerContent`; `bool` → checkbox; `NumberInt/Long/Decimal128` → range check like `EditKind.Integer/Decimal`; `UUID`/`BinData` → like UUID/binary). Save with per-field `$set`/`$unset` instead of replacing the whole document, so other people's changes are not overwritten. Needs a "`ColumnEdit` for BSON" in core, next to `core/edit` |
| **etcd** | shares `KeyBrowserView` | Value is text | Same as Redis string (JSON/number); add lease in place of TTL (attach/revoke lease); values are bytes, so a Hex view is needed |

The reusable pieces already written without depending on the grid: `ColumnEdit.check` (core), `TemporalPickerContent`, `CheckBoxGlyph`, `reformatJson`, `EditHint` (`app/grid/CellEditors.kt`). When doing this part, move them to a shared package (e.g. `app/editors`) instead of importing from `grid`.
