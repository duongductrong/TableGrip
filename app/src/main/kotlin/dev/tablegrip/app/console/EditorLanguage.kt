package dev.tablegrip.app.console

import dev.tablegrip.core.completion.MongoScript
import dev.tablegrip.core.sql.SqlSyntax
import dev.tablegrip.core.sql.Token

/**
 * What [SqlEditor] knows of the language it edits, beyond the lexical [SqlSyntax] that quotes and brackets pair by:
 * [Sql] for SQL consoles, [MongoShell] for the Mongo shell console.
 */
class EditorLanguage(
    /** Tokens to colour; null for SQL's. */
    val tokenize: ((CharSequence) -> List<Token>)? = null,
    /** The statement at the caret, as the gutter marks it; null for SQL's statements. */
    val statementAt: ((text: String, caret: Int) -> IntRange?)? = null,
    val lineComment: String = "--",
    /** Whether `{` pairs with `}` as it's typed, as `(` and `[` always do. */
    val pairBraces: Boolean = false,
    /** Whether the SQL formatter applies. */
    val formats: Boolean = true,
    /**
     * Typed characters after which completion offers what can come next with nothing typed yet, Enter then breaking the
     * line unless the list was navigated.
     */
    val completeAfter: String = "",
) {
    companion object {
        val Sql = EditorLanguage()

        /** Strings in either quote with backslash escapes, as JavaScript writes them. */
        val MongoShellSyntax = SqlSyntax(doubleQuotedStrings = true, backslashEscapes = true)

        val MongoShell =
            EditorLanguage(
                tokenize = MongoScript::tokenize,
                statementAt = { text, caret -> MongoScript.statementAt(text, caret)?.let { it.start..it.end } },
                lineComment = "//",
                pairBraces = true,
                formats = false,
                completeAfter = "{([,: ",
            )
    }
}
