package dev.tablegrip.core.i18n

import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A language the interface is translated into. [tag] is what `settings.json` stores and names the catalog file
 * (`i18n/<tag>.json`); [nativeName] is how the language picker shows it, in that language.
 */
enum class Language(val tag: String, val nativeName: String, val locale: Locale) {
    English("en", "English", Locale.ENGLISH),
    Vietnamese("vi", "Tiếng Việt", Locale.forLanguageTag("vi-VN")),
    ChineseSimplified("zh-CN", "简体中文", Locale.SIMPLIFIED_CHINESE),
    ChineseTraditional("zh-TW", "繁體中文", Locale.TRADITIONAL_CHINESE),
    ;

    companion object {
        /** What `settings.json` stores to follow the system's language. */
        const val System = "system"

        fun ofTag(tag: String): Language? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /**
         * The language for the system's [locale]: Chinese written in traditional characters (`zh-Hant`, Taiwan, Hong
         * Kong, Macau) is [ChineseTraditional], other Chinese [ChineseSimplified]; languages without a translation are
         * [English].
         */
        fun ofLocale(locale: Locale): Language =
            when (locale.language) {
                "vi" -> Vietnamese
                "zh" ->
                    if (locale.script.equals("Hant", ignoreCase = true) || locale.country in setOf("TW", "HK", "MO")) ChineseTraditional
                    else ChineseSimplified
                else -> English
            }

        /** The language a `settings.json` value picks: a [tag], or [System] (and anything unknown) for the system's. */
        fun resolve(setting: String, system: Locale = Locale.getDefault()): Language = ofTag(setting) ?: ofLocale(system)
    }
}

/**
 * The interface's translations. English text in the code is the source and the key: [tr] looks it up in the catalog of
 * the [current] language, `i18n/<tag>.json` on the class path, and falls back to the English text when the catalog has
 * none. The language is chosen once at startup ([install]), before any text is built, so what objects build at their
 * first use is already in that language; changing it takes a restart.
 */
object I18n {
    @Volatile
    var current: Language = Language.English
        private set

    @Volatile
    private var catalog: Map<String, String> = emptyMap()

    /** Switches to [language]; meant for startup, before the first [tr]. */
    fun install(language: Language) {
        catalog = catalog(language)
        current = language
    }

    /** The locale dates and numbers shown in the interface are formatted for. */
    val locale: Locale
        get() = current.locale

    fun translate(source: String): String = catalog[source] ?: source

    /**
     * A formatter for [pattern], written the English way (`MMM d, yyyy`), that puts the date in the current language's
     * order with its month and day names: `31 thg 10, 2026` in Vietnamese, `2026年10月31日` in Chinese.
     */
    fun dates(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(DatePatterns[current]?.get(pattern) ?: pattern, locale)

    /** [language]'s translations by English source text; empty for English and for a missing file. */
    fun catalog(language: Language): Map<String, String> {
        if (language == Language.English) return emptyMap()
        val text = I18n::class.java.getResourceAsStream("/i18n/${language.tag}.json")?.use { it.readBytes().decodeToString() } ?: return emptyMap()
        return (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, value) -> (value as JsonPrimitive).content }
    }

    /** [template] with each `{n}` replaced by `args[n]`; braces that aren't such a placeholder stay as they are. */
    fun format(template: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return template
        return Placeholder.replace(template) { match ->
            val index = match.groupValues[1].toInt()
            if (index < args.size) args[index].toString() else match.value
        }
    }

    private val Placeholder = Regex("""\{(\d+)\}""")

    /** How each language writes the English date patterns the interface uses; patterns not listed are used as they are. */
    private val DatePatterns: Map<Language, Map<String, String>> =
        mapOf(
            Language.Vietnamese to
                mapOf(
                    "MMM d" to "d MMM",
                    "MMM d, yyyy" to "d MMM, yyyy",
                    "MMM d HH:mm" to "d MMM HH:mm",
                    "MMM d, HH:mm" to "d MMM, HH:mm",
                    "EEE d MMM yyyy" to "EEE, d MMM yyyy",
                    "EEE d MMM yyyy, HH:mm" to "EEE, d MMM yyyy, HH:mm",
                    "EEE d MMM yyyy, HH:mm:ss" to "EEE, d MMM yyyy, HH:mm:ss",
                ),
            Language.ChineseSimplified to ChineseDatePatterns,
            Language.ChineseTraditional to ChineseDatePatterns,
        )

    private val ChineseDatePatterns: Map<String, String>
        get() =
            mapOf(
                "MMM" to "M月",
                "MMM d" to "M月d日",
                "MMM yyyy" to "yyyy年M月",
                "MMMM yyyy" to "yyyy年M月",
                "MMM d, yyyy" to "yyyy年M月d日",
                "MMM d HH:mm" to "M月d日 HH:mm",
                "MMM d, HH:mm" to "M月d日 HH:mm",
                "EEE d MMM yyyy" to "yyyy年M月d日 EEE",
                "EEE d MMM yyyy, HH:mm" to "yyyy年M月d日 EEE HH:mm",
                "EEE d MMM yyyy, HH:mm:ss" to "yyyy年M月d日 EEE HH:mm:ss",
            )
}

/**
 * [source], an English text from the interface, in the current language, with `{0}`, `{1}`… replaced by [args].
 * Call it with a string literal so the catalog checks can find every text: `tr("Deleted {0}", name)`.
 */
fun tr(source: String, vararg args: Any?): String = I18n.format(I18n.translate(source), args)
