package dev.tablegrip.app.search

/**
 * Scores [text] against [query] the way JetBrains' Search Everywhere and VS Code's quick open do: the query's characters
 * must appear in order, case-insensitively; matches at the start, at word starts (`uo` → `user_orders`, `UO` →
 * `UserOrders`) and in runs score higher. Spaces in the query are ignored. Null means no match; a blank query matches
 * everything with score 0.
 */
fun fuzzyScore(text: String, query: String): Int? {
    val needle = query.filterNot(Char::isWhitespace)
    if (needle.isEmpty()) return 0
    val positions = match(text, needle, preferWordStarts = true) ?: match(text, needle, preferWordStarts = false) ?: return null
    var score = 0
    positions.forEachIndexed { i, at ->
        score +=
            when {
                at == 0 -> 12
                i > 0 && at == positions[i - 1] + 1 -> 8
                isWordStart(text, at) -> 6
                else -> 1
            }
    }
    // An exact match wins outright; among equal matches shorter texts win.
    if (text.equals(needle, ignoreCase = true)) score += 50
    return score * 4 - text.length / 8
}

/** Where each of [needle]'s characters matches: continuing a run first, then (optionally) at the next word start. */
private fun match(text: String, needle: String, preferWordStarts: Boolean): IntArray? {
    val positions = IntArray(needle.length)
    var from = 0
    for ((i, c) in needle.withIndex()) {
        fun matchesAt(index: Int) = text[index].equals(c, ignoreCase = true)
        val continued = (i > 0 && from < text.length && matchesAt(from)).takeIf { it }?.let { from }
        val wordStart = if (preferWordStarts) (from until text.length).firstOrNull { matchesAt(it) && isWordStart(text, it) } else null
        val found = continued ?: wordStart ?: (from until text.length).firstOrNull(::matchesAt) ?: return null
        positions[i] = found
        from = found + 1
    }
    return positions
}

private fun isWordStart(text: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = text[index - 1]
    val current = text[index]
    return !previous.isLetterOrDigit() || (previous.isLowerCase() && current.isUpperCase()) || (previous.isLetter() && current.isDigit())
}
