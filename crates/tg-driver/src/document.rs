//! Databases, collections and documents (`driver/DocumentConnection.kt`, `DocumentStore.kt`): what the document browser
//! and shell console use. Filters, sorts and documents are Extended JSON, in the shell's relaxed syntax too. The
//! MongoDB engine arrives in S5; [`Guarded`] applies the profile's read-only setting to it.

use crate::error::{DriverError, Result};
use async_trait::async_trait;
use regex::Regex;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::LazyLock;
use tg_core::connection::ConnectionConfig;

/// A collection as the explorer lists it. `kind` is `collection`, `view` or `timeseries`; `documents` is an estimate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CollectionInfo {
    pub name: String,
    pub kind: String,
    pub documents: Option<u64>,
}

/// A page of documents as relaxed Extended JSON, one string each; `has_more` says whether another page follows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DocumentPage {
    pub documents: Vec<String>,
    pub has_more: bool,
}

#[async_trait]
pub trait DocumentConnection: Send + Sync {
    fn config(&self) -> &ConnectionConfig;

    /// The server, e.g. "MongoDB 8.0.4" and its topology.
    async fn server(&self) -> Result<String>;

    async fn databases(&self) -> Result<Vec<String>>;

    async fn collections(&self, database: &str) -> Result<Vec<CollectionInfo>>;

    /// `projection` picks the fields returned, as in `find()`'s second argument: `{name: 1}` or `{items: 0}`.
    #[allow(clippy::too_many_arguments)]
    async fn find(
        &self,
        database: &str,
        collection: &str,
        filter: &str,
        sort: &str,
        skip: u64,
        limit: usize,
        projection: &str,
    ) -> Result<DocumentPage>;

    async fn count(&self, database: &str, collection: &str, filter: &str) -> Result<u64>;

    /// Inserts `document`, returning its `_id` as Extended JSON.
    async fn insert(&self, database: &str, collection: &str, document: &str) -> Result<String>;

    /// Replaces the document whose `_id` is `id`. With `original` (the document as read), only the top-level fields
    /// that differ are written, and only while they still hold their original values.
    async fn replace(
        &self,
        database: &str,
        collection: &str,
        id: &str,
        document: &str,
        original: Option<&str>,
    ) -> Result<()>;

    /// Sets and removes top-level fields while every field in `expected` still holds its value (None: missing);
    /// false when it didn't match.
    async fn update(
        &self,
        database: &str,
        collection: &str,
        id: &str,
        set: &BTreeMap<String, String>,
        unset: &BTreeSet<String>,
        expected: &BTreeMap<String, Option<String>>,
    ) -> Result<bool>;

    /// Up to `size` documents picked at random, e.g. to learn the fields a collection's documents have.
    async fn sample(&self, database: &str, collection: &str, size: usize) -> Result<Vec<String>> {
        Ok(self
            .find(database, collection, "{}", "{}", 0, size, "{}")
            .await?
            .documents)
    }

    /// Deletes the documents with these `_id`s, returning how many were deleted.
    async fn delete(&self, database: &str, collection: &str, ids: &[String]) -> Result<u64>;

    /// Index definitions as Extended JSON.
    async fn indexes(&self, database: &str, collection: &str) -> Result<Vec<String>>;

    /// Runs one shell command in `database` and returns the reply as the shell would print it.
    async fn command(&self, database: &str, line: &str) -> Result<String>;
}

static COLLECTION_CALL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"^db\s*\.\s*(?:getCollection\s*\(\s*(?:'[^']*?'|"[^"]*?")\s*\)|[A-Za-z_$][\w$]*)\s*\.\s*([A-Za-z]+)\s*\("#)
        .unwrap()
});
static DATABASE_CALL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^db\s*\.\s*([A-Za-z]+)\s*\(").unwrap());
static FIRST_KEY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"^\{\s*['"]?([A-Za-z]+)"#).unwrap());
static SHELL_ONLY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^(show\s+\w+|use\s+\S+|it|help)$").unwrap());

const READ_METHODS: &[&str] = &[
    "find",
    "findOne",
    "countDocuments",
    "estimatedDocumentCount",
    "count",
    "distinct",
    "aggregate",
    "getIndexes",
    "stats",
    "explain",
];
const READ_DATABASE_METHODS: &[&str] = &[
    "getCollectionNames",
    "getCollectionInfos",
    "stats",
    "getName",
    "version",
    "serverStatus",
    "hello",
];
const READ_COMMANDS: &[&str] = &[
    "find",
    "aggregate",
    "count",
    "distinct",
    "listCollections",
    "listDatabases",
    "listIndexes",
    "dbStats",
    "collStats",
    "serverStatus",
    "buildInfo",
    "hello",
    "isMaster",
    "ping",
    "explain",
    "connectionStatus",
    "hostInfo",
    "getParameter",
];

/// Whether a console `line` only reads, which is what a read-only profile may run. Anything unrecognised counts as a
/// write; pipelines that write (`$out`, `$merge`) are writes.
pub fn is_read(line: &str) -> bool {
    let text = line.trim();
    let text = text.strip_suffix(';').unwrap_or(text).trim();
    if SHELL_ONLY.is_match(text) {
        return true;
    }
    if text.contains("$out") || text.contains("$merge") {
        return false;
    }
    if let Some(c) = COLLECTION_CALL.captures(text) {
        return READ_METHODS.contains(&&c[1]);
    }
    let Some(method) = DATABASE_CALL.captures(text).map(|c| c[1].to_owned()) else {
        return false;
    };
    if READ_DATABASE_METHODS.contains(&method.as_str()) {
        return true;
    }
    if method == "runCommand" || method == "adminCommand" {
        let argument = text.split_once('(').map_or(text, |(_, a)| a).trim();
        return FIRST_KEY
            .captures(argument)
            .is_some_and(|c| READ_COMMANDS.contains(&&c[1]));
    }
    false
}

/// Applies the profile's read-only setting to an engine's connection.
pub struct Guarded {
    inner: Box<dyn DocumentConnection>,
    read_only: bool,
    _tunnel: Option<tg_core::ssh::SshTunnel>,
}

impl Guarded {
    pub fn new(
        inner: Box<dyn DocumentConnection>,
        read_only: bool,
        tunnel: Option<tg_core::ssh::SshTunnel>,
    ) -> Self {
        Self {
            inner,
            read_only,
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
impl DocumentConnection for Guarded {
    fn config(&self) -> &ConnectionConfig {
        self.inner.config()
    }

    async fn server(&self) -> Result<String> {
        self.inner.server().await
    }

    async fn databases(&self) -> Result<Vec<String>> {
        self.inner.databases().await
    }

    async fn collections(&self, database: &str) -> Result<Vec<CollectionInfo>> {
        self.inner.collections(database).await
    }

    async fn find(
        &self,
        database: &str,
        collection: &str,
        filter: &str,
        sort: &str,
        skip: u64,
        limit: usize,
        projection: &str,
    ) -> Result<DocumentPage> {
        self.inner
            .find(database, collection, filter, sort, skip, limit, projection)
            .await
    }

    async fn count(&self, database: &str, collection: &str, filter: &str) -> Result<u64> {
        self.inner.count(database, collection, filter).await
    }

    async fn insert(&self, database: &str, collection: &str, document: &str) -> Result<String> {
        self.check("inserting documents")?;
        self.inner.insert(database, collection, document).await
    }

    async fn replace(
        &self,
        database: &str,
        collection: &str,
        id: &str,
        document: &str,
        original: Option<&str>,
    ) -> Result<()> {
        self.check("changing documents")?;
        self.inner
            .replace(database, collection, id, document, original)
            .await
    }

    async fn update(
        &self,
        database: &str,
        collection: &str,
        id: &str,
        set: &BTreeMap<String, String>,
        unset: &BTreeSet<String>,
        expected: &BTreeMap<String, Option<String>>,
    ) -> Result<bool> {
        self.check("changing documents")?;
        self.inner
            .update(database, collection, id, set, unset, expected)
            .await
    }

    async fn sample(&self, database: &str, collection: &str, size: usize) -> Result<Vec<String>> {
        self.inner.sample(database, collection, size).await
    }

    async fn delete(&self, database: &str, collection: &str, ids: &[String]) -> Result<u64> {
        self.check("deleting documents")?;
        self.inner.delete(database, collection, ids).await
    }

    async fn indexes(&self, database: &str, collection: &str) -> Result<Vec<String>> {
        self.inner.indexes(database, collection).await
    }

    async fn command(&self, database: &str, line: &str) -> Result<String> {
        if self.read_only && !is_read(line) {
            return Err(DriverError::read_only("this command"));
        }
        self.inner.command(database, line).await
    }
}

#[cfg(test)]
mod tests {
    use super::is_read;

    #[test]
    fn recognises_reads() {
        for line in [
            "show collections",
            "use shop",
            "db.orders.find({status: 'A'}).limit(5);",
            "db.getCollection('a.b').aggregate([{$match: {}}])",
            "db.getCollectionNames()",
            "db.runCommand({ listCollections: 1 })",
            "db.adminCommand({'ping': 1})",
        ] {
            assert!(is_read(line), "{line}");
        }
        for line in [
            "db.orders.insertOne({})",
            "db.orders.aggregate([{$out: 'x'}])",
            "db.dropDatabase()",
            "db.runCommand({drop: 'orders'})",
            "printjson(1)",
        ] {
            assert!(!is_read(line), "{line}");
        }
    }
}
