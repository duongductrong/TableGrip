//! `RedisClientTest` against real servers, skipped unless their variable is set (`host:port`):
//!
//! ```text
//! docker run -d --name dg-redis -p 127.0.0.1:56379:6379 redis:7.4
//! docker run -d --name dg-it-valkey -p 56380:6379 valkey/valkey:8.1
//! docker run -d --name dg-it-keydb -p 56381:6379 eqalpha/keydb
//! docker run -d --name dg-it-dragonfly -p 56382:6379 --ulimit memlock=-1 docker.dragonflydb.io/dragonflydb/dragonfly
//! docker run -d --name dg-it-rcluster -e IP=0.0.0.0 -e INITIAL_PORT=7100 -p 7100-7105:7100-7105 grokzen/redis-cluster:7.0.10
//! TG_IT_REDIS=localhost:56379 cargo test -p tg-driver --test redis
//! ```
//! Also `TG_IT_VALKEY`, `TG_IT_KEYDB`, `TG_IT_DRAGONFLY`, `TG_IT_REDISCLUSTER` and `TG_IT_SENTINEL` (a sentinel
//! monitoring `mymaster`).
#![cfg(feature = "redis")]

mod support;

use std::sync::Arc;
use tg_core::connection::ConnectionConfig;
use tg_driver::keyvalue::{KeyEdit, KeyType, KeyValue};
use tg_driver::{Connection, DriverError, KeyValueConnection, Secrets};
use tg_sql::model::DatabaseType;

async fn server(
    var: &str,
    db_type: DatabaseType,
    read_only: bool,
    properties: &[(&str, &str)],
) -> Option<Arc<dyn KeyValueConnection>> {
    let (host, port) = support::server(var)?;
    let mut config = ConnectionConfig::new(var, var, db_type);
    config.host = host;
    config.port = Some(port);
    config.read_only = read_only;
    for (k, v) in properties {
        config.properties.insert((*k).into(), (*v).into());
    }
    match tg_driver::connect(&config, &Secrets::default(), &support::paths())
        .await
        .expect("connect")
    {
        Connection::KeyValue(c) => Some(c),
        _ => panic!("not key-value"),
    }
}

fn args(line: &str) -> Vec<String> {
    tg_driver::keyvalue::split_arguments(line).unwrap()
}

async fn scan_all(
    redis: &dyn KeyValueConnection,
    db: i32,
    pattern: &str,
    count: usize,
) -> Vec<(String, KeyType)> {
    let mut keys = Vec::new();
    let mut cursor: Option<String> = None;
    loop {
        let page = redis
            .scan(db, pattern, cursor.as_deref(), count)
            .await
            .unwrap();
        keys.extend(page.keys.into_iter().map(|k| (k.name, k.key_type)));
        cursor = page.cursor;
        if cursor.is_none() {
            return keys;
        }
    }
}

async fn contract(redis: &dyn KeyValueConnection, name: &str) {
    let db = 7;
    let server = redis.server().await.unwrap();
    assert!(server.starts_with(name), "{server}");
    assert_eq!(redis.command(db, &args("FLUSHDB")).await.unwrap(), "OK");

    let edit =
        |key: &'static str, e: KeyEdit| async move { redis.edit(db, key, &e).await.unwrap() };
    edit("dg:text", KeyEdit::SetText("hello\nworld".into())).await;
    edit(
        "dg:hash",
        KeyEdit::SetField {
            field: "b".into(),
            value: "2".into(),
        },
    )
    .await;
    edit(
        "dg:hash",
        KeyEdit::SetField {
            field: "a".into(),
            value: "1".into(),
        },
    )
    .await;
    for item in ["x", "y", "z"] {
        edit("dg:list", KeyEdit::AppendItem(item.into())).await;
    }
    edit("dg:set", KeyEdit::AddMember("m".into())).await;
    edit(
        "dg:zset",
        KeyEdit::SetScore {
            member: "low".into(),
            score: 1.0,
        },
    )
    .await;
    edit(
        "dg:zset",
        KeyEdit::SetScore {
            member: "high".into(),
            score: 9.5,
        },
    )
    .await;
    edit(
        "dg:stream",
        KeyEdit::AddEntry(vec![("event".into(), "login".into())]),
    )
    .await;
    for i in 0..300 {
        redis
            .edit(
                db,
                &format!("dg:many:{i}"),
                &KeyEdit::SetText(i.to_string()),
            )
            .await
            .unwrap();
    }

    let keyspaces = redis.keyspaces().await.unwrap();
    assert_eq!(keyspaces.len(), 16);
    assert_eq!(keyspaces[db as usize].keys, Some(306));

    // Every key comes back exactly once across pages, with its type.
    let seen = scan_all(redis, db, "dg:*", 50).await;
    let names: std::collections::BTreeSet<_> = seen.iter().map(|(n, _)| n.clone()).collect();
    assert_eq!(names.len(), 306);
    assert_eq!(seen.len(), 306);
    let mut types: Vec<_> = seen
        .into_iter()
        .filter(|(n, _)| !n.starts_with("dg:many"))
        .collect();
    types.sort_by(|a, b| a.0.cmp(&b.0));
    assert_eq!(
        types,
        [
            ("dg:hash".into(), KeyType::Hash),
            ("dg:list".into(), KeyType::List),
            ("dg:set".into(), KeyType::Set),
            ("dg:stream".into(), KeyType::Stream),
            ("dg:text".into(), KeyType::Text),
            ("dg:zset".into(), KeyType::SortedSet),
        ]
    );
    // A single SCAN batch may hold no match at all; only the whole scan is complete.
    assert_eq!(
        scan_all(redis, db, "dg:h*", 10).await,
        [("dg:hash".into(), KeyType::Hash)]
    );

    let read =
        |key: &'static str, limit: usize| async move { redis.read(db, key, limit).await.unwrap() };
    assert!(
        matches!(read("dg:text", 100).await, Some(KeyValue::Text { value, .. }) if value == "hello\nworld")
    );
    assert!(
        matches!(read("dg:hash", 100).await, Some(KeyValue::Hash { fields, .. })
        if fields == [("a".into(), "1".into()), ("b".into(), "2".into())])
    );
    assert!(
        matches!(read("dg:list", 2).await, Some(KeyValue::List { items, total: 3, .. }) if items == ["x", "y"])
    );
    assert!(
        matches!(read("dg:zset", 100).await, Some(KeyValue::SortedSet { members, .. })
        if members == [("low".into(), 1.0), ("high".into(), 9.5)])
    );
    let Some(KeyValue::Stream { entries, .. }) = read("dg:stream", 100).await else {
        panic!()
    };
    assert_eq!(entries[0].fields, [("event".into(), "login".into())]);
    assert!(read("dg:missing", 100).await.is_none());

    edit(
        "dg:list",
        KeyEdit::SetItem {
            index: 0,
            value: "first".into(),
        },
    )
    .await;
    edit("dg:list", KeyEdit::RemoveItem("y".into())).await;
    assert!(
        matches!(read("dg:list", 100).await, Some(KeyValue::List { items, .. }) if items == ["first", "z"])
    );
    edit("dg:hash", KeyEdit::DeleteField("a".into())).await;
    edit("dg:stream", KeyEdit::DeleteEntry(entries[0].id.clone())).await;
    assert!(matches!(
        read("dg:stream", 100).await,
        Some(KeyValue::Stream { total: 0, .. })
    ));

    // Editing text keeps the TTL; TTL can be set and removed.
    redis.expire(db, "dg:text", Some(60_000)).await.unwrap();
    edit("dg:text", KeyEdit::SetText("changed".into())).await;
    assert!(
        matches!(read("dg:text", 100).await, Some(KeyValue::Text { ttl_millis: Some(t), .. }) if t > 50_000)
    );
    redis.expire(db, "dg:text", None).await.unwrap();
    assert!(matches!(
        read("dg:text", 100).await,
        Some(KeyValue::Text {
            ttl_millis: None,
            ..
        })
    ));

    redis.rename(db, "dg:text", "dg:renamed").await.unwrap();
    assert!(redis.rename(db, "dg:renamed", "dg:hash").await.is_err());
    let deleted = ["dg:renamed", "dg:set", "dg:missing"].map(String::from);
    assert_eq!(redis.delete(db, &deleted).await.unwrap(), 2);

    // The console formats replies like redis-cli, in the browser's database.
    let run = |line: &'static str| async move { redis.command(db, &args(line)).await.unwrap() };
    assert_eq!(run(r#"SET dg:c 'a "b"'"#).await, "OK");
    assert_eq!(run("GET dg:c").await, r#""a \"b\"""#);
    assert_eq!(run("INCR dg:n").await, "(integer) 1");
    assert_eq!(run("LRANGE dg:list 0 -1").await, "1) \"first\"\n2) \"z\"");
    assert_eq!(run("LRANGE dg:none 0 -1").await, "(empty array)");
    assert!(
        run("NOSUCHCOMMAND")
            .await
            .starts_with("(error) ERR unknown command")
    );
    assert!(run("SUBSCRIBE x").await.starts_with("(error)"));
    assert_eq!(
        redis.command(0, &args("GET dg:c")).await.unwrap(),
        "(nil)",
        "keyspace 0 is another database"
    );
    assert!(run("INFO server").await.contains("redis_version"));
    assert_eq!(run("FLUSHDB").await, "OK");
}

#[tokio::test]
async fn redis() {
    if let Some(r) = server("TG_IT_REDIS", DatabaseType::REDIS, false, &[]).await {
        contract(r.as_ref(), "Redis").await;
    }
}

#[tokio::test]
async fn valkey() {
    if let Some(r) = server("TG_IT_VALKEY", DatabaseType::VALKEY, false, &[]).await {
        contract(r.as_ref(), "Valkey").await;
    }
}

#[tokio::test]
async fn keydb() {
    if let Some(r) = server("TG_IT_KEYDB", DatabaseType::KEYDB, false, &[]).await {
        contract(r.as_ref(), "KeyDB").await;
    }
}

#[tokio::test]
async fn dragonfly() {
    if let Some(r) = server("TG_IT_DRAGONFLY", DatabaseType::DRAGONFLY, false, &[]).await {
        contract(r.as_ref(), "Dragonfly").await;
    }
}

#[tokio::test]
async fn sentinel() {
    let props = [("mode", "sentinel"), ("sentinelMaster", "mymaster")];
    let Some(redis) = server("TG_IT_SENTINEL", DatabaseType::REDIS, false, &props).await else {
        return;
    };
    assert!(redis.server().await.unwrap().starts_with("Redis"));
    redis
        .edit(
            3,
            "dg:sentinel",
            &KeyEdit::SetText("through the sentinel".into()),
        )
        .await
        .unwrap();
    assert!(matches!(redis.read(3, "dg:sentinel", 10).await.unwrap(),
        Some(KeyValue::Text { value, .. }) if value == "through the sentinel"));
    assert_eq!(redis.delete(3, &["dg:sentinel".into()]).await.unwrap(), 1);
}

#[tokio::test]
async fn cluster() {
    let Some(redis) = server(
        "TG_IT_REDISCLUSTER",
        DatabaseType::REDIS,
        false,
        &[("mode", "cluster")],
    )
    .await
    else {
        return;
    };
    let server = redis.server().await.unwrap();
    assert!(server.ends_with("(cluster)"), "{server}");
    assert_eq!(
        redis
            .keyspaces()
            .await
            .unwrap()
            .iter()
            .map(|k| k.index)
            .collect::<Vec<_>>(),
        [0]
    );
    redis.command(0, &args("FLUSHALL")).await.unwrap();
    // Keys spread over all three primaries' slots.
    for i in 0..300 {
        redis
            .edit(0, &format!("dg:c:{i}"), &KeyEdit::SetText(i.to_string()))
            .await
            .unwrap();
    }
    redis
        .edit(
            0,
            "dg:c:hash",
            &KeyEdit::SetField {
                field: "f".into(),
                value: "v".into(),
            },
        )
        .await
        .unwrap();
    let seen = scan_all(redis.as_ref(), 0, "dg:c:*", 40).await;
    assert_eq!(seen.len(), 301, "every key once, across nodes");
    assert_eq!(
        seen.iter()
            .map(|k| &k.0)
            .collect::<std::collections::BTreeSet<_>>()
            .len(),
        301
    );
    assert!(matches!(redis.read(0, "dg:c:hash", 10).await.unwrap(),
        Some(KeyValue::Hash { fields, .. }) if fields == [("f".into(), "v".into())]));
    assert_eq!(
        redis.command(0, &args("GET dg:c:7")).await.unwrap(),
        "\"7\""
    );
    // Keys in different slots, deleted in one call.
    let keys = ["dg:c:1", "dg:c:2", "dg:c:3"].map(String::from);
    assert_eq!(redis.delete(0, &keys).await.unwrap(), 3);
    assert!(redis.read(1, "dg:c:4", 10).await.is_err());
    redis.command(0, &args("FLUSHALL")).await.unwrap();
}

#[tokio::test]
async fn read_only_profiles_refuse_writes_also_from_the_console() {
    let Some(redis) = server("TG_IT_REDIS", DatabaseType::REDIS, true, &[]).await else {
        return;
    };
    let read_only =
        |r: Result<(), DriverError>| assert!(matches!(r, Err(DriverError::ReadOnly { .. })));
    read_only(redis.edit(0, "dg:ro", &KeyEdit::SetText("x".into())).await);
    read_only(redis.command(0, &args("SET dg:ro x")).await.map(|_| ()));
    read_only(redis.command(0, &args("FLUSHALL")).await.map(|_| ()));
    assert_eq!(
        redis.command(0, &args("GET dg:ro-missing")).await.unwrap(),
        "(nil)"
    );
}
