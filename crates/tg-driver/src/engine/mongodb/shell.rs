//! The subset of the mongo shell a console needs, without JavaScript (`MongoClient.kt`'s `Shell`): `show
//! dbs|collections`, `db.runCommand(…)`, `db.<collection>.<method>(…)` with `.sort/.limit/.skip/.projection` chained
//! after `find`, and `db.getCollection('…')` for names that aren't identifiers. Arguments are shell-style Extended
//! JSON.

use super::json::{self, ParseError};
use bson::{Bson, Document, doc};
use futures_util::TryStreamExt;
use mongodb::options::IndexOptions;
use mongodb::{Client, Database, IndexModel};
use regex::Regex;
use std::sync::LazyLock;

/// Documents shown by `find` without a `.limit()`, like the shell's first page.
pub const DEFAULT_LIMIT: i64 = 50;

/// A console error: what follows "Error: ".
pub enum ShellError {
    Usage(String),
    Mongo(mongodb::error::Error),
}

impl From<ParseError> for ShellError {
    fn from(e: ParseError) -> Self {
        Self::Usage(e.0)
    }
}

impl From<mongodb::error::Error> for ShellError {
    fn from(e: mongodb::error::Error) -> Self {
        Self::Mongo(e)
    }
}

impl ShellError {
    /// `Error: <message> (<code name>)` for a server's refusal, `Error: <message>` otherwise.
    pub fn console(&self) -> String {
        match self {
            Self::Usage(m) => format!("Error: {m}"),
            Self::Mongo(e) => match e.kind.as_ref() {
                mongodb::error::ErrorKind::Command(c) => {
                    format!("Error: {} ({})", c.message, c.code_name)
                }
                _ => format!("Error: {e}"),
            },
        }
    }
}

type Result<T> = std::result::Result<T, ShellError>;

fn usage<T>(message: impl Into<String>) -> Result<T> {
    Err(ShellError::Usage(message.into()))
}

struct Call {
    name: String,
    arguments: Vec<String>,
}

static SHOW: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^show\s+(\w+)$").unwrap());
static IDENT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z_$][\w$]*").unwrap());

pub async fn run(client: &Client, db: &Database, line: &str) -> Result<String> {
    let text = line.trim();
    let text = text.strip_suffix(';').unwrap_or(text).trim();
    if let Some(m) = SHOW.captures(text) {
        return match m[1].to_lowercase().as_str() {
            "dbs" | "databases" => Ok(client.list_database_names().await?.join("\n")),
            "collections" | "tables" => {
                let mut names = db.list_collection_names().await?;
                names.sort();
                Ok(names.join("\n"))
            }
            _ => usage(format!("Unknown: show {}", &m[1])),
        };
    }
    if !(text.starts_with("db.") || text.starts_with("db ")) {
        return usage("Commands start with db. or show; this console runs no JavaScript.");
    }
    let mut rest = text[2..].trim_start();
    rest = rest.strip_prefix('.').unwrap_or(rest).trim_start();
    let collection: Option<String>;
    if rest.starts_with("getCollection") {
        let (call, after) = parse_call(rest)?;
        let [name] = call.arguments.as_slice() else {
            return usage("getCollection takes a name");
        };
        collection = Some(string(name)?);
        rest = after.trim_start();
        rest = rest.strip_prefix('.').unwrap_or(rest).trim_start();
    } else {
        let Some(name) = IDENT.find(rest).map(|m| m.as_str().to_owned()) else {
            return usage("Expected a collection or method after db.");
        };
        let after = rest[name.len()..].trim_start();
        if after.starts_with('(') {
            collection = None;
        } else {
            collection = Some(name);
            rest = after.strip_prefix('.').unwrap_or(after).trim_start();
        }
    }
    let mut calls = Vec::new();
    while !rest.trim().is_empty() {
        let (call, after) = parse_call(rest)?;
        calls.push(call);
        rest = after.trim_start();
        rest = rest.strip_prefix('.').unwrap_or(rest).trim_start();
    }
    if calls.is_empty() {
        return usage("Expected a method call");
    }
    match collection {
        None => {
            if calls.len() != 1 {
                return usage("Expected a single method call");
            }
            database(client, db, &calls[0]).await
        }
        Some(name) => self::collection(db, &name, &calls).await,
    }
}

async fn database(client: &Client, db: &Database, call: &Call) -> Result<String> {
    let args = &call.arguments;
    let single = || -> Result<Document> {
        match args.as_slice() {
            [a] => Ok(json::document(a)?),
            _ => usage(format!("db.{}() takes one argument", call.name)),
        }
    };
    Ok(match call.name.as_str() {
        "runCommand" => json::to_pretty_json(&db.run_command(single()?).await?),
        "adminCommand" => {
            json::to_pretty_json(&client.database("admin").run_command(single()?).await?)
        }
        "getCollectionNames" => {
            let mut names = db.list_collection_names().await?;
            names.sort();
            names.join("\n")
        }
        "stats" => json::to_pretty_json(&db.run_command(doc! {"dbStats": 1}).await?),
        "createCollection" => {
            let Some(name) = args.first() else {
                return usage("createCollection takes a name");
            };
            db.create_collection(string(name)?).await?;
            "{ ok: 1 }".into()
        }
        "dropDatabase" => json::to_pretty_json(&db.run_command(doc! {"dropDatabase": 1}).await?),
        other => {
            return usage(format!(
                "db.{other}() isn't supported here; db.runCommand({{...}}) runs any command."
            ));
        }
    })
}

fn joined(documents: &[Document]) -> String {
    if documents.is_empty() {
        "(no documents)".into()
    } else {
        documents
            .iter()
            .map(json::to_pretty_json)
            .collect::<Vec<_>>()
            .join("\n")
    }
}

fn documents(text: &str, what: &str) -> Result<Vec<Document>> {
    match json::value(text)? {
        Bson::Array(items) => items
            .into_iter()
            .map(|i| match i {
                Bson::Document(d) => Ok(d),
                _ => usage(format!("{what} takes an array of documents")),
            })
            .collect(),
        _ => usage(format!("{what} takes an array")),
    }
}

async fn collection(db: &Database, name: &str, calls: &[Call]) -> Result<String> {
    let coll = db.collection::<Document>(name);
    let first = &calls[0];
    let args = &first.arguments;
    let arg = |i: usize| -> Result<Document> {
        Ok(match args.get(i) {
            Some(a) => json::document(a)?,
            None => Document::new(),
        })
    };
    let counts = |matched: u64, modified: u64| {
        format!("{{ matchedCount: {matched}, modifiedCount: {modified} }}")
    };
    Ok(match first.name.as_str() {
        "find" => {
            let mut find = coll.find(arg(0)?);
            if let Some(p) = args.get(1) {
                find = find.projection(json::document(p)?);
            }
            let mut limit = DEFAULT_LIMIT;
            let mut limited = false;
            for modifier in &calls[1..] {
                let [a] = modifier.arguments.as_slice() else {
                    return usage(format!(".{}() takes one argument", modifier.name));
                };
                match modifier.name.as_str() {
                    "sort" => find = find.sort(json::document(a)?),
                    "projection" => find = find.projection(json::document(a)?),
                    "skip" => find = find.skip(number(a)?.max(0) as u64),
                    "limit" => {
                        limit = number(a)?;
                        limited = true;
                    }
                    other => return usage(format!(".{other}() isn't supported after find()")),
                }
            }
            let found: Vec<Document> = find
                .limit(if limited { limit } else { limit + 1 })
                .await?
                .try_collect()
                .await?;
            let shown = &found[..found.len().min(limit.unsigned_abs() as usize)];
            let mut out = joined(shown);
            if !limited && found.len() as i64 > limit {
                out += &format!("\n… more documents; add .limit(n) or .skip({limit})");
            }
            out
        }
        "findOne" => {
            let mut find = coll.find_one(arg(0)?);
            if let Some(p) = args.get(1) {
                find = find.projection(json::document(p)?);
            }
            find.await?
                .map(|d| json::to_pretty_json(&d))
                .unwrap_or_else(|| "null".into())
        }
        "countDocuments" | "count" => coll.count_documents(arg(0)?).await?.to_string(),
        "estimatedDocumentCount" => coll.estimated_document_count().await?.to_string(),
        "distinct" => {
            let Some(field) = args.first() else {
                return usage("distinct takes a field name");
            };
            let values = coll.distinct(string(field)?, arg(1)?).await?;
            json::pretty_value(&Bson::Array(values))
        }
        "aggregate" => {
            let [pipeline] = args.as_slice() else {
                return usage("aggregate takes an array of stages");
            };
            let pipeline = documents(pipeline, "aggregate")?;
            let found: Vec<Document> = coll.aggregate(pipeline).await?.try_collect().await?;
            joined(&found)
        }
        "insertOne" => {
            let id = coll.insert_one(arg(0)?).await?.inserted_id;
            format!(
                "{{ acknowledged: true, insertedId: {} }}",
                json::value_json(&id)
            )
        }
        "insertMany" => {
            let [items] = args.as_slice() else {
                return usage("insertMany takes an array");
            };
            let inserted = coll.insert_many(documents(items, "insertMany")?).await?;
            format!(
                "{{ acknowledged: true, insertedCount: {} }}",
                inserted.inserted_ids.len()
            )
        }
        "updateOne" => {
            let r = coll.update_one(arg(0)?, arg(1)?).await?;
            counts(r.matched_count, r.modified_count)
        }
        "updateMany" => {
            let r = coll.update_many(arg(0)?, arg(1)?).await?;
            counts(r.matched_count, r.modified_count)
        }
        "replaceOne" => {
            let r = coll.replace_one(arg(0)?, arg(1)?).await?;
            counts(r.matched_count, r.modified_count)
        }
        "deleteOne" => format!(
            "{{ deletedCount: {} }}",
            coll.delete_one(arg(0)?).await?.deleted_count
        ),
        "deleteMany" => format!(
            "{{ deletedCount: {} }}",
            coll.delete_many(arg(0)?).await?.deleted_count
        ),
        "getIndexes" => {
            let found: Vec<Document> = db
                .run_cursor_command(doc! {"listIndexes": name})
                .await?
                .try_collect()
                .await?;
            found
                .iter()
                .map(json::to_pretty_json)
                .collect::<Vec<_>>()
                .join("\n")
        }
        "createIndex" => {
            let mut options = IndexOptions::default();
            if args.get(1).is_some() {
                let o = arg(1)?;
                if let Ok(n) = o.get_str("name") {
                    options.name = Some(n.to_owned());
                }
                if let Ok(u) = o.get_bool("unique") {
                    options.unique = Some(u);
                }
            }
            let model = IndexModel::builder().keys(arg(0)?).options(options).build();
            coll.create_index(model).await?.index_name
        }
        "dropIndex" => {
            let [index] = args.as_slice() else {
                return usage("dropIndex takes a name");
            };
            coll.drop_index(string(index)?).await?;
            "{ ok: 1 }".into()
        }
        "drop" => {
            coll.drop().await?;
            "true".into()
        }
        "stats" => json::to_pretty_json(&db.run_command(doc! {"collStats": name}).await?),
        other => return usage(format!("db.{name}.{other}() isn't supported here.")),
    })
}

fn string(text: &str) -> Result<String> {
    match json::value(text)? {
        Bson::String(s) => Ok(s),
        _ => usage(format!("Expected a string, got {text}")),
    }
}

fn number(text: &str) -> Result<i64> {
    match json::value(text)? {
        Bson::Int32(i) => Ok(i64::from(i)),
        Bson::Int64(i) => Ok(i),
        Bson::Double(f) => Ok(f as i64),
        _ => usage(format!("Expected a number, got {text}")),
    }
}

/// `name(arg, arg)rest` → the call and what follows it. Commas and parentheses inside strings and brackets don't
/// count.
fn parse_call(text: &str) -> Result<(Call, &str)> {
    let Some(name) = IDENT.find(text).map(|m| m.as_str().to_owned()) else {
        let near: String = text.chars().take(30).collect();
        return usage(format!("Expected a method at: {near}"));
    };
    let mut iter = text.char_indices().skip(name.chars().count()).peekable();
    while iter.peek().is_some_and(|(_, c)| c.is_whitespace()) {
        iter.next();
    }
    if iter.next().map(|(_, c)| c) != Some('(') {
        return usage(format!("Expected ( after {name}"));
    }
    let mut arguments = Vec::new();
    let mut current = String::new();
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    while let Some((i, c)) = iter.next() {
        if let Some(q) = quote {
            current.push(c);
            if c == '\\' {
                if let Some((_, n)) = iter.next() {
                    current.push(n);
                }
            } else if c == q {
                quote = None;
            }
            continue;
        }
        match c {
            '\'' | '"' => {
                quote = Some(c);
                current.push(c);
            }
            '{' | '[' | '(' => {
                depth += 1;
                current.push(c);
            }
            '}' | ']' => {
                depth -= 1;
                current.push(c);
            }
            ')' if depth == 0 => {
                if !current.trim().is_empty() {
                    arguments.push(current.trim().to_owned());
                }
                return Ok((Call { name, arguments }, &text[i + 1..]));
            }
            ')' => {
                depth -= 1;
                current.push(c);
            }
            ',' if depth == 0 => {
                arguments.push(current.trim().to_owned());
                current.clear();
            }
            c => current.push(c),
        }
    }
    usage(format!("Unbalanced parentheses in {name}(…)"))
}
