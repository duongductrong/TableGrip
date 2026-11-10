//! `ChatStoreTest` and `MentionTextTest`, ported.

use indexmap::IndexMap;
use std::collections::{BTreeMap, HashMap};
use tg_agent::chat::{
    ChatStore, RunState, SavedActivity, SavedChat, SavedChatMessage, SavedMention, SavedRun,
};
use tg_agent::mention::{BUDGET, CELL_BUDGET, CELL_ROWS, FIRST, Mention, MentionText};
use tg_agent::{ActivityState, Role};
use tg_sql::model::{ResultColumn, jdbc_types as types};

fn chat(id: &str, updated_at: i64) -> SavedChat {
    let mut user = SavedChatMessage::new(Role::User, "rows in \u{e000}?");
    user.mentions = IndexMap::from([(
        "\u{e000}".to_owned(),
        SavedMention {
            label: "orders".into(),
            context: "table orders: id int".into(),
        },
    )]);
    let mut answer = SavedChatMessage::new(Role::Assistant, "```sql\nSELECT 1\n```");
    answer.reasoning = "count".into();
    answer.thought_seconds = Some(2);
    answer.activities = vec![SavedActivity {
        id: "a".into(),
        label: "Read schema".into(),
        state: ActivityState::Done,
    }];
    answer.runs = vec![SavedRun {
        block: 0,
        state: RunState::Done,
        summary: Some("1 row(s) sent to the agent".into()),
    }];
    let mut note = SavedChatMessage::new(Role::User, "result");
    note.note = true;
    note.label = Some("Result shared".into());
    note.origin = Some(1);
    note.origin_block = Some(0);
    SavedChat {
        id: id.into(),
        title: id.into(),
        created_at: 1,
        updated_at,
        agent: Some("cli:claude".into()),
        messages: vec![user, answer, note],
    }
}

#[test]
fn round_trips_a_chat() {
    let dir = tempfile::tempdir().unwrap();
    let store = ChatStore::new(dir.path());
    let chat = chat("a", 5);
    store.save(&chat).unwrap();
    assert_eq!(store.load("a"), Some(chat));
}

#[test]
fn writes_what_kotlinx_writes() {
    let dir = tempfile::tempdir().unwrap();
    let store = ChatStore::new(dir.path());
    let mut small = chat("b", 3);
    small.messages.truncate(1);
    small.messages[0].mentions.clear();
    store.save(&small).unwrap();
    assert_eq!(
        std::fs::read_to_string(dir.path().join("b.json")).unwrap(),
        "{\"id\":\"b\",\"title\":\"b\",\"createdAt\":1,\"updatedAt\":3,\"agent\":\"cli:claude\",\"messages\":[{\"role\":\"User\",\"text\":\"rows in \u{e000}?\"}]}"
    );
}

#[test]
fn lists_newest_first_and_skips_broken_files() {
    let dir = tempfile::tempdir().unwrap();
    let store = ChatStore::new(dir.path());
    store.save(&chat("old", 1)).unwrap();
    store.save(&chat("new", 9)).unwrap();
    std::fs::write(dir.path().join("broken.json"), "{ not json").unwrap();
    assert_eq!(
        store.list().into_iter().map(|c| c.id).collect::<Vec<_>>(),
        ["new", "old"]
    );
}

#[test]
fn renames_and_deletes() {
    let dir = tempfile::tempdir().unwrap();
    let store = ChatStore::new(dir.path());
    store.save(&chat("a", 5)).unwrap();
    store.rename("a", "Monthly revenue");
    assert_eq!(
        store.load("a").map(|c| c.title),
        Some("Monthly revenue".into())
    );
    store.delete("a");
    assert!(store.load("a").is_none());
    assert!(store.list().is_empty());
}

#[test]
fn never_reads_or_writes_outside_its_directory() {
    let dir = tempfile::tempdir().unwrap();
    let store = ChatStore::new(dir.path());
    assert!(store.save(&chat("../escape", 1)).is_err());
    assert!(store.load("../settings").is_none());
}

// Mentions

fn sql(source: &str, lines: std::ops::RangeInclusive<u32>, engine: &str, text: &str) -> Mention {
    Mention::Sql {
        source: source.into(),
        lines,
        engine: engine.into(),
        sql: text.into(),
    }
}

fn select() -> Mention {
    sql(
        "Query 1",
        12..=14,
        "PostgreSQL",
        "SELECT *\nFROM orders\nWHERE paid;\n",
    )
}

fn count() -> Mention {
    sql(
        "Query 2",
        3..=3,
        "PostgreSQL",
        "SELECT count(*) FROM customers",
    )
}

const A: char = FIRST;
const B: char = '\u{e001}';

#[test]
fn labels_name_the_source_and_lines() {
    assert_eq!(select().label(), "Query 1:12-14");
    assert_eq!(count().label(), "Query 2:3");
}

#[test]
fn plain_text_shows_each_mention_as_its_label() {
    let text = MentionText::new(format!("why is {A} slow?"), HashMap::from([(A, select())]));
    assert_eq!(text.plain(), "why is @Query 1:12-14 slow?");
}

#[test]
fn the_prompt_numbers_mentions_in_order_and_adds_what_each_one_is() {
    let text = MentionText::new(
        format!("compare {B} with {A}"),
        HashMap::from([(A, select()), (B, count())]),
    );
    assert_eq!(
        text.prompt(),
        "compare @Query 2:3 [1] with @Query 1:12-14 [2]\n\n# Mentioned\n\n[1] PostgreSQL SQL from Query 2, line 3:\n```sql\nSELECT count(*) FROM customers\n```\n\n[2] PostgreSQL SQL from Query 1, lines 12-14:\n```sql\nSELECT *\nFROM orders\nWHERE paid;\n```"
    );
}

#[test]
fn a_mention_used_twice_is_sent_once() {
    let text = MentionText::new(format!("{A} and again {A}"), HashMap::from([(A, select())]));
    assert_eq!(text.used().len(), 1);
    assert_eq!(
        text.prompt().lines().next().unwrap(),
        "@Query 1:12-14 [1] and again @Query 1:12-14 [1]"
    );
}

#[test]
fn a_failed_statement_reads_as_the_statement_and_its_error() {
    let error = Mention::Error {
        source: "Query 1".into(),
        engine: "SQLite".into(),
        sql: "SELECT amount FROM orders".into(),
        message: "no such column: amount".into(),
    };
    assert_eq!(error.label(), "Query 1 error");
    assert_eq!(
        error.context(),
        "This SQLite statement from Query 1 failed:\n```sql\nSELECT amount FROM orders\n```\nError: no such column: amount"
    );
}

#[test]
fn a_huge_mention_is_cut_to_the_budget_and_says_so() {
    let huge = sql("Query 1", 1..=1, "SQLite", &"x".repeat(BUDGET + 5));
    assert!(huge.context().contains(&format!(
        "{}\n… (5 more characters not shown)\n```",
        "x".repeat(BUDGET)
    )));
}

#[test]
fn a_table_reads_as_its_structure_once_it_is_known() {
    let table = |details: Option<&str>| Mention::Table {
        connection_id: "shop".into(),
        connection: "Shop".into(),
        engine: "SQLite".into(),
        namespace: Some("main".into()),
        name: "orders".into(),
        details: details.map(str::to_owned),
    };
    assert_eq!(table(None).label(), "orders");
    assert_eq!(
        table(None).context(),
        "SQLite table main.orders on connection Shop:\n(its structure couldn't be read)"
    );
    assert_eq!(
        table(Some("Table columns:\n  id integer pk")).context(),
        "SQLite table main.orders on connection Shop:\nTable columns:\n  id integer pk"
    );
}

fn cells(
    columns: Vec<ResultColumn>,
    rows: std::ops::RangeInclusive<u32>,
    values: Option<Vec<Vec<Option<String>>>>,
) -> Mention {
    Mention::Cells {
        source: "orders".into(),
        engine: "SQLite".into(),
        columns,
        rows,
        values,
        origin: None,
        keys: Vec::new(),
        key_values: None,
        notes: BTreeMap::new(),
    }
}

#[test]
fn cells_send_their_values_only_when_the_connection_shares_data() {
    let columns = vec![
        ResultColumn::new("id", "INTEGER", types::INTEGER),
        ResultColumn::new("note", "TEXT", types::OTHER),
    ];
    let shared = cells(
        columns.clone(),
        3..=4,
        Some(vec![
            vec![Some("3".into()), Some("a\nb|c".into())],
            vec![Some("4".into()), None],
        ]),
    );
    assert_eq!(shared.label(), "orders rows 3-4 · 2 cols");
    assert_eq!(
        shared.context(),
        "Cells from orders (SQLite), 2 column(s) × 2 row(s), rows 3-4:\n| id | note |\n| ---: | --- |\n| 3 | a b\\|c |\n| 4 | NULL |"
    );
    let withheld = cells(columns.clone(), 7..=7, None);
    assert_eq!(withheld.label(), "orders row 7 · 2 cols");
    assert!(withheld.context().ends_with("id integer | note text\n(Values not shared: the connection only shares its schema with agents.)"));
    assert_eq!(
        cells(columns[1..].to_vec(), 3..=4, None).label(),
        "orders.note rows 3-4"
    );
}

#[test]
fn cells_say_where_their_rows_come_from_which_rows_they_are_and_what_isnt_saved() {
    let mention = Mention::Cells {
        source: "orders".into(),
        engine: "SQLite".into(),
        columns: vec![ResultColumn::new("note", "TEXT", types::OTHER)],
        rows: 51..=53,
        values: Some(vec![
            vec![Some("NULL".into())],
            vec![Some("x".repeat(CELL_BUDGET + 5))],
        ]),
        origin: Some("SELECT * FROM orders WHERE total > 10 ORDER BY total DESC".into()),
        keys: vec![ResultColumn::new("id", "INTEGER", types::INTEGER)],
        key_values: Some(vec![vec![Some("7".into())], vec![Some("9".into())]]),
        notes: BTreeMap::from([(52, "edited".to_owned())]),
    };
    let context = mention.context();
    assert!(context.starts_with("Cells from orders (SQLite), 1 column(s) × 3 row(s), rows 51-53 of what this returns:\n```sql\nSELECT * FROM orders WHERE total > 10 ORDER BY total DESC\n```"), "{context}");
    assert!(context.contains("The primary key (id) comes first to identify the rows; it wasn't selected.\n| id | note |"), "{context}");
    assert!(context.contains("| 7 | \"NULL\" |"), "{context}");
    assert!(
        context.contains(&format!("| 9 | {}… |", "x".repeat(CELL_BUDGET))),
        "{context}"
    );
    assert!(
        context
            .ends_with("(1 more rows not shown)\nNot saved to the database yet: row 52 (edited)"),
        "{context}"
    );
}

#[test]
fn wide_cells_stay_within_the_budget() {
    let columns: Vec<ResultColumn> = (1..=40)
        .map(|i| ResultColumn::new(&format!("c{i}"), "TEXT", types::OTHER))
        .collect();
    let values = vec![vec![Some("v".repeat(CELL_BUDGET)); 40]; CELL_ROWS];
    let mention = Mention::Cells {
        source: "wide".into(),
        engine: "SQLite".into(),
        columns,
        rows: 1..=CELL_ROWS as u32,
        values: Some(values),
        origin: None,
        keys: Vec::new(),
        key_values: None,
        notes: BTreeMap::new(),
    };
    let context = mention.context();
    assert!(context.chars().count() < BUDGET + 100, "{}", context.len());
    assert!(context.ends_with("more characters not shown)"));
}

#[test]
fn columns_and_saved_queries_name_themselves() {
    let column = |name: &str, type_name: &str, primary_key: bool| Mention::Column {
        connection: "Shop".into(),
        engine: "SQLite".into(),
        table: "orders".into(),
        name: name.into(),
        type_name: type_name.into(),
        primary_key,
    };
    assert_eq!(column("total", "REAL", false).label(), "orders.total");
    assert_eq!(
        column("id", "INTEGER", true).context(),
        "Column orders.id of SQLite connection Shop: integer, primary key"
    );
    let query = Mention::Query {
        name: "Top customers".into(),
        sql: "SELECT 1".into(),
    };
    assert_eq!(query.label(), "Top customers");
    assert_eq!(
        query.context(),
        "Saved query \"Top customers\":\n```sql\nSELECT 1\n```"
    );
}

#[test]
fn a_removed_mention_is_not_sent_and_an_unknown_stand_in_is_dropped() {
    let text = MentionText::new(format!("just {B}this"), HashMap::from([(A, select())]));
    assert_eq!(text.prompt(), "just this");
}

/// The chats Kotlin's `ChatStore` wrote (`conformance/core/fixtures/chats`, recorded by `ChatAndDataFileFixtureTest`)
/// load, and saving them again writes the same bytes.
#[test]
fn reads_and_rewrites_the_chats_kotlin_wrote() {
    let fixture = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../conformance/core/fixtures/chats");
    let store = ChatStore::new(&fixture);
    let ids: Vec<String> = store.list().into_iter().map(|c| c.id).collect();
    assert_eq!(vec!["7d0c-41aa", "chat-empty"], ids);
    let chat = store.load("7d0c-41aa").unwrap();
    assert_eq!("Orders per customer — “top 10”", chat.title);
    assert_eq!(4, chat.messages.len());
    let first = &chat.messages[0];
    assert_eq!(
        vec!["\u{e000}", "\u{e001}"],
        first
            .mentions
            .keys()
            .map(String::as_str)
            .collect::<Vec<_>>()
    );
    assert_eq!("console 1:3-4", first.mentions["\u{e001}"].label);
    assert_eq!(ActivityState::Failed, chat.messages[1].activities[1].state);
    assert_eq!(Some(0), chat.messages[2].origin_block);

    let out = tempfile::tempdir().unwrap();
    let copy = ChatStore::new(out.path());
    for id in ids {
        copy.save(&store.load(&id).unwrap()).unwrap();
        let name = format!("{id}.json");
        assert_eq!(
            std::fs::read_to_string(fixture.join(&name)).unwrap(),
            std::fs::read_to_string(out.path().join(&name)).unwrap(),
            "{name}"
        );
    }
}
