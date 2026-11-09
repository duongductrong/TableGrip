//! Select lists for the table viewer (`Projection.kt`).

/// What a [`SelectFunction`] needs besides its column. The label reads before the field: "decimals 2".
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum FunctionArgument {
    Digits,
    Range,
    Fallback,
    Bucket,
}

impl FunctionArgument {
    pub fn label(self) -> &'static str {
        match self {
            Self::Digits => "decimals",
            Self::Range => "from, length",
            Self::Fallback => "if NULL",
            Self::Bucket => "to",
        }
    }

    pub fn placeholder(self) -> &'static str {
        match self {
            Self::Digits => "0",
            Self::Range => "1, 10",
            Self::Fallback => "value",
            Self::Bucket => "day",
        }
    }
}

/// A function applied to a column in the select list; its label is what the picker shows.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum SelectFunction {
    #[default]
    None,
    Upper,
    Lower,
    Trim,
    Length,
    Substring,
    Round,
    Abs,
    Floor,
    Ceil,
    Truncate,
    Coalesce,
    AsText,
}

impl SelectFunction {
    pub const ALL: &[SelectFunction] = &[
        Self::None,
        Self::Upper,
        Self::Lower,
        Self::Trim,
        Self::Length,
        Self::Substring,
        Self::Round,
        Self::Abs,
        Self::Floor,
        Self::Ceil,
        Self::Truncate,
        Self::Coalesce,
        Self::AsText,
    ];

    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::None => "None",
            Self::Upper => "Upper",
            Self::Lower => "Lower",
            Self::Trim => "Trim",
            Self::Length => "Length",
            Self::Substring => "Substring",
            Self::Round => "Round",
            Self::Abs => "Abs",
            Self::Floor => "Floor",
            Self::Ceil => "Ceil",
            Self::Truncate => "Truncate",
            Self::Coalesce => "Coalesce",
            Self::AsText => "AsText",
        }
    }

    pub fn by_name(name: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|f| f.name() == name)
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::None => "value",
            Self::Upper => "UPPER",
            Self::Lower => "LOWER",
            Self::Trim => "TRIM",
            Self::Length => "LENGTH",
            Self::Substring => "SUBSTRING",
            Self::Round => "ROUND",
            Self::Abs => "ABS",
            Self::Floor => "FLOOR",
            Self::Ceil => "CEIL",
            Self::Truncate => "truncate",
            Self::Coalesce => "if NULL…",
            Self::AsText => "as text",
        }
    }

    pub fn argument(self) -> Option<FunctionArgument> {
        match self {
            Self::Substring => Some(FunctionArgument::Range),
            Self::Round => Some(FunctionArgument::Digits),
            Self::Truncate => Some(FunctionArgument::Bucket),
            Self::Coalesce => Some(FunctionArgument::Fallback),
            _ => None,
        }
    }

    /// A short name for the column the result shows under, e.g. `upper(name)`.
    pub fn output_name(self, column: &str) -> String {
        if self == Self::None {
            column.to_owned()
        } else {
            format!("{}({column})", self.name().to_lowercase())
        }
    }
}

/// A compiled select list: `columns` go between `SELECT [DISTINCT]` and `FROM`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SqlProjection {
    pub columns: String,
    pub distinct: bool,
}

impl SqlProjection {
    pub fn new(columns: &str, distinct: bool) -> Self {
        Self {
            columns: columns.to_owned(),
            distinct,
        }
    }

    pub fn clause(&self) -> String {
        if self.distinct {
            format!("DISTINCT {}", self.columns)
        } else {
            self.columns.clone()
        }
    }
}
