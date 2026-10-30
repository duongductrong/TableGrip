package dev.datagrok.app.settings

import androidx.compose.runtime.Composable
import dev.datagrok.app.AppState
import dev.datagrok.app.AppTheme
import dev.datagrok.core.agent.provider.AgentMode
import dev.datagrok.core.agent.provider.ProviderProtocol
import dev.datagrok.core.connection.AiAccess
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.drivers.DriverLibrary
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

/** Free text, applied as typed; [placeholder] shows when it's empty. */
class StringField(
    override val label: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
    val placeholder: String = "",
    override val hint: String? = null,
) : SettingField

class SettingsSection(
    val title: String?,
    val fields: List<SettingField>,
    /** Shown under [fields], for what isn't a single setting, e.g. managing the installed drivers. */
    val panel: (@Composable (AppState) -> Unit)? = null,
    /** More words the sidebar search finds this section by, e.g. what [panel] lists. */
    val keywords: List<String> = emptyList(),
)

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
        val texts = listOf(title, group, description) + sections.mapNotNull { it.title } + sections.flatMap { it.keywords } + fields.flatMap { listOfNotNull(it.label, it.hint) }
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
                id = "drivers",
                group = "Database",
                title = "Drivers",
                description = "Where JDBC drivers come from, and the ones on this machine. None ships with Datagrok; each is downloaded the first time it's needed.",
                sections =
                    listOf(
                        SettingsSection(
                            "Downloads",
                            listOf(
                                ChoiceField(
                                    "Downloading drivers",
                                    listOf(false, true),
                                    { if (it) "Automatic for permissive licenses" else "Ask every time" },
                                    Settings::autoDownloadDrivers,
                                    { s, v -> s.copy(autoDownloadDrivers = v) },
                                    hint = "LGPL drivers (MariaDB) always ask. GPL and vendor drivers (MySQL Connector/J, Oracle) are never downloaded: you choose their jars.",
                                ),
                                StringField(
                                    "Maven repository",
                                    Settings::driverRepository,
                                    { s, v -> s.copy(driverRepository = v.trim()) },
                                    placeholder = DriverLibrary.MavenCentral,
                                    hint = "Leave empty for Maven Central, or use your company's mirror. Downloads are checked against Datagrok's checksums either way.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Installed",
                            emptyList(),
                            panel = { app -> DriversPanel(app, installed = true) },
                            keywords = DriverId.entries.map { it.displayName },
                        ),
                        SettingsSection("Available", emptyList(), panel = { app -> DriversPanel(app, installed = false) }),
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
            SettingsPage(
                id = "agent",
                group = "Tools",
                title = "Agent",
                description = "Where the agent chat gets its answers. Changing these sends nothing: the agent is only asked when you send a message.",
                sections =
                    listOf(
                        SettingsSection(
                            "Source",
                            listOf(
                                ChoiceField(
                                    "Agents from",
                                    AgentMode.entries,
                                    AgentMode::label,
                                    get = { s -> AgentMode.of(s.agentMode) },
                                    set = { s, mode -> s.copy(agentMode = mode.name) },
                                    hint = "Command-line agents run the CLIs installed here (Claude Code, Codex, Gemini CLI…) on their own accounts. An API provider is called directly with your key.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "API provider",
                            listOf(
                                ChoiceField(
                                    "Protocol",
                                    ProviderProtocol.entries,
                                    ProviderProtocol::title,
                                    get = { s -> ProviderProtocol.of(s.agentProvider) },
                                    set = { s, protocol -> s.copy(agentProvider = protocol.name) },
                                    hint = "OpenAI-compatible also covers OpenRouter, LiteLLM, Ollama, LM Studio and vLLM. Used when agents come from an API provider.",
                                ),
                                StringField(
                                    "Base URL",
                                    Settings::agentBaseUrl,
                                    { s, v -> s.copy(agentBaseUrl = v.trim()) },
                                    placeholder = "Protocol default",
                                    hint = "Empty uses ${ProviderProtocol.entries.joinToString(" or ") { it.defaultBaseUrl }}. For a local server, e.g. http://localhost:11434/v1.",
                                ),
                                StringField(
                                    "Model",
                                    Settings::agentModel,
                                    { s, v -> s.copy(agentModel = v.trim()) },
                                    placeholder = "Protocol default",
                                    hint = "Empty uses ${ProviderProtocol.entries.joinToString(" or ") { it.defaultModel }}.",
                                ),
                            ),
                            panel = { app -> ApiKeyPanel(app) },
                            keywords = listOf("API key", "token", "Anthropic", "OpenAI", "Ollama"),
                        ),
                        SettingsSection(
                            "Data shared with agents",
                            listOf(
                                ChoiceField(
                                    "Connections share",
                                    AiAccess.entries,
                                    AiAccess::label,
                                    get = { s -> AiAccess.of(s.aiAccess) },
                                    set = { s, access -> s.copy(aiAccess = access.name) },
                                    hint =
                                        "What a connection may send to the agent, unless its own Options say otherwise. Schema only sends names, types and your SQL, " +
                                            "never values from the data. Production connections never share data unless their own Options allow it.",
                                ),
                            ),
                            keywords = listOf("privacy", "AI", "sample data", "sensitive"),
                        ),
                    ),
            ),
        )

    fun byId(id: String): SettingsPage? = all.firstOrNull { it.id == id }
}
