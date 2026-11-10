//! `SqlFixTest`, `SchemaDigestTest`, `ChartSpecTaskTest` and `AssistRunnerTest`, ported. `QueryError.of` (reading
//! JDBC exceptions) has no Rust counterpart: engines build their errors directly.

use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tg_agent::assist::{
    self, AssistInvalid, AssistResult, AssistRunner, AssistTask, ChartSpecTask, JsonObject,
    MissingKind, MissingName, SchemaDigest, SqlErrors, SqlFix, SqlFixTask, SqlNameFixer,
    review_fix,
};
use tg_agent::{
    AgentAdapter, AgentDescriptor, AgentError, AgentEvent, AgentMessage, AgentRequest,
    AgentTransport, EventStream, Role,
};
use tg_chart::{Aggregate, ChartType, ColumnProfiler, Measure, TimeBucket};
use tg_driver::QueryError;
use tg_sql::completion::{ColumnEntry, EmptySchema, Relation, SchemaProvider, TableEntry};
use tg_sql::model::{
    ColumnInfo, DatabaseType, EngineFamily, ForeignKeyInfo, IndexInfo, Namespace, ResultColumn,
    TableDetails, TableRef, jdbc_types as types,
};
use tg_sql::{SqlDialect, SqlSyntax};

struct Schema;

fn column_entry(name: &str, type_: &str, primary_key: bool) -> ColumnEntry {
    ColumnEntry {
        name: name.into(),
        type_: type_.into(),
        primary_key,
    }
}

impl SchemaProvider for Schema {
    fn default_namespace(&self) -> Option<String> {
        Some("public".into())
    }

    fn namespaces(&self) -> Vec<String> {
        vec!["public".into()]
    }

    fn tables(&self, _: Option<&str>) -> Option<Vec<TableEntry>> {
        Some(
            ["users", "orders", "order_items"]
                .iter()
                .map(|t| TableEntry {
                    name: (*t).into(),
                    is_view: false,
                })
                .collect(),
        )
    }

    fn columns(&self, _: Option<&str>, table: &str) -> Option<Vec<ColumnEntry>> {
        match table.to_lowercase().as_str() {
            "users" => Some(vec![
                column_entry("id", "int", true),
                column_entry("email", "text", false),
                column_entry("emails", "text[]", false),
                column_entry("created_at", "timestamp", false),
            ]),
            "orders" => Some(vec![
                column_entry("id", "int", true),
                column_entry("user_id", "int", false),
                column_entry("total", "numeric", false),
                column_entry("Status", "text", false),
            ]),
            _ => None,
        }
    }

    fn relations(&self, _: Option<&str>, _: &str) -> Option<Vec<Relation>> {
        None
    }
}

fn postgres() -> SqlDialect {
    SqlDialect::of(EngineFamily::PostgreSQL).unwrap()
}

fn error(message: &str) -> QueryError {
    QueryError::new(message)
}

#[test]
fn reads_the_unknown_name_from_each_engines_wording() {
    use MissingKind::{Column, Table};
    let cases = [
        (
            "ERROR: column \"usr_id\" does not exist\n  Position: 8",
            Column,
            "usr_id",
        ),
        ("ERROR: column o.usr_id does not exist", Column, "usr_id"),
        ("ERROR: relation \"userz\" does not exist", Table, "userz"),
        ("Unknown column 'usr_id' in 'field list'", Column, "usr_id"),
        ("Table 'shop.userz' doesn't exist", Table, "userz"),
        (
            "[SQLITE_ERROR] SQL error or missing database (no such column: usr_id)",
            Column,
            "usr_id",
        ),
        (
            "[SQLITE_ERROR] SQL error or missing database (no such table: userz)",
            Table,
            "userz",
        ),
        ("Invalid column name 'usr_id'.", Column, "usr_id"),
        ("Invalid object name 'dbo.userz'.", Table, "userz"),
        (
            "Binder Error: Referenced column \"usr_id\" not found in FROM clause!",
            Column,
            "usr_id",
        ),
        (
            "Catalog Error: Table with name userz does not exist!",
            Table,
            "userz",
        ),
    ];
    for (message, kind, name) in cases {
        assert_eq!(
            SqlErrors::missing_name(&error(message)),
            Some(MissingName {
                kind,
                name: name.into()
            }),
            "{message}"
        );
    }
    assert_eq!(
        SqlErrors::missing_name(&error("ERROR: syntax error at or near \"FORM\"")),
        None
    );
}

#[test]
fn a_misspelt_column_or_table_is_replaced_by_the_one_closest_name() {
    let fixer = SqlNameFixer::new(postgres());
    let column = fixer
        .fix(
            "SELECT o.usr_id, total FROM orders o WHERE o.usr_id > 1",
            &error("column o.usr_id does not exist"),
            &Schema,
        )
        .unwrap();
    assert_eq!(
        column.sql.as_deref(),
        Some("SELECT o.user_id, total FROM orders o WHERE o.user_id > 1")
    );
    assert_eq!(
        column.explanation,
        "There is no column usr_id; the closest is user_id."
    );
    let table = fixer
        .fix(
            "SELECT * FROM userz",
            &error("relation \"userz\" does not exist"),
            &Schema,
        )
        .unwrap();
    assert_eq!(table.sql.as_deref(), Some("SELECT * FROM users"));
    // Swapped letters are one typo.
    let swapped = fixer
        .fix(
            "SELECT emial FROM users",
            &error("column \"emial\" does not exist"),
            &Schema,
        )
        .unwrap();
    assert_eq!(swapped.sql.as_deref(), Some("SELECT email FROM users"));
}

#[test]
fn names_that_need_quotes_get_them_and_quoted_ones_stay_quoted() {
    let fixer = SqlNameFixer::new(postgres());
    let fix =
        |sql: &str, message: &str| fixer.fix(sql, &error(message), &Schema).and_then(|f| f.sql);
    assert_eq!(
        fix(
            "SELECT statuss FROM orders",
            "column \"statuss\" does not exist"
        )
        .as_deref(),
        Some("SELECT \"Status\" FROM orders")
    );
    assert_eq!(
        fix(
            "SELECT \"usr_id\" FROM orders",
            "column \"usr_id\" does not exist"
        )
        .as_deref(),
        Some("SELECT \"user_id\" FROM orders")
    );
    // MySQL keeps its own quotes.
    let mysql = SqlNameFixer::new(SqlDialect::of(EngineFamily::MySQL).unwrap());
    let fixed = mysql
        .fix(
            "SELECT `usr_id` FROM orders",
            &error("Unknown column 'usr_id' in 'field list'"),
            &Schema,
        )
        .and_then(|f| f.sql);
    assert_eq!(fixed.as_deref(), Some("SELECT `user_id` FROM orders"));
}

#[test]
fn no_fix_when_nothing_is_close_two_names_are_equally_close_or_the_schema_isnt_loaded() {
    let fixer = SqlNameFixer::new(postgres());
    assert!(
        fixer
            .fix(
                "SELECT nonsense FROM orders",
                &error("column \"nonsense\" does not exist"),
                &Schema
            )
            .is_none()
    );
    assert!(
        fixer
            .fix(
                "SELECT emaill FROM users",
                &error("column \"emaill\" does not exist"),
                &Schema
            )
            .is_none()
    );
    assert!(
        fixer
            .fix(
                "SELECT usr_id FROM orders",
                &error("column \"usr_id\" does not exist"),
                &EmptySchema
            )
            .is_none()
    );
    let missing = MissingName {
        kind: MissingKind::Column,
        name: "usr_id".into(),
    };
    assert_eq!(
        fixer.near_misses("SELECT usr_id FROM orders", &missing, &Schema, 3),
        ["user_id"]
    );
}

#[test]
fn review_flags_a_fix_that_writes_where_the_original_read() {
    assert!(
        review_fix(
            "SELECT usr_id FROM orders",
            "SELECT user_id FROM orders",
            SqlSyntax::POSTGRESQL
        )
        .is_empty()
    );
    assert_eq!(
        review_fix(
            "SELECT * FROM orders",
            "DELETE FROM orders",
            SqlSyntax::POSTGRESQL
        ),
        [
            "DELETE without WHERE affects every row",
            "The fix changes data or schema (DELETE); the original only read."
        ]
    );
    assert_eq!(
        review_fix(
            "UPDATE t SET a = 1 WHERE id = 1",
            "DELETE FROM t WHERE id = 1",
            SqlSyntax::POSTGRESQL
        ),
        ["The fix runs DELETE instead of UPDATE."]
    );
}

fn object(text: &str) -> JsonObject {
    serde_json::from_str::<serde_json::Value>(text)
        .unwrap()
        .as_object()
        .unwrap()
        .clone()
}

#[test]
fn the_agents_fix_must_be_one_changed_statement_or_none_with_a_reason() {
    let mut query_error = error("column \"usr_id\" does not exist");
    query_error.sql_state = Some("42703".into());
    query_error.position = Some(7);
    let task = SqlFixTask {
        dialect: postgres(),
        engine: "PostgreSQL".into(),
        statement: "SELECT usr_id FROM orders".into(),
        error: query_error,
        schema: Some("orders(id int pk, user_id int)".into()),
        near_misses: vec!["user_id".into()],
    };
    let parse = |json: &str| task.parse(&object(json));
    assert_eq!(
        parse(r#"{"sql": "SELECT user_id FROM orders;", "explanation": "Renamed."}"#),
        Ok(SqlFix {
            sql: Some("SELECT user_id FROM orders".into()),
            explanation: "Renamed.".into()
        })
    );
    assert_eq!(
        parse(r#"{"sql": null, "explanation": "No such table."}"#),
        Ok(SqlFix {
            sql: None,
            explanation: "No such table.".into()
        })
    );
    assert!(
        parse(r#"{"sql": "SELECT 1; SELECT 2", "explanation": "x"}"#)
            .unwrap_err()
            .0[0]
            .contains("exactly one statement")
    );
    assert!(
        parse(r#"{"sql": "SELECT  usr_id\nFROM orders", "explanation": "x"}"#)
            .unwrap_err()
            .0[0]
            .contains("same statement")
    );
    assert!(parse(r#"{"sql": "SELECT 1"}"#).is_err());
    let prompt = task.prompt();
    assert!(prompt.contains("SQLSTATE: 42703"));
    assert!(prompt.contains("near: usr_id FROM orders"));
    assert!(prompt.contains("close to the unknown one: user_id"));
    assert!(prompt.contains("orders(id int pk, user_id int)"));
    let unshared = SqlFixTask {
        dialect: postgres(),
        engine: "PostgreSQL".into(),
        statement: "SELECT 1".into(),
        error: error("boom"),
        schema: None,
        near_misses: Vec::new(),
    };
    assert!(unshared.prompt().contains("Not shared"));
}

#[test]
fn a_table_lists_its_columns_keys_and_indexes() {
    let main = Namespace::new(None, Some("main"));
    let column = |name: &str, type_name: &str, pk: bool, nullable: bool, default: Option<&str>| {
        let mut c = ColumnInfo::new(name, type_name);
        c.primary_key = pk;
        c.nullable = nullable;
        c.default_value = default.map(str::to_owned);
        c.jdbc_type = types::OTHER;
        c
    };
    let details = TableDetails {
        table: TableRef::new(main.clone(), "orders"),
        columns: vec![
            column("id", "INTEGER", true, false, None),
            column("user_id", "INTEGER", false, true, None),
            column("status", "TEXT", false, true, Some("'new'")),
        ],
        indexes: vec![IndexInfo {
            name: "orders_user".into(),
            columns: vec!["user_id".into()],
            unique: false,
        }],
        foreign_keys: vec![ForeignKeyInfo {
            name: "fk_user".into(),
            columns: vec!["user_id".into()],
            referenced_table: TableRef::new(main, "users"),
            referenced_columns: vec!["id".into()],
            on_update: None,
            on_delete: None,
        }],
        primary_key_name: None,
    };
    assert_eq!(
        SchemaDigest::table(&details),
        "Columns:\n  id integer pk not null\n  user_id integer\n  status text default 'new'\nPrimary key: id\nForeign key: (user_id) -> users(id)\nIndex orders_user: (user_id)"
    );
}

fn chart_task(samples: HashMap<String, Vec<String>>) -> ChartSpecTask {
    let columns = vec![
        ResultColumn::new("created_at", "timestamp", types::TIMESTAMP),
        ResultColumn::new("region", "text", types::VARCHAR),
        ResultColumn::new("total", "numeric", types::NUMERIC),
    ];
    let rows: Vec<Vec<Option<String>>> = vec![
        vec![
            Some("2024-01-01 10:00".into()),
            Some("north".into()),
            Some("10".into()),
        ],
        vec![
            Some("2024-02-01 10:00".into()),
            Some("south".into()),
            Some("20".into()),
        ],
    ];
    ChartSpecTask {
        request: "revenue by month per region".into(),
        engine: "PostgreSQL".into(),
        sql: "SELECT * FROM orders".into(),
        profiles: ColumnProfiler::profile(&columns, &rows),
        samples,
        truncated: true,
    }
}

#[test]
fn the_prompt_describes_columns_and_values_only_when_given() {
    let prompt = chart_task(HashMap::new()).prompt();
    assert!(
        prompt.contains("- created_at: temporal (timestamp), 2 distinct values"),
        "{prompt}"
    );
    assert!(prompt.contains("the first ones only"));
    assert!(!prompt.contains("north"));
    let with = chart_task(HashMap::from([(
        "region".to_owned(),
        vec!["north".into(), "south".into()],
    )]))
    .prompt();
    assert!(with.contains("e.g. \"north\", \"south\""));
}

#[test]
fn reads_a_spec_treating_null_and_none_as_absent() {
    let task = chart_task(HashMap::new());
    let proposal = task
        .parse(&object(
            r#"{"type": "Line", "x": "created_at", "y": [{"column": "total", "aggregate": "sum"}], "series": "region", "bucket": "month", "horizontal": null, "explanation": "Monthly revenue.", "assumptions": ["total is revenue"]}"#,
        ))
        .unwrap();
    assert_eq!(proposal.spec.chart_type, ChartType::Line);
    assert_eq!(proposal.spec.y, [Measure::new("total", Aggregate::Sum)]);
    assert_eq!(proposal.spec.bucket, Some(TimeBucket::Month));
    assert_eq!(proposal.assumptions, ["total is revenue"]);
    let bars = task
        .parse(&object(r#"{"type": "bar", "x": "region", "y": [{"column": "total", "aggregate": "none"}], "series": "null", "bucket": null, "explanation": ""}"#))
        .unwrap();
    assert_eq!(bars.spec.series, None);
    assert_eq!(bars.spec.bucket, None);
}

#[test]
fn invented_columns_and_wrong_shapes_are_sent_back_with_reasons() {
    let task = chart_task(HashMap::new());
    let invented = task
        .parse(&object(
            r#"{"type": "bar", "x": "country", "y": [{"column": "revenue", "aggregate": "sum"}]}"#,
        ))
        .unwrap_err();
    assert_eq!(invented.0.len(), 2);
    assert!(
        invented
            .0
            .iter()
            .all(|r| r.contains("created_at, region, total")),
        "{invented:?}"
    );
    let shape = task
        .parse(&object(
            r#"{"type": "donut", "x": "region", "y": "total", "bucket": "fortnight"}"#,
        ))
        .unwrap_err();
    assert_eq!(shape.0.len(), 3, "{shape:?}");
}

// The runner, with answers from a script.

struct Scripted {
    answers: Vec<String>,
    requests: Arc<Mutex<Vec<AgentRequest>>>,
    descriptor: AgentDescriptor,
}

impl Scripted {
    fn new(answers: &[&str]) -> Self {
        Self {
            answers: answers.iter().map(|a| (*a).to_owned()).collect(),
            requests: Arc::default(),
            descriptor: AgentDescriptor {
                id: "test".into(),
                name: "Test agent".into(),
                transport: AgentTransport::Http,
                detail: None,
            },
        }
    }
}

#[async_trait]
impl AgentAdapter for Scripted {
    fn descriptor(&self) -> &AgentDescriptor {
        &self.descriptor
    }

    fn send(&self, request: AgentRequest) -> EventStream {
        let mut requests = self.requests.lock().unwrap();
        requests.push(request);
        let answer = self.answers[requests.len() - 1].clone();
        EventStream::spawn(move |events| async move {
            events.send(AgentEvent::Thinking).await;
            let chars: Vec<char> = answer.chars().collect();
            for chunk in chars.chunks(7) {
                events
                    .send(AgentEvent::TextDelta(chunk.iter().collect()))
                    .await;
            }
            events.send(AgentEvent::Completed(answer)).await;
            Ok(())
        })
    }
}

/// Wants `{"value": "<non-empty>"}` and nothing called "bad".
struct Echo;

impl AssistTask for Echo {
    type Output = String;

    fn name(&self) -> &str {
        "echo"
    }

    fn system(&self) -> String {
        "system".into()
    }

    fn prompt(&self) -> String {
        "prompt".into()
    }

    fn parse(&self, json: &JsonObject) -> Result<String, AssistInvalid> {
        let value = assist::require_text(json, "value")?;
        if value == "bad" {
            return Err(AssistInvalid::one("\"value\" can't be bad."));
        }
        Ok(value)
    }
}

#[tokio::test]
async fn takes_the_json_out_of_an_answer_with_prose_and_fences_around_it() {
    let agent =
        Scripted::new(&["Sure! Here you go:\n```json\n{\"value\": \"ok\"}\n```\nLet me know."]);
    let result = AssistRunner::default().run(&agent, &Echo).await;
    assert!(
        matches!(&result, AssistResult::Ok { value, .. } if value == "ok"),
        "{result:?}"
    );
    let request = agent.requests.lock().unwrap()[0].clone();
    assert_eq!(request.system.as_deref(), Some("system"));
    assert_eq!(request.reminder.as_deref(), Some(assist::REMINDER));
    assert_eq!(request.messages, [AgentMessage::new(Role::User, "prompt")]);
}

#[tokio::test]
async fn an_unusable_answer_goes_back_once_with_the_reasons_then_gives_up() {
    let fixed = Scripted::new(&["{\"value\": \"bad\"}", "{\"value\": \"good\"}"]);
    let result = AssistRunner::default().run(&fixed, &Echo).await;
    assert!(matches!(&result, AssistResult::Ok { value, .. } if value == "good"));
    let retry = fixed.requests.lock().unwrap()[1].messages.clone();
    assert_eq!(retry.len(), 3);
    assert_eq!(retry[1].role, Role::Assistant);
    assert!(retry[2].text.contains("\"value\" can't be bad."));
    let stubborn = Scripted::new(&["no json here", "{\"value\": 3}"]);
    let invalid = AssistRunner::default().run(&stubborn, &Echo).await;
    assert_eq!(
        invalid,
        AssistResult::Invalid(vec!["\"value\" must be a non-empty string.".into()])
    );
    assert!(
        stubborn.requests.lock().unwrap()[1]
            .messages
            .last()
            .unwrap()
            .text
            .contains("contained no JSON object")
    );
}

struct Failing(AgentDescriptor, bool);

#[async_trait]
impl AgentAdapter for Failing {
    fn descriptor(&self) -> &AgentDescriptor {
        &self.0
    }

    fn send(&self, _: AgentRequest) -> EventStream {
        let silent = self.1;
        EventStream::spawn(move |_events| async move {
            if silent {
                std::future::pending::<()>().await;
            }
            Err(AgentError("No API key".into()))
        })
    }
}

#[tokio::test]
async fn agent_failures_and_timeouts_come_back_as_a_message() {
    let descriptor = |name: &str| AgentDescriptor {
        id: name.into(),
        name: name.into(),
        transport: AgentTransport::Cli,
        detail: None,
    };
    let failing = Failing(descriptor("X"), false);
    assert_eq!(
        AssistRunner::default().run(&failing, &Echo).await,
        AssistResult::Failed("No API key".into())
    );
    let silent = Failing(descriptor("Slow"), true);
    let runner = AssistRunner {
        timeout: Duration::from_millis(50),
        working_directory: None,
    };
    let failed = runner.run(&silent, &Echo).await;
    assert!(
        matches!(&failed, AssistResult::Failed(m) if m.starts_with("Slow didn't answer")),
        "{failed:?}"
    );
}

#[test]
fn json_extraction_prefers_the_last_fence_then_the_last_balanced_object() {
    let fenced =
        assist::extract("```json\n{\"v\":\"a\"}\n```\n```json\n{\"v\":\"b\"}\n```").unwrap();
    assert_eq!(fenced["v"], "b");
    assert_eq!(
        assist::text(
            &assist::extract("first {\"v\": 1} then {\"v\": \"}{\"} done").unwrap(),
            "v"
        )
        .as_deref(),
        Some("}{")
    );
    assert!(assist::extract("[1, 2]").is_none());
    assert!(assist::extract("{ not json").is_none());
}

#[test]
fn schema_types_are_available() {
    let _ = DatabaseType::POSTGRESQL;
}
