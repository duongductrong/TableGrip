package dev.datagrok.app.console

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.datagrok.app.ui.LocalSettings
import dev.datagrok.core.sql.SqlSyntax
import org.jetbrains.jewel.foundation.theme.JewelTheme
import dev.datagrok.app.ui.parseHexColor
import dev.datagrok.core.settings.Settings

/** The SQL tokens the editor colours, with the key Settings stores a custom colour under. */
enum class SyntaxToken(val key: String, val label: String) {
    Keyword("keyword", "Keyword"),
    String("string", "String"),
    Number("number", "Number"),
    Comment("comment", "Comment"),
    QuotedIdentifier("quotedIdentifier", "Quoted identifier"),
    Parameter("parameter", "Parameter");

    /** The key in [Settings.syntaxColors] for this token in a dark or light theme. */
    fun settingKey(dark: Boolean): kotlin.String = (if (dark) "dark." else "light.") + key
}

/** One theme's half of a [SyntaxScheme]: token colours, and the editor's own background and text unless null (the theme's). */
class SchemeVariant(val tokens: Map<SyntaxToken, Long>, val background: Long? = null, val foreground: Long? = null)

/** A syntax colour scheme, with a variant for dark and light themes. */
enum class SyntaxScheme(val label: String, private val dark: SchemeVariant, private val light: SchemeVariant) {
    // Datagrok's own sits on the islands' colours, like the rest of the window.
    Datagrok(
        "Datagrok",
        dark = SchemeVariant(tokens(0xFFCF8E6D, 0xFF6AAB73, 0xFF2AACB8, 0xFF7A7E85, 0xFFC77DBB, 0xFF56A8F5)),
        light = SchemeVariant(tokens(0xFF0033B3, 0xFF067D17, 0xFF1750EB, 0xFF8C8C8C, 0xFF871094, 0xFF00627A)),
    ),
    Darcula(
        "Darcula",
        dark = SchemeVariant(tokens(0xFFCC7832, 0xFF6A8759, 0xFF6897BB, 0xFF808080, 0xFF9876AA, 0xFFFFC66D), background = 0xFF2B2B2B, foreground = 0xFFA9B7C6),
        light = SchemeVariant(tokens(0xFF000080, 0xFF008000, 0xFF0000FF, 0xFF808080, 0xFF660E7A, 0xFF7A7A43), background = 0xFFFFFFFF, foreground = 0xFF000000),
    ),
    GitHub(
        "GitHub",
        dark = SchemeVariant(tokens(0xFFFF7B72, 0xFFA5D6FF, 0xFF79C0FF, 0xFF8B949E, 0xFFD2A8FF, 0xFFFFA657), background = 0xFF0D1117, foreground = 0xFFE6EDF3),
        light = SchemeVariant(tokens(0xFFCF222E, 0xFF0A3069, 0xFF0550AE, 0xFF6E7781, 0xFF8250DF, 0xFF953800), background = 0xFFFFFFFF, foreground = 0xFF1F2328),
    ),
    Solarized(
        "Solarized",
        dark = SchemeVariant(tokens(0xFF859900, 0xFF2AA198, 0xFFD33682, 0xFF586E75, 0xFF268BD2, 0xFFCB4B16), background = 0xFF002B36, foreground = 0xFF839496),
        light = SchemeVariant(tokens(0xFF859900, 0xFF2AA198, 0xFFD33682, 0xFF93A1A1, 0xFF268BD2, 0xFFCB4B16), background = 0xFFFDF6E3, foreground = 0xFF657B83),
    ),
    Monokai(
        "Monokai",
        dark = SchemeVariant(tokens(0xFFF92672, 0xFFE6DB74, 0xFFAE81FF, 0xFF75715E, 0xFFA6E22E, 0xFFFD971F), background = 0xFF272822, foreground = 0xFFF8F8F2),
        light = SchemeVariant(tokens(0xFFF92672, 0xFF998F2F, 0xFFAE81FF, 0xFF75715E, 0xFF679C00, 0xFFCF7000), background = 0xFFFAFAFA, foreground = 0xFF272822),
    );

    private fun variant(dark: Boolean): SchemeVariant = if (dark) this.dark else light

    fun color(token: SyntaxToken, dark: Boolean): Color = Color(variant(dark).tokens.getValue(token))

    /** The editor's background, or null to keep the theme's panel colour. */
    fun background(dark: Boolean): Color? = variant(dark).background?.let(::Color)

    /** Plain text (identifiers, operators), or null to keep the theme's text colour. */
    fun foreground(dark: Boolean): Color? = variant(dark).foreground?.let(::Color)

    companion object {
        fun of(name: String): SyntaxScheme = entries.firstOrNull { it.name == name } ?: Datagrok
    }
}

private fun tokens(keyword: Long, string: Long, number: Long, comment: Long, quotedIdentifier: Long, parameter: Long): Map<SyntaxToken, Long> =
    mapOf(
        SyntaxToken.Keyword to keyword,
        SyntaxToken.String to string,
        SyntaxToken.Number to number,
        SyntaxToken.Comment to comment,
        SyntaxToken.QuotedIdentifier to quotedIdentifier,
        SyntaxToken.Parameter to parameter,
    )

/** [token]'s colour as the user set it, or their scheme's. */
fun Settings.syntaxColor(token: SyntaxToken, dark: Boolean): Color =
    syntaxColors[token.settingKey(dark)]?.let(::parseHexColor) ?: SyntaxScheme.of(syntaxScheme).color(token, dark)

/** SQL showing every coloured token, for the Color Scheme page's preview. */
private const val PreviewSql =
    """-- Paid orders of active users
SELECT u.id, u."Full Name", count(*) AS orders
FROM users u
JOIN orders o ON o.user_id = u.id
WHERE u.active = true
    AND o.status = 'paid'
    AND o.created_at > :since
GROUP BY u.id
LIMIT 50;"""

/** A read-only editor showing [PreviewSql] in the current scheme, so changes show while Settings covers the consoles. */
@Composable
internal fun SyntaxPreview() {
    val state = remember { TextFieldState(PreviewSql) }
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(230.dp).clip(shape).border(1.dp, JewelTheme.globalColors.borders.normal, shape)) {
        SqlEditor(state, SqlSyntax.PostgreSQL, LocalSettings.current.editorFontSize, Modifier.fillMaxSize(), readOnly = true)
    }
}
