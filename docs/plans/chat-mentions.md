# Chat agent mention plan (⌘L, `@`)

Evaluation and design of a Cursor-style mention feature for the chat agent, written on 2026-10-30 against commit `185e860`.

> **Status (2026-10-30):** P0 and P1 are done. P1 adds the `@` popup (open tabs, tables, columns, saved queries), ⌘L from the grid,
> "Add to Chat" in Explorer, and table structure (columns, keys, indexes) read at send time. Details are in section 5.
>
> P0: Pills sit inline in the text, ⌘L and the right-click "Add to Chat" menu work from the SQL
> console, a preview popup, copy/cut/paste keep pills, and "Ask in chat" inserts an error pill. Typing Vietnamese through an IME was tried
> by hand in the real app and works. The next step is P1.

## 1. Goals

Users point the agent at exactly what they want to ask about, instead of pasting SQL or describing it in words:

- **⌘L / Ctrl+L** where the user is working: the current selection, or the statement at the caret if nothing is selected. That item goes into the chat box as a
  pill, for example `@Console:3-5`, and the user keeps typing the question.
- **Typing `@`** to pick a table, column, open tab, saved query, and so on (P1).
- Pills sit **inline in the sentence**, so users can write things like "join `@orders` with `@customers` on `@Console:3-5`". The agent knows which
  item is mentioned at which position.

## 2. State before this work

| Item | Current state | Problem |
| --- | --- | --- |
| `AgentContext.systemPrompt` | Automatically sends the whole open console (≤ 4k characters) or the name of the table being viewed | Implicit context; users cannot say "ask about this part" |
| Schema in the prompt | Truncated at 40k characters | With a large schema, the table being asked about can be dropped |
| "Ask in chat" (FixView) | Overwrites `draft` | Loses what the user was in the middle of typing |
| Composer | `rememberTextFieldState()` lives inside the composable | Outside code (⌘L) cannot insert into it |
| ⌘L | Not used. Reformat is ⌘⌥L | No conflict |

## 3. Design (implemented in the spike)

### 3.1 Each mention is exactly one character

A mention is stored in the text as **one Unicode Private Use character** (U+E000…U+F8FF). That character is the id, pointing to a
`Mention` in the composer's store. This way, all editing behavior is correct without tracking character ranges:

- Backspace deletes the whole pill, because the pill is a single character.
- The caret cannot stop in the middle of a pill. Left/right arrows jump over the whole pill.
- Undo/redo, cut, and paste in the composer all keep pills intact, because the store keeps the mention until it is sent.
- A Private Use character pasted from outside that the store does not know is filtered out by an `InputTransformation`.

| Component | Location | Role |
| --- | --- | --- |
| `Mention` (sealed): `Sql`, `Error` | `core/.../agent/Mention.kt` | Snapshot of the mentioned item, with `label` and `context`; context is truncated at `Mention.Budget` |
| `MentionText` | same | Text with mentions: `plain` (`@label`) and `prompt` (numbered, with a "# Mentioned" section) |
| `ComposerState` | `app/.../agent/ComposerState.kt` | `TextFieldState` plus the mention store, held by `AgentViewModel`; `insert`, `remove`, `copy`, `paste`, `take`, `restore` |
| `PillLayout`, `drawPills`, `MentionMessageText`, `MentionPreview` | `app/.../agent/MentionPills.kt` | Pill positions in the displayed text, rounded-background drawing, pills in sent messages, preview popup |
| `ConsoleTab.mention()`, `mentionInChat()` | `app/.../console/ConsoleTab.kt` | The current selection or the statement at the caret, with line numbers |
| `Shortcuts.AddToChat` | `app/.../ui/Shortcuts.kt` | ⌘L, listed in the shortcuts dialog and in the editor's right-click menu |
| `TextClipboard` | `app/.../ui/Clipboard.kt` | System clipboard, replaceable with a fake in tests |

### 3.2 Display

- **Composer:** an `OutputTransformation` replaces each mention character with `" @label "` (thin spaces on both sides) and colors it with the accent.
  `drawBehind` + `onTextLayout` draw a rounded background, one block per line if the pill wraps. This is the same technique
  `SqlEditor` already uses for highlighting.
- **Sent messages:** a read-only `Text` uses `AnnotatedString` and the same `drawPills` function. The Copy button copies the `plain` version.
- **Preview:** clicking a pill (in the composer or in a sent message) opens a popup above it: label, engine, line numbers, SQL, and
  the error message for an `Error` pill. In the composer, the popup has a button to remove the pill from the message. Esc or clicking outside closes it.
- **Clipboard:** ⌘C/⌘X on a selection that contains pills copies `@label`, so the text still reads correctly when pasted elsewhere. Pasting that same
  content back into the composer restores the pills, even after the message has been sent (the pill gets a new character). A selection with no
  pills is left to the text field to handle as usual.

### 3.3 Sending to the agent

`ChatMessage` stores `text` (with mention characters) and `mentions`. Every place that builds an `AgentMessage` uses `mentionText.prompt`:

```
@Console:3-5 [1] why is it slow?

# Mentioned

[1] SQLite SQL from Console, lines 3-5:
```sql
SELECT * FROM orders WHERE total > 10
```
```

- A mention that appears several times is sent only once. A pill that was deleted is not sent.
- `AgentMessage` is still a `String`, so the CLI and Provider adapters do not need changes.
- Rewind puts both the text and the pills back into the composer (`composer.restore`).
- **Snapshot at mention time:** if the user edits the editor afterward, the chat history still matches what was asked.

### 3.4 Privacy

- ⌘L does nothing when the connection is at the `Off` level (`ConsoleTab.canAskAgent`). The `SchemaOnly` level still allows sending SQL, because SQL was
  already sent along with the open console.
- **P1, required before mentioning grid cells:** at `SchemaOnly`, send only column names/types and the row count, not values. The pill shows a warning
  icon with the hint "Enable sample data for this connection". Each mention follows the `aiAccess` of **its own connection**.

## 4. Spike results

| Question | Result | Checked by |
| --- | --- | --- |
| `OutputTransformation` turns 1 character into a longer label | Pass | `MentionComposerTest`, screenshot |
| Left/right arrows jump over the whole pill | Pass | `MentionComposerTest` |
| Backspace deletes the whole pill, ⌘Z restores it | Pass | `MentionComposerTest` |
| Unknown Private Use characters on paste are dropped | Pass | `MentionComposerTest` |
| The same mention is not inserted twice | Pass | `MentionComposerTest` |
| Prompt is numbered and includes context | Pass | `MentionTextTest` (core), `MentionComposerTest` |
| Line numbers for the statement at the caret / selection | Pass | `MentionComposerTest` |
| Typing Vietnamese (Telex/VNI) right next to a pill | Pass | Tried by hand in the real app |
| Click in the middle of a pill: caret stops at the edge, popup opens | Pass | `MentionComposerTest` |
| Copy/cut/paste keep pills, copy out as `@label` | Pass | `ComposerStateTest` |
| Right-click "Add to Chat" menu | Pass | `MentionComposerTest` |
| "Ask in chat" inserts an error pill, keeps what the user was typing | Pass | `MentionComposerTest` |

After P0, all `app` (100) and `core` (190) tests pass. During the work, `ConsoleFixTest` exposed a pre-existing race: the test clicked
"Fix with AI" a second time before the new card had settled. Fixed by waiting for the link to be ready before clicking.

## 5. Next steps

| Phase | Content | Estimate |
| --- | --- | --- |
| **P0 — done** | Vietnamese IME and mouse handling; copy/cut/paste keep pills; click a pill to preview; "Add to Chat" in the right-click menu; "Ask in chat" inserts an `Error` pill; length limit per mention | — |
| **P1 — done** | `@` popup; ⌘L and right-click menu on the grid; "Add to Chat" for tables/columns in Explorer; table mentions send detailed structure; do not resend a console that was fully mentioned | — |
| **P2** | More providers: `@history`, `@result`, `@explain`, `@connection`; ⌘⇧L opens a new chat (when there are several conversations) | as needed |

### What P1 did

| Part | Approach |
| --- | --- |
| New mention types | `Mention.Table`, `Column`, `Query`, `Cells` in `core/.../agent/Mention.kt` |
| `@` popup | `ComposerState.typing` picks up `@query` right before the caret (ignoring `@` in the middle of a word, such as an email). `MentionSuggestions` shows a list above the composer, grouped by type. ↑/↓ select, Enter/Tab insert a pill, Esc closes. `table.` lists that table's columns |
| Sources | `MentionSources.search`: open tabs (console, table), tables and columns of the current connection, saved queries. Ranked with the `fuzzyScore` from Go to Anything |
| Table structure | `Mention.Table` carries only the name at insert time. At send time, `MentionSources.complete` reads `tableDetails` and `SchemaDigest.table` writes out columns, types, PK, FK, indexes. The result is saved into the message, so later turns send exactly the same thing |
| ⌘L on the grid | `GridActions.onMention`: ⌘L or the right-click "Add to Chat" sends the selected block of cells. In a table viewer, ⌘L with no cell selected mentions the whole table |
| Explorer | An "Add to Chat" item in the right-click menu of table nodes and column nodes |
| Access | Every source checks the `AiAccess` of its own connection. `Off`: nothing. `SchemaOnly`: cells send only column names/types and the row count. `SchemaAndSamples`: values are sent too, up to 50 rows × 200 characters |
| Duplicate sending | `AgentContext.systemPrompt` drops the "open console" block when the message already mentions that whole console |

**Differences from the original design:**
- `search` runs synchronously and only reads the cache, instead of being `suspend` as the planned `MentionProvider` was. Tables load in the background and the list updates itself by reading `SchemaCache.version`. Only the read of table structure at send time is `suspend`.
- Explorer only has the right-click menu item, with no ⌘L yet, because the Explorer tree has no "selected node" state for a shortcut to rely on.
- The error line in Output reuses the existing "Ask in chat" (which already inserts an `Error` pill since P0), with no separate path added.

### P1 testing

`MentionSourcesTest` (UI, SQLite): `@ord` → Enter inserts the table, `@orders.to` → column, Esc closes the list, the prompt includes the table
structure; open tabs show when nothing has been typed; at `Off` there are no suggestions; cell values are sent only at `SchemaAndSamples`; a fully
mentioned console is not repeated in the system prompt. `ComposerStateTest`: `@` detection, ignoring email, selecting replaces it with a
pill. `DataGridTest`: ⌘L on the grid returns the correct block of cells. After P1, `app` (108) and `core` (194) tests all pass.

## 6. Remaining risks

- **IME:** during composition, `OutputTransformation` is still applied to the text. If composing right after a pill
  breaks, the fallback is to skip the transformation while composing, or to move ⌘L to a row of pills above the composer.
  The `Mention`/`MentionText` model works unchanged for both.
- **Duplicate labels:** two consoles with the same name produce the same label. The agent can still tell them apart by the `[n]` number, but the user
  cannot. Consider adding the connection name to the label when there is a clash.
- **Partial duplicate sending:** when only one statement in the open console is mentioned, the system prompt still sends the whole console, so that statement
  appears twice. Only a mention of the whole console is dropped.
- **The `@` popup has no table list for other connections:** only the current connection. Tables of other connections can still be mentioned
  through Explorer or an open tab.
- **Copy via the right-click menu in the composer** still follows the text field's default path, so it copies out the Private Use character.
  Only the ⌘C/⌘X shortcuts are intercepted.
- **Total length:** each mention is already truncated at 8,000 characters (error messages at 2,000), but there is no overall limit
  for many mentions in one message.
