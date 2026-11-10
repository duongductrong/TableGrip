//! Keys and values (`driver/KeyValueConnection.kt`, `KeyValueStore.kt`): what the key browser and command console use.
//! Engines (Redis, etcd) arrive in S5; [`Guarded`] applies the profile's read-only setting to any of them.

use crate::error::{DriverError, Result};
use async_trait::async_trait;
use tg_core::connection::ConnectionConfig;

/// One of a key-value server's databases: Redis's numbered ones (one only in cluster mode). `keys` is None if unknown.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Keyspace {
    pub index: i32,
    pub keys: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyType {
    Text,
    Hash,
    List,
    Set,
    SortedSet,
    Stream,
    Json,
    Other,
}

impl KeyType {
    pub fn label(self) -> &'static str {
        match self {
            Self::Text => "string",
            Self::Hash => "hash",
            Self::List => "list",
            Self::Set => "set",
            Self::SortedSet => "zset",
            Self::Stream => "stream",
            Self::Json => "json",
            Self::Other => "other",
        }
    }
}

/// A key as the browser lists it. `ttl_millis` is None for keys that never expire.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyInfo {
    pub name: String,
    pub key_type: KeyType,
    pub ttl_millis: Option<i64>,
}

/// A page of a scan; `cursor` continues it, None when the scan is complete.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyPage {
    pub keys: Vec<KeyInfo>,
    pub cursor: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StreamEntry {
    pub id: String,
    pub fields: Vec<(String, String)>,
}

/// A key's value, read up to a limit: `total` is the full size, so the browser can say how much is shown. Strings
/// over the limit come back cut, with `truncated`.
#[derive(Clone, Debug, PartialEq)]
pub enum KeyValue {
    Text {
        key: String,
        ttl_millis: Option<i64>,
        value: String,
        truncated: bool,
    },
    Hash {
        key: String,
        ttl_millis: Option<i64>,
        fields: Vec<(String, String)>,
        total: u64,
    },
    List {
        key: String,
        ttl_millis: Option<i64>,
        items: Vec<String>,
        total: u64,
    },
    Set {
        key: String,
        ttl_millis: Option<i64>,
        members: Vec<String>,
        total: u64,
    },
    SortedSet {
        key: String,
        ttl_millis: Option<i64>,
        members: Vec<(String, f64)>,
        total: u64,
    },
    Stream {
        key: String,
        ttl_millis: Option<i64>,
        entries: Vec<StreamEntry>,
        total: u64,
    },
    /// A type the browser doesn't edit (JSON, time series…): shown as the server describes it.
    Other {
        key: String,
        ttl_millis: Option<i64>,
        type_name: String,
        description: String,
    },
}

/// A change to one key. Edits of a missing key create it with that type.
#[derive(Clone, Debug, PartialEq)]
pub enum KeyEdit {
    SetText(String),
    SetField {
        field: String,
        value: String,
    },
    DeleteField(String),
    SetItem {
        index: i64,
        value: String,
    },
    AppendItem(String),
    /// Removes the first occurrence of the value.
    RemoveItem(String),
    AddMember(String),
    RemoveMember(String),
    SetScore {
        member: String,
        score: f64,
    },
    AddEntry(Vec<(String, String)>),
    DeleteEntry(String),
}

#[async_trait]
pub trait KeyValueConnection: Send + Sync {
    fn config(&self) -> &ConnectionConfig;

    /// The server, e.g. "Redis 7.4.2" or "Valkey 8.1.0", and its mode.
    async fn server(&self) -> Result<String>;

    async fn keyspaces(&self) -> Result<Vec<Keyspace>>;

    /// Keys matching the glob `pattern`, a page at a time; `count` is a hint, not a limit.
    async fn scan(
        &self,
        keyspace: i32,
        pattern: &str,
        cursor: Option<&str>,
        count: usize,
    ) -> Result<KeyPage>;

    /// The value of `key` with up to `limit` elements, or None if it doesn't exist.
    async fn read(&self, keyspace: i32, key: &str, limit: usize) -> Result<Option<KeyValue>>;

    async fn edit(&self, keyspace: i32, key: &str, edit: &KeyEdit) -> Result<()>;

    /// Deletes `keys`, returning how many existed.
    async fn delete(&self, keyspace: i32, keys: &[String]) -> Result<u64>;

    /// Renames `from` to `to`; fails if `to` exists.
    async fn rename(&self, keyspace: i32, from: &str, to: &str) -> Result<()>;

    /// Sets the time to live, or removes it when None.
    async fn expire(&self, keyspace: i32, key: &str, ttl_millis: Option<i64>) -> Result<()>;

    /// Runs one console command (already split into arguments) and formats the reply the way redis-cli does.
    async fn command(&self, keyspace: i32, arguments: &[String]) -> Result<String>;
}

/// Splits a console line into arguments the way redis-cli does: spaces separate, double quotes allow escapes
/// (`\n`, `\"`, `\xHH`), single quotes are literal.
pub fn split_arguments(line: &str) -> Result<Vec<String>> {
    let chars: Vec<char> = line.chars().collect();
    let mut arguments = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() {
            break;
        }
        let mut current = String::new();
        let mut quote: Option<char> = None;
        while i < chars.len() {
            let c = chars[i];
            match quote {
                None if c.is_whitespace() => break,
                None if c == '"' || c == '\'' => quote = Some(c),
                Some(q) if c == q => quote = None,
                Some('"') if c == '\\' && i + 1 < chars.len() => {
                    i += 1;
                    match chars[i] {
                        'n' => current.push('\n'),
                        'r' => current.push('\r'),
                        't' => current.push('\t'),
                        'x' => {
                            let hex: String =
                                chars[i + 1..(i + 3).min(chars.len())].iter().collect();
                            match u32::from_str_radix(&hex, 16)
                                .ok()
                                .filter(|_| !hex.starts_with(['+', '-']))
                            {
                                Some(v) => {
                                    current.push(char::from_u32(v).unwrap_or('\u{FFFD}'));
                                    i += 2;
                                }
                                None => current.push('x'),
                            }
                        }
                        e => current.push(e),
                    }
                }
                _ => current.push(c),
            }
            i += 1;
        }
        if quote.is_some() {
            return Err(DriverError::Unsupported("Unbalanced quotes".into()));
        }
        arguments.push(current);
    }
    Ok(arguments)
}

/// Commands that only read, which is what a read-only profile may run from the console. Anything not listed is
/// refused there: a missing read command costs a little convenience, a missing write command would cost data.
pub const READ_COMMANDS: &[&str] = &[
    "GET",
    "MGET",
    "GETRANGE",
    "STRLEN",
    "EXISTS",
    "TYPE",
    "TTL",
    "PTTL",
    "EXPIRETIME",
    "PEXPIRETIME",
    "KEYS",
    "SCAN",
    "RANDOMKEY",
    "DBSIZE",
    "INFO",
    "PING",
    "ECHO",
    "TIME",
    "LASTSAVE",
    "ROLE",
    "MEMORY",
    "OBJECT",
    "DUMP",
    "LCS",
    "HGET",
    "HMGET",
    "HGETALL",
    "HKEYS",
    "HVALS",
    "HLEN",
    "HEXISTS",
    "HSTRLEN",
    "HSCAN",
    "HRANDFIELD",
    "HTTL",
    "HPTTL",
    "LRANGE",
    "LINDEX",
    "LLEN",
    "LPOS",
    "SMEMBERS",
    "SISMEMBER",
    "SMISMEMBER",
    "SCARD",
    "SSCAN",
    "SRANDMEMBER",
    "SINTER",
    "SUNION",
    "SDIFF",
    "SINTERCARD",
    "ZRANGE",
    "ZRANGEBYSCORE",
    "ZREVRANGE",
    "ZREVRANGEBYSCORE",
    "ZRANGEBYLEX",
    "ZSCORE",
    "ZMSCORE",
    "ZCARD",
    "ZCOUNT",
    "ZRANK",
    "ZREVRANK",
    "ZSCAN",
    "ZRANDMEMBER",
    "ZLEXCOUNT",
    "ZINTER",
    "ZUNION",
    "ZDIFF",
    "XRANGE",
    "XREVRANGE",
    "XLEN",
    "XINFO",
    "XPENDING",
    "PFCOUNT",
    "GETBIT",
    "BITCOUNT",
    "BITPOS",
    "GEOPOS",
    "GEODIST",
    "GEOHASH",
    "GEOSEARCH",
    "JSON.GET",
    "JSON.MGET",
    "JSON.TYPE",
    "JSON.STRLEN",
    "JSON.ARRLEN",
    "JSON.OBJKEYS",
    "JSON.OBJLEN",
    "CLIENT",
    "CLUSTER",
    "COMMAND",
    "SLOWLOG",
    "LATENCY",
    "SELECT",
];

pub fn is_redis_read(arguments: &[String]) -> bool {
    arguments
        .first()
        .is_some_and(|a| READ_COMMANDS.contains(&a.to_uppercase().as_str()))
}

/// etcdctl commands that only read.
pub fn is_etcd_read(arguments: &[String]) -> bool {
    let words: Vec<String> = arguments
        .iter()
        .filter(|a| !a.starts_with("--"))
        .map(|a| a.to_lowercase())
        .collect();
    let second = words.get(1).map(String::as_str);
    match words.first().map(String::as_str) {
        Some("get" | "version") => true,
        Some("member") => second == Some("list"),
        Some("endpoint") => second == Some("status"),
        Some("lease") => matches!(second, Some("timetolive" | "list")),
        _ => false,
    }
}

/// Applies the profile's read-only setting to an engine's connection.
pub struct Guarded {
    inner: Box<dyn KeyValueConnection>,
    read_only: bool,
    is_read: fn(&[String]) -> bool,
    _tunnel: Option<tg_core::ssh::SshTunnel>,
}

impl Guarded {
    pub fn new(
        inner: Box<dyn KeyValueConnection>,
        read_only: bool,
        is_read: fn(&[String]) -> bool,
        tunnel: Option<tg_core::ssh::SshTunnel>,
    ) -> Self {
        Self {
            inner,
            read_only,
            is_read,
            _tunnel: tunnel,
        }
    }

    fn check(&self, what: &str) -> Result<()> {
        if self.read_only {
            Err(DriverError::read_only(what))
        } else {
            Ok(())
        }
    }
}

#[async_trait]
impl KeyValueConnection for Guarded {
    fn config(&self) -> &ConnectionConfig {
        self.inner.config()
    }

    async fn server(&self) -> Result<String> {
        self.inner.server().await
    }

    async fn keyspaces(&self) -> Result<Vec<Keyspace>> {
        self.inner.keyspaces().await
    }

    async fn scan(
        &self,
        keyspace: i32,
        pattern: &str,
        cursor: Option<&str>,
        count: usize,
    ) -> Result<KeyPage> {
        self.inner.scan(keyspace, pattern, cursor, count).await
    }

    async fn read(&self, keyspace: i32, key: &str, limit: usize) -> Result<Option<KeyValue>> {
        self.inner.read(keyspace, key, limit).await
    }

    async fn edit(&self, keyspace: i32, key: &str, edit: &KeyEdit) -> Result<()> {
        self.check("editing keys")?;
        self.inner.edit(keyspace, key, edit).await
    }

    async fn delete(&self, keyspace: i32, keys: &[String]) -> Result<u64> {
        self.check("deleting keys")?;
        self.inner.delete(keyspace, keys).await
    }

    async fn rename(&self, keyspace: i32, from: &str, to: &str) -> Result<()> {
        self.check("renaming keys")?;
        self.inner.rename(keyspace, from, to).await
    }

    async fn expire(&self, keyspace: i32, key: &str, ttl_millis: Option<i64>) -> Result<()> {
        self.check("changing TTL")?;
        self.inner.expire(keyspace, key, ttl_millis).await
    }

    async fn command(&self, keyspace: i32, arguments: &[String]) -> Result<String> {
        let Some(name) = arguments.first().map(|a| a.to_uppercase()) else {
            return Ok(String::new());
        };
        if self.read_only && !(self.is_read)(arguments) {
            return Err(DriverError::read_only(&name));
        }
        self.inner.command(keyspace, arguments).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn split(line: &str) -> Vec<String> {
        split_arguments(line).unwrap()
    }

    #[test]
    fn splits_like_redis_cli() {
        assert_eq!(split("  SET  k   v "), ["SET", "k", "v"]);
        assert_eq!(split(r#"SET "a b" 'c d'"#), ["SET", "a b", "c d"]);
        assert_eq!(split(r#"SET k "x\ny\t\"q\"""#), ["SET", "k", "x\ny\t\"q\""]);
        assert_eq!(split(r#"SET k "\x41\x4a" '\n'"#), ["SET", "k", "AJ", "\\n"]);
        assert_eq!(split(r#"SET k "\xZZ""#), ["SET", "k", "xZZ"]);
        assert_eq!(split(r#"a"b c"d"#), ["ab cd"]);
        assert_eq!(split(""), Vec::<String>::new());
        assert!(split_arguments("SET \"open").is_err());
    }

    #[test]
    fn read_commands() {
        let args = |s: &str| split(s);
        assert!(is_redis_read(&args("get k")));
        assert!(!is_redis_read(&args("SET k v")));
        assert!(is_etcd_read(&args("get --prefix a")));
        assert!(is_etcd_read(&args("member list")));
        assert!(!is_etcd_read(&args("member remove 1")));
        assert!(is_etcd_read(&args("lease timetolive 5")));
        assert!(!is_etcd_read(&args("put a b")));
    }
}
