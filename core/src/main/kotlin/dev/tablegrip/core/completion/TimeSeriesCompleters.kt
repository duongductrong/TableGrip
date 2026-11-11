package dev.tablegrip.core.completion

import dev.tablegrip.core.completion.Completions.functions
import dev.tablegrip.core.completion.Completions.keywords

/**
 * PromQL completion: metric names, functions and aggregations where a selector or expression goes, a metric's label
 * names inside its `{…}` and in `by (…)`/`without (…)`, and durations inside `[…]`.
 */
object PromQlCompleter : StatementCompleter {
    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val tokens = lex(text.substring(0, at), doubleQuotedStrings = true)
        if (tokens.any { it.encloses(at, text) && it.type != Lexeme.Type.Comment } || tokens.lastOrNull()?.let { it.type == Lexeme.Type.Comment && it.end == at && it.text.startsWith("#") } == true) return CompletionResult.empty(at)
        val word = { c: Char -> Completions.isWordChar(c) || c == ':' }
        val start = Completions.wordStart(text, at, word)
        val end = Completions.wordEnd(text, at, word)
        val prefix = text.substring(start, at)
        val before = tokens.filter { it.end <= start }
        fun result(items: List<CompletionItem>) = CompletionResult(start, end, prefix, Completions.rank(items, prefix))

        // The innermost open bracket, and the metric names written so far.
        val stack = ArrayList<Lexeme>()
        for (t in before) {
            when (t.text) {
                "(", "{", "[" -> stack += t
                ")", "}", "]" -> stack.removeLastOrNull()
            }
        }
        val open = stack.lastOrNull()
        val metrics = catalog.containers(emptyList()).orEmpty()
        val used = lex(text, doubleQuotedStrings = true).filter { it.type == Lexeme.Type.Word && it.text in metrics }.map { it.text }.distinct()
        fun labels(metric: String?) = (if (metric != null) listOf(metric) else used).flatMap { catalog.fields(listOf(it)).orEmpty() }.distinct().map { CompletionItem(it, it, CompletionKind.Column, "label") }

        return when (open?.text) {
            "{" -> {
                val last = before.last()
                if (last.text == "{" || last.text == ",") {
                    val metric = before.getOrNull(before.indexOf(open) - 1)?.takeIf { it.type == Lexeme.Type.Word }?.text
                    result(labels(metric))
                } else result(emptyList())
            }
            "[" -> result(Durations.map { CompletionItem(it, it, CompletionKind.Value) })
            "(" -> {
                val opener = before.getOrNull(before.indexOf(open) - 1)?.text?.lowercase()
                if (opener == "by" || opener == "without" || opener == "on" || opener == "ignoring") result(labels(null)) else result(expression(metrics))
            }
            else -> {
                val last = before.lastOrNull()
                val afterValue = last != null && (last.type == Lexeme.Type.Word && last.text.lowercase() !in Operators || last.text == ")" || last.text == "]" || last.text == "}" || last.type == Lexeme.Type.Number)
                if (afterValue) result(keywords(Operators.toList()) + expression(metrics)) else result(expression(metrics))
            }
        }
    }

    private fun expression(metrics: List<String>): List<CompletionItem> =
        metrics.map { CompletionItem(it, it, CompletionKind.Table, "metric") } + functions(Aggregations, "aggregation") + functions(Functions)

    private val Durations = listOf("1m", "5m", "15m", "30m", "1h", "6h", "1d", "7d")
    private val Operators = setOf("by", "without", "offset", "bool", "on", "ignoring", "group_left", "group_right", "and", "or", "unless")
    private val Aggregations = listOf("sum", "avg", "count", "min", "max", "topk", "bottomk", "quantile", "stddev", "stdvar", "count_values", "group")
    private val Functions =
        listOf(
            "rate", "irate", "increase", "delta", "idelta", "deriv", "histogram_quantile", "abs", "ceil", "floor", "round", "clamp", "clamp_min", "clamp_max",
            "label_replace", "label_join", "absent", "absent_over_time", "avg_over_time", "sum_over_time", "min_over_time", "max_over_time", "count_over_time",
            "quantile_over_time", "last_over_time", "stddev_over_time", "changes", "resets", "time", "timestamp", "vector", "scalar", "sort", "sort_desc",
            "predict_linear", "day_of_week", "hour", "ln", "log2", "log10", "exp", "sqrt",
        )
}

/**
 * Flux completion: `from(bucket: …)` and the other sources at the start of a line, the functions after `|>`, buckets in
 * `bucket: "…"`, a bucket's measurements in `r._measurement == "…"`, its fields in `r._field == "…"`, and a row's
 * columns after `r.`. A read-only profile isn't offered `to()`.
 */
object FluxCompleter : StatementCompleter {
    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val tokens = lex(text, doubleQuotedStrings = true)
        val string = tokens.firstOrNull { it.type == Lexeme.Type.String && it.encloses(at, text) }
        if (string == null && tokens.any { it.encloses(at, text) }) return CompletionResult.empty(at)
        val bucket = Regex("""bucket\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
        val measurement = Regex("""_measurement\s*==\s*"([^"]*)"""").find(text)?.groupValues?.get(1)

        if (string != null) {
            // Inside a string: the name the string is compared with or passed as.
            val start = string.start + 1
            val end = string.end.let { if (it > start && text[it - 1] == '"' && it - 1 >= at) it - 1 else it }.coerceAtLeast(at)
            val prefix = text.substring(start, at)
            val before = tokens.filter { it.end <= string.start }.takeLast(3).map { it.text }
            val items =
                when {
                    before.takeLast(2) == listOf("bucket", ":") -> catalog.containers(emptyList()).orEmpty().map { CompletionItem(it, it, CompletionKind.Schema, "bucket") }
                    before.takeLast(3) == listOf("_measurement", "=", "=") -> bucket?.let { catalog.containers(listOf(it)) }.orEmpty().map { CompletionItem(it, it, CompletionKind.Table, "measurement") }
                    before.takeLast(3) == listOf("_field", "=", "=") -> fields(catalog, bucket, measurement).map { CompletionItem(it, it, CompletionKind.Column, "field") }
                    else -> emptyList()
                }
            return CompletionResult(start, end, prefix, Completions.rank(items, prefix))
        }
        val start = Completions.wordStart(text, at)
        val end = Completions.wordEnd(text, at)
        val prefix = text.substring(start, at)
        val before = tokens.filter { it.end <= start && it.type != Lexeme.Type.Comment }
        fun result(items: List<CompletionItem>) = CompletionResult(start, end, prefix, Completions.rank(items, prefix))

        val last = before.lastOrNull()
        if (last?.text == "." && before.getOrNull(before.size - 2)?.text == "r") {
            return result(Columns.map { CompletionItem(it, it, CompletionKind.Column) } + fields(catalog, bucket, measurement).map { CompletionItem(it, it, CompletionKind.Column, "field") })
        }
        if (last?.text == ">" && before.getOrNull(before.size - 2)?.text == "|") {
            return result(Pipes.filter { isRead == null || it.first != "to" }.map { (name, args) -> call(name, args) })
        }
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        if (text.substring(lineStart, start).isBlank()) return result(Sources.map { (name, args) -> call(name, args) } + keywords(listOf("import")))
        return result(emptyList())
    }

    /** Tag keys and field keys, which the catalog lists together as the measurement's fields. */
    private fun fields(catalog: ConsoleCatalog, bucket: String?, measurement: String?): List<String> =
        if (bucket != null && measurement != null) catalog.fields(listOf(bucket, measurement)).orEmpty() else emptyList()

    /** `name(args)` with the caret at the first argument's value, or in the empty parentheses. */
    private fun call(name: String, args: String): CompletionItem {
        val text = "$name($args)"
        val caret =
            when {
                args.isEmpty() -> name.length + 1
                args.startsWith("fn") -> text.length - 1
                else -> {
                    val value = name.length + 1 + args.indexOf(": ") + 2
                    if (text.getOrNull(value) == '"') value + 1 else value
                }
            }
        return CompletionItem("$name()", text, CompletionKind.Function, args.ifEmpty { null }, caret)
    }

    private val Columns = listOf("_measurement", "_field", "_value", "_time", "_start", "_stop")
    private val Sources = listOf("from" to "bucket: \"\"", "buckets" to "", "union" to "tables: []", "join" to "tables: {}, on: []", "array.from" to "rows: []")
    private val Pipes =
        listOf(
            "range" to "start: -1h", "filter" to "fn: (r) => r._measurement == \"\"", "aggregateWindow" to "every: 1m, fn: mean, createEmpty: false", "group" to "columns: []",
            "keep" to "columns: []", "drop" to "columns: []", "pivot" to "rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\"", "sort" to "columns: [\"_time\"], desc: true",
            "limit" to "n: 10", "map" to "fn: (r) => ({ r with })", "mean" to "", "sum" to "", "count" to "", "max" to "", "min" to "", "last" to "", "first" to "",
            "distinct" to "column: \"\"", "window" to "every: 1m", "derivative" to "unit: 1s, nonNegative: true", "difference" to "", "top" to "n: 10", "rename" to "columns: {}",
            "fill" to "value: 0", "unique" to "column: \"\"", "yield" to "name: \"\"", "to" to "bucket: \"\"",
        )
}
