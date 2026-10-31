package dev.datagrok.core.agent.cli

import dev.datagrok.core.agent.AgentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * How a preset's CLI says which models it offers: the [arguments] that print them, and how to read what it prints.
 * Each format was taken from the CLI's own output. A list that can't be read is empty, and the user types a model.
 */
enum class CliModelList(val arguments: List<String>) {
    /** No way to ask: the model is typed by hand. */
    None(emptyList()),
    /** Claude Code can't list models, but these aliases always resolve to the latest of each (see its `--help`). */
    ClaudeAliases(emptyList()),
    /** `codex debug models`: the model catalog as JSON; `visibility: "list"` marks the ones its own picker shows. */
    Codex(listOf("debug", "models")),
    /** `cursor-agent models`: `id - Name` lines under a heading. */
    Cursor(listOf("models")),
    /** `grok models`: `* id (default)` and `- id` lines. */
    Grok(listOf("models")),
    /** `opencode models`: one `provider/model` per line. */
    OpenCode(listOf("models")),
    /** `agy models`: `id<TAB>Name` lines after a progress line. */
    Antigravity(listOf("models"));

    /** Whether the list comes from running the CLI rather than being known up front. */
    val runs: Boolean
        get() = arguments.isNotEmpty()

    fun parse(output: String): List<AgentModel> {
        val lines = output.replace(Ansi, "").lines()
        val models =
            when (this) {
                None -> emptyList()
                ClaudeAliases -> listOf("fable", "opus", "sonnet", "haiku").map { AgentModel(it) }
                Codex -> codex(output)
                Cursor -> lines.mapNotNull { line -> CursorLine.matchEntire(line.trim())?.let { AgentModel(it.groupValues[1], it.groupValues[2].withoutMarker()) } }
                Grok -> lines.mapNotNull { line -> GrokLine.matchEntire(line)?.let { AgentModel(it.groupValues[1]) } }
                OpenCode -> lines.map { it.trim() }.filter { OpenCodeLine.matches(it) }.map { AgentModel(it) }
                Antigravity -> lines.mapNotNull { line -> line.trim().split('\t').takeIf { it.size >= 2 && it[0].isNotBlank() && ' ' !in it[0] }?.let { AgentModel(it[0], it[1].trim()) } }
            }
        return models.distinctBy { it.id }
    }

    private companion object {
        val Ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val CursorLine = Regex("""(\S+) - (.+)""")
        val GrokLine = Regex("""\s*[*-]\s+(\S+)(?:\s+\(.*\))?\s*""")
        val OpenCodeLine = Regex("""[^\s/]+/\S+""")
        val Marker = Regex("""\s*\((default|current)\)\s*$""", RegexOption.IGNORE_CASE)
        val json = Json { ignoreUnknownKeys = true }

        fun String.withoutMarker(): String = replace(Marker, "")

        fun codex(output: String): List<AgentModel> {
            val start = output.indexOf('{').takeIf { it >= 0 } ?: return emptyList()
            val catalog = runCatching { json.parseToJsonElement(output.substring(start)) as? JsonObject }.getOrNull() ?: return emptyList()
            val models = runCatching { catalog["models"]?.jsonArray }.getOrNull() ?: return emptyList()
            return models.mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (model[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val slug = text("slug") ?: return@mapNotNull null
                if (text("visibility").let { it != null && it != "list" }) return@mapNotNull null
                AgentModel(slug, text("display_name"))
            }
        }
    }
}
