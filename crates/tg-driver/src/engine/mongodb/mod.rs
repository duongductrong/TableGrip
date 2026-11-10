//! MongoDB and FerretDB over the official Rust driver (`engines/mongodb/MongoClient.kt`). A profile connects with its
//! connection string (`mongodb://…`, `mongodb+srv://…`) or with host, port and credentials; properties add URI options
//! such as `tls=true`, `authSource=admin`, `replicaSet=rs0`. A single host connects directly unless a replica set is
//! named, so a member that advertises an internal address is still reachable.

pub mod json;
pub mod shell;

use crate::document::{CollectionInfo, DocumentConnection, DocumentPage, Guarded};
use crate::engine::{Endpoint, Secrets, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use async_trait::async_trait;
use bson::{Bson, Document, doc};
use futures_util::TryStreamExt;
use mongodb::options::{ClientOptions, Credential};
use mongodb::{Client, Collection};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;

const SYSTEM_DATABASES: &[&str] = &["admin", "config", "local"];

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn DocumentConnection>> {
    crate::engine::use_ring();
    let (endpoint, url) = match &config.url {
        Some(url) => (None, url.clone()),
        None => {
            let host = config.host.trim();
            let host = if host.is_empty() { "localhost" } else { host };
            let port = config.port.and_then(|p| u16::try_from(p).ok());
            let endpoint = open_tunnel(config, host, port, secrets, paths).await?;
            let url = uri_for(config, &endpoint);
            (Some(endpoint), url)
        }
    };
    let inner = open(config, &url, secrets.password.as_deref()).await?;
    let tunnel = endpoint.and_then(|e| e.tunnel);
    Ok(Arc::new(Guarded::new(
        Box::new(inner),
        config.read_only,
        tunnel,
    )))
}

/// `mongodb://host:port/?options` from the profile's fields and properties.
pub fn uri_for(config: &ConnectionConfig, endpoint: &Endpoint) -> String {
    let mut options: Vec<(String, String)> = config
        .properties
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    if !config.properties.contains_key("replicaSet") && !config.host.contains(',') {
        options.push(("directConnection".into(), "true".into()));
    }
    let query: Vec<String> = options
        .iter()
        .map(|(k, v)| format!("{k}={}", form_encode(v)))
        .collect();
    let hosts = if config.host.contains(',') && config.ssh.is_none() {
        config
            .host
            .split(',')
            .map(str::trim)
            .collect::<Vec<_>>()
            .join(",")
    } else {
        format!("{}:{}", endpoint.host, endpoint.port)
    };
    let query = if query.is_empty() {
        String::new()
    } else {
        format!("?{}", query.join("&"))
    };
    format!("mongodb://{hosts}/{query}")
}

/// `URLEncoder.encode(…, UTF_8)`: form encoding, spaces as `+`.
fn form_encode(text: &str) -> String {
    let mut out = String::new();
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            b => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

async fn open(
    config: &ConnectionConfig,
    url: &str,
    password: Option<&str>,
) -> Result<MongoConnection> {
    let mut options = ClientOptions::parse(url).await.map_err(connect_error)?;
    options.server_selection_timeout = Some(Duration::from_secs(10));
    options.connect_timeout = Some(Duration::from_secs(10));
    // The password lives in the keychain, never in the profile's string: put it back into the credential.
    let from_url = options.credential.clone();
    let user = from_url
        .as_ref()
        .and_then(|c| c.username.clone())
        .or_else(|| Some(config.user.trim().to_owned()).filter(|u| !u.is_empty()));
    if let (Some(user), Some(password)) = (user, password.filter(|p| !p.is_empty()))
        && from_url
            .as_ref()
            .and_then(|c| c.mechanism.as_ref())
            .is_none()
    {
        let source = from_url
            .as_ref()
            .and_then(|c| c.source.clone())
            .or_else(|| config.properties.get("authSource").cloned())
            .unwrap_or_else(|| "admin".into());
        options.credential = Some(
            Credential::builder()
                .username(user)
                .password(password.to_owned())
                .source(source)
                .build(),
        );
    }
    let default_database = options
        .default_database
        .clone()
        .or_else(|| Some(config.database.trim().to_owned()).filter(|d| !d.is_empty()));
    let client = Client::with_options(options).map_err(connect_error)?;
    Ok(MongoConnection {
        config: config.clone(),
        client,
        default_database,
    })
}

fn connect_error(e: mongodb::error::Error) -> DriverError {
    DriverError::Connect(e.to_string())
}

fn error(e: mongodb::error::Error) -> DriverError {
    let message = match e.kind.as_ref() {
        mongodb::error::ErrorKind::Command(c) => format!("{} ({})", c.message, c.code_name),
        _ => e.to_string(),
    };
    DriverError::Query(QueryError::new(message))
}

fn invalid(message: impl Into<String>) -> DriverError {
    DriverError::Query(QueryError::new(message))
}

fn parse_document(text: &str) -> Result<Document> {
    json::document(text).map_err(|e| invalid(e.0))
}

fn parse_value(text: &str) -> Result<Bson> {
    json::value(text).map_err(|e| invalid(e.0))
}

pub struct MongoConnection {
    config: ConnectionConfig,
    client: Client,
    default_database: Option<String>,
}

impl MongoConnection {
    fn collection(&self, database: &str, collection: &str) -> Collection<Document> {
        self.client.database(database).collection(collection)
    }
}

/// Applies `$set` and `$unset` to the document with `key`, only while each field of `expected` still holds its value
/// (None: missing). Matches by `$eq`, so a value that is itself a document of `$` keys isn't read as operators, and
/// arrays by size too, as `$eq` alone also matches an array holding the value as an element.
async fn update_fields(
    coll: &Collection<Document>,
    key: Bson,
    set: Vec<(String, Bson)>,
    unset: BTreeSet<String>,
    expected: Vec<(String, Option<Bson>)>,
) -> Result<bool> {
    for field in set.iter().map(|(f, _)| f).chain(unset.iter()) {
        if field.is_empty() || field.contains('.') || field.starts_with('$') {
            return Err(invalid(format!(
                "Field “{field}” can't be edited here: names with dots or a leading $ need the console."
            )));
        }
        if field == "_id" {
            return Err(invalid(
                "A document's _id can't change; insert a new document instead.",
            ));
        }
    }
    let mut filter = doc! {"_id": key};
    for (field, value) in expected {
        if field == "_id" {
            continue;
        }
        let condition = match value {
            None => doc! {"$exists": false},
            Some(Bson::Null) => doc! {"$type": "null"},
            Some(Bson::Array(items)) => {
                let size = items.len() as i32;
                doc! {"$eq": Bson::Array(items), "$size": size}
            }
            Some(v) => doc! {"$eq": v},
        };
        filter.insert(field, condition);
    }
    let mut update = Document::new();
    if !set.is_empty() {
        update.insert("$set", set.into_iter().collect::<Document>());
    }
    if !unset.is_empty() {
        update.insert(
            "$unset",
            unset
                .into_iter()
                .map(|f| (f, Bson::String(String::new())))
                .collect::<Document>(),
        );
    }
    if update.is_empty() {
        return Ok(coll.count_documents(filter).await.map_err(error)? > 0);
    }
    Ok(coll
        .update_one(filter, update)
        .await
        .map_err(error)?
        .matched_count
        > 0)
}

#[async_trait]
impl DocumentConnection for MongoConnection {
    fn config(&self) -> &ConnectionConfig {
        &self.config
    }

    async fn server(&self) -> Result<String> {
        let admin = self.client.database("admin");
        let build = admin
            .run_command(doc! {"buildInfo": 1})
            .await
            .map_err(error)?;
        let version = build.get_str("version").unwrap_or_default().to_owned();
        // FerretDB answers buildInfo with the MongoDB version it emulates, plus its own.
        if let Ok(ferret) = build.get_document("ferretdb")
            && let Ok(v) = ferret.get_str("version")
        {
            return Ok(format!(
                "FerretDB {} (MongoDB {version} compatible)",
                v.strip_prefix('v').unwrap_or(v)
            ));
        }
        let hello = admin.run_command(doc! {"hello": 1}).await.map_err(error)?;
        let topology = if hello.get_str("msg").ok() == Some("isdbgrid") {
            " (sharded)".to_owned()
        } else if let Ok(set) = hello.get_str("setName") {
            format!(" (replica set {set})")
        } else {
            String::new()
        };
        Ok(format!("MongoDB {version}{topology}"))
    }

    async fn databases(&self) -> Result<Vec<String>> {
        // A user limited to one database may not list them; show the one the profile names.
        let mut names = match self.client.list_database_names().await {
            Ok(n) => n,
            Err(e) => match &self.default_database {
                Some(d) => vec![d.clone()],
                None => return Err(error(e)),
            },
        };
        names.sort_by(|a, b| {
            (SYSTEM_DATABASES.contains(&a.as_str()), a)
                .cmp(&(SYSTEM_DATABASES.contains(&b.as_str()), b))
        });
        Ok(names)
    }

    async fn collections(&self, database: &str) -> Result<Vec<CollectionInfo>> {
        let db = self.client.database(database);
        let specs: Vec<Document> = db
            .run_cursor_command(doc! {"listCollections": 1})
            .await
            .map_err(error)?
            .try_collect()
            .await
            .map_err(error)?;
        let mut out = Vec::new();
        for spec in specs {
            let name = spec.get_str("name").unwrap_or_default().to_owned();
            let kind = spec.get_str("type").unwrap_or("collection").to_owned();
            let documents = if kind == "collection" {
                db.collection::<Document>(&name)
                    .estimated_document_count()
                    .await
                    .ok()
            } else {
                None
            };
            out.push(CollectionInfo {
                name,
                kind,
                documents,
            });
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(out)
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
        // Ties in the sort would let skip() hand out a document on two pages and none on another: break them by _id.
        let mut order = parse_document(sort)?;
        if !order.is_empty() && !order.contains_key("_id") && !order.contains_key("$natural") {
            order.insert("_id", 1);
        }
        let projection = parse_document(projection)?;
        // Empty options are left out: FerretDB refuses an empty sort.
        let coll = self.collection(database, collection);
        let mut find = coll
            .find(parse_document(filter)?)
            .skip(skip)
            .limit(limit as i64 + 1);
        if !order.is_empty() {
            find = find.sort(order);
        }
        if !projection.is_empty() {
            find = find.projection(projection);
        }
        let found: Vec<Document> = find
            .await
            .map_err(error)?
            .try_collect()
            .await
            .map_err(error)?;
        let has_more = found.len() > limit;
        Ok(DocumentPage {
            documents: found.iter().take(limit).map(json::to_json).collect(),
            has_more,
        })
    }

    async fn count(&self, database: &str, collection: &str, filter: &str) -> Result<u64> {
        self.collection(database, collection)
            .count_documents(parse_document(filter)?)
            .await
            .map_err(error)
    }

    async fn insert(&self, database: &str, collection: &str, document: &str) -> Result<String> {
        let parsed = parse_document(document)?;
        let id = self
            .collection(database, collection)
            .insert_one(parsed)
            .await
            .map_err(error)?
            .inserted_id;
        Ok(json::value_json(&id))
    }

    async fn replace(
        &self,
        database: &str,
        collection: &str,
        id: &str,
        document: &str,
        original: Option<&str>,
    ) -> Result<()> {
        let key = parse_value(id)?;
        let replacement = parse_document(document)?;
        if replacement.get("_id").is_some_and(|v| *v != key) {
            return Err(invalid(
                "A document's _id can't change; insert a new document instead.",
            ));
        }
        let coll = self.collection(database, collection);
        let Some(original) = original else {
            let result = coll
                .replace_one(doc! {"_id": key}, replacement)
                .await
                .map_err(error)?;
            if result.matched_count == 0 {
                return Err(invalid("The document no longer exists."));
            }
            return Ok(());
        };
        let before = parse_document(original)?;
        let set: Vec<(String, Bson)> = replacement
            .iter()
            .filter(|(f, v)| f.as_str() != "_id" && before.get(f.as_str()) != Some(v))
            .map(|(f, v)| (f.clone(), v.clone()))
            .collect();
        let unset: BTreeSet<String> = before
            .keys()
            .filter(|k| k.as_str() != "_id" && !replacement.contains_key(k.as_str()))
            .cloned()
            .collect();
        if set.is_empty() && unset.is_empty() {
            return Ok(());
        }
        let expected = set
            .iter()
            .map(|(f, _)| f.clone())
            .chain(unset.iter().cloned())
            .map(|f| {
                let v = before.get(&f).cloned();
                (f, v)
            })
            .collect();
        if update_fields(&coll, key, set, unset, expected).await? {
            Ok(())
        } else {
            Err(invalid(
                "The document was changed or deleted since it was loaded; nothing was saved. Reload it and try again.",
            ))
        }
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
        let set = set
            .iter()
            .map(|(f, v)| Ok((f.clone(), parse_value(v)?)))
            .collect::<Result<Vec<_>>>()?;
        let expected = expected
            .iter()
            .map(|(f, v)| Ok((f.clone(), v.as_deref().map(parse_value).transpose()?)))
            .collect::<Result<Vec<_>>>()?;
        update_fields(
            &self.collection(database, collection),
            parse_value(id)?,
            set,
            unset.clone(),
            expected,
        )
        .await
    }

    async fn sample(&self, database: &str, collection: &str, size: usize) -> Result<Vec<String>> {
        let coll = self.collection(database, collection);
        let sampled: std::result::Result<Vec<Document>, _> = match coll
            .aggregate([doc! {"$sample": {"size": size as i64}}])
            .await
        {
            Ok(cursor) => cursor.try_collect().await,
            Err(e) => Err(e),
        };
        let documents = match sampled {
            Ok(d) => d,
            Err(_) => coll
                .find(doc! {})
                .limit(size as i64)
                .await
                .map_err(error)?
                .try_collect()
                .await
                .map_err(error)?,
        };
        Ok(documents.iter().map(json::to_json).collect())
    }

    async fn delete(&self, database: &str, collection: &str, ids: &[String]) -> Result<u64> {
        let ids = ids
            .iter()
            .map(|i| parse_value(i))
            .collect::<Result<Vec<_>>>()?;
        Ok(self
            .collection(database, collection)
            .delete_many(doc! {"_id": {"$in": ids}})
            .await
            .map_err(error)?
            .deleted_count)
    }

    async fn indexes(&self, database: &str, collection: &str) -> Result<Vec<String>> {
        let found: Vec<Document> = self
            .client
            .database(database)
            .run_cursor_command(doc! {"listIndexes": collection})
            .await
            .map_err(error)?
            .try_collect()
            .await
            .map_err(error)?;
        Ok(found.iter().map(json::to_json).collect())
    }

    async fn command(&self, database: &str, line: &str) -> Result<String> {
        let db = self.client.database(database);
        Ok(match shell::run(&self.client, &db, line).await {
            Ok(out) => out,
            Err(e) => e.console(),
        })
    }
}
