package dev.datagrok.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppTheme
import dev.datagrok.app.ui.parseHexColor
import dev.datagrok.core.DatagrokPaths
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.ThemeSelection
import dev.datagrok.core.theme.ThemeFamily
import dev.datagrok.core.theme.ThemeFileError
import dev.datagrok.core.theme.ThemeFiles
import dev.datagrok.core.theme.ThemeJson
import dev.datagrok.core.theme.ThemeOverride
import dev.datagrok.core.theme.ThemeSpec
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

/** A theme variant and the family it came from. */
data class ThemeEntry(val spec: ThemeSpec, val family: ThemeFamily, val builtIn: Boolean)

/**
 * Every theme the app knows: the built-in families shipped as resources under `/themes`, then the user's own files in
 * `~/.datagrok/themes`. A user theme with a built-in theme's name replaces it, so a copied and edited built-in file
 * wins. App-wide rather than per window because settings pages and plain functions read it outside composition.
 */
object ThemeRegistry {
    private val log = LoggerFactory.getLogger(ThemeRegistry::class.java)

    /** Resource names of the built-in families, in the order the pickers list them. */
    private val BuiltInFiles = listOf("graphite", "vscode-modern", "dracula", "one", "catppuccin", "contrast")

    val builtIn: List<ThemeFamily> by lazy {
        BuiltInFiles.mapNotNull { name ->
            val text = ThemeRegistry::class.java.getResourceAsStream("/themes/$name.json")?.use { it.readBytes().decodeToString() }
            if (text == null) {
                log.error("Built-in theme {} is missing from the resources", name)
                null
            } else ThemeJson.parse(text)
        }
    }

    /** The user's theme files as last read; reading them again updates every window. */
    var userFiles by mutableStateOf(ThemeFiles(emptyList()))
        private set

    val errors: List<ThemeFileError>
        get() = userFiles.errors

    /** Every variant by name, user files over built-ins. */
    val entries: List<ThemeEntry>
        get() {
            val user = userFiles.families.flatMap { f -> f.themes.map { ThemeEntry(it, f, builtIn = false) } }
            val userNames = user.mapTo(HashSet()) { it.spec.name }
            val builtIns = builtIn.flatMap { f -> f.themes.map { ThemeEntry(it, f, builtIn = true) } }.filter { it.spec.name !in userNames }
            return builtIns + user
        }

    /** Names of the variants for [dark] or light mode, for the theme pickers. */
    fun names(dark: Boolean): List<String> = entries.filter { it.spec.isDark == dark }.map { it.spec.name }

    fun entry(name: String): ThemeEntry? = entries.firstOrNull { it.spec.name == name }

    /** Reads `~/.datagrok/themes` (or [dir]) again; bad files are listed in [errors], never fatal. */
    fun reloadUserThemes(dir: Path = DatagrokPaths.themes) {
        userFiles = runCatching { ThemeJson.loadDirectory(dir) }.getOrElse {
            log.warn("Couldn't read themes from {}", dir, it)
            ThemeFiles(emptyList(), listOf(ThemeFileError(dir, it.message ?: it.toString())))
        }
    }

    /** The folder for the user's themes, created so it can be opened. */
    fun userThemesFolder(): Path = DatagrokPaths.themes.also { it.createDirectories() }

    /**
     * Writes the family of the theme named [name] into the themes folder for editing, unless a file for it is already
     * there, and returns the file. Its themes keep their names, so the edited copy replaces the built-in one.
     */
    fun copyToUserFolder(name: String): Path? {
        val family = entry(name)?.family ?: return null
        val file = userThemesFolder().resolve(family.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".json")
        if (!file.exists()) file.writeText(ThemeJson.encode(family) + "\n")
        reloadUserThemes()
        return file
    }

    private fun graphite(dark: Boolean): ThemeSpec? =
        entries.firstOrNull { it.spec.name == if (dark) ThemeSelection.DefaultDarkTheme else ThemeSelection.DefaultLightTheme }?.spec

    private val cache = object : LinkedHashMap<Triple<ThemeSpec, String, ThemeSpec?>, ColorTheme>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<ThemeSpec, String, ThemeSpec?>, ColorTheme>?): Boolean = size > 32
    }

    /**
     * The theme named [name] with [override] laid over it, resolved. An unknown name falls back to Graphite of the
     * mode, so a typo in settings.json or a deleted theme file never leaves the app unstyled.
     */
    fun resolve(name: String, dark: Boolean, override: ThemeOverride? = null): ColorTheme {
        val entry = entry(name)?.takeIf { it.spec.isDark == dark } ?: entry(if (dark) ThemeSelection.DefaultDarkTheme else ThemeSelection.DefaultLightTheme)
        val spec = (entry?.spec ?: ThemeSpec(name, if (dark) "dark" else "light")).with(override)
        val base = graphite(spec.isDark).takeIf { it?.name != spec.name }
        val key = Triple(spec, entry?.family?.name ?: "", base)
        return synchronized(cache) { cache.getOrPut(key) { resolveTheme(spec, key.second, base) } }
    }
}

/** The theme this mode uses, with the user's overrides for it and their accent colour. */
fun Settings.colorTheme(dark: Boolean): ColorTheme {
    val name = if (dark) theme.dark else theme.light
    val accent = accentColor.takeIf { it.isNotBlank() }?.let(::parseHexColor)
    var override = themeOverrides[name]
    if (accent != null) {
        val colors = (override?.colors.orEmpty()) + mapOf("accent" to accent.toThemeHex(), "accent.foreground" to readableOn(accent).toThemeHex())
        override = (override ?: ThemeOverride()).copy(colors = colors)
    }
    return ThemeRegistry.resolve(name, dark, override)
}

/** Whether the app is in dark mode under these settings, following the OS when they say `System`. */
fun Settings.isDarkMode(): Boolean = AppTheme.of(theme.mode).isDark()
