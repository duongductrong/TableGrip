package dev.tablegrip.core.completion

/** A catalog with everything loaded, for the console completers' tests. */
internal class FakeCatalog(
    private val containers: Map<List<String>, List<String>> = emptyMap(),
    private val fields: Map<List<String>, List<String>> = emptyMap(),
    private val graph: Map<GraphNames, List<String>> = emptyMap(),
) : ConsoleCatalog {
    override fun containers(parent: List<String>) = containers[parent].orEmpty()

    override fun fields(container: List<String>) = fields[container].orEmpty()

    override fun graph(kind: GraphNames) = graph[kind].orEmpty()
}

/** The caret's marker in a test's text: `‸` where the text has a `|` of its own (Flux's pipes), else `|`. */
private fun marker(text: String) = if ('‸' in text) '‸' else '|'

/** Runs [completer] at the caret's marker in [text]. */
internal fun StatementCompleter.at(text: String, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)? = null): CompletionResult {
    val caret = text.indexOf(marker(text))
    return complete(text.removeRange(caret, caret + 1), caret, catalog, isRead)
}

internal fun StatementCompleter.labels(text: String, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)? = null) = at(text, catalog, isRead).items.map { it.label }

/** The text after accepting the suggestion labelled [label] (the first by default), with the marker at the caret. */
internal fun StatementCompleter.accept(text: String, catalog: ConsoleCatalog, label: String? = null): String {
    val mark = marker(text)
    val caret = text.indexOf(mark)
    val plain = text.removeRange(caret, caret + 1)
    val result = complete(plain, caret, catalog, null)
    val item = label?.let { l -> result.items.first { it.label == l } } ?: result.items.first()
    val after = plain.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)
    val at = result.replaceStart + item.caretOffset
    return after.substring(0, at) + mark + after.substring(at)
}
