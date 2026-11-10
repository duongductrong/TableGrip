/// Lexical rules that differ between SQL dialects.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct SqlSyntax {
    pub backtick_identifiers: bool,
    /// MySQL treats `"..."` as a string literal by default (unless ANSI_QUOTES is on).
    pub double_quoted_strings: bool,
    pub hash_comments: bool,
    pub dollar_quoted_strings: bool,
    pub backslash_escapes: bool,
    /// MySQL client `DELIMITER //` command, used around procedure bodies.
    pub delimiter_command: bool,
    /// T-SQL `[quoted name]`.
    pub bracket_identifiers: bool,
    /// T-SQL tools' `GO` on a line of its own, which ends a batch.
    pub batch_separator: bool,
}

const NONE: SqlSyntax = SqlSyntax {
    backtick_identifiers: false,
    double_quoted_strings: false,
    hash_comments: false,
    dollar_quoted_strings: false,
    backslash_escapes: false,
    delimiter_command: false,
    bracket_identifiers: false,
    batch_separator: false,
};

impl SqlSyntax {
    pub const STANDARD: Self = NONE;
    pub const POSTGRESQL: Self = Self {
        dollar_quoted_strings: true,
        ..NONE
    };
    pub const MYSQL: Self = Self {
        backtick_identifiers: true,
        double_quoted_strings: true,
        hash_comments: true,
        backslash_escapes: true,
        delimiter_command: true,
        ..NONE
    };
    pub const SQLITE: Self = Self {
        backtick_identifiers: true,
        ..NONE
    };
    pub const TSQL: Self = Self {
        bracket_identifiers: true,
        batch_separator: true,
        ..NONE
    };
    pub const CLICKHOUSE: Self = Self {
        backtick_identifiers: true,
        backslash_escapes: true,
        ..NONE
    };
}
