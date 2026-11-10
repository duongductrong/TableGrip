//! `MongoClientTest` against a real server, skipped unless `TG_IT_MONGO=host:port` is set:
//!
//! ```text
//! docker run -d --name dg-mongo -p 127.0.0.1:57017:27017 mongo:8.0 --replSet rs0 --bind_ip_all
//! docker exec dg-mongo mongosh --eval "rs.initiate({_id:'rs0', members:[{_id:0, host:'localhost:27017'}]})"
//! TG_IT_MONGO=127.0.0.1:57017 cargo test -p tg-driver --test mongodb
//! ```
//! FerretDB with a user: `docker run -d --name dg-it-ferret -p 57018:27017 -e POSTGRES_USER=user
//! -e POSTGRES_PASSWORD=Pass-2026 ghcr.io/ferretdb/ferretdb-eval:2`, `TG_IT_FERRETDB=127.0.0.1:57018`.
#![cfg(feature = "mongodb")]

mod support;

use regex::Regex;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use tg_core::connection::ConnectionConfig;
use tg_driver::{Connection, DocumentConnection, DriverError, Secrets};
use tg_sql::model::DatabaseType;

fn database() -> String {
    format!(
        "dg_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    )
}

struct Server<'a> {
    var: &'a str,
    db_type: DatabaseType,
    read_only: bool,
    url: Option<&'a str>,
    user: &'a str,
    password: Option<&'a str>,
}

impl Default for Server<'_> {
    fn default() -> Self {
        Self {
            var: "TG_IT_MONGO",
            db_type: DatabaseType::MONGODB,
            read_only: false,
            url: None,
            user: "",
            password: None,
        }
    }
}

async fn open(s: Server<'_>) -> Option<Arc<dyn DocumentConnection>> {
    let (host, port) = support::server(s.var)?;
    let mut config = ConnectionConfig::new("mongo", "mongo", s.db_type);
    config.host = host.clone();
    config.port = Some(port);
    config.read_only = s.read_only;
    config.user = s.user.into();
    config.url = s
        .url
        .map(|db| format!("mongodb://{host}:{port}/{db}?directConnection=true"));
    let secrets = Secrets {
        password: s.password.map(str::to_owned),
        ssh: None,
    };
    match tg_driver::connect(&config, &secrets, &support::paths())
        .await
        .expect("connect")
    {
        Connection::Document(c) => Some(c),
        _ => panic!("not a document store"),
    }
}

fn id_of(document: &str) -> i64 {
    Regex::new(r#""_id": (\d+)"#)
        .unwrap()
        .captures(document)
        .unwrap()[1]
        .parse()
        .unwrap()
}

fn map(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| ((*k).into(), (*v).into()))
        .collect()
}

async fn documents(mongo: &dyn DocumentConnection, db: &str) {
    let seeded = mongo
        .command(
            db,
            "db.orders.insertMany([
                {_id: 1, customer: 'ada', status: 'A', total: 30, items: ['pen'], placed: ISODate('2026-10-01T10:00:00Z')},
                {_id: 2, customer: 'linus', status: 'B', total: 10, address: {city: 'Helsinki'}},
                {_id: 3, customer: 'grace', status: 'A', total: 50},
                {_id: 4, customer: 'alan', status: 'A', total: 20}
            ])",
        )
        .await
        .unwrap();
    assert_eq!(seeded, "{ acknowledged: true, insertedCount: 4 }");
    assert!(mongo.databases().await.unwrap().contains(&db.to_owned()));
    let collections = mongo.collections(db).await.unwrap();
    assert_eq!(
        collections
            .iter()
            .map(|c| (c.name.as_str(), c.documents))
            .collect::<Vec<_>>(),
        [("orders", Some(4))]
    );

    let page = mongo
        .find(db, "orders", "{status: 'A'}", "{total: -1}", 0, 2, "")
        .await
        .unwrap();
    assert_eq!(
        page.documents.iter().map(|d| id_of(d)).collect::<Vec<_>>(),
        [3, 1]
    );
    assert!(page.has_more);
    assert!(
        !mongo
            .find(db, "orders", "{status: 'A'}", "{total: -1}", 2, 2, "")
            .await
            .unwrap()
            .has_more
    );
    let first = mongo
        .find(db, "orders", "{_id: 1}", "", 0, 1, "")
        .await
        .unwrap();
    assert!(
        first.documents[0].contains(r#""$date": "2026-10-01T10:00:00Z""#),
        "{}",
        first.documents[0]
    );
    assert_eq!(mongo.count(db, "orders", "{status: 'A'}").await.unwrap(), 3);
    let projected = mongo
        .find(db, "orders", "{_id: 3}", "", 0, 1, "{customer: 1, _id: 0}")
        .await
        .unwrap();
    assert_eq!(projected.documents, [r#"{"customer": "grace"}"#]);

    // Insert gets an ObjectId; replace keeps the _id; it can't be changed.
    let id = mongo
        .insert(db, "orders", "{customer: 'new', total: 1}")
        .await
        .unwrap();
    assert!(id.starts_with(r#"{"$oid":"#), "{id}");
    mongo
        .replace(db, "orders", &id, "{customer: 'renamed', total: 2}", None)
        .await
        .unwrap();
    let renamed = mongo
        .find(db, "orders", &format!("{{_id: {id}}}"), "", 0, 1, "")
        .await
        .unwrap();
    assert!(renamed.documents[0].contains("renamed"));
    assert!(
        mongo
            .replace(db, "orders", &id, "{_id: 99, customer: 'x'}", None)
            .await
            .is_err()
    );

    // Field edits: written only while the fields still hold what was read, and other fields keep their exact types.
    mongo
        .command(
            db,
            "db.orders.insertOne({_id: 10, n: NumberLong(5), d: 1.0, tags: ['a', 'b'], keep: 'x'})",
        )
        .await
        .unwrap();
    let read = mongo
        .find(db, "orders", "{_id: 10}", "", 0, 1, "")
        .await
        .unwrap()
        .documents
        .remove(0);
    assert!(read.contains(r#""n": {"$numberLong": "5"}"#), "{read}");
    let unset: BTreeSet<String> = ["d".to_owned()].into();
    let expected = |pairs: &[(&str, Option<&str>)]| -> BTreeMap<String, Option<String>> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).into(), v.map(str::to_owned)))
            .collect()
    };
    assert!(
        mongo
            .update(
                db,
                "orders",
                "10",
                &map(&[("keep", "'y'")]),
                &unset,
                &expected(&[("keep", Some("'x'")), ("d", Some("1.0"))])
            )
            .await
            .unwrap()
    );
    assert!(
        !mongo
            .update(
                db,
                "orders",
                "10",
                &map(&[("keep", "'z'")]),
                &BTreeSet::new(),
                &expected(&[("keep", Some("'x'"))])
            )
            .await
            .unwrap(),
        "a stale edit doesn't overwrite"
    );
    assert!(
        !mongo
            .update(
                db,
                "orders",
                "10",
                &map(&[("tags", "[]")]),
                &BTreeSet::new(),
                &expected(&[("tags", Some("['a']"))])
            )
            .await
            .unwrap(),
        "arrays match whole, not by element"
    );
    assert!(
        mongo
            .update(
                db,
                "orders",
                "10",
                &map(&[("added", "true")]),
                &BTreeSet::new(),
                &expected(&[("added", None)])
            )
            .await
            .unwrap()
    );
    // The JSON editor's save writes only what changed: n stays a 64-bit integer.
    let edited = mongo
        .find(db, "orders", "{_id: 10}", "", 0, 1, "")
        .await
        .unwrap()
        .documents
        .remove(0);
    mongo
        .replace(
            db,
            "orders",
            "10",
            &edited.replace("\"y\"", "\"w\""),
            Some(&edited),
        )
        .await
        .unwrap();
    assert_eq!(
        mongo
            .command(db, "db.orders.countDocuments({_id: 10, n: {$type: 'long'}, keep: 'w', added: true, d: {$exists: false}})")
            .await
            .unwrap(),
        "1"
    );
    assert!(
        mongo
            .replace(
                db,
                "orders",
                "10",
                &edited.replace("\"y\"", "\"v\""),
                Some(&edited)
            )
            .await
            .is_err()
    );
    mongo
        .command(db, "db.orders.deleteOne({_id: 10})")
        .await
        .unwrap();

    // A sort with ties still pages through every document exactly once.
    let ties: Vec<String> = (1..=25)
        .map(|i| format!("{{_id: {i}, g: {}}}", i % 2))
        .collect();
    mongo
        .command(db, &format!("db.ties.insertMany([{}])", ties.join(", ")))
        .await
        .unwrap();
    let mut paged = BTreeSet::new();
    for page in 0..5 {
        for d in mongo
            .find(db, "ties", "{}", "{g: 1}", page * 5, 5, "")
            .await
            .unwrap()
            .documents
        {
            paged.insert(id_of(&d));
        }
    }
    assert_eq!(paged, (1..=25).collect());
    assert_eq!(mongo.sample(db, "ties", 10).await.unwrap().len(), 10);

    assert_eq!(
        mongo
            .delete(db, "orders", &[id.clone(), "2".into()])
            .await
            .unwrap(),
        2
    );
    assert!(
        mongo
            .replace(db, "orders", "2", "{customer: 'gone'}", None)
            .await
            .is_err()
    );
    mongo.command(db, "db.orders.drop()").await.unwrap();
}

#[tokio::test]
async fn documents_are_listed_paged_filtered_and_edited() {
    let Some(mongo) = open(Server::default()).await else {
        return;
    };
    let server = mongo.server().await.unwrap();
    assert!(
        server.starts_with("MongoDB 8") && server.contains("replica set rs0"),
        "{server}"
    );
    let db = database();
    documents(mongo.as_ref(), &db).await;
    mongo.command(&db, "db.dropDatabase()").await.unwrap();
}

#[tokio::test]
async fn ferretdb() {
    let Some(mongo) = open(Server {
        var: "TG_IT_FERRETDB",
        db_type: DatabaseType::FERRETDB,
        user: "user",
        password: Some("Pass-2026"),
        ..Server::default()
    })
    .await
    else {
        return;
    };
    assert!(mongo.server().await.unwrap().starts_with("FerretDB 2"));
    let db = database();
    documents(mongo.as_ref(), &db).await;
    mongo.command(&db, "db.dropDatabase()").await.unwrap();
}

#[tokio::test]
async fn the_console_speaks_a_javascript_free_subset_of_the_shell() {
    let Some(mongo) = open(Server::default()).await else {
        return;
    };
    let db = database();
    let run = |line: &'static str| {
        let mongo = mongo.clone();
        let db = db.clone();
        async move { mongo.command(&db, line).await.unwrap() }
    };
    run("db.people.insertMany([{_id: 'a', name: 'Ada', age: 36}, {_id: 'b', name: 'Bob', age: 25}, {_id: 'c', name: 'Cy', age: 36}])").await;
    assert!(run("show collections").await.lines().any(|l| l == "people"));
    let found = run("db.people.find({age: {$gt: 30}}, {name: 1}).sort({name: -1}).limit(5)").await;
    let (cy, ada) = (found.find("Cy").unwrap(), found.find("Ada").unwrap());
    assert!(cy < ada && !found.contains("age"), "{found}");
    assert!(
        run("db.people.findOne({_id: 'b'})")
            .await
            .contains("\"Bob\"")
    );
    assert_eq!(run("db.people.countDocuments({age: 36})").await, "2");
    assert!(run("db.people.distinct('age')").await.contains("25"));
    let grouped =
        run("db.people.aggregate([{$group: {_id: '$age', n: {$sum: 1}}}, {$sort: {_id: 1}}])")
            .await;
    assert!(
        Regex::new(r#""_id": 36,\s*"n": 2"#)
            .unwrap()
            .is_match(&grouped),
        "{grouped}"
    );
    assert_eq!(
        run("db.people.updateMany({age: 36}, {$inc: {age: 1}})").await,
        "{ matchedCount: 2, modifiedCount: 2 }"
    );
    assert_eq!(
        run("db.people.deleteOne({_id: 'c'});").await,
        "{ deletedCount: 1 }"
    );
    assert_eq!(run("db.people.createIndex({age: 1})").await, "age_1");
    assert!(
        run("db.getCollection('people').getIndexes()")
            .await
            .contains("age_1")
    );
    assert!(run("db.runCommand({ping: 1})").await.contains("\"ok\": 1"));
    assert!(
        run("db.adminCommand({listDatabases: 1, nameOnly: true})")
            .await
            .contains("\"ok\": 1")
    );
    assert!(
        run("db.people.mapReduce(function(){})")
            .await
            .starts_with("Error:")
    );
    assert!(run("var x = 1").await.starts_with("Error:"));
    assert!(run("db.people.find({$bad: 1})").await.starts_with("Error:"));
    run("db.dropDatabase()").await;
}

#[tokio::test]
async fn read_only_profiles_refuse_writes_also_from_the_console() {
    let Some(mongo) = open(Server {
        read_only: true,
        ..Server::default()
    })
    .await
    else {
        return;
    };
    let db = database();
    let read_only = |r: Result<String, DriverError>| {
        assert!(matches!(r, Err(DriverError::ReadOnly { .. })), "{r:?}")
    };
    read_only(mongo.insert(&db, "x", "{a: 1}").await);
    read_only(mongo.command(&db, "db.x.insertOne({a: 1})").await);
    read_only(
        mongo
            .update(
                &db,
                "x",
                "1",
                &map(&[("a", "2")]),
                &BTreeSet::new(),
                &BTreeMap::new(),
            )
            .await
            .map(|_| String::new()),
    );
    read_only(mongo.command(&db, "db.x.aggregate([{$out: 'y'}])").await);
    read_only(mongo.command(&db, "db.runCommand({drop: 'x'})").await);
    assert_eq!(
        mongo.command(&db, "db.x.find()").await.unwrap(),
        "(no documents)"
    );
    assert_eq!(
        mongo.command(&db, "db.x.countDocuments({})").await.unwrap(),
        "0"
    );
}

#[tokio::test]
async fn a_connection_string_works_as_well_as_host_and_port() {
    let db = database();
    let Some(mongo) = open(Server {
        url: Some(&db),
        ..Server::default()
    })
    .await
    else {
        return;
    };
    assert!(mongo.server().await.unwrap().starts_with("MongoDB"));
}
