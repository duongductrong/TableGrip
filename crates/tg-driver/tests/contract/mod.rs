//! What every relational engine must do for the explorer, table viewer, structure view and consoles to work: a port
//! of `RelationalContract.kt` plus the engine-independent cases of `DatabaseTest.kt` (paging, change sets, manual
//! commit, export, sample values, relations, cancellation, timeouts, read-only). Each engine's test calls [`run`] on a
//! fresh, empty database and [`read_only`] on a read-only connection to the same one.
#![allow(dead_code)]

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tg_driver::{
    Cell, DriverError, ExecOptions, PageRequest, QueryResult, RelationalConnection, ResultColumn,
    RowSink, TableQuery,
};
use tg_sql::edit::{self, BoundValue, RowChange};
use tg_sql::filter::SqlFilter;
use tg_sql::model::{ConstraintKind, TableRef, jdbc_types as types};
use tg_sql::{SortColumn, SortDirection};

/// The engine's SQL types and abilities, as `RelationalContract`'s constructor took them.
pub struct Options {
    pub int_type: &'static str,
    pub text_type: &'static str,
    /// Appended to each CREATE TABLE, e.g. an engine clause.
    pub table_suffix: &'static str,
    pub foreign_keys: bool,
    pub transactions: bool,
    /// A query that runs until cancelled.
    pub endless: &'static str,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            int_type: "INTEGER",
            text_type: "VARCHAR(100)",
            table_suffix: "",
            foreign_keys: true,
            transactions: true,
            endless: "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c",
        }
    }
}

fn exec() -> ExecOptions {
    ExecOptions::default()
}

async fn seed(db: &dyn RelationalConnection, o: &Options) {
    let q = |n: &str| db.dialect().quote_identifier(n);
    let mut session = db.open_session().await.unwrap();
    let statements = [
        format!(
            "CREATE TABLE {} ({} {} NOT NULL PRIMARY KEY, {} {}){}",
            q("teams"),
            q("id"),
            o.int_type,
            q("name"),
            o.text_type,
            o.table_suffix
        ),
        format!(
            "CREATE TABLE {} ({} {} NOT NULL PRIMARY KEY, {} {} NOT NULL, {} {}{}){}",
            q("users"),
            q("id"),
            o.int_type,
            q("name"),
            o.text_type,
            q("team_id"),
            o.int_type,
            // Table-level: MySQL and TiDB parse a column's own REFERENCES clause but ignore it.
            if o.foreign_keys {
                format!(
                    ", FOREIGN KEY ({}) REFERENCES {} ({})",
                    q("team_id"),
                    q("teams"),
                    q("id")
                )
            } else {
                String::new()
            },
            o.table_suffix
        ),
        format!(
            "INSERT INTO {} ({}, {}) VALUES (1, 'red')",
            q("teams"),
            q("id"),
            q("name")
        ),
        format!(
            "INSERT INTO {} ({}, {}) VALUES (2, 'blue')",
            q("teams"),
            q("id"),
            q("name")
        ),
        format!(
            "INSERT INTO {} ({}, {}, {}) VALUES (1, 'carol', 1)",
            q("users"),
            q("id"),
            q("name"),
            q("team_id")
        ),
        format!(
            "INSERT INTO {} ({}, {}, {}) VALUES (2, 'alice', 2)",
            q("users"),
            q("id"),
            q("name"),
            q("team_id")
        ),
        format!(
            "INSERT INTO {} ({}, {}, {}) VALUES (3, 'bob', NULL)",
            q("users"),
            q("id"),
            q("name"),
            q("team_id")
        ),
    ];
    for sql in statements {
        session
            .execute(&sql, exec())
            .await
            .unwrap_or_else(|e| panic!("{sql}: {e}"));
    }
}

fn names(rows: &tg_driver::Rows) -> Vec<String> {
    rows.rows
        .iter()
        .map(|r| r[1].clone().unwrap_or_default())
        .collect()
}

/// Runs the whole contract on an empty database.
pub async fn run(db: Arc<dyn RelationalConnection>, o: Options) {
    let d = db.dialect();
    let q = |n: &str| d.quote_identifier(n);
    seed(db.as_ref(), &o).await;

    // RelationalContract.run
    let namespaces = db.namespaces().await.unwrap();
    let display: Vec<String> = namespaces
        .iter()
        .map(|n| n.display_name().to_owned())
        .collect();
    let default = d.default_namespace(&display, &db.config().database);
    let namespace = namespaces
        .iter()
        .find(|n| Some(n.display_name().to_owned()) == default)
        .unwrap_or_else(|| panic!("no default namespace among {namespaces:?}"))
        .clone();
    let tables: Vec<String> = db
        .tables(&namespace)
        .await
        .unwrap()
        .into_iter()
        .map(|t| t.table.name)
        .collect();
    assert!(
        tables.contains(&"users".into()) && tables.contains(&"teams".into()),
        "tables in {namespace:?}: {tables:?}"
    );
    let users = TableRef::new(namespace.clone(), "users");
    let teams = TableRef::new(namespace.clone(), "teams");

    let details = db.table_details(&users).await.unwrap();
    assert_eq!(
        details
            .columns
            .iter()
            .map(|c| c.name.as_str())
            .collect::<Vec<_>>(),
        ["id", "name", "team_id"]
    );
    assert_eq!(details.primary_key(), ["id"]);
    if o.foreign_keys {
        assert_eq!(details.foreign_keys.len(), 1);
        assert_eq!(details.foreign_keys[0].referenced_table.name, "teams");
    }

    let by_name = [SortColumn::new("name", SortDirection::Asc)];
    let page = db
        .fetch_page(&PageRequest {
            order_by: &by_name,
            limit: 2,
            offset: 1,
            ..PageRequest::new(&users)
        })
        .await
        .unwrap();
    assert_eq!(names(&page), ["bob", "carol"]);
    assert!(!page.truncated);
    let first = db
        .fetch_page(&PageRequest {
            order_by: &by_name,
            limit: 1,
            ..PageRequest::new(&users)
        })
        .await
        .unwrap();
    assert!(first.truncated);

    let starts_with_a = SqlFilter::raw(&format!("{} LIKE 'a%'", q("name"))).unwrap();
    let filtered = db
        .fetch_page(&PageRequest {
            filter: Some(&starts_with_a),
            limit: 10,
            ..PageRequest::new(&users)
        })
        .await
        .unwrap();
    assert_eq!(names(&filtered), ["alice"]);
    let above_one = SqlFilter::raw(&format!("{} > 1", q("id"))).unwrap();
    assert_eq!(
        db.count_rows(
            &TableQuery {
                filter: Some(&above_one),
                ..TableQuery::new(&users)
            },
            None
        )
        .await
        .unwrap(),
        2
    );
    assert_eq!(
        db.sample_values(&users, "name", "a", 30).await.unwrap(),
        ["alice"]
    );

    let structure = db.table_structure(&users).await.unwrap();
    assert!(!structure.ddl.trim().is_empty());
    assert!(
        structure
            .constraints
            .iter()
            .any(|c| c.kind == ConstraintKind::PrimaryKey)
            || !structure.details.primary_key().is_empty()
    );

    // Export streams every row with the filter and sort (DatabaseTest).
    let mut sink = Csv::default();
    let progress = Arc::new(Mutex::new(0u64));
    let seen = progress.clone();
    let report = move |n: u64| *seen.lock().unwrap() = n;
    let exported = db
        .export_table(
            &TableQuery {
                filter: Some(&above_one),
                order_by: &by_name,
                ..TableQuery::new(&users)
            },
            &mut sink,
            &report,
        )
        .await
        .unwrap();
    assert_eq!(exported, 2);
    assert_eq!(*progress.lock().unwrap(), 2);
    assert_eq!(sink.text, "id,name,team_id\n2,alice,2\n3,bob,\n");
    assert!(sink.finished);

    // Columns of the whole namespace and relations seen from either side (DatabaseTest).
    let columns = db.columns_of(&namespace).await.unwrap();
    let of = |t: &str| {
        columns
            .iter()
            .find(|(n, _)| n == t)
            .map(|(_, c)| c.iter().map(|c| c.name.clone()).collect::<Vec<_>>())
    };
    assert_eq!(of("users").unwrap(), ["id", "name", "team_id"]);
    assert_eq!(of("teams").unwrap(), ["id", "name"]);
    if o.foreign_keys {
        let from_teams = db.relations(&teams).await.unwrap();
        assert_eq!(from_teams.len(), 1);
        assert_eq!(
            (
                from_teams[0].from_table.name.as_str(),
                from_teams[0].from_columns.clone()
            ),
            ("users", vec!["team_id".to_owned()])
        );
        assert_eq!(
            db.relations(&users).await.unwrap(),
            from_teams,
            "seen from either side"
        );
    }

    if o.transactions {
        let int = |v: &str| BoundValue::new(Some(v), types::INTEGER, true);
        let text = |v: &str| BoundValue::new(Some(v), types::VARCHAR, false);
        let changes = [
            RowChange::Update {
                key: vec![("id".into(), int("1"))],
                values: vec![("name".into(), text("carol2"))],
            },
            RowChange::Delete {
                key: vec![("id".into(), int("2"))],
            },
            RowChange::Insert {
                values: vec![("id".into(), int("4")), ("name".into(), text("dave"))],
            },
        ];
        assert_eq!(
            db.apply_changes(&edit::statements(d, &users, &changes))
                .await
                .unwrap(),
            3
        );
        let by_id = [SortColumn::new("id", SortDirection::Asc)];
        let all_rows = PageRequest {
            order_by: &by_id,
            limit: 10,
            ..PageRequest::new(&users)
        };
        let all = || db.fetch_page(&all_rows);
        assert_eq!(names(&all().await.unwrap()), ["carol2", "bob", "dave"]);

        // Updating a row that no longer exists fails and leaves everything as it was.
        let stale = [
            RowChange::Update {
                key: vec![("id".into(), int("1"))],
                values: vec![("name".into(), text("x"))],
            },
            RowChange::Update {
                key: vec![("id".into(), int("2"))],
                values: vec![("name".into(), text("y"))],
            },
        ];
        let error = db
            .apply_changes(&edit::statements(d, &users, &stale))
            .await
            .unwrap_err();
        assert!(
            error.to_string().contains("Expected 1 row(s) but 0"),
            "{error}"
        );
        assert_eq!(names(&all().await.unwrap()), ["carol2", "bob", "dave"]);

        // Wildcards in the prefix are literal.
        let mut session = db.open_session().await.unwrap();
        session
            .execute(
                &format!(
                    "INSERT INTO {} ({}, {}) VALUES (10, 'al_x'), (11, 'alpha%')",
                    q("users"),
                    q("id"),
                    q("name")
                ),
                exec(),
            )
            .await
            .unwrap();
        assert_eq!(
            db.sample_values(&users, "name", "al_", 30).await.unwrap(),
            ["al_x"]
        );
        assert_eq!(
            db.sample_values(&users, "name", "al", 30).await.unwrap(),
            ["al_x", "alpha%"]
        );

        // Inserting rows: all in one transaction, and a failing row is named.
        let cols = db.table_columns(&users).await.unwrap().columns;
        let mut rows = vec![
            vec![Some("20".into()), Some("eve".into()), None],
            vec![Some("21".into()), Some("finn".into()), None],
        ]
        .into_iter();
        let inserted = db
            .insert_rows(
                &users,
                &cols,
                &mut rows,
                &|i| format!("line {}", i + 2),
                &|_| {},
            )
            .await
            .unwrap();
        assert_eq!(inserted, 2);
        let mut bad = vec![
            vec![Some("22".into()), Some("gus".into()), None],
            vec![Some("20".into()), Some("dup".into()), None],
        ]
        .into_iter();
        let error = db
            .insert_rows(
                &users,
                &cols,
                &mut bad,
                &|i| format!("line {}", i + 2),
                &|_| {},
            )
            .await
            .unwrap_err();
        assert!(
            matches!(&error, DriverError::Insert { row, .. } if row == "line 3"),
            "{error:?}"
        );
        assert_eq!(
            db.count_rows(&TableQuery::new(&users), None).await.unwrap(),
            7,
            "nothing of the failed insert is kept"
        );
    }

    // Console sessions: a count, updates, truncation.
    let mut session = db.open_session().await.unwrap();
    let QueryResult::Rows(count) = session
        .execute(
            &format!("SELECT COUNT(*) FROM {}", d.qualified_name(&users)),
            exec(),
        )
        .await
        .unwrap()
    else {
        panic!("COUNT(*) returned no rows")
    };
    assert!(count.rows[0][0].is_some());
    session
        .execute(
            &format!(
                "CREATE TABLE {} ({} {}){}",
                q("n"),
                q("x"),
                o.int_type,
                suffix_for(&o, "x")
            ),
            exec(),
        )
        .await
        .unwrap();
    let inserted = session
        .execute(
            &format!("INSERT INTO {} VALUES (1), (2), (3)", q("n")),
            exec(),
        )
        .await
        .unwrap();
    if o.transactions {
        assert_eq!(
            inserted,
            QueryResult::Update {
                affected_rows: 3,
                elapsed_ms: inserted.elapsed_ms()
            }
        );
    }
    let QueryResult::Rows(two) = session
        .execute(
            &format!("SELECT {} FROM {}", q("x"), q("n")),
            ExecOptions {
                max_rows: 2,
                timeout: None,
            },
        )
        .await
        .unwrap()
    else {
        panic!("no rows")
    };
    assert_eq!(two.rows.len(), 2);
    assert!(two.truncated);

    if o.transactions {
        manual_commit(db.as_ref(), &o).await;
    }
    cancellation(db.as_ref(), &o).await;
}

fn suffix_for(o: &Options, key: &str) -> String {
    if o.table_suffix.is_empty() {
        String::new()
    } else {
        o.table_suffix
            .replace("ORDER BY id", &format!("ORDER BY {key}"))
    }
}

async fn manual_commit(db: &dyn RelationalConnection, o: &Options) {
    let q = |n: &str| db.dialect().quote_identifier(n);
    let mut session = db.open_session().await.unwrap();
    session
        .execute(
            &format!("CREATE TABLE {} ({} {})", q("tx"), q("x"), o.int_type),
            exec(),
        )
        .await
        .unwrap();
    session.set_auto_commit(false).await.unwrap();
    session
        .execute(&format!("INSERT INTO {} VALUES (1)", q("tx")), exec())
        .await
        .unwrap();
    assert_eq!(session.uncommitted_statements(), 1);
    session.rollback().await.unwrap();
    assert_eq!(session.uncommitted_statements(), 0);
    session
        .execute(&format!("INSERT INTO {} VALUES (2)", q("tx")), exec())
        .await
        .unwrap();
    session.commit().await.unwrap();
    let mut other = db.open_session().await.unwrap();
    let QueryResult::Rows(rows) = other
        .execute(&format!("SELECT {} FROM {}", q("x"), q("tx")), exec())
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(rows.rows, vec![vec![Some("2".to_owned())]]);
    // Typing COMMIT ends the transaction too.
    session
        .execute(&format!("INSERT INTO {} VALUES (3)", q("tx")), exec())
        .await
        .unwrap();
    session.execute("COMMIT", exec()).await.unwrap();
    assert_eq!(session.uncommitted_statements(), 0);
    session.set_auto_commit(true).await.unwrap();
}

async fn cancellation(db: &dyn RelationalConnection, o: &Options) {
    // Dropping the future cancels the statement on the server; the session stays usable.
    let mut session = db.open_session().await.unwrap();
    let outcome = tokio::time::timeout(
        Duration::from_millis(300),
        session.execute(o.endless, exec()),
    )
    .await;
    assert!(outcome.is_err(), "the endless query finished");
    let QueryResult::Rows(r) = tokio::time::timeout(
        Duration::from_secs(10),
        session.execute("SELECT 42", exec()),
    )
    .await
    .unwrap()
    .unwrap() else {
        panic!()
    };
    assert_eq!(r.rows, vec![vec![Some("42".to_owned())]]);

    // A statement timeout surfaces as Timeout, and the session goes on.
    let timed = session
        .execute(
            o.endless,
            ExecOptions {
                max_rows: 10,
                timeout: Some(Duration::from_millis(200)),
            },
        )
        .await;
    assert!(matches!(timed, Err(DriverError::Timeout)), "{timed:?}");
    assert!(session.is_valid().await);

    // More cancelled loads than the pool has connections: each gives its connection back (or a new one takes its place).
    let q = |n: &str| db.dialect().quote_identifier(n);
    session
        .execute(
            &format!("CREATE VIEW {} AS {}", q("endless"), o.endless),
            exec(),
        )
        .await
        .unwrap();
    let ns = db.namespaces().await.unwrap();
    let display: Vec<String> = ns.iter().map(|n| n.display_name().to_owned()).collect();
    let default = db
        .dialect()
        .default_namespace(&display, &db.config().database);
    let namespace = ns
        .into_iter()
        .find(|n| Some(n.display_name().to_owned()) == default)
        .unwrap();
    let endless = TableRef::new(namespace.clone(), "endless");
    let request = PageRequest {
        limit: 10,
        ..PageRequest::new(&endless)
    };
    for _ in 0..6 {
        let load = db.fetch_page(&request);
        assert!(
            tokio::time::timeout(Duration::from_millis(200), load)
                .await
                .is_err()
        );
    }
    let users = TableRef::new(namespace, "users");
    let page = tokio::time::timeout(
        Duration::from_secs(10),
        db.fetch_page(&PageRequest {
            limit: 100,
            ..PageRequest::new(&users)
        }),
    )
    .await;
    assert!(!page.expect("pool exhausted").unwrap().rows.is_empty());
}

/// On a read-only connection to the database [`run`] filled: reads work, writes are refused before they're sent.
pub async fn read_only(db: Arc<dyn RelationalConnection>) {
    let users = db.dialect().quote_identifier("users");
    let mut session = db.open_session().await.unwrap();
    let QueryResult::Rows(r) = session
        .execute(&format!("SELECT count(*) FROM {users}"), exec())
        .await
        .unwrap()
    else {
        panic!()
    };
    assert!(r.rows[0][0].is_some());
    for sql in [
        format!("DELETE FROM {users}"),
        format!("DROP TABLE {users}"),
    ] {
        assert!(
            matches!(
                session.execute(&sql, exec()).await,
                Err(DriverError::ReadOnly { .. })
            ),
            "{sql}"
        );
    }
    assert!(matches!(
        db.apply_changes(&[]).await,
        Err(DriverError::ReadOnly { .. })
    ));
}

/// A CSV sink like `ResultWriter(CopyFormat.Csv)` for these plain values.
#[derive(Default)]
struct Csv {
    text: String,
    finished: bool,
}

impl RowSink for Csv {
    fn begin(&mut self, columns: &[ResultColumn]) -> std::io::Result<()> {
        self.text = columns
            .iter()
            .map(|c| c.name.as_str())
            .collect::<Vec<_>>()
            .join(",")
            + "\n";
        Ok(())
    }

    fn write(&mut self, row: &[Cell]) -> std::io::Result<()> {
        self.text += &(row
            .iter()
            .map(|c| c.clone().unwrap_or_default())
            .collect::<Vec<_>>()
            .join(",")
            + "\n");
        Ok(())
    }

    fn finish(&mut self) -> std::io::Result<()> {
        self.finished = true;
        Ok(())
    }
}
