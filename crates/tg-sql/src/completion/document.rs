//! Completion for a document browser's filter, sort and projection (`DocumentCompleter.kt`), written in the shell's
//! relaxed Extended JSON (`{status: 'A', total: {$gt: 10}}`). It reads the text up to the caret as nested objects and
//! arrays to tell what the caret is in: the keys of the filter (fields, `$and`…), of a field's condition (`$gt`,
//! `$in`…), of `$elemMatch` (the array's element fields), `$expr` or `$text`; or a value: a field's values,
//! `true`/`false` for `$exists`, BSON type names for `$type`, `1`/`-1` for a sort, `1`/`0` for a projection. The outer
//! braces may be left out.

use regex::Regex;
use std::sync::LazyLock;

use super::{CompletionItem, CompletionKind, CompletionResult};
use crate::kt::{self, char_at};

/// A field of a collection's documents, as completion offers it: `path` is dotted for fields of embedded documents
/// (`address.city`), `types` are the BSON types seen in it, and `values` some of its values as shell literals (`'A'`,
/// `30`, `ObjectId('…')`), most frequent first. For arrays the values are the elements', which is what a filter
/// matches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DocumentField {
    pub path: String,
    pub types: Vec<String>,
    pub values: Vec<String>,
}

/// Which `find()` argument is being completed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DocumentQuery {
    Filter,
    Sort,
    Projection,
}

pub fn complete(
    text: &str,
    caret: usize,
    query: DocumentQuery,
    fields: &[DocumentField],
) -> CompletionResult {
    let at = caret.min(text.len());
    let Some(scan) = Scanner::new(text, at, query).run() else {
        return CompletionResult::empty(at);
    };
    let frame = &scan.frame;
    let token = scan.token.as_ref();
    let start = token.map_or(at, |t| t.start);
    let end = token_end(text, at, token.and_then(|t| t.quote));
    let prefix = token.map_or("", |t| t.text.as_str());
    let typed = prefix.trim_start_matches(['\'', '"']);
    let items = if frame.is_object && frame.expecting_key {
        let has_colon = kt::trim_start(&text[end..]).starts_with(':');
        keys(&frame.role, typed, fields, has_colon)
    } else if frame.is_object {
        let Some(key) = &frame.key else {
            return CompletionResult::empty(at);
        };
        values(&frame.role, key, typed, fields)
    } else {
        elements(&frame.role, typed, fields)
    };
    CompletionResult {
        replace_start: start,
        replace_end: end,
        prefix: prefix.to_owned(),
        items,
        value: None,
        pending_tables: vec![],
    }
}

// Suggestions

fn field_item(name: &str, field: &DocumentField, has_colon: bool) -> CompletionItem {
    let detail = if field.types.is_empty() {
        None
    } else {
        Some(field.types.join(" | "))
    };
    CompletionItem::new(
        name,
        key_text(name, has_colon),
        CompletionKind::Column,
        detail,
    )
}

fn keys(
    role: &Role,
    typed: &str,
    fields: &[DocumentField],
    has_colon: bool,
) -> Vec<CompletionItem> {
    let operators = |list: &[Operator]| list.iter().map(|o| o.item(has_colon)).collect::<Vec<_>>();
    let field_items = |base: &str| {
        under(base, fields)
            .into_iter()
            .map(|(name, f)| field_item(&name, f, has_colon))
            .collect::<Vec<_>>()
    };
    let all = match role {
        Role::Query { base, elements } => {
            let mut all = field_items(base);
            all.extend(operators(&QUERY_OPERATORS));
            if *elements {
                all.extend(operators(&FIELD_OPERATORS));
            }
            all
        }
        Role::Operators(field) => {
            let mut all = operators(&FIELD_OPERATORS);
            all.extend(field_items(field));
            all
        }
        Role::Sort | Role::Projection => field_items(""),
        Role::Text => operators(&TEXT_OPERATORS),
        Role::Expression => operators(&EXPRESSION_OPERATORS),
        _ => vec![],
    };
    rank(all, typed)
}

fn values(role: &Role, key: &str, typed: &str, fields: &[DocumentField]) -> Vec<CompletionItem> {
    match role {
        Role::Query { base, .. } => match key {
            "$and" | "$or" | "$nor" => vec![snippet(
                "[{}]",
                "conditions, all/any/none of which match",
                2,
            )],
            "$expr" => vec![snippet("{}", "an aggregation expression", 1)],
            "$text" => vec![snippet("{$search: ''}", "text index search", 10)],
            "$comment" => vec![],
            _ => {
                let mut items = field_values(&join(base, key), typed, fields);
                if typed.is_empty() {
                    items.extend(condition_snippets());
                }
                items
            }
        },
        Role::Operators(field) => match key {
            "$exists" => rank(booleans(), typed),
            "$type" => rank(bson_types(), typed),
            "$in" | "$nin" | "$all" => vec![snippet("[]", "a list of values", 1)],
            "$elemMatch" => vec![snippet("{}", "conditions on one element", 1)],
            "$not" => vec![snippet("{}", "a condition that must not match", 1)],
            "$options" => rank(regex_options(), typed),
            "$regex" | "$size" | "$mod" => vec![],
            _ if key.starts_with('$') => field_values(field, typed, fields),
            _ => field_values(&join(field, key), typed, fields),
        },
        Role::Sort => rank(
            vec![
                CompletionItem::new(
                    "1",
                    "1",
                    CompletionKind::Value,
                    Some("ascending".to_owned()),
                ),
                CompletionItem::new(
                    "-1",
                    "-1",
                    CompletionKind::Value,
                    Some("descending".to_owned()),
                ),
            ],
            typed,
        ),
        Role::Projection => rank(
            vec![
                CompletionItem::new("1", "1", CompletionKind::Value, Some("include".to_owned())),
                CompletionItem::new("0", "0", CompletionKind::Value, Some("exclude".to_owned())),
            ],
            typed,
        ),
        Role::Text if key == "$caseSensitive" || key == "$diacriticSensitive" => {
            rank(booleans(), typed)
        }
        _ => vec![],
    }
}

fn elements(role: &Role, typed: &str, fields: &[DocumentField]) -> Vec<CompletionItem> {
    match role {
        Role::Values(field) => field_values(field, typed, fields),
        _ => vec![],
    }
}

fn field_values(path: &str, typed: &str, fields: &[DocumentField]) -> Vec<CompletionItem> {
    let Some(field) = fields.iter().find(|f| f.path == path) else {
        return vec![];
    };
    let wanted = typed.to_lowercase();
    let mut matching: Vec<(&String, String)> = field
        .values
        .iter()
        .map(|v| (v, plain(v).to_lowercase()))
        .filter(|(_, plain)| wanted.is_empty() || plain.contains(&wanted))
        .collect();
    matching.sort_by_key(|(_, plain)| if plain.starts_with(&wanted) { 0 } else { 1 });
    matching
        .into_iter()
        .map(|(literal, _)| {
            CompletionItem::new(
                literal.clone(),
                literal.clone(),
                CompletionKind::Value,
                Some(format!("{path} value")),
            )
        })
        .collect()
}

/// Fields under `base` (all for ""), named relative to it: `city` for `address.city` under `address`.
fn under<'a>(base: &str, fields: &'a [DocumentField]) -> Vec<(String, &'a DocumentField)> {
    if base.is_empty() {
        return fields.iter().map(|f| (f.path.clone(), f)).collect();
    }
    let prefix = format!("{base}.");
    fields
        .iter()
        .filter_map(|f| {
            f.path
                .strip_prefix(&prefix)
                .map(|rest| (rest.to_owned(), f))
        })
        .collect()
}

/// Prefix matches first, shallow fields before nested ones; then fields merely containing what's typed.
fn rank(items: Vec<CompletionItem>, typed: &str) -> Vec<CompletionItem> {
    if typed.is_empty() {
        return items;
    }
    let wanted = typed.to_lowercase();
    let bare = wanted.strip_prefix('$').unwrap_or(&wanted).to_owned();
    let mut scored: Vec<(u8, usize, CompletionItem)> = items
        .into_iter()
        .filter_map(|item| {
            let label = item.label.to_lowercase();
            let score = if label.starts_with(&wanted) {
                0
            } else if label.strip_prefix('$').unwrap_or(&label).starts_with(&bare) {
                1
            } else if label.split('.').any(|p| p.starts_with(&bare)) {
                2
            } else if item.kind == CompletionKind::Column && label.contains(&bare) {
                3
            } else {
                return None;
            };
            let dots = item.label.matches('.').count();
            Some((score, dots, item))
        })
        .collect();
    scored.sort_by_key(|(score, dots, _)| (*score, *dots));
    scored.into_iter().map(|(_, _, item)| item).collect()
}

fn key_text(name: &str, has_colon: bool) -> String {
    format!("{}{}", quote_key(name), if has_colon { "" } else { ": " })
}

static IDENTIFIER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z_$][A-Za-z0-9_$]*$").unwrap());
static ESCAPE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\\(.)").unwrap());

/// Names that aren't identifiers, dotted paths included, need quotes as keys.
pub fn quote_key(name: &str) -> String {
    if IDENTIFIER.is_match(name) {
        name.to_owned()
    } else {
        format!("'{}'", name.replace('\\', "\\\\").replace('\'', "\\'"))
    }
}

/// A literal's text without quotes and wrappers, to match what's typed: `ObjectId('65f…')` → `65f…`.
fn plain(literal: &str) -> &str {
    let after = literal.split_once('(').map_or(literal, |(_, a)| a);
    let inner = after.rsplit_once(')').map_or(after, |(b, _)| b);
    inner.trim_matches(['\'', '"'])
}

fn join(base: &str, key: &str) -> String {
    if base.is_empty() {
        key.to_owned()
    } else {
        format!("{base}.{key}")
    }
}

fn snippet(text: &str, detail: &str, caret: usize) -> CompletionItem {
    CompletionItem::new(text, text, CompletionKind::Keyword, Some(detail.to_owned()))
        .with_caret(caret)
}

struct Operator {
    name: &'static str,
    detail: &'static str,
    value: Option<&'static str>,
    caret: Option<usize>,
}

const fn op(
    name: &'static str,
    detail: &'static str,
    value: Option<&'static str>,
    caret: Option<usize>,
) -> Operator {
    Operator {
        name,
        detail,
        value,
        caret,
    }
}

impl Operator {
    /// `$in: []` with the caret inside the brackets, or `$gt: ` ready for the value.
    fn item(&self, has_colon: bool) -> CompletionItem {
        let detail = Some(self.detail.to_owned());
        match self.value {
            Some(value) if !has_colon => {
                let text = format!("{}: {value}", self.name);
                let caret = self.name.len() + 2 + self.caret.unwrap_or(value.len());
                CompletionItem::new(self.name, text, CompletionKind::Operator, detail)
                    .with_caret(caret)
            }
            _ => {
                let text = format!("{}{}", self.name, if has_colon { "" } else { ": " });
                CompletionItem::new(self.name, text, CompletionKind::Operator, detail)
            }
        }
    }
}

const QUERY_OPERATORS: [Operator; 7] = [
    op("$and", "all of the conditions", Some("[{}]"), Some(2)),
    op("$or", "any of the conditions", Some("[{}]"), Some(2)),
    op("$nor", "none of the conditions", Some("[{}]"), Some(2)),
    op("$expr", "aggregation expression", Some("{}"), Some(1)),
    op(
        "$text",
        "text index search",
        Some("{$search: ''}"),
        Some(10),
    ),
    op("$jsonSchema", "matches a JSON schema", Some("{}"), Some(1)),
    op("$comment", "comment for the profiler", None, None),
];

const FIELD_OPERATORS: [Operator; 17] = [
    op("$eq", "equal to", None, None),
    op("$ne", "not equal to", None, None),
    op("$gt", "greater than", None, None),
    op("$gte", "greater than or equal to", None, None),
    op("$lt", "less than", None, None),
    op("$lte", "less than or equal to", None, None),
    op("$in", "any of the values", Some("[]"), Some(1)),
    op("$nin", "none of the values", Some("[]"), Some(1)),
    op("$exists", "has the field", Some("true"), None),
    op("$type", "BSON type is", None, None),
    op(
        "$regex",
        "matches a regular expression",
        Some("''"),
        Some(1),
    ),
    op("$options", "regex flags: i m x s", None, None),
    op("$not", "doesn't match the condition", Some("{}"), Some(1)),
    op("$all", "array has all the values", Some("[]"), Some(1)),
    op(
        "$elemMatch",
        "an array element matches all conditions",
        Some("{}"),
        Some(1),
    ),
    op("$size", "array has this many elements", None, None),
    op("$mod", "divisor and remainder", Some("[]"), Some(1)),
];

const TEXT_OPERATORS: [Operator; 4] = [
    op("$search", "words to search for", Some("''"), Some(1)),
    op("$language", "stemming language", Some("''"), Some(1)),
    op("$caseSensitive", "match case", None, None),
    op("$diacriticSensitive", "match diacritics", None, None),
];

const fn ex(name: &'static str, detail: &'static str) -> Operator {
    op(name, detail, None, Some(1))
}

const fn ex_array(name: &'static str, detail: &'static str) -> Operator {
    op(name, detail, Some("[]"), Some(1))
}

const EXPRESSION_OPERATORS: [Operator; 34] = [
    ex_array("$eq", "equal"),
    ex_array("$ne", "not equal"),
    ex_array("$gt", "greater than"),
    ex_array("$gte", "greater or equal"),
    ex_array("$lt", "less than"),
    ex_array("$lte", "less or equal"),
    ex_array("$and", "all true"),
    ex_array("$or", "any true"),
    ex_array("$not", "negation"),
    ex_array("$in", "value in array"),
    ex_array("$cmp", "compare"),
    ex_array("$add", "sum"),
    ex_array("$subtract", "difference"),
    ex_array("$multiply", "product"),
    ex_array("$divide", "quotient"),
    ex_array("$mod", "remainder"),
    ex("$abs", "absolute value"),
    ex("$size", "array length"),
    ex_array("$ifNull", "first non-null"),
    ex("$cond", "if-then-else"),
    ex_array("$concat", "join strings"),
    ex("$toLower", "lower case"),
    ex("$toUpper", "upper case"),
    ex("$strLenCP", "string length"),
    ex("$regexMatch", "matches a regex"),
    ex("$toString", "convert to string"),
    ex("$toInt", "convert to int"),
    ex("$toDouble", "convert to double"),
    ex("$toDate", "convert to date"),
    ex("$type", "BSON type"),
    ex("$year", "year of a date"),
    ex("$month", "month of a date"),
    ex("$dayOfMonth", "day of a date"),
    ex("$dateToString", "format a date"),
];

fn booleans() -> Vec<CompletionItem> {
    ["true", "false"]
        .iter()
        .map(|b| CompletionItem::new(*b, *b, CompletionKind::Value, None))
        .collect()
}

fn regex_options() -> Vec<CompletionItem> {
    [
        ("'i'", "case-insensitive"),
        ("'m'", "multi-line anchors"),
        ("'x'", "ignore whitespace"),
        ("'s'", "dot matches newlines"),
    ]
    .iter()
    .map(|(v, d)| CompletionItem::new(*v, *v, CompletionKind::Value, Some((*d).to_owned())))
    .collect()
}

fn bson_types() -> Vec<CompletionItem> {
    [
        "string",
        "number",
        "int",
        "long",
        "double",
        "decimal",
        "bool",
        "date",
        "objectId",
        "object",
        "array",
        "null",
        "binData",
        "regex",
        "timestamp",
    ]
    .iter()
    .map(|t| {
        let text = format!("'{t}'");
        CompletionItem::new(
            text.clone(),
            text,
            CompletionKind::Value,
            Some("BSON type".to_owned()),
        )
    })
    .collect()
}

fn condition_snippets() -> Vec<CompletionItem> {
    vec![
        snippet("{$gt: }", "a comparison", 6),
        snippet("{$in: []}", "any of these values", 6),
        snippet("{$exists: true}", "has the field", 15),
        snippet("{$regex: '', $options: 'i'}", "matches a pattern", 10),
    ]
}

// Reading the text

/// What the keys or elements of an object or array are.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Role {
    /// A filter's keys: fields under `base`, `$and`…; with `elements`, `$elemMatch`'s, which also takes operators.
    Query {
        base: String,
        elements: bool,
    },
    /// `{field: {|}}`: a condition's operators, or the fields of an embedded document.
    Operators(String),
    /// `$in: [|]` and other lists of a field's values.
    Values(String),
    /// `$and: [|]`: each element is a filter.
    Clauses(String),
    Sort,
    /// A projection's keys are fields; their values include or exclude them.
    Projection,
    Text,
    Expression,
    /// Anything else, e.g. a document value: nothing to suggest.
    Literal,
}

/// What the caret is in: `role` says what its keys or elements are; `key` is the key whose value is being typed.
#[derive(Clone, Debug)]
struct Frame {
    is_object: bool,
    role: Role,
    key: Option<String>,
    expecting_key: bool,
    /// The last complete token, a key once a colon follows it.
    last: Option<String>,
}

impl Frame {
    fn new(is_object: bool, role: Role) -> Self {
        Self {
            is_object,
            role,
            key: None,
            expecting_key: is_object,
            last: None,
        }
    }
}

struct Token {
    start: usize,
    text: String,
    quote: Option<char>,
}

struct Scan {
    frame: Frame,
    token: Option<Token>,
}

struct Scanner<'a> {
    text: &'a str,
    caret: usize,
    query: DocumentQuery,
    stack: Vec<Frame>,
    parens: i32,
}

fn is_word_char(c: char) -> bool {
    kt::is_letter_or_digit(c) || matches!(c, '_' | '$' | '.' | '-' | '+')
}

impl<'a> Scanner<'a> {
    fn new(text: &'a str, caret: usize, query: DocumentQuery) -> Self {
        Self {
            text,
            caret,
            query,
            stack: Vec::new(),
            parens: 0,
        }
    }

    fn run(mut self) -> Option<Scan> {
        let text = self.text;
        let caret = self.caret;
        let root_role = match self.query {
            DocumentQuery::Sort => Role::Sort,
            DocumentQuery::Projection => Role::Projection,
            DocumentQuery::Filter => Role::Query {
                base: String::new(),
                elements: false,
            },
        };
        // Without outer braces, the text is the root document's content.
        if !kt::trim_start(&text[..caret]).starts_with('{') {
            self.stack.push(Frame::new(true, root_role.clone()));
        }
        let mut i = 0;
        let mut token: Option<Token> = None;
        while i < caret {
            let c = char_at(text, i).unwrap();
            token = None;
            if kt::is_whitespace(c) {
                i += c.len_utf8();
            } else if c == '\'' || c == '"' {
                let close = self.closing_quote(i, c);
                match close {
                    Some(close) if close < caret => {
                        let inner = &text[i + 1..close];
                        self.complete(ESCAPE.replace_all(inner, "$1").into_owned());
                        i = close + 1;
                    }
                    _ => {
                        // The caret is inside this string.
                        if self.parens > 0 {
                            return None;
                        }
                        let frame = self.stack.last()?.clone();
                        return Some(Scan {
                            frame,
                            token: Some(Token {
                                start: i,
                                text: text[i..caret].to_owned(),
                                quote: Some(c),
                            }),
                        });
                    }
                }
            } else if c == '/' && self.stack.last().is_some_and(|f| !f.expecting_key) {
                // A regular expression literal.
                let mut j = i + 1;
                while let Some(ch) = char_at(text, j).filter(|&ch| ch != '/') {
                    j += if ch == '\\' {
                        1 + char_at(text, j + 1).map_or(1, char::len_utf8)
                    } else {
                        ch.len_utf8()
                    };
                }
                if j >= caret {
                    return None;
                }
                self.complete(text[i..=j].to_owned());
                i = j + 1;
            } else if c == '{' || c == '[' {
                let is_object = c == '{';
                let role = match self.stack.last() {
                    None => {
                        if is_object {
                            root_role.clone()
                        } else {
                            Role::Literal
                        }
                    }
                    Some(_) if self.parens > 0 => Role::Literal,
                    Some(parent) => child(parent, is_object),
                };
                self.stack.push(Frame::new(is_object, role));
                i += 1;
            } else if c == '}' || c == ']' {
                self.stack.pop();
                // The parent now holds a complete value (or, unbalanced, nothing more to complete).
                if let Some(parent) = self.stack.last_mut() {
                    parent.last = Some(c.to_string());
                }
                i += 1;
            } else if c == '(' {
                self.parens += 1;
                i += 1;
            } else if c == ')' {
                self.parens = (self.parens - 1).max(0);
                i += 1;
            } else if c == ':' {
                let parens = self.parens;
                if let Some(frame) = self.stack.last_mut().filter(|f| f.is_object && parens == 0) {
                    frame.key = frame.last.take();
                    frame.expecting_key = false;
                }
                i += 1;
            } else if c == ',' {
                let parens = self.parens;
                if let Some(frame) = self.stack.last_mut().filter(|f| f.is_object && parens == 0) {
                    frame.key = None;
                    frame.last = None;
                    frame.expecting_key = true;
                }
                i += 1;
            } else if is_word_char(c) {
                let mut j = i;
                while let Some(ch) = char_at(text, j).filter(|&ch| is_word_char(ch)) {
                    j += ch.len_utf8();
                }
                if j >= caret {
                    token = Some(Token {
                        start: i,
                        text: text[i..caret].to_owned(),
                        quote: None,
                    });
                } else {
                    self.complete(text[i..j].to_owned());
                }
                i = j;
            } else {
                i += c.len_utf8();
            }
        }
        if self.parens > 0 {
            return None;
        }
        let frame = self.stack.last()?.clone();
        // A word right before the caret is what's being typed; after a complete value, nothing is expected.
        if token.is_none() && frame.is_object && frame.last.is_some() {
            return None;
        }
        Some(Scan { frame, token })
    }

    fn complete(&mut self, word: String) {
        let parens = self.parens;
        if let Some(frame) = self.stack.last_mut()
            && parens == 0
        {
            frame.last = Some(word);
        }
    }

    fn closing_quote(&self, open: usize, quote: char) -> Option<usize> {
        let mut j = open + 1;
        while let Some(ch) = char_at(self.text, j) {
            if ch == '\\' {
                j += 1 + char_at(self.text, j + 1).map_or(1, char::len_utf8);
            } else if ch == quote {
                return Some(j);
            } else {
                j += ch.len_utf8();
            }
        }
        None
    }
}

fn child(parent: &Frame, is_object: bool) -> Role {
    if !parent.is_object {
        return match &parent.role {
            Role::Clauses(base) if is_object => Role::Query {
                base: base.clone(),
                elements: false,
            },
            Role::Expression => Role::Expression,
            _ => Role::Literal,
        };
    }
    let Some(key) = parent.key.as_deref() else {
        return Role::Literal;
    };
    match &parent.role {
        Role::Query { base, .. } => match key {
            "$and" | "$or" | "$nor" => {
                if is_object {
                    Role::Literal
                } else {
                    Role::Clauses(base.clone())
                }
            }
            "$expr" => Role::Expression,
            "$text" => {
                if is_object {
                    Role::Text
                } else {
                    Role::Literal
                }
            }
            _ if key.starts_with('$') => Role::Literal,
            _ if is_object => Role::Operators(join(base, key)),
            _ => Role::Values(join(base, key)),
        },
        Role::Operators(field) => match key {
            "$in" | "$nin" | "$all" => {
                if is_object {
                    Role::Literal
                } else {
                    Role::Values(field.clone())
                }
            }
            "$not" => {
                if is_object {
                    Role::Operators(field.clone())
                } else {
                    Role::Literal
                }
            }
            "$elemMatch" => {
                if is_object {
                    Role::Query {
                        base: field.clone(),
                        elements: true,
                    }
                } else {
                    Role::Literal
                }
            }
            _ if key.starts_with('$') => Role::Literal,
            _ if is_object => Role::Operators(join(field, key)),
            _ => Role::Values(join(field, key)),
        },
        Role::Expression => Role::Expression,
        _ => Role::Literal,
    }
}

/// Where a completed item ends: the rest of the word at the caret, and the closing quote of a string.
fn token_end(text: &str, caret: usize, quote: Option<char>) -> usize {
    let mut end = caret;
    while let Some(c) = char_at(text, end).filter(|&c| is_word_char(c)) {
        end += c.len_utf8();
    }
    if let Some(q) = quote
        && char_at(text, end) == Some(q)
    {
        end += 1;
    }
    end
}
