//! `EtcdClientTest` against real servers, skipped unless their variables are set (`host:port`):
//!
//! ```text
//! docker run -d --name dg-etcd -p 127.0.0.1:52379:2379 quay.io/coreos/etcd:v3.5.17 etcd \
//!   --advertise-client-urls http://0.0.0.0:2379 --listen-client-urls http://0.0.0.0:2379
//! TG_IT_ETCD=127.0.0.1:52379 cargo test -p tg-driver --test etcd
//! ```
//! `TG_IT_ETCDAUTH`: a second server with user `root`, password `Secret-2026` and auth enabled
//! (`etcdctl user add root:Secret-2026 && etcdctl auth enable`).
#![cfg(feature = "etcd")]

mod support;

use regex::Regex;
use std::sync::Arc;
use tg_core::connection::ConnectionConfig;
use tg_driver::keyvalue::{KeyEdit, KeyValue};
use tg_driver::{Connection, DriverError, KeyValueConnection, Secrets};
use tg_sql::model::DatabaseType;

async fn server(
    var: &str,
    user: &str,
    password: Option<&str>,
    read_only: bool,
) -> Option<Result<Arc<dyn KeyValueConnection>, DriverError>> {
    let (host, port) = support::server(var)?;
    let mut config = ConnectionConfig::new(var, var, DatabaseType::ETCD);
    config.host = host;
    config.port = Some(port);
    config.user = user.into();
    config.read_only = read_only;
    let secrets = Secrets {
        password: password.map(str::to_owned),
        ssh: None,
    };
    Some(
        tg_driver::connect(&config, &secrets, &support::paths())
            .await
            .map(|c| match c {
                Connection::KeyValue(c) => c,
                _ => panic!("not key-value"),
            }),
    )
}

fn args(line: &str) -> Vec<String> {
    tg_driver::keyvalue::split_arguments(line).unwrap()
}

fn text(value: Option<KeyValue>) -> Option<(String, Option<i64>)> {
    match value? {
        KeyValue::Text {
            value, ttl_millis, ..
        } => Some((value, ttl_millis)),
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn keys_page_edit_expire_and_rename_on_a_real_server() {
    let Some(etcd) = server("TG_IT_ETCD", "", None, false).await else {
        return;
    };
    let etcd = etcd.unwrap();
    let run = |line: &str| {
        let etcd = etcd.clone();
        let a = args(line);
        async move { etcd.command(0, &a).await.unwrap() }
    };
    assert!(etcd.server().await.unwrap().starts_with("etcd 3.5"));
    run("del dg/ --prefix").await;
    for i in 0..250 {
        etcd.edit(
            0,
            &format!("dg/svc/{i}/port"),
            &KeyEdit::SetText((8000 + i).to_string()),
        )
        .await
        .unwrap();
    }
    etcd.edit(
        0,
        "dg/config",
        &KeyEdit::SetText("{\"debug\": true}".into()),
    )
    .await
    .unwrap();
    assert!(etcd.keyspaces().await.unwrap()[0].keys.unwrap_or(0) >= 251);

    let mut seen = Vec::new();
    let mut cursor: Option<String> = None;
    loop {
        let page = etcd
            .scan(0, "dg/svc/*/port", cursor.as_deref(), 40)
            .await
            .unwrap();
        seen.extend(page.keys.into_iter().map(|k| k.name));
        cursor = page.cursor;
        if cursor.is_none() {
            break;
        }
    }
    assert_eq!(
        seen.iter().collect::<std::collections::BTreeSet<_>>().len(),
        250
    );
    assert_eq!(seen.len(), 250, "no key twice across pages");
    let config = etcd.scan(0, "dg/c*", None, 100).await.unwrap();
    assert_eq!(
        config
            .keys
            .iter()
            .map(|k| k.name.as_str())
            .collect::<Vec<_>>(),
        ["dg/config"]
    );

    assert_eq!(
        text(etcd.read(0, "dg/config", 100).await.unwrap())
            .unwrap()
            .0,
        "{\"debug\": true}"
    );
    assert!(etcd.read(0, "dg/missing", 100).await.unwrap().is_none());

    // A TTL is a lease; editing keeps it; it can be removed.
    etcd.expire(0, "dg/config", Some(120_000)).await.unwrap();
    assert!(
        text(etcd.read(0, "dg/config", 100).await.unwrap())
            .unwrap()
            .1
            .unwrap_or(0)
            > 100_000
    );
    etcd.edit(0, "dg/config", &KeyEdit::SetText("{}".into()))
        .await
        .unwrap();
    assert!(
        text(etcd.read(0, "dg/config", 100).await.unwrap())
            .unwrap()
            .1
            .is_some(),
        "editing keeps the lease"
    );
    etcd.expire(0, "dg/config", None).await.unwrap();
    assert!(
        text(etcd.read(0, "dg/config", 100).await.unwrap())
            .unwrap()
            .1
            .is_none()
    );

    etcd.rename(0, "dg/config", "dg/settings").await.unwrap();
    assert!(etcd.read(0, "dg/config", 100).await.unwrap().is_none());
    assert!(
        etcd.rename(0, "dg/settings", "dg/svc/1/port")
            .await
            .is_err()
    );
    let deleted = ["dg/settings", "dg/svc/0/port", "dg/missing"].map(String::from);
    assert_eq!(etcd.delete(0, &deleted).await.unwrap(), 2);
    assert!(
        etcd.edit(
            0,
            "dg/x",
            &KeyEdit::SetField {
                field: "a".into(),
                value: "b".into()
            }
        )
        .await
        .is_err()
    );

    // etcdctl-style console.
    assert_eq!(run("put dg/a 1").await, "OK");
    assert_eq!(run("get dg/a").await, "dg/a\n1");
    assert_eq!(
        run("get dg/svc/ --prefix --keys-only --limit=2").await,
        "dg/svc/1/port\ndg/svc/10/port"
    );
    let grant = run("lease grant 60").await;
    assert!(
        Regex::new(r"^lease [0-9a-f]+ granted with TTL\(60s\)$")
            .unwrap()
            .is_match(&grant),
        "{grant}"
    );
    let lease = grant.split(' ').nth(1).unwrap().to_owned();
    assert!(
        etcd.command(0, &["lease".into(), "timetolive".into(), lease])
            .await
            .unwrap()
            .contains("remaining")
    );
    assert!(run("member list").await.contains("default"));
    assert!(run("endpoint status").await.contains("version 3.5"));
    assert!(run("frobnicate").await.starts_with("Error:"));
    assert_eq!(run("del dg/svc/ --prefix").await, "249");
    run("del dg/ --prefix").await;
}

#[tokio::test]
async fn read_only_profiles_refuse_writes() {
    let Some(etcd) = server("TG_IT_ETCD", "", None, true).await else {
        return;
    };
    let etcd = etcd.unwrap();
    assert!(matches!(
        etcd.edit(0, "dg/ro", &KeyEdit::SetText("x".into())).await,
        Err(DriverError::ReadOnly { .. })
    ));
    assert!(matches!(
        etcd.command(0, &args("put dg/ro x")).await,
        Err(DriverError::ReadOnly { .. })
    ));
    assert_eq!(etcd.command(0, &args("get dg/ro")).await.unwrap(), "");
}

#[tokio::test]
async fn users_authenticate_for_a_token() {
    let Some(etcd) = server("TG_IT_ETCDAUTH", "root", Some("Secret-2026"), false).await else {
        return;
    };
    let etcd = etcd.unwrap();
    assert_eq!(
        etcd.command(0, &args("put dg/auth yes")).await.unwrap(),
        "OK"
    );
    assert_eq!(
        text(etcd.read(0, "dg/auth", 10).await.unwrap()).unwrap().0,
        "yes"
    );
    let failure = server("TG_IT_ETCDAUTH", "root", Some("wrong"), false)
        .await
        .unwrap()
        .err()
        .unwrap();
    assert!(
        failure.to_string().contains("authentication failed"),
        "{failure}"
    );
}
