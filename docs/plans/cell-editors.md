# Kế hoạch: editor theo kiểu dữ liệu cho DataGrid

Khảo sát code ngày 2026-10-31 (commit `8e6b169`).

## 0. Trạng thái (2026-10-31)

**Đã làm: nền + P1.**

| Phần | Ở đâu |
| --- | --- |
| `ColumnEdit`, `EditKind`, `EditCheck`, `BooleanStyle`, `Uuids` (v4/v7) | `core/edit/ColumnEdit.kt`, test `core/.../edit/ColumnEditTest.kt` |
| `UPDATE … SET c = DEFAULT` (`BoundValue.isDefault`) | `core/edit/ChangeSet.kt` |
| `GridEditor.columnEdit/canSetNull/canSetDefault/setDefault`, `GridModel.problem` | `app/grid/GridModel.kt` |
| `TableEditModel` nhận `TableDetails` + dialect; so sánh theo nghĩa (`t` = `true`); `firstProblem()` chặn Save | `app/table/TableEditModel.kt`, `TableDataTab.submit/submitNow` |
| Commit: Enter/Tab chặn giá trị sai (viền đỏ + hint dưới ô); click ra ngoài giữ nguyên chữ đã gõ, tô đỏ ô và chặn Save; ô rỗng ở cột không phải text → NULL (dòng mới → default) | `GridController.commitEdit/editCheck` |
| Boolean: checkbox vẽ trong ô, click hoặc Space để đổi (true → false → NULL) | `CellEditors.CheckBoxGlyph`, `GridController.toggleBoolean` |
| Date / DateTime / Time: lịch + ô giờ (⌥↓ hoặc nút ▾), nhận `today-7d`, `now`; `timestamptz` ghi kèm offset máy | `CellEditors.TemporalPicker` |
| Số: kiểm tra khoảng, precision/scale (cảnh báo làm tròn), NaN/Infinity; ⌥↑/↓ tăng giảm | `ColumnEdit.check`, `stepNumber` |
| UUID: kiểm tra + chuẩn hoá; menu "Generate UUID" / "v7" cho các ô đang chọn | `GridController.generateUuids` |
| JSON: Enter mở thẳng editor lớn, báo lỗi parse, Format / Minify | `ExpandedCellEditor` |
| Text: `12/255` khi gần giới hạn, chặn khi vượt (SQLite chỉ cảnh báo); text nhiều dòng mở editor lớn | `ColumnEdit.checkText` |
| `<auto>`, `<default: now()>`, `<required>` trong dòng mới; Set DEFAULT; ẩn Set NULL ở cột NOT NULL | `ColumnEdit.placeholder`, menu ô |
| Value panel: radio True/False/NULL, lịch, New UUID / v7, Minify, nút **Empty** (chuỗi rỗng ≠ NULL), Set DEFAULT, Apply bị khoá khi giá trị sai | `app/grid/ValuePanel.kt` |

Khác với đề xuất ban đầu:
- Cột auto-increment ở dòng cũ vẫn sửa được (không thêm công tắc "Allow editing"); dòng mới hiện `<auto>` nhưng vẫn gõ đè được.
- Editor JSON lớn chưa tô màu cú pháp (`BasicTextField` chưa có chỗ gắn `jsonTokens`); Tree vẫn chỉ để xem.
- Grid kết quả của console vẫn chỉ đọc nên không vẽ checkbox.

**Chưa làm:** P2 (enum/set, FK lookup), P3 (binary qua file, array, kiểu riêng của PostgreSQL).

## 1. Hiện trạng

| Thứ | Ở đâu | Nhận xét |
| --- | --- | --- |
| Sửa inline | `CellEditor` trong `grid/DataGrid.kt` | Một `BasicTextField` một dòng cho **mọi** kiểu. ⇧Enter mở `ExpandedCellEditor` (text nhiều dòng, 420×180). |
| Value panel | `grid/ValuePanel.kt` | View Text / Tree (JSON) / Hex / Image, nhưng chỉ **Text** sửa được. Date/time có bảng quy đổi timestamp, chỉ để xem. |
| Model | `GridModel` / `GridEditor` (`grid/GridModel.kt`) | Giá trị là `String?`. Grid chỉ biết `ResultColumn(name, typeName, jdbcType)`: không biết nullable, default, độ dài, scale, enum, FK. |
| Model bảng | `table/TableEditModel.kt` | `canEdit` loại cột binary. Không kiểm tra giá trị, server báo lỗi lúc Save. |
| Ghi xuống DB | `core/edit/ChangeSet.kt`, `SqlDialect.bind` | `BoundValue(value: String?, jdbcType, numeric)` → `setString`/`setNull`. PostgreSQL gửi literal không kiểu để server tự ép. |
| Phân loại kiểu | `ValueKind.of()` trong `core/filter/Filter.kt` | Đã có: Text, Number, Boolean, Date, Time, DateTime, Uuid, Json, Binary, Other. Dùng lại được. |
| Parser | `FilterValues` (`core/filter/FilterValues.kt`) | `number`, `uuid`, `boolean`, `temporal` (cả `today-7d`, `now-2h`). Dùng lại được để kiểm tra và chuẩn hoá. |
| Metadata | `TableDataTab.details: TableDetails` | Đã nạp cùng trang đầu (`ColumnInfo` có nullable, default, size, scale, autoIncrement, PK; có `foreignKeys`) nhưng **không** truyền vào `TableEditModel`. |

### Lỗi/khó chịu cụ thể hôm nay

1. **Ô NULL thành chuỗi rỗng.** `startEditing` lấy `value.orEmpty()`; gõ rồi xoá hết trên ô số/ngày sẽ commit `""` → lỗi cast lúc Save (`invalid input syntax for type integer: ""`).
2. **Không kiểm tra trước khi Save.** Sai một ô ngày trong 50 thay đổi thì cả lô dừng ở statement đó.
3. **Boolean phải gõ tay** `true`/`t`/`1`, và mỗi engine nhận khác nhau.
4. **Ngày giờ phải gõ đúng định dạng server**, trong khi filter builder đã hiểu `today-7d`.
5. **Enum / FK phải nhớ giá trị**, dù app đã biết FK (có `linkedColumns`, "follow link").
6. **Binary không sửa được** chút nào, kể cả "nạp từ file".
7. **Cột auto-increment / có default** trong dòng mới chỉ hiện `<default>`, không nói default là gì; với UPDATE không có cách đặt lại `DEFAULT`.

## 2. Kiến trúc đề xuất

Giữ nguyên hợp đồng `String?` xuống `ChangeSet` cho mọi kiểu trừ binary: editor chỉ cần **sinh ra text chuẩn mà server parse được**. Phần lớn công việc nằm ở app, core chỉ thêm mô tả cột và hàm chuẩn hoá.

```
core/edit/ColumnEdit.kt        (mới, không UI)
  data class ColumnEdit(
      kind: EditKind,            // từ ValueKind + typeName, chi tiết hơn: Integer/Decimal/Float, Enum, Set, Array…
      nullable, defaultValue, generated (autoIncrement/identity/computed),
      maxLength, precision, scale, unsigned,
      allowedValues: List<String>?,   // enum, set, CHECK IN (...)
      reference: ForeignKeyInfo?,     // cột thuộc FK một cột
      dialect                          // để chọn dạng chuẩn: true/1, 'T' hay ' '…
  )
  fun ColumnEdit.normalize(text): EditResult   // Ok(canonical) | Invalid(message) | Null
```

- `GridEditor` thêm `fun columnEdit(column: Int): ColumnEdit` (mặc định dựng từ `ResultColumn` + `ValueKind`, nên console grid sau này cũng dùng được).
- `TableEditModel` nhận thêm `TableDetails` (đã có sẵn ở `TableDataTab` dòng 372).
- `app/grid/editors/`: một `CellEditorKind` mỗi nhóm kiểu, gồm 3 bề mặt:
  - **inline** (trong ô, ưu tiên bàn phím, luôn cho gõ text),
  - **popup** (picker nổi dưới ô, mở bằng ⌥↓ hoặc nút ▾ ở cuối ô, thay cho `ExpandedCellEditor` khi kiểu có picker),
  - **panel** (trong `ValuePanel`, editor đầy đủ).
- `commitEdit` đi qua `normalize`: giá trị **Invalid** viền đỏ + tooltip, Enter không commit (Esc huỷ); ô rỗng ở cột không phải text thành **NULL** (hoặc `<default>` trong dòng mới). So sánh "đã sửa chưa" sau khi chuẩn hoá (`1.50` = `1.5` không tính là thay đổi).
- Menu ngữ cảnh thêm **Set DEFAULT** (cần `RowChange.Update` hỗ trợ giá trị đặc biệt `DEFAULT`, SQLite không có thì ẩn).

Text vẫn là lối thoát cho mọi kiểu: picker chỉ là trợ giúp, không chặn người gõ thẳng.

## 3. Đề xuất theo DataType

Ưu tiên: **P1** làm ngay (chỉ cần `ColumnEdit` + `FilterValues`), **P2** cần thêm introspection, **P3** cần đổi binding ở core hoặc ít dùng.

| Nhóm (`EditKind`) | Kiểu thực tế | Inline | Popup / Panel | Kiểm tra & chuẩn hoá | Ưu tiên |
| --- | --- | --- | --- | --- | --- |
| **Boolean** | `bool`, `boolean`, `bit(1)`, MySQL `tinyint(1)` | Checkbox vẽ trong ô; Space bật/tắt; click 1 lần. Nullable: vòng ba trạng thái true → false → NULL | Panel: radio True / False / NULL | `FilterValues.boolean`; ghi `true`/`false` (PG, CockroachDB, H2) hoặc `1`/`0` (MySQL, SQLite, SQL Server `bit`) | P1 |
| **Integer** | `int2/4/8`, `smallint`, `bigint`, `tinyint`, `serial`, `year` | Text căn phải, chặn ký tự lạ; ⌥↑/↓ tăng giảm 1 | Panel: hiện thêm dạng hex, khoảng hợp lệ | Khoảng theo kiểu (tinyint −128…127, `unsigned`, int32/int64); bỏ `_` và dấu cách | P1 |
| **Decimal** | `numeric(p,s)`, `decimal`, `money` | Như Integer | Panel: cảnh báo làm tròn | Số chữ số ≤ p, phần lẻ ≤ s (cảnh báo khi phải làm tròn, không âm thầm cắt) | P1 |
| **Float** | `real`, `float`, `double` | Như Integer | — | Cho `NaN`, `Infinity` với PG; dạng `1e-5` | P1 |
| **Text ngắn** | `varchar(n)`, `char(n)`, `name` | Text field + bộ đếm `12/255` khi ≥ 80% giới hạn, đỏ khi vượt | Popup nhiều dòng như hiện tại | Độ dài ≤ n; `char(n)` báo sẽ bị đệm dấu cách | P1 |
| **Text dài** | `text`, `clob`, `longtext`, `citext` | Text field; giá trị có xuống dòng thì Enter mở popup thay vì sửa một dòng | Panel: bật "hiện khoảng trắng", word wrap, và phân biệt rõ **chuỗi rỗng** với **NULL** (nút "Empty string" riêng) | — | P1 |
| **Date** | `date` | Text, nhận `2026-10-31`, `today`, `today-7d` (gợi ý "= Fri 31 Oct 2026" như filter builder) | Popup lịch tháng; Today / Yesterday; ←↑→↓ chọn ngày, PgUp/PgDn đổi tháng | `FilterValues.temporal` → ISO `yyyy-MM-dd` | P1 |
| **DateTime** | `timestamp`, `timestamptz`, `datetime`, `datetime2` | Như Date, nhận `now`, `now-2h` | Popup lịch + ô giờ `HH:mm:ss.SSS`; nút Now; với `timestamptz` hiện múi giờ (mặc định múi máy, đổi được) và giá trị UTC | Ra `yyyy-MM-dd HH:mm:ss[.f][±hh:mm]`; dùng dấu cách (MySQL, SQLite không nhận `T` ở mọi chỗ); cắt phần giây lẻ theo precision cột | P1 |
| **Time** | `time`, `timetz` | Text `13:45`, `13:45:30` | Popup: spinner giờ/phút/giây | → `HH:mm:ss` | P1 |
| **UUID** | `uuid`, `uniqueidentifier` | Text có kiểm tra | Nút **Generate** (v4, v7) trong popup/panel; menu ngữ cảnh "Generate UUID" cho nhiều ô đang chọn | `FilterValues.uuid` → chữ thường có gạch; nhận cả dạng `{…}` và không gạch | P1 |
| **JSON** | `json`, `jsonb` (và cột text có giá trị bắt đầu `{`/`[`) | Không sửa một dòng: Enter mở popup | Popup/panel: editor có tô màu (đã có `jsonTokens`), báo lỗi parse kèm dòng/cột, Format / Minify; giai đoạn sau sửa trực tiếp trên Tree (sửa lá, thêm/xoá key) | Parse bằng kotlinx; `jsonb` ghi bản rút gọn | P1 (editor), P3 (sửa trên Tree) |
| **Generated** | `autoIncrement`, identity, `serial` PK, cột computed | Dòng mới: `<auto>` mờ, không sửa; dòng cũ: chỉ đọc trừ khi người dùng bật "Allow editing" | Panel hiện default (`nextval(...)`, `now()`) | — | P1 |
| **NOT NULL / default** | mọi cột | Ẩn/khóa **Set NULL** khi `nullable = false`; `<default: now()>` thay cho `<default>` | **Set DEFAULT** | Cảnh báo dòng mới thiếu cột NOT NULL không default trước khi Save | P1 |
| **Enum** | PG `CREATE TYPE … AS ENUM`, MySQL `enum('a','b')`, `CHECK (c IN (…))` | Combo box: gõ để lọc, ↑↓ chọn | Panel: danh sách radio | Giá trị phải nằm trong danh sách | P2 — cần đọc `pg_enum`, `COLUMN_TYPE` của MySQL, phân tích CHECK |
| **Set** | MySQL `set('a','b')` | Hiện dạng chip | Popup: danh sách checkbox | Ghép `a,b` theo thứ tự khai báo | P2 |
| **Foreign key** | cột thuộc FK một cột | Text + nút ▾ | Popup tìm kiếm trên bảng được tham chiếu: `SELECT pk, <cột hiển thị> … WHERE … LIKE ? LIMIT 50`, hiện pk + cột chữ đầu tiên (`name`, `title`, `email`…); Enter chọn | Cảnh báo (không chặn) khi giá trị không có ở bảng cha | P2 |
| **Array** | PG `int[]`, `text[]` (`_int4`…) | Hiện `{1,2,3}`, Enter mở popup | Popup/panel: danh sách phần tử, thêm/xoá/kéo đổi thứ tự, mỗi phần tử dùng editor của kiểu phần tử | Ghép literal PG `{a,"b c",NULL}` có escape | P3 |
| **Binary** | `bytea`, `blob`, `varbinary` | Chỉ đọc (như hiện tại) | Panel: **Load from file…**, **Save to file…**, Set NULL; sau đó sửa hex | Cần `BoundValue` mang `ByteArray` và `dialect.bind` gọi `setBytes` | P3 |
| **Khác (PG)** | `interval`, `inet`/`cidr`/`macaddr`, `bit(n)`, `hstore`, range (`tsrange`…), `xml`, `geometry` | Text như hiện tại | Lần lượt: interval → các ô năm/tháng/ngày/giờ; `bit(n)` → dãy toggle; `hstore` → bảng key/value; range → hai biên + `[`/`(`; `xml` → editor có kiểm tra well-formed; geometry → WKT | Kiểm tra cú pháp nhẹ, còn lại để server | P3 |

Redis, MongoDB và etcd không đi qua `TableEditModel`; xem mục 7, làm ở một phần riêng.

## 4. Lộ trình

1. **Nền** (P1): `ColumnEdit` + `normalize` trong core (test thuần như `FilterValuesTest`); truyền `TableDetails` vào `TableEditModel`; sửa luồng `commitEdit` (rỗng → NULL, invalid chặn commit, so sánh sau chuẩn hoá). Riêng bước này đã sửa lỗi 1, 2 ở mục 1.
2. **Editor P1**: Boolean (checkbox trong ô), Date/DateTime/Time (lịch + giờ), Number, UUID, JSON popup, Text đếm độ dài, Generated/NOT NULL/Set DEFAULT. Mỗi editor một snapshot test trong `GridSnapshotTest`, kiểm tra round-trip qua SQLite và PostgreSQL trong `RelationalContract`.
3. **Introspection P2**: thêm `allowedValues` vào `ColumnInfo` (PG `pg_enum`, MySQL `COLUMN_TYPE`, CHECK IN), rồi Enum/Set combo; FK lookup popup.
4. **P3**: binary qua file (đổi `BoundValue`), array, rồi các kiểu riêng của PostgreSQL theo nhu cầu.

## 5. Câu hỏi còn mở

- Giá trị invalid: **chặn** commit (đề xuất, vì Save cả lô sẽ hỏng) hay chỉ cảnh báo? Kiểu `Other` luôn chỉ cảnh báo vì app không biết hết cú pháp server nhận.
- `timestamptz`: nhập theo múi giờ máy hay UTC? Đề xuất múi giờ máy, có hiện quy đổi UTC ngay dưới ô như `TimestampInfo`.
- Popup FK lấy cột hiển thị nào khi bảng cha không có cột chữ rõ ràng: chỉ pk, hay cho người dùng chọn và nhớ theo bảng?

## 7. Redis, MongoDB, etcd (làm ở phần riêng)

Ba engine này không dùng `DataGrid` + `TableEditModel`, nên phần trên không tự áp dụng. Hiện trạng và việc cần làm:

| Engine | Màn hình | Đang sửa thế nào | Đề xuất |
| --- | --- | --- | --- |
| **Redis** (Valkey, KeyDB, Dragonfly) | `keyvalue/KeyBrowserView.kt`, core `KeyValueConnection.edit(KeyEdit)` | String: `TextArea`; hash/list/set/zset: danh sách phần tử với ô text; TTL: ô số giây | String: nhận diện JSON → dùng lại editor JSON (Format/Minify/báo lỗi); số → kiểm tra như `EditKind.Integer/Float` (cho `INCRBY`); zset score: ô số có ⌥↑/↓; TTL: chọn nhanh (1 phút, 1 giờ, 1 ngày) và hiện giờ hết hạn; stream: form thêm entry dạng field/value; binary-safe value: Hex view + nạp từ file |
| **MongoDB** (FerretDB) | `documents/DocumentBrowserView.kt`, `DocumentConnection.replace/insert` | Cả document là một khối JSON trong `TextArea`, lưu bằng `replaceOne` | Sửa theo field trên Tree: mỗi lá chọn editor theo kiểu BSON (`ObjectId` → kiểm tra 24 hex + Generate; `ISODate` → dùng lại `TemporalPickerContent`; `bool` → checkbox; `NumberInt/Long/Decimal128` → kiểm tra khoảng như `EditKind.Integer/Decimal`; `UUID`/`BinData` → như UUID/binary). Lưu bằng `$set`/`$unset` từng field thay cho thay cả document, để không ghi đè thay đổi người khác. Cần một "`ColumnEdit` cho BSON" ở core, đặt cạnh `core/edit` |
| **etcd** | dùng chung `KeyBrowserView` | Value là text | Như Redis string (JSON/số); thêm lease thay cho TTL (gắn/thu hồi lease); value là bytes nên cần Hex view |

Phần dùng lại được đã viết không phụ thuộc grid: `ColumnEdit.check` (core), `TemporalPickerContent`, `CheckBoxGlyph`, `reformatJson`, `EditHint` (`app/grid/CellEditors.kt`). Khi làm phần này nên chuyển chúng sang một package chung (vd. `app/editors`) thay vì import từ `grid`.

