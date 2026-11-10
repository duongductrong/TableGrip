package dev.tablegrip.app.agent

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.search.fuzzyScore
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.assist.SchemaDigest
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.metadata.TableRef
import org.slf4j.LoggerFactory

/** Something `@` can mention: how the list shows it, and the mention picking it makes. */
class MentionCandidate(val kind: Kind, val title: String, val detail: String?, val mention: Mention) {
    /** In the order the list shows them. */
    enum class Kind(val label: String) {
        Tab(tr("Open tabs")),
        Table(tr("Tables")),
        Column(tr("Columns")),
        Query(tr("Saved queries")),
    }
}

/**
 * Where mentions come from: what `@` finds by name, and what a table mention says once its message is sent. Everything is
 * offered only as far as its connection is shared with agents.
 */
class MentionSources(private val app: AppState) {
    /**
     * Candidates for [query], best first and grouped by kind. Reads only what is already cached, so it never blocks; the
     * current connection's tables load in the background and show up on a later call. `table.` lists that table's columns.
     */
    fun search(query: String, limit: Int = 40): List<MentionCandidate> {
        val candidates = buildList {
            addAll(tabs())
            addAll(tables())
            if ('.' in query) addAll(columns(query.substringBefore('.')))
            addAll(queries())
        }
        val needle = if ('.' in query) query.substringAfter('.') else query
        return candidates
            .mapNotNull { candidate ->
                val text = if (candidate.kind == MentionCandidate.Kind.Column) candidate.title.substringAfter('.') else candidate.title
                fuzzyScore(text, needle)?.let { candidate to it }
            }
            // Columns only when asked for with `table.`; a blank query skips the (possibly long) table list.
            .filter { (candidate, _) -> if ('.' in query) candidate.kind == MentionCandidate.Kind.Column else query.isNotBlank() || candidate.kind != MentionCandidate.Kind.Table }
            .sortedWith(compareBy<Pair<MentionCandidate, Int>> { it.first.kind.ordinal }.thenByDescending { it.second })
            .take(limit)
            .map { it.first }
    }

    /** [mention] as it should be sent: a table gets its structure read now. Others are already complete. */
    suspend fun complete(mention: Mention): Mention {
        if (mention !is Mention.Table || mention.details != null) return mention
        val config = app.connections.byId(mention.connectionId)?.takeIf { app.aiAccess(it).sharesSchema } ?: return mention
        return runCatching {
            val database = app.databases.database(config)
            val ref =
                app.schemaCache(config).allTables().map { it.first }.firstOrNull { it.name == mention.name && it.namespace.displayName == mention.namespace }
                    ?: database.namespaces().firstOrNull { it.displayName == mention.namespace }?.let { TableRef(it, mention.name) }
                    ?: return mention
            mention.copy(details = SchemaDigest.table(database.tableDetails(ref)))
        }.onFailure { log.debug("No structure for a mentioned table", it) }.getOrDefault(mention)
    }

    private fun shared(config: ConnectionConfig?): Boolean = app.aiAccess(config).sharesSchema

    private fun tabs(): List<MentionCandidate> =
        app.workspace.tabs.mapNotNull { tab ->
            when (tab) {
                is ConsoleTab -> tab.whole()?.let { MentionCandidate(MentionCandidate.Kind.Tab, tab.title, tab.config.name, it) }
                is TableDataTab ->
                    if (!shared(tab.config)) null
                    else MentionCandidate(MentionCandidate.Kind.Tab, tab.title, tab.config.name, table(tab.config, tab.table))
                else -> null
            }
        }

    private fun tables(): List<MentionCandidate> {
        val config = relational() ?: return emptyList()
        return app.schemaCache(config).allTables().map { (ref, isView) ->
            MentionCandidate(MentionCandidate.Kind.Table, ref.name, "${if (isView) tr("view") else tr("table")} · ${ref.namespace.displayName}", table(config, ref))
        }
    }

    private fun columns(table: String): List<MentionCandidate> {
        val config = relational() ?: return emptyList()
        val engine = config.type.displayName
        return app.schemaCache(config).columns(null, table).orEmpty().map { column ->
            MentionCandidate(
                MentionCandidate.Kind.Column,
                "$table.${column.name}",
                column.type.lowercase(),
                Mention.Column(config.name, engine, table, column.name, column.type, column.primaryKey),
            )
        }
    }

    private fun queries(): List<MentionCandidate> =
        app.savedQueries.queries.mapNotNull { query ->
            val config = query.connection?.let { name -> app.connections.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            if (!shared(config)) return@mapNotNull null
            MentionCandidate(MentionCandidate.Kind.Query, query.name, query.folder.ifEmpty { query.connection }, Mention.Query(query.name, query.sql))
        }

    /**
     * The current connection, when it has tables to mention and shares them. Reads its cache's version, so a list built in
     * composition updates as tables and columns load.
     */
    private fun relational(): ConnectionConfig? =
        app.currentConnection?.takeIf { it.type.paradigm == Paradigm.Relational && shared(it) }?.also { app.schemaCache(it).version }

    private fun table(config: ConnectionConfig, ref: TableRef) =
        Mention.Table(config.id, config.name, config.type.displayName, ref.namespace.displayName, ref.name)

    companion object {
        private val log = LoggerFactory.getLogger(MentionSources::class.java)
    }
}
