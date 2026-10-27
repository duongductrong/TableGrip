package dev.datagrok.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.core.sql.SqlLexer
import dev.datagrok.core.sql.SqlSyntax
import dev.datagrok.core.sql.TokenType
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * A plain-Compose code editor: line numbers, SQL highlighting, auto-indent, and no soft wrapping. Kept on
 * [BasicTextField] rather than Swing so it follows the Jewel theme and works with Compose focus and key handling.
 */
@Composable
internal fun SqlEditor(
    state: TextFieldState,
    syntax: SqlSyntax,
    fontSize: Int,
    modifier: Modifier = Modifier,
    onShortcut: (KeyEvent) -> Boolean = { false },
) {
    val isDark = JewelTheme.isDark
    val highlighter = remember(syntax, isDark) { SqlHighlighter(syntax, if (isDark) DarkPalette else LightPalette) }
    val textStyle =
        JewelTheme.editorTextStyle.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp, color = JewelTheme.globalColors.text.normal)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    BoxWithConstraints(modifier.background(JewelTheme.globalColors.panelBackground)) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        Row(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            LineNumbers(lineCount = state.text.count { it == '\n' } + 1, textStyle = textStyle)
            Box(Modifier.horizontalScroll(horizontalScroll)) {
                BasicTextField(
                    state = state,
                    modifier =
                        Modifier.defaultMinSize(minWidth = viewportWidth - GutterWidth, minHeight = viewportHeight)
                            .padding(start = 8.dp, top = 4.dp, end = 24.dp, bottom = 4.dp)
                            .onPreviewKeyEvent { event -> onShortcut(event) || handleEditingKeys(state, event) },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(JewelTheme.globalColors.text.normal),
                    outputTransformation = highlighter,
                )
            }
        }
    }
}

private val GutterWidth = 48.dp

@Composable
private fun LineNumbers(lineCount: Int, textStyle: TextStyle) {
    Column(Modifier.fillMaxHeight().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.End) {
        val style = textStyle.copy(color = JewelTheme.globalColors.text.info, textAlign = TextAlign.End)
        for (line in 1..lineCount) {
            Text(line.toString(), modifier = Modifier.padding(start = 8.dp).defaultMinSize(minWidth = GutterWidth - 8.dp), style = style)
        }
    }
}

/** Enter keeps the current line's indentation; Tab inserts spaces. */
private fun handleEditingKeys(state: TextFieldState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return false
    return when (event.key) {
        Key.Enter,
        Key.NumPadEnter -> {
            if (event.isShiftPressed) return false
            val text = state.text
            val caret = state.selection.min
            val lineStart = text.lastIndexOf('\n', caret - 1) + 1
            val indent = text.subSequence(lineStart, caret).takeWhile { it == ' ' || it == '\t' }
            state.edit { replaceSelection("\n$indent") }
            true
        }
        Key.Tab -> {
            if (event.isShiftPressed) return false
            state.edit { replaceSelection("    ") }
            true
        }
        else -> false
    }
}

private fun TextFieldBuffer.replaceSelection(text: String) {
    val start = selection.min
    replace(start, selection.max, text)
    selection = androidx.compose.ui.text.TextRange(start + text.length)
}

private class SqlPalette(
    val keyword: SpanStyle,
    val string: SpanStyle,
    val number: SpanStyle,
    val comment: SpanStyle,
    val quotedIdentifier: SpanStyle,
    val parameter: SpanStyle,
)

private val DarkPalette =
    SqlPalette(
        keyword = SpanStyle(color = Color(0xFFCF8E6D)),
        string = SpanStyle(color = Color(0xFF6AAB73)),
        number = SpanStyle(color = Color(0xFF2AACB8)),
        comment = SpanStyle(color = Color(0xFF7A7E85), fontStyle = FontStyle.Italic),
        quotedIdentifier = SpanStyle(color = Color(0xFFC77DBB)),
        parameter = SpanStyle(color = Color(0xFF56A8F5)),
    )

private val LightPalette =
    SqlPalette(
        keyword = SpanStyle(color = Color(0xFF0033B3)),
        string = SpanStyle(color = Color(0xFF067D17)),
        number = SpanStyle(color = Color(0xFF1750EB)),
        comment = SpanStyle(color = Color(0xFF8C8C8C), fontStyle = FontStyle.Italic),
        quotedIdentifier = SpanStyle(color = Color(0xFF871094)),
        parameter = SpanStyle(color = Color(0xFF00627A)),
    )

/** Colours tokens on the displayed text only; the underlying state stays plain. */
private class SqlHighlighter(syntax: SqlSyntax, private val palette: SqlPalette) : OutputTransformation {
    private val lexer = SqlLexer(syntax)

    override fun TextFieldBuffer.transformOutput() {
        for (token in lexer.tokenize(asCharSequence())) {
            val style =
                when (token.type) {
                    TokenType.Keyword -> palette.keyword
                    TokenType.String -> palette.string
                    TokenType.Number -> palette.number
                    TokenType.Comment -> palette.comment
                    TokenType.QuotedIdentifier -> palette.quotedIdentifier
                    TokenType.Parameter -> palette.parameter
                    else -> null
                }
            if (style != null) addStyle(style, token.start, token.end)
        }
    }
}
