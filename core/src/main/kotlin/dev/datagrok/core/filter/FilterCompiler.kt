package dev.datagrok.core.filter

import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.sql.SqlLexer
import dev.datagrok.core.sql.TokenType
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** A value bound to a filter's `?`. [value] is a String, Long, BigDecimal, Boolean, UUID or java.time value. */
data class SqlParam(val value: Any, val kind: ValueKind)

/** A WHERE condition with its bound parameters; [preview] has the values inlined, for display and copying only. */
data class SqlFilter(val sql: String, val parameters: List<SqlParam> = emptyList(), val preview: String = sql) {
    companion object {
        /** A condition typed as SQL; returns null when blank. */
        fun raw(sql: String): SqlFilter? = sql.trim().removeSuffix(";").trim().takeIf { it.isNotEmpty() }?.let(::SqlFilter)
    }
}

/** The outcome of compiling a filter: the condition (null when nothing is active) or, per node id, what's wrong. */
data class CompiledFilter(val filter: SqlFilter?, val issues: Map<Long, String>) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

/**
 * Turns a [FilterGroup] into parameterized SQL for [dialect]. Values are parsed for their column's [ValueKind] and bound
 * with matching JDBC types, never spliced into the SQL. Relative dates resolve against [clock] at compile time.
 */
class FilterCompiler(private val dialect: SqlDialect, columns: List<FilterColumn>, private val clock: Clock = Clock.systemDefaultZone()) {
    private val columns = columns.associateBy { it.name }
    private val issues = mutableMapOf<Long, String>()
    private val now: LocalDateTime = LocalDateTime.now(clock).withNano(0)
    private val today: LocalDate = now.toLocalDate()

    fun compile(root: FilterGroup): CompiledFilter {
        issues.clear()
        val sql = group(root, top = true)
        val filter = sql?.let { SqlFilter(it.text, it.params, it.preview) }
        if (filter != null && filter.parameters.isNotEmpty()) flagPlaceholders(root)
        return CompiledFilter(filter.takeIf { issues.isEmpty() }, issues.toMap())
    }

    /** Fragment of SQL kept alongside its parameters and a literal-inlined copy. */
    private class Sql(val text: String, val preview: String, val params: List<SqlParam>) {
        operator fun plus(other: Sql) = Sql(text + other.text, preview + other.preview, params + other.params)

        operator fun plus(other: String) = Sql(text + other, preview + other, params)
    }

    private fun t(text: String) = Sql(text, text, emptyList())

    private fun p(param: SqlParam) = Sql("?", dialect.literal(param), listOf(param))

    private fun join(parts: List<Sql>, separator: String): Sql = parts.drop(1).fold(parts.first()) { acc, part -> acc + separator + part }

    /** Joins with OR, parenthesized so it can sit inside an AND. */
    private fun anyOf(parts: List<Sql>): Sql = if (parts.size == 1) parts.single() else t("(") + join(parts, " OR ") + ")"

    private fun group(group: FilterGroup, top: Boolean = false): Sql? {
        val parts = group.children.filter { it.enabled }.mapNotNull { node ->
            when (node) {
                is FilterGroup -> group(node)
                is RawCondition -> raw(node)
                is FilterCondition -> condition(node)
            }
        }
        if (parts.isEmpty()) return null
        val joined = join(parts, " ${group.combinator.keyword} ")
        return when {
            group.negated -> t("NOT (") + joined + ")"
            parts.size == 1 || top -> joined
            else -> t("(") + joined + ")"
        }
    }

    private fun raw(node: RawCondition): Sql? {
        val sql = SqlFilter.raw(node.sql)?.sql
        if (sql == null) {
            issues[node.id] = "Enter a SQL condition"
            return null
        }
        return t("($sql)")
    }

    /** JDBC would take a `?` in hand-written SQL for a parameter once other conditions bind values. */
    private fun flagPlaceholders(node: FilterNode) {
        when (node) {
            is FilterGroup -> node.children.filter { it.enabled }.forEach(::flagPlaceholders)
            is RawCondition ->
                if (SqlLexer(dialect.syntax).tokenize(node.sql).any { it.type == TokenType.Parameter && it.text(node.sql) == "?" }) {
                    issues[node.id] = "“?” can't be combined with other conditions; use the SQL filter for this expression"
                }
            is FilterCondition -> Unit
        }
    }

    private fun condition(condition: FilterCondition): Sql? {
        val column = columns[condition.column]
        if (column == null) {
            issues[condition.id] = if (condition.column.isEmpty()) "Choose a column" else "No column “${condition.column}” in this table"
            return null
        }
        if (FilterOperator.forKind(column.kind, dialect.supportsRegex).none { condition.operator in it }) {
            issues[condition.id] = "“${condition.operator.label(column.kind)}” doesn't apply to ${column.kind.label} columns"
            return null
        }
        return try {
            compile(condition, column)
        } catch (e: FilterValueException) {
            issues[condition.id] = e.message.orEmpty()
            null
        }
    }

    private fun compile(condition: FilterCondition, column: FilterColumn): Sql {
        val c = dialect.quoteIdentifier(column.name)
        val kind = column.kind
        val text = if (kind == ValueKind.Text) c else dialect.castToText(c)
        return when (val op = condition.operator) {
            FilterOperator.IsNull -> t("$c IS NULL")
            FilterOperator.IsNotNull -> t("$c IS NOT NULL")
            FilterOperator.IsEmpty -> t("($c IS NULL OR $text = '')")
            FilterOperator.IsNotEmpty -> t("($c IS NOT NULL AND $text <> '')")
            FilterOperator.IsTrue, FilterOperator.IsFalse -> t("$c = ") + p(SqlParam(op == FilterOperator.IsTrue, ValueKind.Boolean))
            FilterOperator.Contains, FilterOperator.NotContains, FilterOperator.StartsWith, FilterOperator.EndsWith -> {
                val value = required(condition.value)
                val escaped = escapeLike(value)
                val pattern =
                    when (op) {
                        FilterOperator.StartsWith -> "$escaped%"
                        FilterOperator.EndsWith -> "%$escaped"
                        else -> "%$escaped%"
                    }
                like(text, pattern, condition.caseSensitive, negated = op == FilterOperator.NotContains)
            }
            FilterOperator.Like, FilterOperator.NotLike ->
                like(text, required(condition.value).replace("!", "!!"), condition.caseSensitive, negated = op == FilterOperator.NotLike)
            FilterOperator.Regex, FilterOperator.NotRegex -> {
                val (before, after) =
                    dialect.regexMatch(text, condition.caseSensitive, negated = op == FilterOperator.NotRegex)
                        ?: throw FilterValueException("This database has no regular expressions; use “like” instead")
                t(before) + p(SqlParam(required(condition.value), ValueKind.Text)) + after
            }
            FilterOperator.Equals, FilterOperator.NotEquals, FilterOperator.Less, FilterOperator.LessOrEqual, FilterOperator.Greater,
            FilterOperator.GreaterOrEqual -> compare(c, text, op, kind, condition.value)
            FilterOperator.Between, FilterOperator.NotBetween -> between(c, kind, condition, negated = op == FilterOperator.NotBetween)
            FilterOperator.In, FilterOperator.NotIn -> inList(c, kind, condition.value, negated = op == FilterOperator.NotIn)
            FilterOperator.Today, FilterOperator.Yesterday, FilterOperator.ThisWeek, FilterOperator.ThisMonth, FilterOperator.ThisYear,
            FilterOperator.LastDays -> {
                val (from, until) = period(op, condition.value)
                dayRange(c, kind, from, until)
            }
        }
    }

    private fun required(value: String): String = value.ifEmpty { throw FilterValueException("Enter a value") }

    private fun escapeLike(value: String) = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun like(expression: String, pattern: String, caseSensitive: Boolean, negated: Boolean): Sql {
        val (before, after) = dialect.textMatch(expression, caseSensitive, negated)
        return t(before) + p(SqlParam(dialect.textPattern(pattern, caseSensitive), ValueKind.Text)) + after
    }

    private fun compare(c: String, text: String, op: FilterOperator, kind: ValueKind, input: String): Sql {
        val symbol = Symbols.getValue(op)
        if (kind == ValueKind.Json) return t("$text $symbol ") + p(SqlParam(required(input), ValueKind.Text))
        if (kind.isTemporal) {
            val value = temporal(input, kind)
            if (kind == ValueKind.DateTime && value is TemporalValue.Day) {
                // A bare date on a date-time column means the whole day.
                val start = p(SqlParam(value.date.atStartOfDay(), kind))
                val end = p(SqlParam(value.date.plusDays(1).atStartOfDay(), kind))
                return when (op) {
                    FilterOperator.Equals -> t("($c >= ") + start + " AND $c < " + end + ")"
                    FilterOperator.NotEquals -> t("($c < ") + start + " OR $c >= " + end + ")"
                    FilterOperator.Less -> t("$c < ") + start
                    FilterOperator.LessOrEqual -> t("$c < ") + end
                    FilterOperator.Greater -> t("$c >= ") + end
                    else -> t("$c >= ") + start
                }
            }
            return t("$c $symbol ") + p(bindable(value, kind))
        }
        return t("$c $symbol ") + p(scalar(kind, input))
    }

    private fun between(c: String, kind: ValueKind, condition: FilterCondition, negated: Boolean): Sql {
        if (condition.value.isEmpty() || condition.value2.isEmpty()) throw FilterValueException("Enter both bounds")
        if (kind.isTemporal) {
            val from = bindable(temporal(condition.value, kind), kind)
            val to = temporal(condition.value2, kind)
            // A whole day as the upper bound includes that day.
            val inclusiveDay = kind == ValueKind.DateTime && to is TemporalValue.Day
            val upper = if (to is TemporalValue.Day && inclusiveDay) SqlParam(to.date.plusDays(1).atStartOfDay(), kind) else bindable(to, kind)
            checkOrder(from.value, upper.value)
            return if (negated) t("($c < ") + p(from) + (if (inclusiveDay) " OR $c >= " else " OR $c > ") + p(upper) + ")"
            else t("($c >= ") + p(from) + (if (inclusiveDay) " AND $c < " else " AND $c <= ") + p(upper) + ")"
        }
        val from = scalar(kind, condition.value)
        val to = scalar(kind, condition.value2)
        checkOrder(from.value, to.value)
        return t("$c ${if (negated) "NOT " else ""}BETWEEN ") + p(from) + " AND " + p(to)
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkOrder(from: Any, to: Any) {
        val ordered =
            when {
                from is Number && to is Number -> BigDecimal(from.toString()) <= BigDecimal(to.toString())
                // Text order is the column's collation's business.
                from is String -> true
                from is Comparable<*> && from::class == to::class -> (from as Comparable<Any>) <= to
                else -> true
            }
        if (!ordered) throw FilterValueException("The first bound is after the second")
    }

    private fun inList(c: String, kind: ValueKind, input: String, negated: Boolean): Sql {
        val items = FilterValues.parseList(input)
        if (items.isEmpty()) throw FilterValueException("Enter one or more values, separated by commas")
        if (items.size > MaxListSize) throw FilterValueException("At most $MaxListSize values")
        val values = items.filterNotNull()
        val parts = mutableListOf<Sql>()
        val days = mutableListOf<LocalDate>()
        val params = mutableListOf<SqlParam>()
        for (value in values) {
            if (kind.isTemporal) {
                val parsed = temporal(value, kind)
                if (kind == ValueKind.DateTime && parsed is TemporalValue.Day) days += parsed.date else params += bindable(parsed, kind)
            } else {
                // A quoted '' is a real (empty) text value in a list.
                params += if (kind == ValueKind.Text || kind == ValueKind.Other) SqlParam(value, kind) else scalar(kind, value)
            }
        }
        if (params.isNotEmpty()) {
            val list = join(params.distinct().map(::p), ", ")
            if (negated && days.isEmpty() && null !in items) return t("$c NOT IN (") + list + ")"
            parts += t("$c IN (") + list + ")"
        }
        days.distinct().forEach { parts += dayRange(c, kind, it, it.plusDays(1)) }
        if (null in items) parts += t("$c IS NULL")
        val any = anyOf(parts)
        return if (negated) t("NOT ") + (if (parts.size == 1) t("(") + any + ")" else any) else any
    }

    /** `[from, until)` in whole days. */
    private fun dayRange(c: String, kind: ValueKind, from: LocalDate, until: LocalDate): Sql {
        fun bound(date: LocalDate) = SqlParam(if (kind == ValueKind.DateTime) date.atStartOfDay() else date, kind)
        return t("($c >= ") + p(bound(from)) + " AND $c < " + p(bound(until)) + ")"
    }

    private fun period(op: FilterOperator, input: String): Pair<LocalDate, LocalDate> =
        when (op) {
            FilterOperator.Today -> today to today.plusDays(1)
            FilterOperator.Yesterday -> today.minusDays(1) to today
            FilterOperator.ThisWeek -> {
                val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
                val start = today.with(TemporalAdjusters.previousOrSame(first ?: DayOfWeek.MONDAY))
                start to start.plusWeeks(1)
            }
            FilterOperator.ThisMonth -> today.withDayOfMonth(1).let { it to it.plusMonths(1) }
            FilterOperator.ThisYear -> today.withDayOfYear(1).let { it to it.plusYears(1) }
            else -> {
                val days = FilterValues.number(input).toBigIntegerExactOrNull()?.toLong()?.takeIf { it in 1..36_500 }
                    ?: throw FilterValueException("Enter a whole number of days")
                // "The last 7 days" counts today as one of them.
                today.minusDays(days - 1) to today.plusDays(1)
            }
        }

    private fun BigDecimal.toBigIntegerExactOrNull() = runCatching { toBigIntegerExact() }.getOrNull()

    private fun temporal(input: String, kind: ValueKind): TemporalValue {
        val value = FilterValues.temporal(required(input), now, clock.zone)
        if (kind == ValueKind.Time && value is TemporalValue.Day) throw FilterValueException("Enter a time of day, e.g. 13:45")
        if (kind != ValueKind.Time && value is TemporalValue.TimeOfDay) throw FilterValueException("Enter a date, e.g. ${today}")
        return value
    }

    /** The value to bind for a parsed date, date-time or time on a [kind] column. */
    private fun bindable(value: TemporalValue, kind: ValueKind): SqlParam =
        SqlParam(
            when (kind) {
                ValueKind.Date ->
                    when (value) {
                        is TemporalValue.Day -> value.date
                        is TemporalValue.Moment -> value.dateTime.toLocalDate()
                        is TemporalValue.TimeOfDay -> error("rejected above")
                    }
                ValueKind.Time ->
                    when (value) {
                        is TemporalValue.Moment -> value.dateTime.toLocalTime()
                        is TemporalValue.TimeOfDay -> value.time
                        is TemporalValue.Day -> error("rejected above")
                    }
                else ->
                    when (value) {
                        is TemporalValue.Day -> value.date.atStartOfDay()
                        is TemporalValue.Moment -> value.dateTime
                        is TemporalValue.TimeOfDay -> error("rejected above")
                    }
            },
            kind,
        )

    private fun scalar(kind: ValueKind, input: String): SqlParam =
        when (kind) {
            ValueKind.Number -> {
                val number = FilterValues.number(input)
                val integral = runCatching { number.longValueExact() }.getOrNull()
                SqlParam(integral ?: number, kind)
            }
            ValueKind.Uuid -> SqlParam(FilterValues.uuid(input), kind)
            ValueKind.Boolean -> SqlParam(FilterValues.boolean(input) ?: throw FilterValueException("Enter true or false"), kind)
            ValueKind.Binary -> throw FilterValueException("Binary values can only be tested for NULL")
            else -> SqlParam(required(input), kind)
        }

    companion object {
        const val MaxListSize = 1_000

        private val Symbols =
            mapOf(
                FilterOperator.Equals to "=",
                FilterOperator.NotEquals to "<>",
                FilterOperator.Less to "<",
                FilterOperator.LessOrEqual to "<=",
                FilterOperator.Greater to ">",
                FilterOperator.GreaterOrEqual to ">=",
            )
    }
}
