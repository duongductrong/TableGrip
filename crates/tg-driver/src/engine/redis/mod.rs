//! Redis, Valkey, KeyDB and Dragonfly over redis-rs (`engines/redis/RedisClient.kt`): standalone servers, Sentinel and
//! Cluster, with TLS and ACL users. Profile properties choose the mode: `mode=cluster` (hosts are seed nodes),
//! `mode=sentinel` with `sentinelMaster` (hosts are sentinels), `ssl=true`, `verifyPeer=false`.

mod reply;

pub use reply::{format_reply, parse_info};

use crate::engine::{Endpoint, Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::keyvalue::{
    Guarded, KeyEdit, KeyInfo, KeyPage, KeyType, KeyValue, KeyValueConnection, Keyspace,
    StreamEntry, is_redis_read,
};
use async_trait::async_trait;
use redis::aio::MultiplexedConnection;
use redis::cluster_async::ClusterConnection;
use redis::cluster_routing::{RoutingInfo, SingleNodeRoutingInfo};
use redis::{
    AsyncConnectionConfig, Cmd, ConnectionAddr, IntoConnectionInfo, RedisConnectionInfo,
    RedisError, TlsMode, Value, cmd,
};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tokio::sync::Mutex;

/// Strings longer than this (in bytes) are shown cut; editing them in full belongs in the console.
pub const TEXT_LIMIT: i64 = 1_000_000;

const TIMEOUT: Duration = Duration::from_secs(10);

/// Commands that would take over the shared connection.
const UNSUPPORTED: &[&str] = &[
    "SUBSCRIBE",
    "PSUBSCRIBE",
    "SSUBSCRIBE",
    "MONITOR",
    "SYNC",
    "PSYNC",
    "BLPOP",
    "BRPOP",
    "BLMOVE",
    "BZPOPMIN",
    "BZPOPMAX",
    "XREAD",
    "XREADGROUP",
    "WAIT",
];

/// Opens the profile's server (through its SSH tunnel) with its read-only setting applied.
pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn KeyValueConnection>> {
    crate::engine::use_ring();
    let (host, port) = host_and_port(config)?;
    let Endpoint { host, port, tunnel } = open_tunnel(config, &host, port, secrets, paths).await?;
    let inner = open(config, &host, port, secrets.password.as_deref()).await?;
    Ok(Arc::new(Guarded::new(
        Box::new(inner),
        config.read_only,
        is_redis_read,
        tunnel,
    )))
}

fn property<'a>(config: &'a ConnectionConfig, name: &str) -> Option<&'a str> {
    config.properties.get(name).map(String::as_str)
}

/// Seed nodes or sentinels: the profile's host list, or the (tunnelled) host and port for a single one.
fn hosts(list: &str, host: &str, port: u16) -> Vec<(String, u16)> {
    let entries: Vec<&str> = list
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    if entries.len() <= 1 {
        return vec![(host.to_owned(), port)];
    }
    entries
        .into_iter()
        .map(|e| match e.rsplit_once(':') {
            Some((h, p)) => (h.to_owned(), p.parse().unwrap_or(port)),
            None => (e.to_owned(), port),
        })
        .collect()
}

async fn open(
    config: &ConnectionConfig,
    host: &str,
    port: u16,
    password: Option<&str>,
) -> Result<RedisConnection> {
    let tls = property(config, "ssl").is_some_and(|v| v.eq_ignore_ascii_case("true"));
    let insecure = property(config, "verifyPeer").is_some_and(|v| v.eq_ignore_ascii_case("false"));
    let user = Some(config.user.trim()).filter(|u| !u.is_empty());
    let password = password.filter(|p| !p.is_empty());
    let addr = |h: &str, p: u16| {
        if tls {
            ConnectionAddr::TcpTls {
                host: h.to_owned(),
                port: p,
                insecure,
                tls_params: None,
            }
        } else {
            ConnectionAddr::Tcp(h.to_owned(), p)
        }
    };
    let mut settings = RedisConnectionInfo::default();
    if let Some(p) = password {
        if let Some(u) = user {
            settings = settings.set_username(u);
        }
        settings = settings.set_password(p);
    }
    let seeds = hosts(&config.host, host, port);
    let mode = property(config, "mode").map(str::to_lowercase);
    let cluster = async || -> Result<RedisConnection> {
        let nodes: Vec<_> = seeds
            .iter()
            .map(|(h, p)| addr(h, *p).into_connection_info())
            .collect::<redis::RedisResult<_>>()
            .map_err(connect_error)?;
        let mut builder = redis::cluster::ClusterClientBuilder::new(nodes)
            .connection_timeout(TIMEOUT)
            .response_timeout(TIMEOUT);
        if let Some(p) = password {
            builder = builder.password(p);
            if let Some(u) = user {
                builder = builder.username(u);
            }
        }
        if tls {
            builder = builder.tls(if insecure {
                TlsMode::Insecure
            } else {
                TlsMode::Secure
            });
        }
        let client = builder.build().map_err(connect_error)?;
        let mut conn = client.get_async_connection().await.map_err(connect_error)?;
        let primaries = primaries(&mut conn).await?;
        Ok(RedisConnection::new(config, Conn::Cluster(conn, primaries)))
    };
    if mode.as_deref() == Some("cluster") {
        return cluster().await;
    }
    let single = if mode.as_deref() == Some("sentinel") {
        let master = property(config, "sentinelMaster").ok_or_else(|| {
            DriverError::Connect(
                "Sentinel mode needs the sentinelMaster property (the monitored master's name)."
                    .into(),
            )
        })?;
        let mut builder = redis::sentinel::SentinelClientBuilder::new(
            seeds
                .iter()
                .map(|(h, p)| ConnectionAddr::Tcp(h.clone(), *p)),
            master,
            redis::sentinel::SentinelServerType::Master,
        )
        .map_err(connect_error)?;
        if let Some(p) = password {
            builder = builder.set_client_to_redis_password(p);
            if let Some(u) = user {
                builder = builder.set_client_to_redis_username(u);
            }
        }
        if tls {
            builder = builder.set_client_to_redis_tls_mode(if insecure {
                TlsMode::Insecure
            } else {
                TlsMode::Secure
            });
        }
        let mut client = builder.build().map_err(connect_error)?;
        tokio::time::timeout(TIMEOUT, client.get_async_connection())
            .await
            .map_err(|_| DriverError::Connect("Timed out reaching the sentinels.".into()))?
            .map_err(connect_error)?
    } else {
        let info = addr(host, port)
            .into_connection_info()
            .map_err(connect_error)?
            .set_redis_settings(settings);
        let client = redis::Client::open(info).map_err(connect_error)?;
        let options = AsyncConnectionConfig::new()
            .set_connection_timeout(Some(TIMEOUT))
            .set_response_timeout(None);
        client
            .get_multiplexed_async_connection_with_config(&options)
            .await
            .map_err(connect_error)?
    };
    let mut single = single;
    // A cluster node reached without mode=cluster answers MOVED for most keys; switch to the cluster client.
    if mode.is_none() {
        let info: Option<String> = cmd("INFO")
            .arg("cluster")
            .query_async(&mut single)
            .await
            .ok();
        if info
            .is_some_and(|i| parse_info(&i).get("cluster_enabled").map(String::as_str) == Some("1"))
        {
            return cluster().await;
        }
    }
    Ok(RedisConnection::new(config, Conn::Single(single)))
}

fn connect_error(e: RedisError) -> DriverError {
    DriverError::Connect(e.to_string())
}

/// The server's words for a failed command, `ERR unknown command…`, or the client's for anything else.
fn server_message(e: &RedisError) -> Option<String> {
    let code = e.code()?;
    Some(match e.detail() {
        Some(d) => format!("{code} {d}"),
        None => code.to_owned(),
    })
}

fn error(e: RedisError) -> DriverError {
    DriverError::Query(QueryError::new(
        server_message(&e).unwrap_or_else(|| e.to_string()),
    ))
}

/// The primaries' addresses, from `CLUSTER NODES`, so a scan can walk each node's keys.
async fn primaries(conn: &mut ClusterConnection) -> Result<Vec<(String, u16)>> {
    let nodes: String = cmd("CLUSTER")
        .arg("NODES")
        .query_async(conn)
        .await
        .map_err(error)?;
    let mut out: Vec<(String, u16)> = nodes
        .lines()
        .filter_map(|line| {
            let fields: Vec<&str> = line.split_whitespace().collect();
            let flags = fields.get(2)?;
            if !flags.split(',').any(|f| f == "master") || flags.contains("fail") {
                return None;
            }
            let address = fields.get(1)?.split('@').next()?;
            let (h, p) = address.rsplit_once(':')?;
            Some((h.to_owned(), p.parse().ok()?))
        })
        .collect();
    out.sort();
    Ok(out)
}

enum Conn {
    Single(MultiplexedConnection),
    Cluster(ClusterConnection, Vec<(String, u16)>),
}

impl Conn {
    async fn run(&mut self, command: &Cmd) -> redis::RedisResult<Value> {
        match self {
            Self::Single(c) => command.query_async(c).await,
            Self::Cluster(c, _) => command.query_async(c).await,
        }
    }

    /// A command for any one node: `INFO` on a cluster would answer from every node at once.
    async fn one<T: redis::FromRedisValue>(&mut self, command: Cmd) -> Result<T> {
        let value = match self {
            Self::Single(c) => command.query_async(c).await,
            Self::Cluster(c, _) => {
                let route = RoutingInfo::SingleNode(SingleNodeRoutingInfo::RandomPrimary);
                c.route_command(command, route).await
            }
        }
        .map_err(error)?;
        redis::from_redis_value(value)
            .map_err(|e| DriverError::Query(QueryError::new(e.to_string())))
    }

    async fn get<T: redis::FromRedisValue>(&mut self, command: &Cmd) -> Result<T> {
        let value = self.run(command).await.map_err(error)?;
        redis::from_redis_value(value)
            .map_err(|e| DriverError::Query(QueryError::new(e.to_string())))
    }
}

struct State {
    conn: Conn,
    /// SELECT is per connection, so every call that depends on it runs under the lock.
    selected: i32,
}

pub struct RedisConnection {
    config: ConnectionConfig,
    state: Mutex<State>,
}

impl RedisConnection {
    fn new(config: &ConnectionConfig, conn: Conn) -> Self {
        Self {
            config: config.clone(),
            state: Mutex::new(State { conn, selected: 0 }),
        }
    }

    /// The connection, switched to `keyspace` and held for one operation.
    async fn on(&self, keyspace: i32) -> Result<tokio::sync::MutexGuard<'_, State>> {
        let mut state = self.state.lock().await;
        let selected = state.selected;
        match &mut state.conn {
            Conn::Cluster(..) => {
                if keyspace != 0 {
                    return Err(DriverError::Unsupported(
                        "A Redis cluster has database 0 only.".into(),
                    ));
                }
            }
            Conn::Single(c) => {
                if keyspace != selected {
                    let _: () = cmd("SELECT")
                        .arg(keyspace)
                        .query_async(c)
                        .await
                        .map_err(error)?;
                    state.selected = keyspace;
                }
            }
        }
        Ok(state)
    }

    /// Each key's type and TTL: one pipeline on a single server, one request per key on a cluster.
    async fn describe(conn: &mut Conn, keys: Vec<String>) -> Result<Vec<KeyInfo>> {
        let mut out = Vec::with_capacity(keys.len());
        if keys.is_empty() {
            return Ok(out);
        }
        match conn {
            Conn::Single(c) => {
                let mut pipe = redis::pipe();
                for k in &keys {
                    pipe.cmd("TYPE").arg(k).cmd("PTTL").arg(k);
                }
                let replies: Vec<Value> = pipe.query_async(c).await.map_err(error)?;
                for (k, pair) in keys.into_iter().zip(replies.chunks(2)) {
                    out.push(KeyInfo {
                        name: k,
                        key_type: key_type(&text(&pair[0])),
                        ttl_millis: ttl(&pair[1]),
                    });
                }
            }
            Conn::Cluster(c, _) => {
                let futures = keys.into_iter().map(|k| {
                    let mut c = c.clone();
                    async move {
                        let t: Value = cmd("TYPE").arg(&k).query_async(&mut c).await?;
                        let p: Value = cmd("PTTL").arg(&k).query_async(&mut c).await?;
                        Ok::<_, RedisError>(KeyInfo {
                            key_type: key_type(&text(&t)),
                            ttl_millis: ttl(&p),
                            name: k,
                        })
                    }
                });
                for info in futures_util::future::join_all(futures).await {
                    out.push(info.map_err(error)?);
                }
            }
        }
        Ok(out)
    }
}

fn text(value: &Value) -> String {
    match value {
        Value::BulkString(b) => String::from_utf8_lossy(b).into_owned(),
        Value::SimpleString(s) => s.clone(),
        Value::Okay => "OK".into(),
        Value::Int(i) => i.to_string(),
        _ => String::new(),
    }
}

fn ttl(value: &Value) -> Option<i64> {
    match value {
        Value::Int(i) if *i >= 0 => Some(*i),
        _ => None,
    }
}

pub fn key_type(name: &str) -> KeyType {
    match name {
        "string" => KeyType::Text,
        "hash" => KeyType::Hash,
        "list" => KeyType::List,
        "set" => KeyType::Set,
        "zset" => KeyType::SortedSet,
        "stream" => KeyType::Stream,
        "ReJSON-RL" => KeyType::Json,
        _ => KeyType::Other,
    }
}

/// `HSCAN`/`SSCAN` until `limit` elements or the end: each reply is `[cursor, [elements…]]`.
async fn scan_elements(
    conn: &mut Conn,
    command: &str,
    key: &str,
    limit: usize,
    per_element: usize,
) -> Result<Vec<String>> {
    let mut out = Vec::new();
    let mut cursor = "0".to_owned();
    loop {
        let (next, items): (String, Vec<String>) = conn
            .get(cmd(command).arg(key).arg(&cursor).arg("COUNT").arg(500))
            .await?;
        for chunk in items.chunks(per_element) {
            if out.len() / per_element < limit {
                out.extend(chunk.iter().cloned());
            }
        }
        cursor = next;
        if cursor == "0" || out.len() / per_element >= limit {
            return Ok(out);
        }
    }
}

#[async_trait]
impl KeyValueConnection for RedisConnection {
    fn config(&self) -> &ConnectionConfig {
        &self.config
    }

    async fn server(&self) -> Result<String> {
        let mut state = self.state.lock().await;
        let info: String = state.conn.one(cmd("INFO").arg("server").clone()).await?;
        Ok(describe_server(&parse_info(&info)))
    }

    async fn keyspaces(&self) -> Result<Vec<Keyspace>> {
        let mut state = self.state.lock().await;
        let info: String = state.conn.one(cmd("INFO").arg("keyspace").clone()).await?;
        let counts: std::collections::HashMap<i32, u64> = parse_info(&info)
            .into_iter()
            .filter_map(|(name, value)| {
                let index = name.strip_prefix("db")?.parse().ok()?;
                let keys = value
                    .split(',')
                    .find_map(|p| p.strip_prefix("keys="))
                    .and_then(|k| k.parse().ok())
                    .unwrap_or(0);
                Some((index, keys))
            })
            .collect();
        if matches!(state.conn, Conn::Cluster(..)) {
            return Ok(vec![Keyspace {
                index: 0,
                keys: Some(counts.get(&0).copied().unwrap_or(0)),
            }]);
        }
        // Managed services often forbid CONFIG; 16 is the default.
        let databases = state
            .conn
            .get::<Vec<String>>(cmd("CONFIG").arg("GET").arg("databases"))
            .await
            .ok()
            .and_then(|v| v.get(1).and_then(|n| n.parse::<i32>().ok()))
            .unwrap_or(16);
        Ok((0..databases)
            .map(|i| Keyspace {
                index: i,
                keys: Some(counts.get(&i).copied().unwrap_or(0)),
            })
            .collect())
    }

    async fn scan(
        &self,
        keyspace: i32,
        pattern: &str,
        cursor: Option<&str>,
        count: usize,
    ) -> Result<KeyPage> {
        let mut state = self.on(keyspace).await?;
        let pattern = if pattern.is_empty() { "*" } else { pattern };
        let (keys, next) = match &mut state.conn {
            Conn::Single(c) => {
                let (next, keys): (String, Vec<String>) = cmd("SCAN")
                    .arg(cursor.unwrap_or("0"))
                    .arg("MATCH")
                    .arg(pattern)
                    .arg("COUNT")
                    .arg(count)
                    .query_async(c)
                    .await
                    .map_err(error)?;
                (keys, (next != "0").then_some(next))
            }
            // A cluster's keys live on its primaries: the cursor is `<node>:<that node's cursor>`.
            Conn::Cluster(c, nodes) => {
                let (mut node, mut position) = cursor
                    .and_then(|t| t.split_once(':'))
                    .and_then(|(n, p)| Some((n.parse::<usize>().ok()?, p.to_owned())))
                    .unwrap_or((0, "0".into()));
                let mut keys = Vec::new();
                let mut next = None;
                while let Some((host, port)) = nodes.get(node) {
                    let route = RoutingInfo::SingleNode(SingleNodeRoutingInfo::ByAddress {
                        host: host.clone(),
                        port: *port,
                    });
                    let mut scan = cmd("SCAN");
                    scan.arg(&position)
                        .arg("MATCH")
                        .arg(pattern)
                        .arg("COUNT")
                        .arg(count);
                    let reply = c.route_command(scan, route).await.map_err(error)?;
                    let (cursor, found): (String, Vec<String>) = redis::from_redis_value(reply)
                        .map_err(|e| DriverError::Query(QueryError::new(e.to_string())))?;
                    keys.extend(found);
                    if cursor != "0" {
                        next = Some(format!("{node}:{cursor}"));
                        break;
                    }
                    node += 1;
                    position = "0".into();
                    if !keys.is_empty() && node < nodes.len() {
                        next = Some(format!("{node}:0"));
                        break;
                    }
                }
                (keys, next)
            }
        };
        let mut keys = Self::describe(&mut state.conn, keys).await?;
        keys.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(KeyPage { keys, cursor: next })
    }

    async fn read(&self, keyspace: i32, key: &str, limit: usize) -> Result<Option<KeyValue>> {
        let mut state = self.on(keyspace).await?;
        let conn = &mut state.conn;
        let kind: String = conn.get(cmd("TYPE").arg(key)).await?;
        if kind == "none" {
            return Ok(None);
        }
        let ttl_millis = ttl(&conn.run(cmd("PTTL").arg(key)).await.map_err(error)?);
        let key_s = key.to_owned();
        let last = limit as i64 - 1;
        Ok(Some(match kind.as_str() {
            "string" => {
                let length: i64 = conn.get(cmd("STRLEN").arg(key)).await?;
                let truncated = length > TEXT_LIMIT;
                let value = if truncated {
                    let bytes: Vec<u8> = conn
                        .get(cmd("GETRANGE").arg(key).arg(0).arg(TEXT_LIMIT - 1))
                        .await?;
                    String::from_utf8_lossy(&bytes).into_owned()
                } else {
                    let bytes: Option<Vec<u8>> = conn.get(cmd("GET").arg(key)).await?;
                    String::from_utf8_lossy(&bytes.unwrap_or_default()).into_owned()
                };
                KeyValue::Text {
                    key: key_s,
                    ttl_millis,
                    value,
                    truncated,
                }
            }
            "hash" => {
                let flat = scan_elements(conn, "HSCAN", key, limit, 2).await?;
                let mut fields: Vec<(String, String)> = flat
                    .chunks(2)
                    .map(|p| (p[0].clone(), p.get(1).cloned().unwrap_or_default()))
                    .collect();
                fields.sort_by(|a, b| a.0.cmp(&b.0));
                let total = conn.get(cmd("HLEN").arg(key)).await?;
                KeyValue::Hash {
                    key: key_s,
                    ttl_millis,
                    fields,
                    total,
                }
            }
            "list" => KeyValue::List {
                key: key_s,
                ttl_millis,
                items: conn.get(cmd("LRANGE").arg(key).arg(0).arg(last)).await?,
                total: conn.get(cmd("LLEN").arg(key)).await?,
            },
            "set" => {
                let mut members = scan_elements(conn, "SSCAN", key, limit, 1).await?;
                members.sort();
                KeyValue::Set {
                    key: key_s,
                    ttl_millis,
                    members,
                    total: conn.get(cmd("SCARD").arg(key)).await?,
                }
            }
            "zset" => KeyValue::SortedSet {
                key: key_s,
                ttl_millis,
                members: conn
                    .get(cmd("ZRANGE").arg(key).arg(0).arg(last).arg("WITHSCORES"))
                    .await?,
                total: conn.get(cmd("ZCARD").arg(key)).await?,
            },
            "stream" => {
                let entries: Vec<(String, Vec<(String, String)>)> = conn
                    .get(
                        cmd("XRANGE")
                            .arg(key)
                            .arg("-")
                            .arg("+")
                            .arg("COUNT")
                            .arg(limit),
                    )
                    .await?;
                KeyValue::Stream {
                    key: key_s,
                    ttl_millis,
                    entries: entries
                        .into_iter()
                        .map(|(id, fields)| StreamEntry { id, fields })
                        .collect(),
                    total: conn.get(cmd("XLEN").arg(key)).await?,
                }
            }
            "ReJSON-RL" => {
                let description = match conn.run(cmd("JSON.GET").arg(key)).await {
                    Ok(v) => format_reply(&v),
                    Err(e) => format!("(error) {}", server_message(&e).unwrap_or(e.to_string())),
                };
                KeyValue::Other {
                    key: key_s,
                    ttl_millis,
                    type_name: "json".into(),
                    description,
                }
            }
            other => KeyValue::Other {
                key: key_s,
                ttl_millis,
                type_name: other.to_owned(),
                description: String::new(),
            },
        }))
    }

    async fn edit(&self, keyspace: i32, key: &str, edit: &KeyEdit) -> Result<()> {
        let mut state = self.on(keyspace).await?;
        let c = match edit {
            KeyEdit::SetText(v) => {
                let mut c = cmd("SET");
                c.arg(key).arg(v).arg("KEEPTTL");
                c
            }
            KeyEdit::SetField { field, value } => {
                let mut c = cmd("HSET");
                c.arg(key).arg(field).arg(value);
                c
            }
            KeyEdit::DeleteField(f) => {
                let mut c = cmd("HDEL");
                c.arg(key).arg(f);
                c
            }
            KeyEdit::SetItem { index, value } => {
                let mut c = cmd("LSET");
                c.arg(key).arg(index).arg(value);
                c
            }
            KeyEdit::AppendItem(v) => {
                let mut c = cmd("RPUSH");
                c.arg(key).arg(v);
                c
            }
            KeyEdit::RemoveItem(v) => {
                let mut c = cmd("LREM");
                c.arg(key).arg(1).arg(v);
                c
            }
            KeyEdit::AddMember(m) => {
                let mut c = cmd("SADD");
                c.arg(key).arg(m);
                c
            }
            KeyEdit::RemoveMember(m) => {
                let mut c = cmd("SREM");
                c.arg(key).arg(m);
                c
            }
            KeyEdit::SetScore { member, score } => {
                let mut c = cmd("ZADD");
                c.arg(key).arg(score).arg(member);
                c
            }
            KeyEdit::AddEntry(fields) => {
                let mut c = cmd("XADD");
                c.arg(key).arg("*");
                for (f, v) in fields {
                    c.arg(f).arg(v);
                }
                c
            }
            KeyEdit::DeleteEntry(id) => {
                let mut c = cmd("XDEL");
                c.arg(key).arg(id);
                c
            }
        };
        state.conn.run(&c).await.map_err(error)?;
        Ok(())
    }

    async fn delete(&self, keyspace: i32, keys: &[String]) -> Result<u64> {
        if keys.is_empty() {
            return Ok(0);
        }
        let mut state = self.on(keyspace).await?;
        match &mut state.conn {
            Conn::Single(_) => state.conn.get(cmd("DEL").arg(keys)).await,
            // Keys in different slots can't share one DEL.
            Conn::Cluster(..) => {
                let mut total = 0;
                for k in keys {
                    total += state.conn.get::<u64>(cmd("DEL").arg(k)).await?;
                }
                Ok(total)
            }
        }
    }

    async fn rename(&self, keyspace: i32, from: &str, to: &str) -> Result<()> {
        let mut state = self.on(keyspace).await?;
        let renamed: bool = state.conn.get(cmd("RENAMENX").arg(from).arg(to)).await?;
        if renamed {
            Ok(())
        } else {
            Err(DriverError::Query(QueryError::new(format!(
                "A key named \"{to}\" already exists."
            ))))
        }
    }

    async fn expire(&self, keyspace: i32, key: &str, ttl_millis: Option<i64>) -> Result<()> {
        let mut state = self.on(keyspace).await?;
        let c = match ttl_millis {
            None => {
                let mut c = cmd("PERSIST");
                c.arg(key);
                c
            }
            Some(ms) => {
                let mut c = cmd("PEXPIRE");
                c.arg(key).arg(ms);
                c
            }
        };
        state.conn.run(&c).await.map_err(error)?;
        Ok(())
    }

    async fn command(&self, keyspace: i32, arguments: &[String]) -> Result<String> {
        let Some(name) = arguments.first().map(|a| a.to_uppercase()) else {
            return Ok(String::new());
        };
        if UNSUPPORTED.contains(&name.as_str()) {
            return Ok(format!(
                "(error) {name} keeps the connection busy; it isn't available in the console."
            ));
        }
        if name == "SELECT" {
            let Some(index) = arguments.get(1).and_then(|a| a.parse::<i32>().ok()) else {
                return Ok("(error) ERR invalid DB index".into());
            };
            return Ok(match self.on(index).await {
                Ok(_) => "OK".into(),
                Err(e) => format!("(error) {e}"),
            });
        }
        let mut state = self.on(keyspace).await?;
        let mut c = cmd(&arguments[0]);
        for a in &arguments[1..] {
            c.arg(a);
        }
        match state.conn.run(&c).await {
            Ok(v) => Ok(format_reply(&v)),
            Err(e) => match server_message(&e) {
                Some(m) => Ok(format!("(error) {m}")),
                None => Err(error(e)),
            },
        }
    }
}

/// "Redis 7.4.2", "Valkey 8.1.0 (cluster)"… from `INFO server`.
pub fn describe_server(info: &std::collections::HashMap<String, String>) -> String {
    let get = |k: &str| info.get(k).cloned();
    let (name, version) =
        if get("server_name").as_deref() == Some("valkey") || info.contains_key("valkey_version") {
            ("Valkey", get("valkey_version"))
        } else if info.contains_key("dragonfly_version") {
            ("Dragonfly", get("dragonfly_version"))
        } else if info.contains_key("keydb_version")
            || get("executable").is_some_and(|e| e.contains("keydb"))
        {
            // KeyDB reports its own version as redis_version.
            (
                "KeyDB",
                get("keydb_version").or_else(|| get("redis_version")),
            )
        } else {
            ("Redis", get("redis_version"))
        };
    let mut out = match version {
        Some(v) => format!("{name} {v}"),
        None => name.to_owned(),
    };
    if let Some(mode) = get("redis_mode").filter(|m| m != "standalone") {
        out += &format!(" ({mode})");
    }
    out
}
