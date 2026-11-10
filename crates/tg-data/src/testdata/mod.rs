//! Fake rows for a table (`testdata/*.kt`): a generator per column, picked from its name and type, drawing from a
//! seeded stream so the same plan gives the same rows.

mod random;
pub mod words;

use random::{SplittableRandom, java_hash};
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;
use tg_sql::filter::ValueKind;
use tg_sql::model::{ColumnInfo, jdbc_types as types};
use words as w;

/// What kind of value a column is filled with when generating test data.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GeneratorKind {
    Sequence,
    Integer,
    Decimal,
    Price,
    Boolean,
    Uuid,
    FirstName,
    LastName,
    FullName,
    Username,
    Email,
    Phone,
    Company,
    JobTitle,
    Street,
    City,
    Region,
    PostalCode,
    Country,
    CountryCode,
    Url,
    ImageUrl,
    Domain,
    IpAddress,
    Word,
    Title,
    Sentence,
    Paragraph,
    Slug,
    Code,
    Hash,
    FileName,
    ProductName,
    Color,
    HexColor,
    CurrencyCode,
    LanguageCode,
    Status,
    Date,
    PastDate,
    FutureDate,
    BirthDate,
    Timestamp,
    PastTimestamp,
    FutureTimestamp,
    Time,
    Json,
    ForeignKey,
    Constant,
    Null,
    Skip,
}

use GeneratorKind as K;

impl GeneratorKind {
    /// What the column's dropdown shows.
    pub fn label(self) -> &'static str {
        match self {
            K::Sequence => "Sequence (1, 2, 3…)",
            K::Integer => "Random integer",
            K::Decimal => "Random decimal",
            K::Price => "Price",
            K::Boolean => "Boolean",
            K::Uuid => "UUID",
            K::FirstName => "First name",
            K::LastName => "Last name",
            K::FullName => "Full name",
            K::Username => "Username",
            K::Email => "Email address",
            K::Phone => "Phone number",
            K::Company => "Company name",
            K::JobTitle => "Job title",
            K::Street => "Street address",
            K::City => "City",
            K::Region => "State / province",
            K::PostalCode => "Postal code",
            K::Country => "Country",
            K::CountryCode => "Country code (VN, US…)",
            K::Url => "URL",
            K::ImageUrl => "Image URL",
            K::Domain => "Domain name",
            K::IpAddress => "IPv4 address",
            K::Word => "Word",
            K::Title => "Title (a few words)",
            K::Sentence => "Sentence",
            K::Paragraph => "Paragraph",
            K::Slug => "Slug (some-words-here)",
            K::Code => "Code (ABC-1234)",
            K::Hash => "Hex hash",
            K::FileName => "File name",
            K::ProductName => "Product name",
            K::Color => "Color name",
            K::HexColor => "Hex color (#1a2b3c)",
            K::CurrencyCode => "Currency code (USD, VND…)",
            K::LanguageCode => "Language code (en, vi…)",
            K::Status => "Status (active, pending…)",
            K::Date => "Date",
            K::PastDate => "Date in the past year",
            K::FutureDate => "Date in the next year",
            K::BirthDate => "Date of birth (18–80 years ago)",
            K::Timestamp => "Timestamp",
            K::PastTimestamp => "Timestamp in the past year",
            K::FutureTimestamp => "Timestamp in the next year",
            K::Time => "Time of day",
            K::Json => "JSON object",
            K::ForeignKey => "Existing value from referenced table",
            K::Constant => "Fixed value",
            K::Null => "Always NULL",
            K::Skip => "Leave to the database default",
        }
    }

    pub const TEXT_KINDS: &[GeneratorKind] = &[
        K::FirstName,
        K::LastName,
        K::FullName,
        K::Username,
        K::Email,
        K::Phone,
        K::Company,
        K::JobTitle,
        K::Street,
        K::City,
        K::Region,
        K::PostalCode,
        K::Country,
        K::CountryCode,
        K::Url,
        K::ImageUrl,
        K::Domain,
        K::IpAddress,
        K::Word,
        K::Title,
        K::Sentence,
        K::Paragraph,
        K::Slug,
        K::Code,
        K::Hash,
        K::FileName,
        K::ProductName,
        K::Color,
        K::HexColor,
        K::CurrencyCode,
        K::LanguageCode,
        K::Status,
    ];
    pub const NUMERIC_KINDS: &[GeneratorKind] = &[K::Sequence, K::Integer, K::Decimal, K::Price];
    pub const TEMPORAL_KINDS: &[GeneratorKind] = &[
        K::Date,
        K::PastDate,
        K::FutureDate,
        K::BirthDate,
        K::Timestamp,
        K::PastTimestamp,
        K::FutureTimestamp,
        K::Time,
    ];

    /// Produces words rather than numbers, times or identifiers.
    pub fn is_text(self) -> bool {
        Self::TEXT_KINDS.contains(&self)
    }

    pub fn is_temporal(self) -> bool {
        Self::TEMPORAL_KINDS.contains(&self)
    }

    pub fn is_numeric(self) -> bool {
        Self::NUMERIC_KINDS.contains(&self)
    }
}

/// How to fill one column. `null_ratio` (0..1) applies only to nullable columns. `unique` retries and derives values
/// to avoid duplicates. `constant` is the value for [`GeneratorKind::Constant`] (None inserts NULL). `min` and `max`
/// override the range of numeric generators, and are clamped to what the column's type can hold.
#[derive(Clone, Debug, PartialEq)]
pub struct ColumnPlan {
    pub column: ColumnInfo,
    pub kind: GeneratorKind,
    pub null_ratio: f64,
    pub unique: bool,
    pub constant: Option<String>,
    pub min: Option<f64>,
    pub max: Option<f64>,
}

impl ColumnPlan {
    pub fn new(column: ColumnInfo, kind: GeneratorKind) -> Self {
        Self {
            column,
            kind,
            null_ratio: 0.0,
            unique: false,
            constant: None,
            min: None,
            max: None,
        }
    }
}

/// A bound that keeps every range's size within an i64.
pub const BIG: i64 = 1 << 61;
pub const MAX_SCALE: i32 = 9;
const INT_NAMES: &[&str] = &["int", "integer", "int4"];
const EXACT_NAMES: &[&str] = &["numeric", "decimal", "dec", "number", "fixed"];

pub fn pow10(exponent: i32) -> i64 {
    let mut result = 1i64;
    for _ in 0..exponent.max(0) {
        if result >= BIG / 10 {
            return BIG;
        }
        result *= 10;
    }
    result
}

/// What a column's declared type allows: integer bounds, decimal places, text length.
pub struct ColumnType {
    pub kind: ValueKind,
    pub integer_bounds: Option<(i64, i64)>,
    pub scale: i32,
    pub max_scaled: Option<i64>,
    pub text_limit: Option<i32>,
}

impl ColumnType {
    pub fn new(column: &ColumnInfo) -> Self {
        static DECLARED: LazyLock<Regex> =
            LazyLock::new(|| Regex::new(r"\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)").unwrap());
        let kind = ValueKind::of(&column.type_name, column.jdbc_type, column.size);
        let lower = column.type_name.to_lowercase();
        let base = lower.split('(').next().unwrap_or("").trim();
        let base = base.strip_suffix(" unsigned").unwrap_or(base);
        let name = base
            .strip_suffix(" zerofill")
            .unwrap_or(base)
            .trim()
            .to_owned();
        let declared = DECLARED.captures(&column.type_name);
        let exact = EXACT_NAMES.contains(&name.as_str());
        let precision = declared
            .as_ref()
            .and_then(|d| d[1].parse::<i32>().ok())
            .or_else(|| column.size.filter(|s| exact && (1..=1000).contains(s)));
        let declared_scale = column
            .scale
            .or_else(|| {
                declared
                    .as_ref()
                    .and_then(|d| d.get(2))
                    .and_then(|s| s.as_str().parse().ok())
            })
            .or_else(|| (declared.is_some() && exact).then_some(0));
        let int = (i64::from(i32::MIN), i64::from(i32::MAX));
        let integer_bounds = if kind != ValueKind::Number {
            None
        } else {
            match name.as_str() {
                "tinyint" | "int1" => Some((-128, 127)),
                n if n == "smallint"
                    || n == "int2"
                    || n.starts_with("smallserial")
                    || n == "serial2" =>
                {
                    Some((-32768, 32767))
                }
                "mediumint" | "int3" => Some((-8388608, 8388607)),
                n if INT_NAMES.contains(&n) || n == "serial" || n == "serial4" => Some(int),
                n if n == "bigint"
                    || n == "int8"
                    || n.starts_with("bigserial")
                    || n == "serial8"
                    || n == "hugeint" =>
                {
                    Some((-BIG, BIG))
                }
                "year" => Some((1901, 2155)),
                _ if exact && declared_scale == Some(0) && precision.is_some() => {
                    let p = pow10(precision.unwrap_or(0));
                    Some((-(p - 1), p - 1))
                }
                n if exact
                    || n.starts_with("float")
                    || n.starts_with("double")
                    || n == "real"
                    || n == "money" =>
                {
                    None
                }
                _ => match column.jdbc_type {
                    types::TINYINT => Some((-128, 127)),
                    types::SMALLINT => Some((-32768, 32767)),
                    types::INTEGER => Some(int),
                    types::BIGINT => Some((-BIG, BIG)),
                    _ => None,
                },
            }
        };
        let scale = if integer_bounds.is_some() {
            0
        } else {
            declared_scale.unwrap_or(2).min(MAX_SCALE)
        };
        let max_scaled = if kind == ValueKind::Number
            && integer_bounds.is_none()
            && exact
            && precision.is_some()
        {
            // With the scale assumed rather than known, precision still caps the digits as a whole.
            let p = pow10(precision.unwrap_or(0) - declared_scale.unwrap_or(2) + scale);
            if p >= BIG { None } else { Some(p - 1) }
        } else {
            None
        };
        let text_limit = column.size.filter(|s| kind == ValueKind::Text && *s > 0);
        Self {
            kind,
            integer_bounds,
            scale,
            max_scaled,
            text_limit,
        }
    }

    pub fn is_integer(&self) -> bool {
        self.integer_bounds.is_some()
    }
}

/// A column name split into lower-case words: `userEmail`, `user_email` and `user-email` are all `user`, `email`.
struct Name {
    words: Vec<String>,
    joined: String,
}

impl Name {
    fn new(raw: &str) -> Self {
        static A: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([a-z0-9])([A-Z])").unwrap());
        static B: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([A-Z]+)([A-Z][a-z])").unwrap());
        static C: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([a-zA-Z])([0-9])").unwrap());
        static SPLIT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^a-z0-9]+").unwrap());
        let text = A.replace_all(raw, "$1 $2");
        let text = B.replace_all(&text, "$1 $2");
        let text = C.replace_all(&text, "$1 $2").to_lowercase();
        let words: Vec<String> = SPLIT
            .split(&text)
            .filter(|w| !w.is_empty() && w.chars().any(|c| c.is_alphabetic()))
            .map(str::to_owned)
            .collect();
        let joined = words.concat();
        Self { words, joined }
    }

    fn has(&self, candidates: &[&str]) -> bool {
        candidates
            .iter()
            .any(|c| self.words.iter().any(|w| w == c) || *c == self.joined)
    }

    /// `is_active`, `has_paid`, `enabled`, `deleted`: a yes/no column.
    fn is_flag(&self) -> bool {
        const PREFIXES: &[&str] = &[
            "is", "has", "can", "should", "allow", "allows", "enable", "use", "was", "must", "show",
        ];
        const WORDS: &[&str] = &[
            "flag",
            "enabled",
            "disabled",
            "active",
            "deleted",
            "verified",
            "published",
            "archived",
            "visible",
            "hidden",
            "locked",
            "confirmed",
            "approved",
            "admin",
            "blocked",
            "banned",
            "featured",
            "public",
            "private",
            "default",
            "subscribed",
            "read",
            "done",
            "completed",
        ];
        let Some(first) = self.words.first() else {
            return false;
        };
        (PREFIXES.contains(&first.as_str()) && self.words.len() > 1)
            || (self.words.len() <= 2
                && self
                    .words
                    .last()
                    .is_some_and(|l| WORDS.contains(&l.as_str())))
    }
}

/// Picks a generator for a column from its name and type.
pub struct GeneratorSuggestion;

impl GeneratorSuggestion {
    /// The best generator for `column` from its name (snake, camel and kebab case alike) and type. `foreign_key` is
    /// true when the column is part of a foreign key, `unique_indexed` when a unique index or constraint covers it
    /// alone. Auto-increment and identity keys are left to the database. Never offers text for a numeric column or
    /// vice versa.
    pub fn suggest(column: &ColumnInfo, foreign_key: bool, unique_indexed: bool) -> ColumnPlan {
        let t = ColumnType::new(column);
        let unique = unique_indexed || column.primary_key;
        let null_ratio = if column.nullable && !unique { 0.1 } else { 0.0 };
        let plan = |kind: GeneratorKind, min: Option<f64>, max: Option<f64>| ColumnPlan {
            column: column.clone(),
            kind,
            null_ratio: if kind == K::Skip { 0.0 } else { null_ratio },
            unique,
            constant: None,
            min,
            max,
        };
        if column.auto_increment || is_generated_default(column.default_value.as_deref()) {
            return plan(K::Skip, None, None);
        }
        if column.primary_key && column.default_value.is_some() && t.kind == ValueKind::Uuid {
            return plan(K::Skip, None, None);
        }
        if foreign_key && t.kind != ValueKind::Binary {
            return plan(K::ForeignKey, None, None);
        }
        let name = Name::new(&column.name);
        match t.kind {
            ValueKind::Number => numeric(&name, &t, column, unique, &plan),
            ValueKind::Boolean => plan(K::Boolean, None, None),
            ValueKind::Date => plan(temporal(&name, true).unwrap_or(K::Date), None, None),
            ValueKind::DateTime => plan(temporal(&name, false).unwrap_or(K::Timestamp), None, None),
            ValueKind::Time => plan(K::Time, None, None),
            ValueKind::Uuid => plan(K::Uuid, None, None),
            ValueKind::Json => plan(K::Json, None, None),
            ValueKind::Text => plan(text(&name, &t, unique), None, None),
            ValueKind::Other => {
                let lower = column.type_name.to_lowercase();
                if lower == "inet" || lower == "cidr" || name.has(&["ip"]) {
                    plan(K::IpAddress, None, None)
                } else {
                    // Enums and other server types: no way to know their values, so leave them to the default or NULL.
                    plan(K::Skip, None, None)
                }
            }
            ValueKind::Binary => plan(K::Skip, None, None),
        }
    }

    /// Kinds that make sense for `column`'s type, most fitting first, for the per-column dropdown.
    pub fn choices(column: &ColumnInfo, foreign_key: bool) -> Vec<GeneratorKind> {
        let numbers = [K::Sequence, K::Integer, K::Decimal, K::Price];
        let dates = [K::Date, K::PastDate, K::FutureDate, K::BirthDate];
        let timestamps = [K::Timestamp, K::PastTimestamp, K::FutureTimestamp];
        let kind = ColumnType::new(column).kind;
        let mut out: Vec<GeneratorKind> = match kind {
            ValueKind::Number => numbers.iter().copied().chain([K::Boolean]).collect(),
            ValueKind::Boolean => vec![K::Boolean],
            ValueKind::Date => dates.to_vec(),
            ValueKind::DateTime => timestamps.iter().chain(&dates).copied().collect(),
            ValueKind::Time => vec![K::Time],
            ValueKind::Uuid => vec![K::Uuid],
            ValueKind::Json => vec![K::Json],
            ValueKind::Text | ValueKind::Other => GeneratorKind::TEXT_KINDS
                .iter()
                .copied()
                .chain([K::Uuid, K::Json])
                .chain(numbers)
                .chain([K::Boolean])
                .chain(timestamps)
                .chain(dates)
                .chain([K::Time])
                .collect(),
            ValueKind::Binary => Vec::new(),
        };
        if foreign_key {
            out.push(K::ForeignKey);
        }
        if kind != ValueKind::Binary {
            out.push(K::Constant);
        }
        if column.nullable {
            out.push(K::Null);
        }
        out.push(K::Skip);
        out
    }
}

fn is_generated_default(default: Option<&str>) -> bool {
    let Some(text) = default.map(str::to_lowercase) else {
        return false;
    };
    ["nextval(", "identity", "autoincrement", "generated"]
        .iter()
        .any(|t| text.contains(t))
}

fn numeric(
    name: &Name,
    t: &ColumnType,
    column: &ColumnInfo,
    unique: bool,
    plan: &dyn Fn(GeneratorKind, Option<f64>, Option<f64>) -> ColumnPlan,
) -> ColumnPlan {
    let whole = if t.is_integer() {
        K::Integer
    } else {
        K::Decimal
    };
    let r = |a: f64, b: f64| (Some(a), Some(b));
    let (kind, (min, max)) = if unique {
        (K::Sequence, (None, None))
    } else if name.is_flag() {
        (K::Boolean, (None, None))
    } else if name.has(&[
        "price", "cost", "amount", "total", "subtotal", "fee", "salary", "balance", "revenue",
        "tax", "discount", "wage", "payment", "budget", "income", "charge", "spend", "paid",
    ]) {
        (K::Price, (None, None))
    } else if name.has(&["latitude", "lat"]) {
        (K::Decimal, r(-90.0, 90.0))
    } else if name.has(&["longitude", "lng", "lon"]) {
        (K::Decimal, r(-180.0, 180.0))
    } else if name.has(&["age"]) {
        (K::Integer, r(18.0, 80.0))
    } else if name.has(&["quantity", "qty", "stock", "inventory", "units"]) {
        (K::Integer, r(1.0, 100.0))
    } else if name.has(&[
        "count",
        "views",
        "likes",
        "clicks",
        "visits",
        "downloads",
        "followers",
    ]) {
        (K::Integer, r(0.0, 10_000.0))
    } else if name.has(&["rating", "stars"]) {
        (K::Integer, r(1.0, 5.0))
    } else if name.has(&["score", "percent", "percentage", "pct", "progress"]) {
        (whole, r(0.0, 100.0))
    } else if name.has(&["year"]) && column.type_name.to_lowercase() != "year" {
        (K::Integer, r(1990.0, 2030.0))
    } else if name.has(&[
        "priority", "level", "rank", "position", "sort", "order", "ordinal", "seq", "idx", "index",
    ]) {
        (K::Integer, r(1.0, 100.0))
    } else if name.has(&["status", "type", "kind", "state", "category", "role"]) {
        (K::Integer, r(0.0, 5.0))
    } else if name.has(&[
        "weight", "height", "width", "length", "depth", "distance", "duration", "size",
    ]) {
        (whole, r(1.0, 1000.0))
    } else {
        (whole, (None, None))
    };
    plan(kind, min, max)
}

/// A time column's kind by what its name says happened: `created_at` in the past, `expires_at` ahead.
fn temporal(name: &Name, date_only: bool) -> Option<GeneratorKind> {
    let past = if date_only {
        K::PastDate
    } else {
        K::PastTimestamp
    };
    let future = if date_only {
        K::FutureDate
    } else {
        K::FutureTimestamp
    };
    if name.has(&["birth", "dob", "birthday", "birthdate", "born"]) {
        Some(K::BirthDate)
    } else if name.has(&[
        "due",
        "expires",
        "expiry",
        "expiration",
        "deadline",
        "scheduled",
        "until",
        "renews",
        "renewal",
        "next",
    ]) {
        Some(future)
    } else if name.has(&[
        "created",
        "updated",
        "modified",
        "deleted",
        "changed",
        "last",
        "logged",
        "login",
        "published",
        "joined",
        "registered",
        "sent",
        "received",
        "paid",
        "seen",
        "visited",
        "completed",
        "closed",
        "opened",
        "signed",
        "verified",
        "confirmed",
    ]) {
        Some(past)
    } else {
        None
    }
}

fn text(name: &Name, t: &ColumnType, unique: bool) -> GeneratorKind {
    let size = t.text_limit.unwrap_or(i32::MAX);
    let n = |c: &[&str]| name.has(c);
    let kind = if n(&["uuid", "guid"]) {
        K::Uuid
    } else if n(&["email", "mail"]) || name.joined.contains("email") {
        K::Email
    } else if n(&[
        "phone",
        "mobile",
        "tel",
        "telephone",
        "fax",
        "cell",
        "msisdn",
    ]) || name.joined.contains("phone")
    {
        K::Phone
    } else if n(&[
        "avatar",
        "image",
        "img",
        "photo",
        "picture",
        "thumbnail",
        "logo",
        "icon",
        "banner",
        "cover",
    ]) {
        K::ImageUrl
    } else if n(&["url", "website", "homepage", "link", "href", "uri", "site"]) {
        K::Url
    } else if n(&["ip", "ipv4"]) || name.joined.contains("ipaddress") {
        K::IpAddress
    } else if n(&["domain", "hostname", "host"]) {
        K::Domain
    } else if n(&["fname", "forename", "firstname", "givenname"])
        || (n(&["first", "given", "middle"]) && n(&["name"]))
    {
        K::FirstName
    } else if n(&["lname", "surname", "lastname", "familyname"])
        || (n(&["last", "family"]) && n(&["name"]))
    {
        K::LastName
    } else if n(&[
        "username",
        "login",
        "handle",
        "nick",
        "nickname",
        "screenname",
        "userid",
    ]) || (n(&["user", "screen"]) && n(&["name"]))
    {
        K::Username
    } else if n(&[
        "company",
        "organization",
        "organisation",
        "org",
        "employer",
        "vendor",
        "supplier",
        "brand",
        "manufacturer",
        "publisher",
        "merchant",
        "agency",
    ]) {
        K::Company
    } else if (n(&["product", "item", "sku"]) && n(&["name"])) || n(&["product"]) {
        K::ProductName
    } else if n(&["job", "occupation", "designation", "position", "profession"]) {
        K::JobTitle
    } else if n(&["street", "address", "addr", "line"]) {
        K::Street
    } else if n(&["city", "town", "municipality"]) {
        K::City
    } else if n(&["province", "region", "county", "prefecture"]) {
        K::Region
    } else if n(&["country"]) && (n(&["code", "iso", "iso2", "cc"]) || size <= 3) {
        K::CountryCode
    } else if n(&["country", "nationality"]) {
        K::Country
    } else if n(&["zip", "zipcode", "postal", "postcode", "postalcode"]) {
        K::PostalCode
    } else if n(&["currency", "ccy"]) {
        K::CurrencyCode
    } else if n(&["language", "lang", "locale"]) {
        K::LanguageCode
    } else if n(&["color", "colour"]) {
        if n(&["hex"]) || (6..=7).contains(&size) {
            K::HexColor
        } else {
            K::Color
        }
    } else if n(&["status", "state"]) {
        K::Status
    } else if n(&["slug", "permalink"]) {
        K::Slug
    } else if n(&[
        "password",
        "passwd",
        "pwd",
        "hash",
        "digest",
        "checksum",
        "token",
        "secret",
        "salt",
        "signature",
        "fingerprint",
    ]) {
        K::Hash
    } else if n(&["file", "filename", "attachment", "path"]) {
        K::FileName
    } else if n(&[
        "json",
        "metadata",
        "meta",
        "settings",
        "preferences",
        "config",
        "attributes",
        "properties",
        "payload",
        "extra",
    ]) {
        K::Json
    } else if n(&[
        "description",
        "desc",
        "bio",
        "about",
        "summary",
        "body",
        "content",
        "notes",
        "note",
        "comment",
        "comments",
        "message",
        "text",
        "details",
        "remarks",
        "review",
        "feedback",
        "biography",
    ]) {
        if size < 160 {
            K::Sentence
        } else {
            K::Paragraph
        }
    } else if n(&[
        "title", "subject", "headline", "caption", "label", "heading", "topic",
    ]) {
        K::Title
    } else if n(&[
        "sku",
        "code",
        "ref",
        "reference",
        "serial",
        "barcode",
        "invoice",
        "number",
        "no",
    ]) {
        K::Code
    } else if name.is_flag() {
        K::Boolean
    } else if n(&[
        "name",
        "fullname",
        "displayname",
        "author",
        "owner",
        "customer",
        "contact",
        "person",
        "employee",
        "member",
        "recipient",
        "sender",
        "manager",
        "client",
        "assignee",
        "reporter",
    ]) {
        K::FullName
    } else if let Some(k) = temporal(name, false) {
        // SQLite stores times as text more often than not.
        k
    } else if n(&["timestamp", "datetime", "at"]) {
        K::Timestamp
    } else if n(&["date", "day", "on"]) {
        K::Date
    } else if n(&["time"]) {
        K::Time
    } else if n(&["id", "key", "uid"]) {
        if size >= 36 { K::Uuid } else { K::Code }
    } else if n(&[
        "category", "type", "kind", "tag", "tags", "group", "role", "gender", "genre", "keyword",
    ]) || size <= 24
    {
        K::Word
    } else {
        K::Sentence
    };
    if unique && kind == K::Word && size >= 36 {
        K::Uuid
    } else {
        kind
    }
}

/// Lower-case ASCII form of a name for emails and user names: `Nguyễn` -> `nguyen`, `Müller` -> `muller`.
pub fn ascii(text: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    text.replace('đ', "d")
        .replace('Đ', "D")
        .nfd()
        .collect::<String>()
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
        .collect()
}

#[derive(Clone)]
struct Person {
    first: &'static str,
    middle: Option<&'static str>,
    last: &'static str,
    family_first: bool,
}

impl Person {
    fn draw(random: &mut SplittableRandom) -> Self {
        let total: i32 = w::NAME_POOLS.iter().map(|p| p.weight).sum();
        let mut pick = random.next_int_bound(total);
        let pool = w::NAME_POOLS
            .iter()
            .find(|p| {
                pick -= p.weight;
                pick < 0
            })
            .unwrap_or(&w::NAME_POOLS[0]);
        let middle = (!pool.middle.is_empty()).then(|| *random.pick(pool.middle));
        let first = *random.pick(pool.first);
        let last = *random.pick(pool.last);
        Self {
            first,
            middle,
            last,
            family_first: pool.family_first,
        }
    }

    fn full_name(&self) -> String {
        if self.family_first {
            [Some(self.last), self.middle, Some(self.first)]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(" ")
        } else {
            format!("{} {}", self.first, self.last)
        }
    }
}

struct Row {
    random: SplittableRandom,
    person: Option<Person>,
}

impl Row {
    fn person(&mut self) -> Person {
        if self.person.is_none() {
            self.person = Some(Person::draw(&mut self.random));
        }
        self.person.clone().unwrap()
    }
}

type Produce = Box<dyn Fn(&mut SplittableRandom, &mut Row) -> String>;
type Draw = Box<dyn Fn(&mut SplittableRandom) -> i64>;
type Format = Box<dyn Fn(i64) -> String>;

enum Body {
    Constant(Option<String>),
    Sequence {
        start: i64,
        last: i64,
    },
    Range {
        first: i64,
        last: i64,
        format: Format,
        draw: Draw,
        used: Option<HashSet<i64>>,
    },
    Text {
        separator: &'static str,
        produce: Produce,
        used: Option<HashSet<String>>,
        suffix: i64,
    },
    Uuid,
    Json,
    Missing,
}

struct Generator {
    plan: ColumnPlan,
    random: SplittableRandom,
    t: ColumnType,
    body: Body,
}

impl Generator {
    /// How many distinct values this can produce, or None when practically unlimited.
    fn space(&self) -> Option<i64> {
        match &self.body {
            Body::Constant(_) => Some(1),
            Body::Range { first, last, .. } => Some(last - first + 1),
            Body::Missing => Some(0),
            _ => None,
        }
    }

    fn next(&mut self, row: &mut Row, index: i64) -> Result<Option<String>, String> {
        if self.plan.column.nullable
            && self.plan.null_ratio > 0.0
            && self.random.next_double() < self.plan.null_ratio
        {
            return Ok(None);
        }
        self.value(row, index)
    }

    fn value(&mut self, row: &mut Row, index: i64) -> Result<Option<String>, String> {
        let name = self.plan.column.name.clone();
        let kind = self.plan.kind;
        let limit = self.t.text_limit;
        Ok(match &mut self.body {
            Body::Constant(c) => c.clone(),
            Body::Sequence { start, .. } => Some((*start + index).to_string()),
            Body::Missing => return Err(format!("{name}: no rows in the referenced table")),
            Body::Uuid => Some(fit(limit, uuid(&mut self.random))),
            Body::Json => Some(fit(limit, json(&mut self.random))),
            Body::Range {
                first,
                last,
                format,
                draw,
                used,
            } => {
                let value = match used {
                    None => draw(&mut self.random),
                    Some(used) => {
                        let mut value = draw(&mut self.random);
                        let mut found = used.insert(value);
                        for _ in 0..8 {
                            if found {
                                break;
                            }
                            value = draw(&mut self.random);
                            found = used.insert(value);
                        }
                        if !found {
                            // Random draws keep colliding as the range fills up; walk to the next free value instead.
                            let space = *last - *first + 1;
                            if used.len() as i64 >= space {
                                return Err(format!(
                                    "{name}: unique with only {space} possible values"
                                ));
                            }
                            loop {
                                value = if value >= *last { *first } else { value + 1 };
                                if used.insert(value) {
                                    break;
                                }
                            }
                        }
                        value
                    }
                };
                Some(fit(limit, format(value)))
            }
            Body::Text {
                separator,
                produce,
                used,
                suffix,
            } => {
                let fit_text =
                    |value: &str, extra: &str| fit_text(kind, limit, &name, value, extra);
                let mut value = fit_text(&produce(&mut self.random, row), "")?;
                let Some(used) = used else {
                    return Ok(Some(value));
                };
                if used.insert(value.clone()) {
                    return Ok(Some(value));
                }
                for _ in 0..3 {
                    value = fit_text(&produce(&mut self.random, row), "")?;
                    if used.insert(value.clone()) {
                        return Ok(Some(value));
                    }
                }
                loop {
                    let candidate = fit_text(&value, &format!("{separator}{suffix}"))?;
                    *suffix += 1;
                    if used.insert(candidate.clone()) {
                        return Ok(Some(candidate));
                    }
                }
            }
        })
    }
}

/// Cuts a value to the column's length.
fn fit(limit: Option<i32>, value: String) -> String {
    match limit {
        Some(limit) if value.chars().count() > limit as usize => {
            value.chars().take(limit as usize).collect()
        }
        _ => value,
    }
}

/// `value` with `extra` inserted where a suffix belongs, shortened from its head to fit the column.
fn fit_text(
    kind: GeneratorKind,
    limit: Option<i32>,
    name: &str,
    value: &str,
    extra: &str,
) -> Result<String, String> {
    let at = match kind {
        K::Email => value.rfind('@'),
        K::FileName | K::ImageUrl => value.rfind('.'),
        K::Domain => value.find('.'),
        _ => None,
    }
    .filter(|a| *a > 0);
    let (head, tail) = match at {
        Some(a) => (&value[..a], &value[a..]),
        None => (value, ""),
    };
    let Some(limit) = limit.map(|l| l as usize) else {
        return Ok(format!("{head}{extra}{tail}"));
    };
    let len = |s: &str| s.chars().count();
    if len(extra) >= limit {
        return Err(format!(
            "{name}: {limit} characters are too few for more unique values"
        ));
    }
    if len(head) + len(extra) + len(tail) <= limit {
        return Ok(format!("{head}{extra}{tail}"));
    }
    let room = limit as i64 - len(extra) as i64 - len(tail) as i64;
    // An email keeps its domain, a file name its extension; when even that doesn't fit, the value is just cut.
    Ok(if room >= 1 {
        let cut: String = head.chars().take(room as usize).collect();
        format!("{}{extra}{tail}", cut.trim_end())
    } else {
        let cut: String = value.chars().take(limit - len(extra)).collect();
        format!("{cut}{extra}")
    })
}

/// Generates fake rows for plans. Columns planned as [`GeneratorKind::Skip`] are left out of the columns and of each
/// row. `foreign_key_values` maps a column name to existing key values of the referenced table, as strings.
///
/// Output is the same for the same plans, seed and `now`: each column draws from its own random stream (keyed by its
/// name, so adding a column doesn't change the others), and relative dates count from `now`. Values are strings a
/// statement binds for the column's type: plain numbers, `true`/`false`, `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss`,
/// `HH:mm:ss`, canonical UUIDs and compact JSON; NULL is None.
pub struct TestDataGenerator {
    all_plans: Vec<ColumnPlan>,
    plans: Vec<ColumnPlan>,
    seed: i64,
    pools: HashMap<String, Vec<String>>,
    now: chrono::NaiveDateTime,
}

const PERSON_SALT: i64 = 0x5DEECE66D;

impl TestDataGenerator {
    pub fn new(
        plans: Vec<ColumnPlan>,
        seed: i64,
        foreign_key_values: HashMap<String, Vec<String>>,
        now: chrono::NaiveDateTime,
    ) -> Self {
        let pools = foreign_key_values
            .into_iter()
            .map(|(k, v)| {
                let mut seen = HashSet::new();
                (
                    k,
                    v.into_iter().filter(|x| seen.insert(x.clone())).collect(),
                )
            })
            .collect();
        Self {
            plans: plans
                .iter()
                .filter(|p| p.kind != K::Skip)
                .cloned()
                .collect(),
            all_plans: plans,
            seed,
            pools,
            now,
        }
    }

    pub fn columns(&self) -> Vec<ColumnInfo> {
        self.plans.iter().map(|p| p.column.clone()).collect()
    }

    /// `count` rows, generated as they're read; each call starts over and yields the same rows. An error when a
    /// unique column runs out of values, or a foreign key has nothing to reference: [`problems`](Self::problems)
    /// reports those up front.
    pub fn rows(
        &self,
        count: usize,
    ) -> impl Iterator<Item = Result<Vec<Option<String>>, String>> + '_ {
        let mut generators: Vec<Generator> = self.plans.iter().map(|p| self.generator(p)).collect();
        let mut row = Row {
            random: SplittableRandom::new(self.seed ^ PERSON_SALT),
            person: None,
        };
        (0..count).map(move |index| {
            row.person = None;
            generators
                .iter_mut()
                .map(|g| g.next(&mut row, index as i64))
                .collect()
        })
    }

    /// What would stop `count` rows from generating or inserting, e.g. "team_id: no rows in the referenced table".
    pub fn problems(&self, count: usize) -> Vec<String> {
        let mut problems = Vec::new();
        for plan in &self.all_plans {
            let column = &plan.column;
            let name = &column.name;
            let t = ColumnType::new(column);
            match plan.kind {
                K::Skip
                    if !column.nullable
                        && column.default_value.is_none()
                        && !column.auto_increment =>
                {
                    problems.push(format!(
                        "{name}: NOT NULL with no default, so it needs a generator"
                    ))
                }
                K::Null if !column.nullable => {
                    problems.push(format!("{name}: NOT NULL, so it can't always be NULL"))
                }
                K::Constant => {
                    if plan.constant.is_none() && !column.nullable {
                        problems.push(format!(
                            "{name}: NOT NULL, so the fixed value can't be NULL"
                        ));
                    }
                    if plan.unique && count > 1 {
                        problems.push(format!(
                            "{name}: unique, but a fixed value repeats on every row"
                        ));
                    }
                }
                K::ForeignKey
                    if self.pools.get(name).is_none_or(Vec::is_empty)
                        && (!column.nullable || plan.null_ratio < 1.0) =>
                {
                    problems.push(format!("{name}: no rows in the referenced table"))
                }
                _ => {}
            }
            if matches!(plan.kind, K::Skip | K::Null) {
                continue;
            }
            if !GeneratorSuggestion::choices(column, true).contains(&plan.kind) {
                problems.push(format!(
                    "{name}: {} values don't fit a {} column",
                    plan.kind.label(),
                    column.type_name
                ));
                continue;
            }
            if let (Some(min), Some(max)) = (plan.min, plan.max)
                && min > max
            {
                problems.push(format!("{name}: the minimum is above the maximum"));
            }
            let generator = self.generator(plan);
            if let Body::Sequence { start, last } = generator.body
                && start + count as i64 - 1 > last
            {
                problems.push(format!(
                    "{name}: {count} rows run past {last}, the most this {} column holds",
                    column.type_name
                ));
            }
            if !plan.unique || count <= 1 {
                continue;
            }
            match generator.space() {
                Some(space) if space < count as i64 => {
                    problems.push(format!("{name}: unique with only {space} possible values"))
                }
                _ => {
                    if matches!(generator.body, Body::Text { .. })
                        && let Some(limit) = t.text_limit
                        && pow10(limit) < count as i64
                    {
                        problems.push(format!(
                            "{name}: {limit} characters are too few for {count} unique values"
                        ));
                    }
                }
            }
        }
        problems
    }

    fn generator(&self, plan: &ColumnPlan) -> Generator {
        let t = ColumnType::new(&plan.column);
        let random = SplittableRandom::new(mix(self.seed, &plan.column.name));
        let body = self.body(plan, &t);
        Generator {
            plan: plan.clone(),
            random,
            t,
            body,
        }
    }

    fn body(&self, plan: &ColumnPlan, t: &ColumnType) -> Body {
        let range = |first: i64, last: i64, format: Format| Body::Range {
            first,
            last,
            format,
            draw: Box::new(move |r: &mut SplittableRandom| r.next_long_range(first, last + 1)),
            used: plan.unique.then(HashSet::new),
        };
        let words = |list: Vec<String>, draw: Option<Draw>| {
            let n = list.len() as i64;
            Body::Range {
                first: 0,
                last: n - 1,
                format: Box::new(move |i| list[i as usize].clone()),
                draw: draw.unwrap_or_else(|| {
                    Box::new(move |r: &mut SplittableRandom| i64::from(r.next_int_bound(n as i32)))
                }),
                used: plan.unique.then(HashSet::new),
            }
        };
        let text = |separator: &'static str, produce: Produce| Body::Text {
            separator,
            produce,
            used: plan.unique.then(HashSet::new),
            suffix: 2,
        };
        let s = |list: &[&str]| list.iter().map(|x| (*x).to_owned()).collect::<Vec<_>>();
        match plan.kind {
            K::Skip | K::Null => Body::Constant(None),
            K::Constant => Body::Constant(plan.constant.clone()),
            K::ForeignKey => match self.pools.get(&plan.column.name).filter(|p| !p.is_empty()) {
                Some(pool) => words(pool.clone(), None),
                None => Body::Missing,
            },
            K::Sequence => {
                let start = plan.min.map_or(1, |m| m as i64);
                let last = t
                    .integer_bounds
                    .map(|b| b.1)
                    .or_else(|| t.max_scaled.map(|m| m / pow10(t.scale)))
                    .or_else(|| t.text_limit.map(|l| pow10(l) - 1))
                    .unwrap_or(i64::MAX);
                Body::Sequence { start, last }
            }
            K::Integer => number(plan, t, 0, 0.0, 1000.0, false),
            K::Decimal => number(plan, t, t.scale, 0.0, 10_000.0, false),
            K::Price => number(
                plan,
                t,
                if t.is_integer() {
                    0
                } else {
                    t.scale.clamp(2, MAX_SCALE)
                },
                1.0,
                1000.0,
                true,
            ),
            K::Boolean => {
                let (yes, no) = if t.kind == ValueKind::Number {
                    ("1", "0")
                } else {
                    ("true", "false")
                };
                range(
                    0,
                    1,
                    Box::new(move |v| if v == 1 { yes } else { no }.to_owned()),
                )
            }
            K::Uuid => Body::Uuid,
            K::Json => Body::Json,
            K::FirstName => text("", Box::new(|_, row| row.person().first.to_owned())),
            K::LastName => text("", Box::new(|_, row| row.person().last.to_owned())),
            K::FullName => text(" ", Box::new(|_, row| row.person().full_name())),
            K::Username => text("", Box::new(|r, row| username(&row.person(), r))),
            K::Email => text(
                "",
                Box::new(|r, row| {
                    format!(
                        "{}@{}",
                        email_local(&row.person(), r),
                        r.pick(w::EMAIL_DOMAINS)
                    )
                }),
            ),
            K::Phone => text("", Box::new(|r, _| pattern(r.pick(w::PHONE_FORMATS), r))),
            K::Company => text(" ", Box::new(|r, _| company(r))),
            K::JobTitle => text(" ", Box::new(|r, _| (*r.pick(w::JOB_TITLES)).to_owned())),
            K::Street => text(" ", Box::new(|r, _| street(r))),
            K::City => text(" ", Box::new(|r, _| (*r.pick(w::CITIES)).to_owned())),
            K::PostalCode => text("", Box::new(|r, _| pattern(r.pick(w::POSTAL_FORMATS), r))),
            K::Url => text(
                "-",
                Box::new(|r, _| {
                    let d = domain(r);
                    let path = if r.next_boolean() {
                        format!("/{}", r.pick(w::NOUNS))
                    } else {
                        String::new()
                    };
                    format!("https://{d}{path}")
                }),
            ),
            K::ImageUrl => text(
                "-",
                Box::new(|r, _| {
                    let noun = r.pick(w::NOUNS);
                    format!("https://cdn.example.com/images/{noun}-{}.jpg", hex(r, 8))
                }),
            ),
            K::Domain => text("", Box::new(|r, _| domain(r))),
            K::Word => text(
                "",
                Box::new(|r, _| {
                    let list = if r.next_int_bound(3) == 0 {
                        w::ADJECTIVES
                    } else {
                        w::NOUNS
                    };
                    (*r.pick(list)).to_owned()
                }),
            ),
            K::Title => text(" ", Box::new(|r, _| title(r))),
            K::Sentence => text(" ", Box::new(|r, _| sentence(r))),
            K::Paragraph => text(
                " ",
                Box::new(|r, _| {
                    let n = 3 + r.next_int_bound(4);
                    (0..n).map(|_| sentence(r)).collect::<Vec<_>>().join(" ")
                }),
            ),
            K::Slug => text(
                "-",
                Box::new(|r, _| {
                    let n = 2 + r.next_int_bound(3);
                    (1..=n)
                        .map(|i| {
                            (*r.pick(if i == 1 { w::ADJECTIVES } else { w::NOUNS })).to_owned()
                        })
                        .collect::<Vec<_>>()
                        .join("-")
                }),
            ),
            K::Code => text("", Box::new(|r, _| pattern("AAA-####", r))),
            K::Hash => text("", Box::new(|r, _| hex(r, 64))),
            K::FileName => text(
                "_",
                Box::new(|r, _| {
                    let noun = r.pick(w::NOUNS);
                    let adjective = r.pick(w::ADJECTIVES);
                    format!("{noun}_{adjective}.{}", r.pick(w::FILE_EXTENSIONS))
                }),
            ),
            K::ProductName => text(" ", Box::new(|r, _| product(r))),
            K::IpAddress => range(
                0x0B00_0001,
                0xDFFF_FFFE,
                Box::new(|ip| {
                    format!(
                        "{}.{}.{}.{}",
                        ip >> 24,
                        ip >> 16 & 255,
                        ip >> 8 & 255,
                        ip & 255
                    )
                }),
            ),
            K::HexColor => range(0, 0xFF_FFFF, Box::new(|v| format!("#{v:06x}"))),
            K::Status => {
                let n = w::STATUSES.len() as i32;
                words(
                    s(w::STATUSES),
                    Some(Box::new(move |r: &mut SplittableRandom| {
                        if r.next_int_bound(100) < 55 {
                            0
                        } else {
                            i64::from(r.next_int_bound(n))
                        }
                    })),
                )
            }
            K::Color => words(s(w::COLORS), None),
            K::Region => words(s(w::REGIONS), None),
            K::Country => words(w::COUNTRIES.iter().map(|c| c.0.to_owned()).collect(), None),
            K::CountryCode => words(w::COUNTRIES.iter().map(|c| c.1.to_owned()).collect(), None),
            K::CurrencyCode => words(s(w::CURRENCIES), None),
            K::LanguageCode => words(s(w::LANGUAGES), None),
            K::Date | K::PastDate | K::FutureDate | K::BirthDate => {
                let today = self.now.date();
                let years = |n: i32| {
                    let months = chrono::Months::new(n.unsigned_abs() * 12);
                    if n >= 0 {
                        today.checked_add_months(months)
                    } else {
                        today.checked_sub_months(months)
                    }
                    .unwrap_or(today)
                };
                let (from, to) = match plan.kind {
                    K::PastDate => (years(-1), today),
                    K::FutureDate => (today, years(1)),
                    K::BirthDate => (years(-80), years(-18)),
                    _ => (years(-3), years(1)),
                };
                let with_time = t.kind == ValueKind::DateTime;
                let day = |d: chrono::NaiveDate| d.signed_duration_since(epoch()).num_days();
                range(
                    day(from),
                    day(to),
                    Box::new(move |d| {
                        let date = epoch() + chrono::Duration::days(d);
                        format!(
                            "{}{}",
                            date.format("%Y-%m-%d"),
                            if with_time { " 00:00:00" } else { "" }
                        )
                    }),
                )
            }
            K::Timestamp | K::PastTimestamp | K::FutureTimestamp => {
                let now = self.now;
                let years = |n: i32| {
                    if n > 0 {
                        now.checked_add_months(chrono::Months::new(n as u32 * 12))
                            .unwrap_or(now)
                    } else {
                        now.checked_sub_months(chrono::Months::new(n.unsigned_abs() * 12))
                            .unwrap_or(now)
                    }
                };
                let (from, to) = match plan.kind {
                    K::PastTimestamp => (years(-1), now),
                    K::FutureTimestamp => (now, years(1)),
                    _ => (years(-3), years(1)),
                };
                let date_only = t.kind == ValueKind::Date;
                range(
                    from.and_utc().timestamp(),
                    to.and_utc().timestamp(),
                    Box::new(move |second| timestamp(second, date_only)),
                )
            }
            K::Time => range(0, 86_399, Box::new(time)),
        }
    }
}

fn epoch() -> chrono::NaiveDate {
    chrono::NaiveDate::from_ymd_opt(1970, 1, 1).unwrap_or_default()
}

/// A number in whole units of 10^-`scale`, within the plan's range (or the default one) and what the column holds.
fn number(
    plan: &ColumnPlan,
    t: &ColumnType,
    scale: i32,
    default_min: f64,
    default_max: f64,
    price: bool,
) -> Body {
    let unit = pow10(scale);
    let bounds = if let Some((lo, hi)) = t.integer_bounds {
        (
            lo.saturating_mul(unit).max(-BIG),
            hi.saturating_mul(unit).min(BIG),
        )
    } else if let Some(max) = t.max_scaled {
        let m = max / pow10(t.scale - scale);
        (-m, m)
    } else {
        (-BIG, BIG)
    };
    // BigDecimal.valueOf(value).movePointRight(scale).setScale(0, mode), clamped: done on the decimal digits, as a
    // double times a power of ten can land just below a whole number.
    let scaled = |value: f64, ceiling: bool| -> i64 {
        let text = format!("{}", value.abs());
        let (int, frac) = text.split_once('.').unwrap_or((&text, ""));
        let digits = scale.max(0) as usize;
        let mut whole: String = int.to_owned();
        whole.extend(frac.chars().chain(std::iter::repeat('0')).take(digits));
        let rest_nonzero = frac.chars().skip(digits).any(|c| c != '0');
        let mut n: i128 = whole.parse().unwrap_or(i128::MAX / 2);
        if value < 0.0 {
            n = -n;
        }
        if rest_nonzero {
            // Ceiling rounds towards +infinity, floor towards -infinity.
            if ceiling && value > 0.0 {
                n += 1;
            } else if !ceiling && value < 0.0 {
                n -= 1;
            }
        }
        n.clamp(i128::from(bounds.0), i128::from(bounds.1)) as i64
    };
    let first = scaled(plan.min.unwrap_or(default_min), true);
    let last = scaled(plan.max.unwrap_or(default_max), false).max(first);
    let format: Format = Box::new(move |v| decimal(v, scale));
    let used = plan.unique.then(HashSet::new);
    if !price {
        return Body::Range {
            first,
            last,
            format,
            draw: Box::new(move |r: &mut SplittableRandom| r.next_long_range(first, last + 1)),
            used,
        };
    }
    let endings = [99i64, 0, 49, 95, 50];
    Body::Range {
        first,
        last,
        format,
        used,
        draw: Box::new(move |r: &mut SplittableRandom| {
            // Log-uniform, so cheap items outnumber expensive ones; cents end the way shop prices do.
            let low = ((first as f64 / unit as f64).max(0.0) + 1.0).ln();
            let high = ((last as f64 / unit as f64).max(0.0) + 1.0).ln();
            let whole = ((low + r.next_double() * (high - low)).exp() - 1.0) as i64;
            let cents = if r.next_int_bound(4) == 0 {
                r.next_long_bound(100)
            } else {
                endings[r.next_int_bound(endings.len() as i32) as usize]
            };
            let fraction = match scale {
                s if s >= 2 => cents * pow10(s - 2),
                1 => cents / 10,
                _ => 0,
            };
            (whole * unit + fraction).clamp(first, last)
        }),
    }
}

fn take(text: &str, n: usize) -> String {
    text.chars().take(n).collect()
}

fn username(p: &Person, r: &mut SplittableRandom) -> String {
    let (first, last) = (ascii(p.first), ascii(p.last));
    match r.next_int_bound(6) {
        0 => format!("{first}.{last}"),
        1 => format!("{first}{last}"),
        2 => format!("{}{last}", take(&first, 1)),
        3 => format!("{first}_{last}"),
        4 => format!("{first}{}", r.next_int_range(10, 10_000)),
        _ => format!("{first}{}{}", take(&last, 1), r.next_int_range(10, 100)),
    }
}

fn email_local(p: &Person, r: &mut SplittableRandom) -> String {
    let (first, last) = (ascii(p.first), ascii(p.last));
    match r.next_int_bound(7) {
        0 | 1 => format!("{first}.{last}"),
        2 => format!("{first}{last}"),
        3 => format!("{}.{last}", take(&first, 1)),
        4 => format!("{first}_{last}"),
        5 => format!("{last}.{first}"),
        _ => format!("{first}{}", r.next_int_range(10, 100)),
    }
}

fn company(r: &mut SplittableRandom) -> String {
    let root = *r.pick(w::COMPANY_ROOTS);
    let industry = if r.next_int_bound(5) == 0 {
        String::new()
    } else {
        format!(" {}", r.pick(w::COMPANY_INDUSTRIES))
    };
    format!("{root}{industry}{}", r.pick(w::COMPANY_SUFFIXES))
}

fn domain(r: &mut SplittableRandom) -> String {
    let root = ascii(r.pick(w::COMPANY_ROOTS));
    let industry = if r.next_boolean() {
        r.pick(w::COMPANY_INDUSTRIES).to_lowercase()
    } else {
        String::new()
    };
    format!("{root}{industry}.{}", r.pick(w::TOP_LEVEL_DOMAINS))
}

fn street(r: &mut SplittableRandom) -> String {
    if r.next_int_bound(10) < 7 {
        let number = r.next_int_range(1, 10_000);
        let name = r.pick(w::STREET_NAMES);
        format!("{number} {name} {}", r.pick(w::STREET_TYPES))
    } else {
        let number = r.next_int_range(1, 300);
        format!("{number} {}", r.pick(w::VIETNAMESE_STREETS))
    }
}

fn capitalize(word: &str) -> String {
    let mut c = word.chars();
    c.next()
        .map(|f| f.to_uppercase().chain(c).collect())
        .unwrap_or_default()
}

fn title(r: &mut SplittableRandom) -> String {
    let n = 2 + r.next_int_bound(4);
    (1..=n)
        .map(|i| capitalize(r.pick(if i % 2 == 1 { w::ADJECTIVES } else { w::NOUNS })))
        .collect::<Vec<_>>()
        .join(" ")
}

fn sentence(r: &mut SplittableRandom) -> String {
    let n = 6 + r.next_int_bound(9);
    let words: Vec<&str> = (0..n).map(|_| *r.pick(w::LOREM)).collect();
    capitalize(&format!("{}.", words.join(" ")))
}

fn product(r: &mut SplittableRandom) -> String {
    let material = if r.next_boolean() {
        format!(" {}", r.pick(w::PRODUCT_MATERIALS))
    } else {
        String::new()
    };
    let adjective = r.pick(w::PRODUCT_ADJECTIVES);
    format!("{adjective}{material} {}", r.pick(w::PRODUCTS))
}

fn json(r: &mut SplittableRandom) -> String {
    match r.next_int_bound(5) {
        0 => {
            let id = r.next_int_range(1, 100_000);
            let label = quote(r.pick(w::NOUNS));
            format!(
                r#"{{"id":{id},"label":{label},"active":{}}}"#,
                r.next_boolean()
            )
        }
        1 => {
            let p = Person::draw(r);
            let email = format!(
                "{}.{}@{}",
                ascii(p.first),
                ascii(p.last),
                r.pick(w::EMAIL_DOMAINS)
            );
            format!(
                r#"{{"name":{},"email":{},"age":{}}}"#,
                quote(&p.full_name()),
                quote(&email),
                r.next_int_range(18, 81)
            )
        }
        2 => {
            let n = 1 + r.next_int_bound(3);
            let tags: Vec<String> = (0..n).map(|_| quote(r.pick(w::ADJECTIVES))).collect();
            let sku = quote(&pattern("AAA-####", r));
            let price = decimal(r.next_long_range(100, 100_000), 2);
            format!(
                r#"{{"sku":{sku},"price":{price},"tags":[{}]}}"#,
                tags.join(",")
            )
        }
        3 => {
            let theme = quote(if r.next_boolean() { "light" } else { "dark" });
            let notifications = r.next_boolean();
            format!(
                r#"{{"theme":{theme},"notifications":{notifications},"language":{}}}"#,
                quote(r.pick(w::LANGUAGES))
            )
        }
        _ => {
            let city = quote(r.pick(w::CITIES));
            let lat = decimal(r.next_long_range(-90_000_000, 90_000_001), 6);
            format!(
                r#"{{"city":{city},"lat":{lat},"lng":{}}}"#,
                decimal(r.next_long_range(-180_000_000, 180_000_001), 6)
            )
        }
    }
}

fn mix(seed: i64, name: &str) -> i64 {
    // SplitMix64's finalizer: neighbouring seeds and similar names still give unrelated streams.
    let mut z = seed.wrapping_add(i64::from(java_hash(name)).wrapping_mul(-0x61c8864680b583eb));
    z = (z ^ ((z as u64) >> 30) as i64).wrapping_mul(-0x40a7b892e31b1a47);
    z = (z ^ ((z as u64) >> 27) as i64).wrapping_mul(-0x6b2fb644ecceee15);
    z ^ ((z as u64) >> 31) as i64
}

fn uuid(r: &mut SplittableRandom) -> String {
    // Version 4, variant 1, as UUID.randomUUID makes them, but from the seeded stream.
    let high = (r.next_long() & -0xf001) | 0x4000;
    let low = (r.next_long() & 0x3fff_ffff_ffff_ffff) | i64::MIN;
    let (h, l) = (high as u64, low as u64);
    format!(
        "{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        h >> 32,
        (h >> 16) & 0xffff,
        h & 0xffff,
        l >> 48,
        l & 0xffff_ffff_ffff
    )
}

fn hex(r: &mut SplittableRandom, length: usize) -> String {
    const DIGITS: &[u8] = b"0123456789abcdef";
    (0..length)
        .map(|_| DIGITS[r.next_int_bound(16) as usize] as char)
        .collect()
}

/// Fills `#` with digits and `A` with upper-case letters.
fn pattern(format: &str, r: &mut SplittableRandom) -> String {
    format
        .chars()
        .map(|c| match c {
            '#' => (b'0' + r.next_int_bound(10) as u8) as char,
            'A' => (b'A' + r.next_int_bound(26) as u8) as char,
            c => c,
        })
        .collect()
}

fn decimal(value: i64, scale: i32) -> String {
    if scale == 0 {
        return value.to_string();
    }
    let unit = pow10(scale);
    let magnitude = value.unsigned_abs();
    let fraction = format!(
        "{:0width$}",
        magnitude % unit as u64,
        width = scale as usize
    );
    format!(
        "{}{}.{fraction}",
        if value < 0 { "-" } else { "" },
        magnitude / unit as u64
    )
}

fn timestamp(epoch_second: i64, date_only: bool) -> String {
    let day = epoch_second.div_euclid(86_400);
    let date = (epoch() + chrono::Duration::days(day))
        .format("%Y-%m-%d")
        .to_string();
    if date_only {
        date
    } else {
        format!("{date} {}", time(epoch_second - day * 86_400))
    }
}

fn time(second_of_day: i64) -> String {
    format!(
        "{:02}:{:02}:{:02}",
        second_of_day / 3600,
        second_of_day / 60 % 60,
        second_of_day % 60
    )
}

fn quote(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' | '\\' => {
                out.push('\\');
                out.push(c);
            }
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
