package dev.tablegrip.core.filter

import java.sql.Types

/** How a column's values are compared, parsed and bound: decides which operators and value editors a filter offers. */
enum class ValueKind(val label: String) {
    Text("text"),
    Number("number"),
    Boolean("boolean"),
    Date("date"),
    Time("time"),
    DateTime("date-time"),
    Uuid("uuid"),
    Json("json"),
    Binary("binary"),
    /** Enums, network addresses, geometry, arrays…: compared as the server parses their text. */
    Other("other");

    val isTemporal: kotlin.Boolean
        get() = this == Date || this == Time || this == DateTime

    companion object {
        /** Classifies a column by its database type name, falling back to the JDBC type for names it doesn't know. */
        fun of(typeName: String, jdbcType: Int = Types.OTHER, size: Int? = null): ValueKind {
            val name = typeName.lowercase().substringBefore('(').trim().removeSuffix(" unsigned").removeSuffix(" zerofill").trim()
            if (name.startsWith("_") || name.endsWith("[]")) return Other
            if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT && (size == null || size <= 1)) return Boolean
            return when {
                name in BooleanNames -> Boolean
                name == "uuid" || name == "uniqueidentifier" -> Uuid
                name == "json" || name == "jsonb" -> Json
                name == "date" -> Date
                name.startsWith("timestamp") || name.startsWith("datetime") -> DateTime
                name == "time" || name == "timetz" || name.startsWith("time with") || name.startsWith("time without") -> Time
                name in NumberNames || name.startsWith("serial") || name.startsWith("float") || name.startsWith("double") -> Number
                name in BinaryNames || name.endsWith("blob") -> Binary
                name in TextNames || name.endsWith("text") || name.contains("char") || name.contains("clob") -> Text
                // SQLite columns may have no declared type at all; their values are usually text.
                name.isEmpty() -> Text
                else -> byJdbcType(jdbcType)
            }
        }

        private fun byJdbcType(jdbcType: Int): ValueKind =
            when (jdbcType) {
                Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> Number
                Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> Text
                Types.DATE -> Date
                Types.TIME, Types.TIME_WITH_TIMEZONE -> Time
                Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> DateTime
                Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Binary
                else -> Other
            }

        private val BooleanNames = setOf("bool", "boolean")
        private val NumberNames =
            setOf(
                "int", "integer", "int2", "int4", "int8", "smallint", "bigint", "tinyint", "mediumint", "numeric", "decimal", "dec",
                "real", "number", "year", "smallserial", "bigserial",
            )
        private val BinaryNames = setOf("bytea", "binary", "varbinary", "bit varying", "varbit")
        private val TextNames = setOf("text", "citext", "name", "string", "set")
    }
}

/** How many values an operator takes. */
enum class Arity { None, One, Two, List }

/** A comparison in a filter condition. [label] reads as part of a sentence: "created_at · after · 2026-01-01". */
enum class FilterOperator(val label: String, val arity: Arity) {
    Equals("=", Arity.One),
    NotEquals("≠", Arity.One),
    Less("<", Arity.One),
    LessOrEqual("≤", Arity.One),
    Greater(">", Arity.One),
    GreaterOrEqual("≥", Arity.One),
    Between("between", Arity.Two),
    NotBetween("not between", Arity.Two),
    In("is one of", Arity.List),
    NotIn("is none of", Arity.List),
    Contains("contains", Arity.One),
    NotContains("doesn't contain", Arity.One),
    StartsWith("starts with", Arity.One),
    EndsWith("ends with", Arity.One),
    Like("like", Arity.One),
    NotLike("not like", Arity.One),
    Regex("matches regex", Arity.One),
    NotRegex("doesn't match regex", Arity.One),
    IsEmpty("is empty", Arity.None),
    IsNotEmpty("is not empty", Arity.None),
    IsTrue("is true", Arity.None),
    IsFalse("is false", Arity.None),
    Today("is today", Arity.None),
    Yesterday("is yesterday", Arity.None),
    ThisWeek("is this week", Arity.None),
    ThisMonth("is this month", Arity.None),
    ThisYear("is this year", Arity.None),
    LastDays("in the last … days", Arity.One),
    IsNull("is NULL", Arity.None),
    IsNotNull("is not NULL", Arity.None);

    /** Comparisons read better as words for dates and times. */
    fun label(kind: ValueKind): String =
        when {
            !kind.isTemporal -> label
            this == Equals -> if (kind == ValueKind.Time) "is at" else "is on"
            this == NotEquals -> if (kind == ValueKind.Time) "is not at" else "is not on"
            this == Less -> "is before"
            this == LessOrEqual -> if (kind == ValueKind.Time) "is at or before" else "is on or before"
            this == Greater -> "is after"
            this == GreaterOrEqual -> if (kind == ValueKind.Time) "is at or after" else "is on or after"
            else -> label
        }

    /** Text matching where letter case can matter. */
    val matchesText: Boolean
        get() = this in TextMatches

    companion object {
        private val TextMatches = setOf(Contains, NotContains, StartsWith, EndsWith, Like, NotLike, Regex, NotRegex)

        /**
         * Operators that make sense for [kind], most useful first, in sections the UI separates. Regex is offered where
         * the engine has it.
         */
        fun forKind(kind: ValueKind, regex: kotlin.Boolean = true): List<List<FilterOperator>> {
            val nulls = listOf(IsNull, IsNotNull)
            val regexes = if (regex) listOf(Regex, NotRegex) else emptyList()
            return when (kind) {
                ValueKind.Text ->
                    listOf(
                        listOf(Equals, NotEquals, Contains, NotContains, StartsWith, EndsWith),
                        listOf(In, NotIn, Like, NotLike) + regexes,
                        listOf(IsEmpty, IsNotEmpty) + nulls,
                        listOf(Less, LessOrEqual, Greater, GreaterOrEqual, Between, NotBetween),
                    )
                ValueKind.Number ->
                    listOf(
                        listOf(Equals, NotEquals, Less, LessOrEqual, Greater, GreaterOrEqual),
                        listOf(Between, NotBetween, In, NotIn),
                        nulls,
                        listOf(Contains, StartsWith, EndsWith),
                    )
                ValueKind.Boolean -> listOf(listOf(IsTrue, IsFalse), nulls)
                ValueKind.Date, ValueKind.DateTime ->
                    listOf(
                        listOf(Equals, NotEquals, Less, LessOrEqual, Greater, GreaterOrEqual),
                        listOf(Between, NotBetween, In, NotIn),
                        listOf(Today, Yesterday, ThisWeek, ThisMonth, ThisYear, LastDays),
                        nulls,
                    )
                ValueKind.Time -> listOf(listOf(Equals, NotEquals, Less, LessOrEqual, Greater, GreaterOrEqual), listOf(Between, NotBetween), nulls)
                ValueKind.Uuid -> listOf(listOf(Equals, NotEquals, In, NotIn), listOf(StartsWith, Contains), nulls)
                ValueKind.Json -> listOf(listOf(Contains, NotContains, Equals, NotEquals) + regexes, nulls)
                ValueKind.Binary -> listOf(nulls)
                ValueKind.Other ->
                    listOf(
                        listOf(Equals, NotEquals, In, NotIn),
                        listOf(Contains, NotContains, StartsWith, EndsWith) + regexes,
                        listOf(Less, LessOrEqual, Greater, GreaterOrEqual, Between),
                        nulls,
                    )
            }
        }

        /** What a new condition on a [kind] column starts with. */
        fun defaultFor(kind: ValueKind): FilterOperator =
            when (kind) {
                ValueKind.Text, ValueKind.Json -> Contains
                ValueKind.Boolean -> IsTrue
                ValueKind.Binary -> IsNotNull
                else -> Equals
            }
    }
}

enum class Combinator(val keyword: String, val label: String) {
    All("AND", "all"),
    Any("OR", "any"),
}

/** A filter tree, as built in the table viewer. Disabled nodes are kept but ignored. */
sealed interface FilterNode {
    val id: Long
    val enabled: Boolean
}

/** `column <operator> value(s)`. [value] holds the whole list for [Arity.List] operators; [value2] the upper bound. */
data class FilterCondition(
    override val id: Long,
    val column: String,
    val operator: FilterOperator,
    val value: String = "",
    val value2: String = "",
    val caseSensitive: Boolean = false,
    override val enabled: Boolean = true,
) : FilterNode

data class FilterGroup(
    override val id: Long,
    val combinator: Combinator = Combinator.All,
    val children: List<FilterNode> = emptyList(),
    /** Matches rows the group would not: `NOT (…)`. */
    val negated: Boolean = false,
    override val enabled: Boolean = true,
) : FilterNode

/** A hand-written SQL condition, used as is. */
data class RawCondition(override val id: Long, val sql: String, override val enabled: Boolean = true) : FilterNode

/** A column a filter can refer to. */
data class FilterColumn(val name: String, val typeName: String, val kind: ValueKind)
