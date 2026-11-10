//! `TestDataGeneratorTest`, ported.

use std::collections::{HashMap, HashSet};
use tg_data::testdata::{
    ColumnPlan, GeneratorKind as K, GeneratorSuggestion, TestDataGenerator, ascii, words,
};
use tg_sql::model::{ColumnInfo, jdbc_types as types};

fn now() -> chrono::NaiveDateTime {
    chrono::NaiveDate::from_ymd_opt(2026, 10, 30)
        .unwrap()
        .and_hms_opt(0, 0, 0)
        .unwrap()
}

#[derive(Default)]
struct C {
    size: Option<i32>,
    nullable: bool,
    primary_key: bool,
    auto_increment: bool,
    default: Option<&'static str>,
}

fn column(name: &str, type_name: &str, jdbc_type: i32, c: C) -> ColumnInfo {
    let mut info = ColumnInfo::new(name, type_name);
    info.jdbc_type = jdbc_type;
    info.size = c.size;
    info.nullable = c.nullable;
    info.primary_key = c.primary_key;
    info.auto_increment = c.auto_increment;
    info.default_value = c.default.map(str::to_owned);
    info
}

fn other(name: &str, type_name: &str) -> ColumnInfo {
    column(name, type_name, types::OTHER, C::default())
}

fn varchar(name: &str) -> ColumnInfo {
    varchar_with(name, 255, false)
}

fn varchar_with(name: &str, size: i32, nullable: bool) -> ColumnInfo {
    column(
        name,
        "varchar",
        types::VARCHAR,
        C {
            size: Some(size),
            nullable,
            ..C::default()
        },
    )
}

fn int(name: &str) -> ColumnInfo {
    int_typed(name, "int4", types::INTEGER)
}

fn int_typed(name: &str, type_name: &str, jdbc: i32) -> ColumnInfo {
    column(name, type_name, jdbc, C::default())
}

fn suggest(c: ColumnInfo) -> K {
    GeneratorSuggestion::suggest(&c, false, false).kind
}

fn plan(c: ColumnInfo, kind: K) -> ColumnPlan {
    ColumnPlan::new(c, kind)
}

fn unique(mut p: ColumnPlan) -> ColumnPlan {
    p.unique = true;
    p
}

fn range(mut p: ColumnPlan, min: Option<f64>, max: Option<f64>) -> ColumnPlan {
    p.min = min;
    p.max = max;
    p
}

fn generate(
    plans: Vec<ColumnPlan>,
    count: usize,
    pools: HashMap<String, Vec<String>>,
) -> Vec<Vec<Option<String>>> {
    TestDataGenerator::new(plans, 7, pools, now())
        .rows(count)
        .collect::<Result<_, _>>()
        .unwrap()
}

#[test]
fn suggests_text_generators_from_column_names_in_any_case_style() {
    for (name, kind) in [
        ("email", K::Email),
        ("user_email", K::Email),
        ("emailAddress", K::Email),
        ("phone_number", K::Phone),
        ("phoneNumber", K::Phone),
        ("zip", K::PostalCode),
        ("postal-code", K::PostalCode),
        ("first_name", K::FirstName),
        ("fname", K::FirstName),
        ("firstName", K::FirstName),
        ("surname", K::LastName),
        ("LastName", K::LastName),
        ("name", K::FullName),
        ("username", K::Username),
        ("website", K::Url),
        ("url", K::Url),
        ("avatar_url", K::ImageUrl),
        ("ip", K::IpAddress),
        ("ip_address", K::IpAddress),
        ("country_code", K::CountryCode),
        ("country", K::Country),
        ("currency", K::CurrencyCode),
        ("title", K::Title),
        ("job_title", K::JobTitle),
        ("slug", K::Slug),
        ("status", K::Status),
        ("company_name", K::Company),
        ("address_line1", K::Street),
        ("city", K::City),
        ("guid", K::Uuid),
        ("password_hash", K::Hash),
    ] {
        assert_eq!(suggest(varchar(name)), kind, "{name}");
    }
    assert_eq!(suggest(varchar_with("country", 2, false)), K::CountryCode);
    assert_eq!(
        suggest(column("description", "text", types::VARCHAR, C::default())),
        K::Paragraph
    );
    assert_eq!(
        suggest(column("bio", "text", types::VARCHAR, C::default())),
        K::Paragraph
    );
    // SQLite keeps times as text.
    assert_eq!(suggest(other("created_at", "TEXT")), K::PastTimestamp);
}

#[test]
fn suggests_from_types_and_never_text_for_numbers() {
    assert_eq!(suggest(other("uuid", "uuid")), K::Uuid);
    assert_eq!(suggest(other("external_ref", "uuid")), K::Uuid);
    assert_eq!(
        suggest(column(
            "created_at",
            "timestamptz",
            types::TIMESTAMP,
            C::default()
        )),
        K::PastTimestamp
    );
    assert_eq!(
        suggest(column(
            "updatedAt",
            "datetime",
            types::TIMESTAMP,
            C::default()
        )),
        K::PastTimestamp
    );
    assert_eq!(
        suggest(column(
            "expires_at",
            "timestamp",
            types::TIMESTAMP,
            C::default()
        )),
        K::FutureTimestamp
    );
    assert_eq!(
        suggest(column(
            "happened",
            "timestamp",
            types::TIMESTAMP,
            C::default()
        )),
        K::Timestamp
    );
    assert_eq!(
        suggest(column("date_of_birth", "date", types::DATE, C::default())),
        K::BirthDate
    );
    assert_eq!(
        suggest(column("start", "date", types::DATE, C::default())),
        K::Date
    );
    assert_eq!(
        suggest(column("opens", "time", types::TIME, C::default())),
        K::Time
    );
    assert_eq!(
        suggest(column("is_active", "bool", types::BOOLEAN, C::default())),
        K::Boolean
    );
    assert_eq!(
        suggest(column(
            "is_active",
            "TINYINT",
            types::BIT,
            C {
                size: Some(1),
                ..C::default()
            }
        )),
        K::Boolean
    );
    assert_eq!(
        suggest(int_typed("is_active", "INTEGER", types::INTEGER)),
        K::Boolean
    );
    assert_eq!(suggest(other("data", "jsonb")), K::Json);
    assert_eq!(
        suggest(column(
            "price",
            "numeric(10,2)",
            types::NUMERIC,
            C::default()
        )),
        K::Price
    );
    assert_eq!(suggest(int("amount")), K::Price);
    assert_eq!(suggest(int("age")), K::Integer);
    assert_eq!(
        suggest(column(
            "weight",
            "double precision",
            types::DOUBLE,
            C::default()
        )),
        K::Decimal
    );
    let id = |c: C| column("id", "int4", types::INTEGER, c);
    assert_eq!(
        suggest(id(C {
            primary_key: true,
            auto_increment: true,
            ..C::default()
        })),
        K::Skip
    );
    assert_eq!(
        suggest(column(
            "id",
            "int8",
            types::BIGINT,
            C {
                primary_key: true,
                default: Some("nextval('users_id_seq'::regclass)"),
                ..C::default()
            }
        )),
        K::Skip
    );
    assert_eq!(
        suggest(id(C {
            primary_key: true,
            ..C::default()
        })),
        K::Sequence
    );
    assert_eq!(
        GeneratorSuggestion::suggest(&int("team_id"), true, false).kind,
        K::ForeignKey
    );
    assert_eq!(suggest(other("mood", "mood")), K::Skip);

    // Text-sounding names on numeric columns.
    for name in [
        "email",
        "user_email",
        "phone_number",
        "first_name",
        "url",
        "status",
        "zip",
        "description",
        "country_code",
        "title",
        "uuid",
    ] {
        for c in [
            int(name),
            int_typed(name, "smallint", types::SMALLINT),
            column(name, "numeric(12,2)", types::NUMERIC, C::default()),
        ] {
            let p = GeneratorSuggestion::suggest(&c, false, false);
            assert!(!p.kind.is_text(), "{name} {} -> {:?}", c.type_name, p.kind);
            assert!(
                GeneratorSuggestion::choices(&c, false).contains(&p.kind),
                "{name} {} -> {:?}",
                c.type_name,
                p.kind
            );
        }
    }
    assert!(
        GeneratorSuggestion::choices(&int("n"), false)
            .iter()
            .all(|k| !k.is_text())
    );
    assert!(
        GeneratorSuggestion::choices(&varchar("s"), false)
            .iter()
            .any(|k| k.is_numeric())
    );
    assert_eq!(
        GeneratorSuggestion::suggest(&int("code"), false, true).kind,
        K::Sequence
    );
}

#[test]
fn every_suggestion_is_among_the_columns_choices() {
    let columns = [
        varchar("email"),
        varchar("created_at"),
        int("age"),
        column("price", "decimal(8,2)", types::DECIMAL, C::default()),
        column("is_admin", "boolean", types::BOOLEAN, C::default()),
        column("born", "date", types::DATE, C::default()),
        other("x", "uuid"),
        other("ip", "inet"),
        column(
            "blob",
            "bytea",
            types::BINARY,
            C {
                nullable: true,
                ..C::default()
            },
        ),
        other("meta", "json"),
    ];
    for c in &columns {
        let p = GeneratorSuggestion::suggest(c, false, false);
        assert!(
            GeneratorSuggestion::choices(c, false).contains(&p.kind),
            "{}: {:?}",
            c.name,
            p.kind
        );
    }
}

#[test]
fn the_same_seed_gives_the_same_rows_another_seed_different_ones() {
    let plans: Vec<ColumnPlan> = [
        "id",
        "first_name",
        "last_name",
        "email",
        "price",
        "created_at",
        "note",
        "uuid",
    ]
    .iter()
    .map(|name| {
        let c = match *name {
            "id" => int("id"),
            "price" => column("price", "numeric(10,2)", types::NUMERIC, C::default()),
            n => varchar_with(n, 255, true),
        };
        GeneratorSuggestion::suggest(&c, false, *name == "id")
    })
    .collect();
    let rows = |seed| -> Vec<Vec<Option<String>>> {
        TestDataGenerator::new(plans.clone(), seed, HashMap::new(), now())
            .rows(500)
            .collect::<Result<_, _>>()
            .unwrap()
    };
    assert_eq!(rows(42), rows(42));
    assert_ne!(rows(42), rows(43));
    // Iterating again starts over.
    let generator = TestDataGenerator::new(plans.clone(), 42, HashMap::new(), now());
    let a: Vec<_> = generator.rows(50).collect();
    let b: Vec<_> = generator.rows(50).collect();
    assert_eq!(a, b);
}

fn email_shape(email: &str) -> bool {
    regex::Regex::new(r"^[a-z0-9._]+@[a-z0-9.-]+\.[a-z]+$")
        .unwrap()
        .is_match(email)
}

#[test]
fn emails_and_user_names_follow_the_rows_person() {
    let rows = generate(
        vec![
            plan(varchar("first_name"), K::FirstName),
            plan(varchar("last_name"), K::LastName),
            plan(varchar("email"), K::Email),
            plan(varchar("username"), K::Username),
        ],
        200,
        HashMap::new(),
    );
    for row in &rows {
        let [first, last, email, username] = [0, 1, 2, 3].map(|i| row[i].clone().unwrap());
        let (f, l) = (ascii(&first), ascii(&last));
        let local = email.split('@').next().unwrap();
        assert!(
            local.contains(&f) || local.contains(&l),
            "{first} {last} -> {email}"
        );
        assert!(
            username.contains(&f) || username.contains(&l),
            "{first} {last} -> {username}"
        );
        assert!(email_shape(&email), "{email}");
    }
    assert!(
        rows.iter().any(|r| words::NAME_POOLS[1]
            .last
            .contains(&r[1].as_deref().unwrap())),
        "some Vietnamese names"
    );
}

#[test]
fn text_is_cut_to_the_columns_size_emails_keep_their_domain() {
    let rows = generate(
        vec![
            unique(plan(varchar_with("email", 18, false), K::Email)),
            plan(varchar_with("bio", 20, false), K::Paragraph),
            plan(varchar_with("code", 2, false), K::CountryCode),
        ],
        2000,
        HashMap::new(),
    );
    for row in &rows {
        let email = row[0].clone().unwrap();
        assert!(email.chars().count() <= 18, "{email}");
        assert!(email_shape(&email), "{email}");
        assert!(row[1].as_ref().unwrap().chars().count() <= 20);
        assert_eq!(row[2].as_ref().unwrap().chars().count(), 2);
    }
    assert_eq!(
        rows.iter()
            .map(|r| r[0].clone())
            .collect::<HashSet<_>>()
            .len(),
        2000
    );
}

#[test]
fn unique_columns_stay_unique_over_many_rows() {
    let plans = vec![
        unique(plan(varchar("email"), K::Email)),
        unique(plan(varchar_with("username", 12, false), K::Username)),
        unique(plan(varchar("first_name"), K::FirstName)),
        unique(plan(varchar("city"), K::City)),
        unique(range(
            plan(int_typed("code", "smallint", types::SMALLINT), K::Integer),
            Some(1.0),
            Some(10_000.0),
        )),
        unique(plan(int("id"), K::Sequence)),
        unique(range(
            plan(
                column("price", "numeric(6,2)", types::NUMERIC, C::default()),
                K::Price,
            ),
            Some(0.0),
            Some(1000.0),
        )),
        unique(plan(
            column("day", "date", types::DATE, C::default()),
            K::Date,
        )),
        unique(plan(other("ref", "uuid"), K::Uuid)),
        unique(plan(varchar("ip"), K::IpAddress)),
    ];
    let generator = TestDataGenerator::new(plans.clone(), 1, HashMap::new(), now());
    assert_eq!(generator.problems(1400), Vec::<String>::new());
    let rows: Vec<Vec<Option<String>>> = generator
        .rows(10_000)
        .take(1400)
        .collect::<Result<_, _>>()
        .unwrap();
    for (i, p) in plans.iter().enumerate() {
        assert_eq!(
            rows.iter()
                .map(|r| r[i].clone())
                .collect::<HashSet<_>>()
                .len(),
            rows.len(),
            "{}",
            p.column.name
        );
    }
    // Columns without a small range keep going.
    let wide_plans: Vec<ColumnPlan> = plans
        .into_iter()
        .filter(|p| !["day", "price"].contains(&p.column.name.as_str()))
        .collect();
    let wide: Vec<Vec<Option<String>>> =
        TestDataGenerator::new(wide_plans, 1, HashMap::new(), now())
            .rows(10_000)
            .collect::<Result<_, _>>()
            .unwrap();
    for i in 0..wide[0].len() {
        assert_eq!(
            wide.iter()
                .map(|r| r[i].clone())
                .collect::<HashSet<_>>()
                .len(),
            10_000,
            "column {i}"
        );
    }
    assert!(
        wide.iter()
            .all(|r| r[1].as_ref().unwrap().chars().count() <= 12)
    );
}

#[test]
fn null_ratio_is_roughly_honoured_and_only_for_nullable_columns() {
    let mut nickname = plan(varchar_with("nickname", 255, true), K::Word);
    nickname.null_ratio = 0.3;
    let mut name = plan(varchar("name"), K::FullName);
    name.null_ratio = 0.3;
    let rows = generate(vec![nickname, name], 10_000, HashMap::new());
    let nulls = rows.iter().filter(|r| r[0].is_none()).count();
    assert!((2700..=3300).contains(&nulls), "{nulls}");
    assert!(rows.iter().all(|r| r[1].is_some()));
}

#[test]
fn foreign_keys_come_from_the_referenced_values_only() {
    let pool: Vec<String> = ["10", "20", "30", "40"].map(String::from).to_vec();
    let pools = HashMap::from([("team_id".to_owned(), pool.clone())]);
    let rows = generate(
        vec![plan(int("team_id"), K::ForeignKey)],
        1000,
        pools.clone(),
    );
    assert!(rows.iter().all(|r| pool.contains(r[0].as_ref().unwrap())));
    let all: HashSet<String> = pool.iter().cloned().collect();
    assert_eq!(
        rows.iter()
            .map(|r| r[0].clone().unwrap())
            .collect::<HashSet<_>>(),
        all
    );
    let generator = TestDataGenerator::new(
        vec![unique(plan(int("team_id"), K::ForeignKey))],
        3,
        pools,
        now(),
    );
    let drawn: HashSet<String> = generator
        .rows(4)
        .map(|r| r.unwrap()[0].clone().unwrap())
        .collect();
    assert_eq!(drawn, all);
}

#[test]
fn reports_problems_before_generating() {
    let plans = vec![
        plan(int("team_id"), K::ForeignKey),
        unique(plan(varchar("code"), K::Status)),
        unique(range(
            plan(int_typed("level", "smallint", types::SMALLINT), K::Integer),
            Some(1.0),
            Some(100.0),
        )),
        unique(plan(
            column("flag", "boolean", types::BOOLEAN, C::default()),
            K::Boolean,
        )),
        plan(
            int_typed("small_id", "smallint", types::SMALLINT),
            K::Sequence,
        ),
        plan(varchar("name"), K::Null),
        plan(varchar("kind"), K::Skip),
        plan(int("age"), K::Email),
        unique(plan(varchar_with("tag", 2, false), K::Word)),
        plan(varchar_with("fine", 2, true), K::Skip),
    ];
    let generator = TestDataGenerator::new(plans, 1, HashMap::new(), now());
    assert_eq!(
        generator.problems(40_000),
        [
            "team_id: no rows in the referenced table",
            "code: unique with only 8 possible values",
            "level: unique with only 100 possible values",
            "flag: unique with only 2 possible values",
            "small_id: 40000 rows run past 32767, the most this smallint column holds",
            "name: NOT NULL, so it can't always be NULL",
            "kind: NOT NULL with no default, so it needs a generator",
            "age: Email address values don't fit a int4 column",
            "tag: 2 characters are too few for 40000 unique values",
        ]
    );
    assert_eq!(
        generator.problems(2),
        [
            "team_id: no rows in the referenced table",
            "name: NOT NULL, so it can't always be NULL",
            "kind: NOT NULL with no default, so it needs a generator",
            "age: Email address values don't fit a int4 column",
        ]
    );
}

#[test]
fn integers_stay_within_their_type() {
    let rows = generate(
        vec![
            range(
                plan(int_typed("a", "smallint", types::SMALLINT), K::Integer),
                Some(-1e9),
                Some(1e9),
            ),
            range(
                plan(int_typed("b", "tinyint", types::TINYINT), K::Integer),
                Some(0.0),
                Some(1e6),
            ),
            plan(int_typed("c", "int2", types::SMALLINT), K::Integer),
            range(
                plan(
                    column("d", "numeric(4,2)", types::NUMERIC, C::default()),
                    K::Decimal,
                ),
                None,
                Some(1e6),
            ),
            plan(
                column("e", "numeric", types::NUMERIC, C::default()),
                K::Price,
            ),
        ],
        5000,
        HashMap::new(),
    );
    let a: Vec<i32> = rows
        .iter()
        .map(|r| r[0].as_ref().unwrap().parse().unwrap())
        .collect();
    assert!(a.iter().all(|v| (-32768..=32767).contains(v)));
    assert!(
        a.iter().any(|v| *v > 1000) && a.iter().any(|v| *v < -1000),
        "uses the type's range"
    );
    assert!(
        rows.iter()
            .all(|r| (0..=127).contains(&r[1].as_ref().unwrap().parse::<i32>().unwrap()))
    );
    assert!(
        rows.iter()
            .all(|r| (0..=1000).contains(&r[2].as_ref().unwrap().parse::<i32>().unwrap()))
    );
    let scale = |s: &str| s.split_once('.').map_or(0, |(_, f)| f.len());
    assert!(rows.iter().all(|r| {
        let d = r[3].as_ref().unwrap();
        d.parse::<f64>().unwrap() < 100.0 && scale(d) == 2
    }));
    assert!(rows.iter().all(|r| {
        let e = r[4].as_ref().unwrap();
        let v = e.parse::<f64>().unwrap();
        scale(e) == 2 && (1.0..=1000.0).contains(&v)
    }));
}

#[test]
fn formats_bind_and_satisfy_constraints_in_a_real_database() {
    let db = rusqlite::Connection::open_in_memory().unwrap();
    db.execute_batch(
        "PRAGMA foreign_keys = ON;
         CREATE TABLE teams (id INTEGER PRIMARY KEY);
         INSERT INTO teams (id) VALUES (1), (2), (3);
         CREATE TABLE people (
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             email VARCHAR(30) NOT NULL UNIQUE CHECK (length(email) <= 30 AND email LIKE '%_@_%._%'),
             full_name VARCHAR(40) CHECK (length(full_name) <= 40),
             rank SMALLINT NOT NULL CHECK (rank BETWEEN -32768 AND 32767),
             price NUMERIC(10,2) NOT NULL CHECK (price >= 0),
             is_active BOOLEAN NOT NULL CHECK (is_active IN (0, 1)),
             born DATE NOT NULL CHECK (born = date(born)),
             created_at TIMESTAMP NOT NULL CHECK (created_at = datetime(created_at)),
             opens TIME CHECK (opens = time(opens)),
             ref UUID NOT NULL UNIQUE,
             meta JSON CHECK (json_valid(meta)),
             team_id INTEGER NOT NULL REFERENCES teams (id)
         );",
    )
    .unwrap();
    let columns = vec![
        column(
            "id",
            "INTEGER",
            types::INTEGER,
            C {
                primary_key: true,
                auto_increment: true,
                ..C::default()
            },
        ),
        varchar_with("email", 30, false),
        varchar_with("full_name", 40, true),
        int_typed("rank", "SMALLINT", types::SMALLINT),
        column("price", "NUMERIC(10,2)", types::NUMERIC, C::default()),
        column("is_active", "BOOLEAN", types::BOOLEAN, C::default()),
        column("born", "DATE", types::DATE, C::default()),
        column("created_at", "TIMESTAMP", types::TIMESTAMP, C::default()),
        column(
            "opens",
            "TIME",
            types::TIME,
            C {
                nullable: true,
                ..C::default()
            },
        ),
        other("ref", "UUID"),
        column(
            "meta",
            "JSON",
            types::OTHER,
            C {
                nullable: true,
                ..C::default()
            },
        ),
        int_typed("team_id", "INTEGER", types::INTEGER),
    ];
    let plans: Vec<ColumnPlan> = columns
        .iter()
        .map(|c| {
            GeneratorSuggestion::suggest(
                c,
                c.name == "team_id",
                ["email", "ref"].contains(&c.name.as_str()),
            )
        })
        .collect();
    assert_eq!(
        plans.iter().map(|p| p.kind).collect::<Vec<_>>(),
        [
            K::Skip,
            K::Email,
            K::FullName,
            K::Integer,
            K::Price,
            K::Boolean,
            K::BirthDate,
            K::PastTimestamp,
            K::Time,
            K::Uuid,
            K::Json,
            K::ForeignKey
        ]
    );
    let generator = TestDataGenerator::new(
        plans,
        99,
        HashMap::from([(
            "team_id".to_owned(),
            vec!["1".into(), "2".into(), "3".into()],
        )]),
        now(),
    );
    assert!(generator.problems(500).is_empty());
    assert_eq!(generator.columns(), columns[1..]);
    let names: Vec<String> = generator.columns().iter().map(|c| c.name.clone()).collect();
    let sql = format!(
        "INSERT INTO people ({}) VALUES ({})",
        names.join(", "),
        vec!["?"; names.len()].join(", ")
    );
    let gen_columns = generator.columns();
    for row in generator.rows(500) {
        let row = row.unwrap();
        let values: Vec<rusqlite::types::Value> = row
            .iter()
            .zip(&gen_columns)
            .map(|(v, c)| match v {
                None => rusqlite::types::Value::Null,
                Some(v) => match c.jdbc_type {
                    types::INTEGER | types::SMALLINT | types::BIGINT => {
                        rusqlite::types::Value::Integer(v.parse().unwrap())
                    }
                    types::NUMERIC => rusqlite::types::Value::Real(v.parse().unwrap()),
                    types::BOOLEAN => {
                        rusqlite::types::Value::Integer(i64::from(v.parse::<bool>().unwrap()))
                    }
                    _ => {
                        if c.type_name == "UUID" {
                            assert_eq!(v.len(), 36);
                        }
                        if c.type_name == "JSON" {
                            assert!(
                                serde_json::from_str::<serde_json::Value>(v)
                                    .unwrap()
                                    .is_object()
                            );
                        }
                        rusqlite::types::Value::Text(v.clone())
                    }
                },
            })
            .collect();
        db.execute(&sql, rusqlite::params_from_iter(values))
            .unwrap();
    }
    let (count, distinct, names, born, created): (i64, i64, i64, String, String) = db
        .query_row(
            "SELECT count(*), count(DISTINCT email), count(full_name), min(born), max(created_at) FROM people",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .unwrap();
    assert_eq!((count, distinct), (500, 500));
    assert!((400..500).contains(&names), "about a tenth NULL");
    assert!(born.as_str() >= "1946-10-30");
    assert!(created.as_str() <= "2026-10-30 00:00:00");
}

#[test]
fn generates_a_hundred_thousand_rows_quickly() {
    let mut bio = plan(varchar_with("bio", 255, true), K::Sentence);
    bio.null_ratio = 0.2;
    let plans = vec![
        plan(int("id"), K::Sequence),
        plan(varchar("first_name"), K::FirstName),
        plan(varchar("last_name"), K::LastName),
        unique(plan(varchar("email"), K::Email)),
        plan(varchar("phone"), K::Phone),
        plan(
            column("price", "numeric(10,2)", types::NUMERIC, C::default()),
            K::Price,
        ),
        plan(
            column("created_at", "timestamp", types::TIMESTAMP, C::default()),
            K::PastTimestamp,
        ),
        plan(varchar("status"), K::Status),
        plan(other("ref", "uuid"), K::Uuid),
        bio,
    ];
    let generator = TestDataGenerator::new(plans, 5, HashMap::new(), now());
    assert_eq!(generator.rows(10_000).count(), 10_000);
    let started = std::time::Instant::now();
    assert_eq!(generator.rows(100_000).count(), 100_000);
    let millis = started.elapsed().as_millis();
    // Debug builds are several times slower than Kotlin's JIT; the bound is generous.
    assert!(millis < 20_000, "took {millis} ms");
}
