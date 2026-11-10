package dev.tablegrip.core.i18n

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    @Test
    fun `system locales map to the languages TableGrip has`() {
        assertEquals(Language.Vietnamese, Language.ofLocale(Locale.forLanguageTag("vi-VN")))
        assertEquals(Language.ChineseSimplified, Language.ofLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(Language.ChineseSimplified, Language.ofLocale(Locale.forLanguageTag("zh-Hans-SG")))
        assertEquals(Language.ChineseTraditional, Language.ofLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(Language.ChineseTraditional, Language.ofLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals(Language.ChineseTraditional, Language.ofLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals(Language.English, Language.ofLocale(Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun `the setting picks a language by tag and falls back to the system's`() {
        val vietnamese = Locale.forLanguageTag("vi-VN")
        assertEquals(Language.ChineseTraditional, Language.resolve("zh-TW", vietnamese))
        assertEquals(Language.English, Language.resolve("en", vietnamese))
        assertEquals(Language.Vietnamese, Language.resolve(Language.System, vietnamese))
        assertEquals(Language.Vietnamese, Language.resolve("klingon", vietnamese))
    }

    @Test
    fun `placeholders are filled by position and other braces stay`() {
        assertEquals("Deleted 3 rows from t", I18n.format("Deleted {0} rows from {1}", arrayOf<Any?>(3, "t")))
        assertEquals("{ \"name\": x }", I18n.format("{ \"name\": {0} }", arrayOf("x")))
        assertEquals("{2} left", I18n.format("{2} left", arrayOf("only one")))
        assertEquals("No {0} here", I18n.format("No {0} here", emptyArray()))
    }

    @Test
    fun `english needs no catalog and returns the source`() {
        assertTrue(I18n.catalog(Language.English).isEmpty())
        assertEquals("Couldn't open a: b", tr("Couldn't open {0}: {1}", "a", "b"))
    }

    /** Every text the code passes to [tr], read from the sources of every module. */
    private val sources: Map<String, String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val dirs = listOf(File(root, "core/src/main/kotlin"), File(root, "app/src/main/kotlin")) +
            (File(root, "engines").listFiles().orEmpty().map { File(it, "src/main/kotlin") })
        buildMap {
            dirs.filter { it.isDirectory }.forEach { dir ->
                dir.walk().filter { it.extension == "kt" }.forEach { file ->
                    val text = file.readText()
                    TrCall.findAll(text).forEach { match -> putIfAbsent(unescape(match.groupValues[1]), "${file.name}:${line(text, match.range.first)}") }
                }
            }
        }
    }

    @Test
    fun `tr is only called with a plain string literal`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val problems = mutableListOf<String>()
        listOf("core", "app", "engines").map { File(root, it) }.forEach { dir ->
            dir.walk().filter { it.extension == "kt" && "/src/main/" in it.path }.forEach { file ->
                val text = file.readText()
                AnyTrCall.findAll(text).forEach { match ->
                    val after = text.substring(match.range.last + 1).trimStart()
                    val literal = TrCall.matchAt(text, match.range.first)
                    when {
                        !after.startsWith("\"") || after.startsWith("\"\"\"") -> problems += "${file.name}:${line(text, match.range.first)} passes something other than a string literal"
                        literal == null -> problems += "${file.name}:${line(text, match.range.first)} has a literal the check can't read"
                        Template.containsMatchIn(literal.groupValues[1]) -> problems += "${file.name}:${line(text, match.range.first)} uses a \$ template; use {0}, {1}… instead"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `every language translates every text and nothing else`() {
        assertTrue(sources.size > 1000, "Found only ${sources.size} texts; is the source scan broken?")
        val problems = mutableListOf<String>()
        Language.entries.filter { it != Language.English }.forEach { language ->
            val catalog = I18n.catalog(language)
            sources.forEach { (key, where) ->
                val translation = catalog[key]
                when {
                    translation == null -> problems += "${language.tag}: missing \"$key\" ($where)"
                    translation.isBlank() -> problems += "${language.tag}: empty \"$key\""
                    placeholders(translation) != placeholders(key) -> problems += "${language.tag}: placeholders differ in \"$key\" → \"$translation\""
                }
            }
            (catalog.keys - sources.keys).forEach { problems += "${language.tag}: \"$it\" isn't used any more" }
        }
        assertTrue(problems.isEmpty(), "${problems.size} problems; update core/src/main/resources/i18n:\n" + problems.joinToString("\n"))
    }

    private fun placeholders(text: String) = Placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun line(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

    /** A Kotlin string literal's text as the program sees it. */
    private fun unescape(literal: String): String =
        buildString {
            var i = 0
            while (i < literal.length) {
                val c = literal[i]
                if (c != '\\') {
                    append(c)
                    i++
                    continue
                }
                when (val next = literal[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    'b' -> append('\b')
                    'u' -> append(literal.substring(i + 2, i + 6).toInt(16).toChar()).also { i += 4 }
                    else -> append(next)
                }
                i += 2
            }
        }

    private companion object {
        val TrCall = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")
        val AnyTrCall = Regex("""(?<!fun )(?<![\w.])tr\(""")
        val Template = Regex("""(?<!\\)\$[\w{]""")
        val Placeholder = Regex("""\{\d+\}""")
    }
}
