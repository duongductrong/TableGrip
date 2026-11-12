package dev.tablegrip.core.session

import dev.tablegrip.core.TableGripPaths
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An editor tab as it is reopened on the next start. */
@Serializable
sealed interface SessionTab {
    val connectionId: String

    /** A SQL console: its text even if unsaved, the file it edits, and the caret. */
    @Serializable
    @SerialName("console")
    data class Console(
        override val connectionId: String,
        val text: String,
        val file: String? = null,
        val caret: Int = 0,
        /** The file's content when the console last saved or opened it, to tell whether [text] has unsaved edits. */
        val savedText: String? = null,
    ) : SessionTab

    /** A table viewer: which view was showing, and its filter as a WHERE clause. */
    @Serializable
    @SerialName("table")
    data class Table(
        override val connectionId: String,
        val catalog: String? = null,
        val schema: String? = null,
        val name: String,
        val structure: Boolean = false,
        val filter: String? = null,
    ) : SessionTab

    /** A record browser (an index, a wide-column table, a label): the container, its query and the view showing. */
    @Serializable
    @SerialName("records")
    data class Records(
        override val connectionId: String,
        val path: List<String>,
        val kind: String = "",
        val query: String = "",
        val definition: Boolean = false,
    ) : SessionTab

    /** A console of an engine browsed as records, with its text. */
    @Serializable
    @SerialName("recordConsole")
    data class RecordConsole(override val connectionId: String, val text: String) : SessionTab

    /** A document store's shell console: its database and script. */
    @Serializable
    @SerialName("shellConsole")
    data class ShellConsole(override val connectionId: String, val database: String, val text: String) : SessionTab
}

/** The workspace as the user left it. */
@Serializable
data class Session(
    val tabs: List<SessionTab> = emptyList(),
    /** Index into [tabs] of the tab in front. */
    val selected: Int = -1,
    /** The left tool window's name, or null when it was hidden. */
    val leftTool: String? = "Explorer",
    val connectionId: String? = null,
    val agentOpen: Boolean = false,
    /** The agent chat that was open, reopened from the chat history. */
    val agentChat: String? = null,
    /** Where the main window was, in dp; null until it has been saved once. */
    val window: WindowBounds? = null,
    /** Divider positions as fractions of their split: the left tool window's and the agent chat's. */
    val sidebarSplit: Float? = null,
    val agentSplit: Float? = null,
    /** When each connection was last opened, in epoch millis by connection id; the Welcome window lists them by it. */
    val recentConnections: Map<String, Long> = emptyMap(),
)

/** The main window's place and size in dp, or just [maximized]. */
@Serializable
data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean = false)

/** Reads and writes [Session]s; a missing or unreadable file is an empty session, never an error at startup. */
class SessionStore(private val file: Path = TableGripPaths.session) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun load(): Session = runCatching { if (file.exists()) json.decodeFromString<Session>(file.readText()) else null }.getOrNull() ?: Session()

    /** Writes to a temporary file first, so a crash mid-write leaves the previous session intact. */
    fun save(session: Session) {
        file.parent?.createDirectories()
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        temp.writeText(json.encodeToString(session))
        runCatching { temp.moveTo(file, ATOMIC_MOVE, REPLACE_EXISTING) }.getOrElse { temp.moveTo(file, REPLACE_EXISTING) }
    }
}
