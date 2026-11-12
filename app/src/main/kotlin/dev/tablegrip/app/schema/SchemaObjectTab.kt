package dev.tablegrip.app.schema

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.metadata.SchemaObjectDefinition
import dev.tablegrip.core.metadata.SchemaObjectKind
import dev.tablegrip.core.metadata.SchemaObjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A function, procedure or sequence, read-only: its properties and its source. */
class SchemaObjectTab(private val app: AppState, val config: ConnectionConfig, val ref: SchemaObjectRef) : WorkspaceTab {
    override val connectionId: String = config.id
    override val title: String = ref.name
    val dialect: SqlDialect = SqlDialect.of(config.type)

    var definition by mutableStateOf<SchemaObjectDefinition?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set
    val editorFontSize: Int
        get() = app.settings.editorFontSize
    val connectionStatus: ConnectionStatus
        get() = app.databases.status(config.id)
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        job =
            app.scope.launch {
                loading = true
                try {
                    definition = app.databases.database(config).schemaObjectDefinition(ref)
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr("Couldn't read {0}: {1}", ref.name, e.message ?: e)
                } finally {
                    loading = false
                }
            }
    }

    fun copySource() {
        val source = definition?.source ?: return
        copyToClipboard(source)
        app.status = tr("Copied the source of {0}", ref.name)
    }

    /** The source as a statement that recreates the object over the existing one where the engine can. */
    fun openInConsole() {
        val sql = definition?.replaceSql ?: return
        app.workspace.openConsole(config, sql)
    }

    /** The qualified name, with a function's argument types where they tell overloads apart. */
    val qualifiedName: String
        get() {
            val name = listOfNotNull(ref.namespace.catalog, ref.namespace.schema?.ifEmpty { null }, ref.name).joinToString(".") { dialect.quoteIdentifier(it) }
            return if (ref.kind != SchemaObjectKind.Sequence && ref.id != ref.name) "$name(${ref.id})" else name
        }

    override fun dispose() {
        job?.cancel()
    }
}
