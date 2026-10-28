package dev.datagrok.app.settings

import dev.datagrok.app.AppTheme
import dev.datagrok.core.settings.Settings

/** One editable setting, declared once here and rendered by the settings dialog. */
sealed interface SettingField {
    val label: String
    /** Shown under the control. */
    val hint: String?
}

/** Picks one of [options], e.g. from a combo box. */
class ChoiceField<T>(
    override val label: String,
    val options: List<T>,
    val optionLabel: (T) -> String,
    val get: (Settings) -> T,
    val set: (Settings, T) -> Settings,
    override val hint: String? = null,
) : SettingField

/** A whole number typed into a text field, checked against [range] when applied. */
class IntField(
    override val label: String,
    val range: IntRange,
    val get: (Settings) -> Int,
    val set: (Settings, Int) -> Settings,
    /** Shown after the field, e.g. "seconds". */
    val unit: String? = null,
    override val hint: String? = null,
) : SettingField {
    /** Why [text] isn't acceptable, or null if it is. */
    fun error(text: String): String? =
        if (text.trim().toIntOrNull()?.takeIf { it in range } == null) "Must be a number from ${range.first} to ${range.last}" else null
}

class SettingsSection(val title: String?, val fields: List<SettingField>)

/** A page of the settings dialog; [group] is the sidebar heading it sits under. */
class SettingsPage(
    val id: String,
    val group: String,
    val title: String,
    val description: String,
    val sections: List<SettingsSection>,
) {
    val fields: List<SettingField>
        get() = sections.flatMap { it.fields }

    /** Whether the sidebar search [query] finds this page, by its names or any of its settings. */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val texts = listOf(title, group, description) + sections.mapNotNull { it.title } + fields.flatMap { listOfNotNull(it.label, it.hint) }
        return texts.any { it.contains(needle, ignoreCase = true) }
    }
}

/** Every settings page, in sidebar order. Adding a setting means adding a field to a page here (and to [Settings]). */
object SettingsPages {
    val all: List<SettingsPage> =
        listOf(
            SettingsPage(
                id = "appearance",
                group = "Appearance & Behavior",
                title = "Appearance",
                description = "How Datagrok looks.",
                sections =
                    listOf(
                        SettingsSection(
                            "Theme",
                            listOf(
                                ChoiceField(
                                    "Theme",
                                    AppTheme.entries,
                                    AppTheme::label,
                                    get = { s -> AppTheme.entries.firstOrNull { it.name == s.theme } ?: AppTheme.System },
                                    set = { s, theme -> s.copy(theme = theme.name) },
                                    hint = "\"Sync with OS\" follows the system's light or dark mode.",
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "editor",
                group = "Editor",
                title = "SQL Editor",
                description = "Typing and reading SQL in consoles.",
                sections =
                    listOf(
                        SettingsSection(
                            "Font",
                            listOf(IntField("Font size", 9..32, Settings::editorFontSize, { s, v -> s.copy(editorFontSize = v) }, unit = "pt")),
                        ),
                    ),
            ),
            SettingsPage(
                id = "queries",
                group = "Database",
                title = "Query Execution",
                description = "Limits applied when running statements from a console.",
                sections =
                    listOf(
                        SettingsSection(
                            "Results",
                            listOf(
                                IntField(
                                    "Max result rows",
                                    10..1_000_000,
                                    Settings::maxResultRows,
                                    { s, v -> s.copy(maxResultRows = v) },
                                    unit = "rows",
                                    hint = "Rows beyond this are not fetched; the result says it was cut short.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Timeouts",
                            listOf(
                                IntField(
                                    "Query timeout",
                                    0..86_400,
                                    Settings::queryTimeoutSeconds,
                                    { s, v -> s.copy(queryTimeoutSeconds = v) },
                                    unit = "seconds",
                                    hint = "0 waits as long as the query takes.",
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "data-editor",
                group = "Database",
                title = "Data Editor",
                description = "Browsing and editing table data.",
                sections =
                    listOf(
                        SettingsSection(
                            "Paging",
                            listOf(
                                IntField(
                                    "Page size",
                                    10..10_000,
                                    Settings::pageSize,
                                    { s, v -> s.copy(pageSize = v) },
                                    unit = "rows",
                                    hint = "Rows loaded per page when opening a table.",
                                ),
                            ),
                        ),
                    ),
            ),
        )

    fun byId(id: String): SettingsPage? = all.firstOrNull { it.id == id }
}
