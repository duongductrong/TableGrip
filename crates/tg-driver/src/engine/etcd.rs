//! etcd through its v3 HTTP/JSON gateway, which every etcd server serves next to gRPC (`driver/etcd/EtcdClient.kt`).
//! Keys and values travel base64-encoded and int64s as strings, as the gateway does. Properties: `ssl=true` for HTTPS,
//! `verifyPeer=false` to accept a self-signed certificate. A user and password authenticate for a token.

use crate::engine::{Endpoint, Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::keyvalue::{
    Guarded, KeyEdit, KeyInfo, KeyPage, KeyType, KeyValue, KeyValueConnection, Keyspace,
    is_etcd_read,
};
use async_trait::async_trait;
use regex::Regex;
use serde_json::{Map, Value, json};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tokio::sync::RwLock;

/// Values longer than this (in characters) are shown cut.
pub const TEXT_LIMIT: usize = 1_000_000;

/// etcdctl's everyday commands, for usage messages.
const USAGE: &[(&str, &str)] = &[
    ("get", "get KEY [--prefix] [--keys-only] [--limit=N]"),
    ("put", "put KEY VALUE [--lease=ID]"),
    ("del", "del KEY [--prefix]"),
    (
        "lease",
        "lease grant TTL | revoke ID | timetolive ID | list",
    ),
    ("member", "member list"),
    ("endpoint", "endpoint status"),
];

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn KeyValueConnection>> {
    crate::engine::use_ring();
    let (host, port) = host_and_port(config)?;
    let Endpoint { host, port, tunnel } = open_tunnel(config, &host, port, secrets, paths).await?;
    let property = |k: &str| config.properties.get(k).map(String::as_str);
    let tls = property("ssl").is_some_and(|v| v.eq_ignore_ascii_case("true"));
    let mut builder = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .user_agent("TableGrip");
    if tls && property("verifyPeer").is_some_and(|v| v.eq_ignore_ascii_case("false")) {
        builder = builder.tls_danger_accept_invalid_certs(true);
    }
    let http = builder
        .build()
        .map_err(|e| DriverError::Connect(e.to_string()))?;
    let host = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host
    };
    let connection = EtcdConnection {
        config: config.clone(),
        http,
        base: format!("{}://{host}:{port}", if tls { "https" } else { "http" }),
        token: RwLock::new(None),
    };
    let user = config.user.trim();
    if !user.is_empty() {
        connection
            .authenticate(user, secrets.password.as_deref().unwrap_or(""))
            .await?;
    }
    connection.version().await?;
    Ok(Arc::new(Guarded::new(
        Box::new(connection),
        config.read_only,
        is_etcd_read,
        tunnel,
    )))
}

pub struct EtcdConnection {
    config: ConnectionConfig,
    http: reqwest::Client,
    base: String,
    token: RwLock<Option<String>>,
}

/// A failure the console shows after "Error: " (`IOException`, `IllegalArgumentException` in Kotlin).
fn failure(message: impl Into<String>) -> DriverError {
    DriverError::Query(QueryError::new(message))
}

impl EtcdConnection {
    async fn authenticate(&self, user: &str, password: &str) -> Result<()> {
        let reply = self
            .post(
                "/v3/auth/authenticate",
                json!({"name": user, "password": password}),
            )
            .await?;
        *self.token.write().await = reply
            .get("token")
            .and_then(Value::as_str)
            .map(str::to_owned);
        Ok(())
    }

    fn unreachable(&self, e: reqwest::Error) -> DriverError {
        failure(format!("Couldn't reach etcd at {}: {e}", self.base))
    }

    /// One gateway call. Errors come back as JSON with a message, which becomes the error's.
    async fn post(&self, path: &str, body: Value) -> Result<Map<String, Value>> {
        let mut request = self
            .http
            .post(format!("{}{path}", self.base))
            .timeout(Duration::from_secs(30))
            .body(body.to_string());
        if let Some(token) = self.token.read().await.as_ref() {
            request = request.header("Authorization", token);
        }
        let response = request.send().await.map_err(|e| self.unreachable(e))?;
        let status = response.status();
        let text = response.text().await.map_err(|e| self.unreachable(e))?;
        let json = serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(|v| match v {
                Value::Object(o) => Some(o),
                _ => None,
            });
        let head: String = text.chars().take(200).collect();
        if status.as_u16() != 200 {
            let message = json
                .as_ref()
                .and_then(|j| j.get("message").or_else(|| j.get("error")))
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or(head);
            return Err(failure(format!("etcd: {message}")));
        }
        json.ok_or_else(|| failure(format!("etcd sent something that isn't JSON: {head}")))
    }

    /// `etcd 3.5.17`, from `/version`; also how connecting checks the server is etcd.
    async fn version(&self) -> Result<String> {
        let body = self
            .http
            .get(format!("{}/version", self.base))
            .timeout(Duration::from_secs(10))
            .send()
            .await
            .map_err(|e| self.unreachable(e))?
            .text()
            .await
            .map_err(|e| self.unreachable(e))?;
        let version = serde_json::from_str::<Value>(&body)
            .ok()
            .and_then(|v| {
                v.get("etcdserver")
                    .and_then(Value::as_str)
                    .map(str::to_owned)
            })
            .ok_or_else(|| {
                let head: String = body.chars().take(100).collect();
                failure(format!("{} doesn't look like etcd: {head}", self.base))
            })?;
        Ok(format!("etcd {version}"))
    }

    async fn ttl_of(&self, lease: &str) -> Result<Option<i64>> {
        let reply = self
            .post("/v3/lease/timetolive", json!({"ID": lease}))
            .await?;
        Ok(long(&reply, "TTL").filter(|t| *t >= 0))
    }

    async fn first_kv(&self, key: &str) -> Result<Option<Map<String, Value>>> {
        let reply = self
            .post("/v3/kv/range", json!({"key": b64(key.as_bytes())}))
            .await?;
        Ok(kvs(&reply).into_iter().next())
    }

    async fn run(&self, arguments: &[String]) -> Result<String> {
        let flags: std::collections::HashMap<String, String> = arguments
            .iter()
            .filter_map(|a| a.strip_prefix("--"))
            .map(|f| match f.split_once('=') {
                Some((k, v)) => (k.to_owned(), v.to_owned()),
                None => (f.to_owned(), String::new()),
            })
            .collect();
        let args: Vec<&String> = arguments.iter().filter(|a| !a.starts_with("--")).collect();
        let Some(first) = args.first().map(|a| a.to_lowercase()) else {
            return Ok(String::new());
        };
        let arg = |i: usize| -> Result<&str> {
            args.get(i).map(|a| a.as_str()).ok_or_else(|| {
                let usage = USAGE
                    .iter()
                    .find(|(k, _)| *k == first)
                    .map(|(_, u)| (*u).to_owned())
                    .unwrap_or_else(|| {
                        args.iter()
                            .map(|a| a.as_str())
                            .collect::<Vec<_>>()
                            .join(" ")
                    });
                failure(format!("Usage: {usage}"))
            })
        };
        Ok(match first.as_str() {
            "get" => {
                let key = arg(1)?.as_bytes();
                let mut body = json!({"key": b64(key)});
                if flags.contains_key("prefix") {
                    body["range_end"] = json!(b64(&prefix_end(key)));
                }
                if let Some(limit) = flags.get("limit").and_then(|l| l.parse::<i64>().ok()) {
                    body["limit"] = json!(limit);
                }
                let keys_only = flags.contains_key("keys-only");
                if keys_only {
                    body["keys_only"] = json!(true);
                }
                let reply = self.post("/v3/kv/range", body).await?;
                kvs(&reply)
                    .iter()
                    .map(|kv| {
                        let name = text(kv, "key");
                        if keys_only {
                            name
                        } else {
                            format!("{name}\n{}", text(kv, "value"))
                        }
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
            }
            "put" => {
                let mut body =
                    json!({"key": b64(arg(1)?.as_bytes()), "value": b64(arg(2)?.as_bytes())});
                if let Some(lease) = flags.get("lease") {
                    body["lease"] = json!(lease_id(lease)?);
                }
                self.post("/v3/kv/put", body).await?;
                "OK".into()
            }
            "del" => {
                let key = arg(1)?.as_bytes();
                let mut body = json!({"key": b64(key)});
                if flags.contains_key("prefix") {
                    body["range_end"] = json!(b64(&prefix_end(key)));
                }
                let reply = self.post("/v3/kv/deleterange", body).await?;
                long(&reply, "deleted").unwrap_or(0).to_string()
            }
            "lease" => match arg(1)?.to_lowercase().as_str() {
                "grant" => {
                    let ttl: i64 = arg(2)?
                        .parse()
                        .map_err(|_| failure("Usage: lease grant TTL"))?;
                    let reply = self.post("/v3/lease/grant", json!({"TTL": ttl})).await?;
                    format!(
                        "lease {} granted with TTL({}s)",
                        hex(&string(&reply, "ID")?),
                        string(&reply, "TTL")?
                    )
                }
                "revoke" => {
                    self.post("/v3/lease/revoke", json!({"ID": lease_id(arg(2)?)?}))
                        .await?;
                    format!("lease {} revoked", arg(2)?)
                }
                "timetolive" => {
                    let reply = self
                        .post("/v3/lease/timetolive", json!({"ID": lease_id(arg(2)?)?}))
                        .await?;
                    if long(&reply, "TTL").unwrap_or(-1) < 0 {
                        format!("lease {} already expired", arg(2)?)
                    } else {
                        format!(
                            "lease {} granted with TTL({}s), remaining({}s)",
                            arg(2)?,
                            string(&reply, "grantedTTL")?,
                            string(&reply, "TTL")?
                        )
                    }
                }
                "list" => {
                    let reply = self.post("/v3/lease/leases", json!({})).await?;
                    let ids: Vec<String> = array(&reply, "leases")
                        .iter()
                        .filter_map(|l| l.as_object())
                        .map(|l| string(l, "ID").map(|id| hex(&id)))
                        .collect::<Result<_>>()?;
                    if ids.is_empty() {
                        "found 0 leases".into()
                    } else {
                        ids.join("\n")
                    }
                }
                _ => {
                    return Err(failure(
                        "Usage: lease grant TTL | revoke ID | timetolive ID | list",
                    ));
                }
            },
            "member" => {
                let reply = self.post("/v3/cluster/member/list", json!({})).await?;
                array(&reply, "members")
                    .iter()
                    .filter_map(|m| m.as_object())
                    .map(|m| {
                        Ok([
                            hex(&string(m, "ID")?),
                            m.get("name")
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .to_owned(),
                            list(m, "peerURLs"),
                            list(m, "clientURLs"),
                        ]
                        .join(", "))
                    })
                    .collect::<Result<Vec<_>>>()?
                    .join("\n")
            }
            "endpoint" => {
                let s = self.post("/v3/maintenance/status", json!({})).await?;
                format!(
                    "{}, version {}, db size {} bytes, leader {}, raft term {}, raft index {}",
                    self.base,
                    string(&s, "version")?,
                    string(&s, "dbSize")?,
                    hex(&string(&s, "leader")?),
                    string(&s, "raftTerm")?,
                    string(&s, "raftIndex")?
                )
            }
            "version" => self.version().await?,
            _ => {
                let all: Vec<&str> = USAGE.iter().map(|(_, u)| *u).collect();
                return Err(failure(format!(
                    "Unknown command {}. Try: {}",
                    args[0],
                    all.join(" · ")
                )));
            }
        })
    }
}

fn single(keyspace: i32) -> Result<()> {
    if keyspace == 0 {
        Ok(())
    } else {
        Err(DriverError::Unsupported(
            "etcd has a single keyspace.".into(),
        ))
    }
}

#[async_trait]
impl KeyValueConnection for EtcdConnection {
    fn config(&self) -> &ConnectionConfig {
        &self.config
    }

    async fn server(&self) -> Result<String> {
        self.version().await
    }

    async fn keyspaces(&self) -> Result<Vec<Keyspace>> {
        // The whole keyspace: from the lowest key ("\0") to the end ("\0" as range_end).
        let reply = self
            .post(
                "/v3/kv/range",
                json!({"key": b64(&[0]), "range_end": b64(&[0]), "count_only": true}),
            )
            .await?;
        Ok(vec![Keyspace {
            index: 0,
            keys: long(&reply, "count").map(|c| c.max(0) as u64),
        }])
    }

    async fn scan(
        &self,
        keyspace: i32,
        pattern: &str,
        cursor: Option<&str>,
        count: usize,
    ) -> Result<KeyPage> {
        single(keyspace)?;
        // etcd ranges by prefix; the rest of the glob is matched here.
        let glob = Glob::new(if pattern.is_empty() { "*" } else { pattern });
        let prefix = glob.prefix.as_bytes();
        let start = match cursor {
            Some(c) => unb64(c),
            None => prefix.to_vec(),
        };
        let reply = self
            .post(
                "/v3/kv/range",
                json!({"key": b64(&start), "range_end": b64(&prefix_end(prefix)), "limit": count, "keys_only": true}),
            )
            .await?;
        let found = kvs(&reply);
        let keys = found
            .iter()
            .map(|kv| text(kv, "key"))
            .filter(|name| glob.matches(name))
            .map(|name| KeyInfo {
                name,
                key_type: KeyType::Text,
                ttl_millis: None,
            })
            .collect();
        let more = reply
            .get("more")
            .is_some_and(|m| m == &json!(true) || m == "true");
        let next = match found.last() {
            Some(last) if more => {
                let mut key = unb64(last.get("key").and_then(Value::as_str).unwrap_or(""));
                key.push(0);
                Some(b64(&key))
            }
            _ => None,
        };
        Ok(KeyPage { keys, cursor: next })
    }

    async fn read(&self, keyspace: i32, key: &str, _limit: usize) -> Result<Option<KeyValue>> {
        single(keyspace)?;
        let Some(kv) = self.first_kv(key).await? else {
            return Ok(None);
        };
        let value = text(&kv, "value");
        let ttl_millis = match kv
            .get("lease")
            .and_then(Value::as_str)
            .filter(|l| *l != "0")
        {
            Some(lease) => self.ttl_of(lease).await?.map(|t| t * 1_000),
            None => None,
        };
        let truncated = value.chars().count() > TEXT_LIMIT;
        Ok(Some(KeyValue::Text {
            key: key.to_owned(),
            ttl_millis,
            value: if truncated {
                value.chars().take(TEXT_LIMIT).collect()
            } else {
                value
            },
            truncated,
        }))
    }

    async fn edit(&self, keyspace: i32, key: &str, edit: &KeyEdit) -> Result<()> {
        single(keyspace)?;
        let KeyEdit::SetText(value) = edit else {
            return Err(failure("etcd keys hold plain values."));
        };
        // Existing keys keep their lease (and so their TTL); new ones get none.
        let count = self
            .post(
                "/v3/kv/range",
                json!({"key": b64(key.as_bytes()), "count_only": true}),
            )
            .await?;
        let mut body = json!({"key": b64(key.as_bytes()), "value": b64(value.as_bytes())});
        if long(&count, "count") == Some(1) {
            body["ignore_lease"] = json!(true);
        }
        self.post("/v3/kv/put", body).await?;
        Ok(())
    }

    async fn delete(&self, keyspace: i32, keys: &[String]) -> Result<u64> {
        single(keyspace)?;
        let mut total = 0;
        for key in keys {
            let reply = self
                .post("/v3/kv/deleterange", json!({"key": b64(key.as_bytes())}))
                .await?;
            total += long(&reply, "deleted").unwrap_or(0).max(0) as u64;
        }
        Ok(total)
    }

    /// One transaction: only if `to` doesn't exist, write it with `from`'s value and lease, then delete `from`.
    async fn rename(&self, keyspace: i32, from: &str, to: &str) -> Result<()> {
        single(keyspace)?;
        let kv = self
            .first_kv(from)
            .await?
            .ok_or_else(|| failure(format!("{from} no longer exists.")))?;
        let mut put = json!({"key": b64(to.as_bytes()), "value": kv.get("value").cloned().unwrap_or(json!(""))});
        if let Some(lease) = kv.get("lease") {
            put["lease"] = lease.clone();
        }
        let reply = self
            .post(
                "/v3/kv/txn",
                json!({
                    "compare": [{"key": b64(to.as_bytes()), "result": "EQUAL", "target": "CREATE", "create_revision": "0"}],
                    "success": [{"request_put": put}, {"request_delete_range": {"key": b64(from.as_bytes())}}],
                }),
            )
            .await?;
        if reply
            .get("succeeded")
            .is_some_and(|s| s == &json!(true) || s == "true")
        {
            Ok(())
        } else {
            Err(failure(format!("A key named \"{to}\" already exists.")))
        }
    }

    /// A TTL is a lease: grant one and attach it, or detach the key from its lease to keep it forever.
    async fn expire(&self, keyspace: i32, key: &str, ttl_millis: Option<i64>) -> Result<()> {
        single(keyspace)?;
        let lease = match ttl_millis {
            None => "0".to_owned(),
            Some(ms) => {
                let ttl = ((ms + 999) / 1_000).max(1);
                string(
                    &self.post("/v3/lease/grant", json!({"TTL": ttl})).await?,
                    "ID",
                )?
            }
        };
        self.post(
            "/v3/kv/put",
            json!({"key": b64(key.as_bytes()), "lease": lease, "ignore_value": true}),
        )
        .await?;
        Ok(())
    }

    /// etcdctl's everyday commands; failures come back as `Error: …` text, as the console shows them.
    async fn command(&self, _keyspace: i32, arguments: &[String]) -> Result<String> {
        Ok(match self.run(arguments).await {
            Ok(out) => out,
            Err(e) => format!("Error: {e}"),
        })
    }
}

fn kvs(reply: &Map<String, Value>) -> Vec<Map<String, Value>> {
    array(reply, "kvs")
        .iter()
        .filter_map(|v| v.as_object().cloned())
        .collect()
}

fn array<'a>(reply: &'a Map<String, Value>, name: &str) -> &'a [Value] {
    reply
        .get(name)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

/// A base64 field decoded as text.
fn text(kv: &Map<String, Value>, name: &str) -> String {
    String::from_utf8_lossy(&unb64(kv.get(name).and_then(Value::as_str).unwrap_or(""))).into_owned()
}

/// A field as the gateway writes it (int64s are strings).
fn string(reply: &Map<String, Value>, name: &str) -> Result<String> {
    match reply.get(name) {
        Some(Value::String(s)) => Ok(s.clone()),
        Some(Value::Number(n)) => Ok(n.to_string()),
        Some(Value::Bool(b)) => Ok(b.to_string()),
        _ => Err(failure(format!("etcd's reply has no {name}"))),
    }
}

/// A number field; the gateway leaves out fields at their default, so a missing one is 0.
fn long(reply: &Map<String, Value>, name: &str) -> Option<i64> {
    match reply.get(name) {
        None => Some(0),
        Some(Value::String(s)) => s.parse().ok(),
        Some(Value::Number(n)) => n.as_i64(),
        _ => None,
    }
}

fn list(reply: &Map<String, Value>, name: &str) -> String {
    array(reply, name)
        .iter()
        .filter_map(Value::as_str)
        .collect::<Vec<_>>()
        .join(",")
}

/// A decimal lease ID as etcdctl prints it, in hex.
fn hex(decimal: &str) -> String {
    decimal
        .parse::<u64>()
        .map(|v| format!("{v:x}"))
        .unwrap_or_else(|_| decimal.to_owned())
}

/// etcdctl prints lease IDs in hex; the gateway wants them in decimal.
pub fn lease_id(text: &str) -> Result<String> {
    u64::from_str_radix(text, 16)
        .map(|v| v.to_string())
        .map_err(|_| failure(format!("{text} isn't a lease ID")))
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn b64(bytes: &[u8]) -> String {
    let mut out = String::new();
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(B64[(n >> (18 - 6 * i)) as usize & 63] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

pub fn unb64(text: &str) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buffer = 0u32;
    let mut bits = 0;
    for c in text.bytes() {
        let Some(v) = B64.iter().position(|b| *b == c) else {
            continue;
        };
        buffer = (buffer << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
        }
    }
    out
}

/// The end of a prefix range: the prefix with its last byte below 0xff incremented, or "\0" (no end) for all keys.
pub fn prefix_end(prefix: &[u8]) -> Vec<u8> {
    let mut end = prefix.to_vec();
    for i in (0..end.len()).rev() {
        if end[i] != 0xff {
            end[i] += 1;
            end.truncate(i + 1);
            return end;
        }
    }
    vec![0]
}

/// A Redis-style glob (`*`, `?`, `[abc]`, `\` escapes): the literal prefix etcd can range over, and a matcher for the
/// rest.
pub struct Glob {
    pub prefix: String,
    regex: Regex,
}

impl Glob {
    pub fn new(pattern: &str) -> Self {
        let chars: Vec<char> = pattern.chars().collect();
        let mut literal = String::new();
        let mut expression = String::from("(?s)^");
        let mut wild = false;
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            match c {
                '\\' if i + 1 < chars.len() => {
                    i += 1;
                    if !wild {
                        literal.push(chars[i]);
                    }
                    expression += &regex::escape(&chars[i].to_string());
                }
                '*' => {
                    wild = true;
                    expression += ".*";
                }
                '?' => {
                    wild = true;
                    expression.push('.');
                }
                '[' => {
                    wild = true;
                    match chars[i + 1..].iter().position(|c| *c == ']') {
                        None => expression += "\\[",
                        Some(offset) => {
                            let close = i + 1 + offset;
                            let class: String = chars[i + 1..close].iter().collect();
                            expression += &format!("[{}]", class.replace('\\', "\\\\"));
                            i = close;
                        }
                    }
                }
                c => {
                    if !wild {
                        literal.push(c);
                    }
                    expression += &regex::escape(&c.to_string());
                }
            }
            i += 1;
        }
        expression.push('$');
        Self {
            prefix: literal,
            regex: Regex::new(&expression).unwrap_or_else(|_| Regex::new("^$").unwrap()),
        }
    }

    pub fn matches(&self, key: &str) -> bool {
        self.regex.is_match(key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn globs_become_a_prefix_range_and_a_matcher() {
        assert_eq!(Glob::new("user:*").prefix, "user:");
        assert_eq!(Glob::new("*:config").prefix, "");
        assert_eq!(Glob::new("a\\*b?").prefix, "a*b");
        assert!(Glob::new("svc/*/port").matches("svc/api/port"));
        assert!(!Glob::new("svc/*/port").matches("svc/api/host"));
        assert!(Glob::new("node[12]").matches("node2") && !Glob::new("node[12]").matches("node3"));
        assert_eq!(prefix_end(b"ab"), b"ac");
        assert_eq!(prefix_end(b""), [0], "no prefix ranges over everything");
        assert_eq!(prefix_end(&[b'a', 0xff]), b"b");
        assert_eq!(lease_id("694da183c780b108").unwrap(), "7587898534549041416");
    }

    #[test]
    fn base64_round_trips() {
        for bytes in [
            &b""[..],
            b"a",
            b"ab",
            b"abc",
            b"dg/svc/1/port",
            &[0, 255, 7],
        ] {
            assert_eq!(unb64(&b64(bytes)), bytes);
        }
        assert_eq!(b64(b"dg/a"), "ZGcvYQ==");
    }

    #[test]
    fn reads_are_told_from_writes() {
        let split = |s: &str| crate::keyvalue::split_arguments(s).unwrap();
        for line in [
            "get a --prefix",
            "member list",
            "endpoint status",
            "lease timetolive 1f",
            "version",
        ] {
            assert!(is_etcd_read(&split(line)), "{line}");
        }
        for line in [
            "put a b",
            "del a --prefix",
            "lease grant 60",
            "lease revoke 1f",
            "defrag",
        ] {
            assert!(!is_etcd_read(&split(line)), "{line}");
        }
    }
}
