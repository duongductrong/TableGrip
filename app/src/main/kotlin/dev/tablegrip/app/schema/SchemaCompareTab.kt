package dev.tablegrip.app.schema

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.ddl.DdlGenerator
import dev.tablegrip.core.ddl.DdlScript
import dev.tablegrip.core.ddl.TableDesign
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.schema.SchemaComparison
import dev.tablegrip.core.schema.SchemaDiff
import dev.tablegrip.core.schema.TableDiff
import dev.tablegrip.core.schema.TableDiffKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One side of a comparison: a connection and one of its namespaces, loaded on demand. */
class CompareSide(private val app: AppState, config: ConnectionConfig?, namespace: Namespace?) {
    var config by mutableStateOf(config)
        private set
    var namespace by mutableStateOf(namespace)
    var namespaces by mutableStateOf<List<Namespace>>(emptyList())
        private set
    var loadingNamespaces by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** Tables read so far while comparing, and how many there are. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)

    init {
        config?.let { pick(it, namespace) }
    }

    /** Switches to [config], keeping [preferred] (or the namespace of the same name) when it has one. */
    fun pick(config: ConnectionConfig, preferred: Namespace? = null) {
        val previous = namespace
        this.config = config
        namespaces = emptyList()
        namespace = preferred
        error = null
        loadingNamespaces = true
        app.scope.launch {
            try {
                val database = app.databases.database(config)
                val names = database.namespaces()
                namespaces = names
                namespace = preferred?.takeIf { it in names }
                    ?: names.firstOrNull { it.displayName == previous?.displayName }
                    ?: names.firstOrNull { it.displayName == SqlDialect.of(config.type).defaultNamespace(names.map { n -> n.displayName }, config.database) }
                    ?: names.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loadingNamespaces = false
            }
        }
    }

    val label: String
        get() = listOfNotNull(config?.name, namespace?.displayName?.takeIf { namespaces.size > 1 || it != "main" }).joinToString(" · ")
}

/**
 * Compares two schemas — often the same database on two servers, e.g. staging and production — and writes the script
 * that brings the target in line with the source, for the differences the user picks.
 */
class SchemaCompareTab(private val app: AppState, sourceConfig: ConnectionConfig?, sourceNamespace: Namespace?) : WorkspaceTab {
    val source = CompareSide(app, sourceConfig, sourceNamespace)
    val target = CompareSide(app, null, null)

    override val connectionId: String
        get() = (target.config ?: source.config)?.id.orEmpty()
    override val title: String = tr("Schema Compare")

    var comparison by mutableStateOf<SchemaComparison?>(null)
        private set
    /** Keys of the selected differences (see [SchemaComparison.defaultSelection]). */
    val selected = mutableStateMapOf<String, Boolean>()
    /** Tables whose differences are listed under them. */
    val expanded = mutableStateListOf<String>()
    var comparing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    internal var pendingRun by mutableStateOf<ScriptRun?>(null)
    /** Sides as they were when compared: the script is for this target even if the pickers changed since. */
    private var comparedTarget: Pair<ConnectionConfig, Namespace>? = null
    private var job: Job? = null

    val candidates: List<ConnectionConfig>
        get() = app.connections.profiles.filter { it.type.paradigm == Paradigm.Relational }

    val canCompare: Boolean
        get() = !comparing && source.config != null && source.namespace != null && target.config != null && target.namespace != null &&
            !(source.config?.id == target.config?.id && source.namespace == target.namespace)

    val isStale: Boolean
        get() = comparison != null && comparedTarget != target.config?.let { c -> target.namespace?.let { c to it } }

    val editorFontSize: Int
        get() = app.settings.editorFontSize

    fun swap() {
        val (sc, sn) = source.config to source.namespace
        val (tc, tn) = target.config to target.namespace
        if (tc != null) source.pick(tc, tn)
        if (sc != null) target.pick(sc, sn)
        comparison = null
    }

    fun compare() {
        if (!canCompare) return
        val sourceConfig = source.config!!
        val targetConfig = target.config!!
        val sourceNamespace = source.namespace!!
        val targetNamespace = target.namespace!!
        job?.cancel()
        comparing = true
        error = null
        job =
            app.scope.launch {
                try {
                    val (sourceDesigns, targetDesigns) =
                        coroutineScope {
                            val left = async { read(source, sourceConfig, sourceNamespace) }
                            val right = async { read(target, targetConfig, targetNamespace) }
                            left.await() to right.await()
                        }
                    val result = SchemaDiff.compare(sourceDesigns, targetDesigns, targetNamespace)
                    comparison = result
                    comparedTarget = targetConfig to targetNamespace
                    selected.clear()
                    result.defaultSelection().forEach { selected[it] = true }
                    expanded.clear()
                    expanded.addAll(result.diffs.filter { it.kind == TableDiffKind.Changed }.take(8).map { it.id })
                    app.status = if (result.isEmpty) tr("Schemas are identical") else tr("{0} table(s) differ", result.diffs.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr("Couldn't compare: {0}", e.message ?: e)
                } finally {
                    comparing = false
                    source.progress = null
                    target.progress = null
                }
            }
    }

    private suspend fun read(side: CompareSide, config: ConnectionConfig, namespace: Namespace): List<TableDesign> {
        val database = app.databases.database(config)
        val snapshot = database.schemaSnapshot(namespace) { done, total -> side.progress = done to total }
        return snapshot.tables.map { TableDesign.from(it, database.dialect) }
    }

    fun cancel() {
        job?.cancel()
        comparing = false
    }

    fun isSelected(key: String) = selected[key] == true

    fun toggle(key: String, on: Boolean) {
        selected[key] = on
    }

    /** Selects or clears every difference of [diff] at once. */
    fun toggleTable(diff: TableDiff, on: Boolean) {
        if (diff.kind == TableDiffKind.Changed) diff.items.forEach { selected[diff.itemKey(it)] = on } else selected[diff.id] = on
    }

    fun selectedCount(diff: TableDiff): Int =
        if (diff.kind == TableDiffKind.Changed) diff.items.count { isSelected(diff.itemKey(it)) } else if (isSelected(diff.id)) 1 else 0

    fun selectAll(on: Boolean, includeDrops: Boolean) {
        val comparison = comparison ?: return
        for (diff in comparison.diffs) {
            when (diff.kind) {
                TableDiffKind.Removed -> selected[diff.id] = on && includeDrops
                TableDiffKind.Added -> selected[diff.id] = on
                TableDiffKind.Changed -> diff.items.forEach { selected[diff.itemKey(it)] = on && (includeDrops || !it.destructive) }
            }
        }
    }

    fun toggleExpanded(diff: TableDiff) {
        if (diff.id in expanded) expanded.remove(diff.id) else expanded.add(diff.id)
    }

    /** The migration for what's selected, in the target's dialect. */
    fun script(): DdlScript {
        val comparison = comparison ?: return DdlScript(emptyList(), emptyList(), false)
        val targetConfig = comparedTarget?.first ?: return DdlScript(emptyList(), emptyList(), false)
        val keys = selected.filterValues { it }.keys
        val script = SchemaDiff.migration(comparison, keys, DdlGenerator(SqlDialect.of(targetConfig.type)))
        val crossEngine = source.config?.let { SqlDialect.of(it.type)::class != SqlDialect.of(targetConfig.type)::class } == true
        return if (crossEngine && !script.isEmpty) {
            script.copy(warnings = listOf(tr("Source and target are different engines: column types are copied as the source spells them. Check them before running.")) + script.warnings)
        } else {
            script
        }
    }

    val targetDialect: SqlDialect?
        get() = (comparedTarget?.first ?: target.config)?.let { SqlDialect.of(it.type) }

    fun requestApply() {
        val (config, namespace) = comparedTarget ?: return
        val script = script()
        if (script.isEmpty) return
        val tables = comparison?.diffs?.mapNotNull { it.target?.table }.orEmpty()
        pendingRun =
            ScriptRun(tr("Migrate {0}", target.label), config, script, runLabel = tr("Run Migration")) {
                app.status = tr("Migrated {0}: {1} statement(s)", config.name, script.statements.size)
                app.schemaChanged(config, namespace, tables)
                compare()
            }
    }

    fun openInConsole() {
        val config = comparedTarget?.first ?: return
        app.workspace.openConsole(config, script().text)
    }

    fun saveScript(file: File) {
        runCatching { file.writeText(script().text + "\n") }
            .onSuccess { app.status = tr("Saved the migration to {0}", file.path) }
            .onFailure { error = tr("Couldn't save {0}: {1}", file.name, it.message) }
    }

    override fun dispose() {
        job?.cancel()
    }
}
