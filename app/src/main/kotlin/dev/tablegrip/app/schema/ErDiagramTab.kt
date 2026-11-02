package dev.tablegrip.app.schema

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.tablegrip.app.AppState
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.schema.ErBox
import dev.tablegrip.core.schema.ErEdge
import dev.tablegrip.core.schema.ErLayout
import dev.tablegrip.core.schema.Mermaid
import dev.tablegrip.core.schema.SchemaSnapshot
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * An ER diagram of a namespace, or of the tables around [focus]. Boxes can be dragged; the arrangement lasts while the
 * tab is open, and Auto-Arrange starts over.
 */
class ErDiagramTab(private val app: AppState, val config: ConnectionConfig, val namespace: Namespace, val focus: TableRef?) : WorkspaceTab {
    override val connectionId: String = config.id
    override val title: String = "ER: ${focus?.name ?: namespace.displayName}"
    val dialect: SqlDialect = SqlDialect.of(config.type)

    var snapshot by mutableStateOf<SchemaSnapshot?>(null)
        private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set

    /** Everything in the namespace, or just [focus] and the tables it's linked to. */
    var showAll by mutableStateOf(focus == null)
        private set

    var boxes by mutableStateOf<Map<TableRef, ErBox>>(emptyMap())
        private set
    /** Where the user dragged boxes, by how much. */
    private val moved = mutableStateMapOf<TableRef, Offset>()

    var zoom by mutableStateOf(1f)
        private set
    /** Screen pixels per dp: diagram units are dp, so boxes and text keep their size on high-density screens. */
    var density by mutableStateOf(1f)

    /** Screen pixels per diagram unit. */
    val scale: Float
        get() = zoom * density

    /** Box widths from the canvas's text measurement; an estimate until the canvas provides it. */
    private var measure: (TableDetails) -> Float = ::boxWidth
    var pan by mutableStateOf(Offset(24f, 24f))
    var viewport by mutableStateOf(Size.Zero)
    var selected by mutableStateOf<TableRef?>(focus)
    val search = TextFieldState()
    private var job: Job? = null
    /** Fit the diagram to the viewport once it's both laid out and measured. */
    private var fitPending = true

    init {
        load()
    }

    val tables: List<TableDetails>
        get() {
            val all = snapshot?.tables.orEmpty()
            return if (showAll || focus == null) all else ErLayout.neighbourhood(all, focus)
        }

    val edges: List<ErEdge>
        get() = ErLayout.edges(tables)

    fun load() {
        job?.cancel()
        loading = true
        error = null
        job =
            app.scope.launch {
                try {
                    val database = app.databases.database(config)
                    snapshot = database.schemaSnapshot(namespace) { done, total -> progress = done to total }
                    arrange()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Couldn't read ${namespace.displayName}: ${e.message ?: e}"
                } finally {
                    loading = false
                    progress = null
                }
            }
    }

    /** Lays the boxes out from scratch, forgetting where they were dragged. */
    fun arrange() {
        moved.clear()
        boxes = ErLayout.layout(tables, measure)
        fitPending = true
        fitIfPending()
    }

    fun showAll(all: Boolean) {
        if (showAll == all) return
        showAll = all
        arrange()
    }

    /** Where [table]'s box is now, with any dragging applied. */
    fun box(table: TableRef): ErBox? {
        val base = boxes[table] ?: return null
        val offset = moved[table] ?: return base
        return base.copy(x = base.x + offset.x, y = base.y + offset.y)
    }

    fun move(table: TableRef, by: Offset) {
        moved[table] = (moved[table] ?: Offset.Zero) + by
    }

    /** The table under a point in diagram units, the topmost (last drawn) first. */
    fun hit(point: Offset): TableRef? = tables.map { it.table }.lastOrNull { box(it)?.contains(point.x, point.y) == true }

    fun toDiagram(screen: Offset): Offset = (screen - pan) / scale

    /** Lays out with real text widths from now on; re-arranges if nothing was dragged yet. */
    fun useMeasure(measure: (TableDetails) -> Float) {
        this.measure = measure
        if (boxes.isNotEmpty() && moved.isEmpty()) arrange()
    }

    /** Zooms by [factor] keeping the diagram point under [anchor] (screen coordinates) in place. */
    fun zoomBy(factor: Float, anchor: Offset = Offset(viewport.width / 2, viewport.height / 2)) {
        val next = (zoom * factor).coerceIn(MinZoom, MaxZoom)
        val point = toDiagram(anchor)
        zoom = next
        pan = anchor - point * scale
    }

    fun resetZoom() = zoomBy(1f / zoom)

    /** Scales and centers so every box is visible. */
    fun fit() {
        val all = tables.mapNotNull { box(it.table) }
        if (all.isEmpty() || viewport.width <= 0f) return
        val left = all.minOf { it.x }
        val top = all.minOf { it.y }
        val width = all.maxOf { it.right } - left
        val height = all.maxOf { it.bottom } - top
        val margin = 32f
        zoom = min((viewport.width - margin * 2) / (width * density), (viewport.height - margin * 2) / (height * density)).coerceIn(MinZoom, 1f)
        pan = Offset((viewport.width - width * scale) / 2 - left * scale, max(margin, (viewport.height - height * scale) / 2) - top * scale)
    }

    fun viewportChanged(size: Size) {
        viewport = size
        fitIfPending()
    }

    private fun fitIfPending() {
        if (fitPending && boxes.isNotEmpty() && viewport.width > 0f) {
            fitPending = false
            fit()
        }
    }

    /** Selects the first table matching the search and brings it into view. */
    fun findNext() {
        val query = search.text.toString().trim()
        if (query.isEmpty()) return
        val matches = tables.map { it.table }.filter { it.name.contains(query, ignoreCase = true) }
        if (matches.isEmpty()) {
            app.status = "No table matching \"$query\""
            return
        }
        val next = matches.getOrNull(matches.indexOf(selected) + 1) ?: matches.first()
        selected = next
        reveal(next)
    }

    fun matches(table: TableRef): Boolean {
        val query = search.text.toString().trim()
        return query.isNotEmpty() && table.name.contains(query, ignoreCase = true)
    }

    private fun reveal(table: TableRef) {
        val box = box(table) ?: return
        if (zoom < 0.6f) zoom = 0.8f
        pan = Offset(viewport.width / 2 - (box.x + box.width / 2) * scale, viewport.height / 2 - (box.y + box.height / 2) * scale)
    }

    fun openData(table: TableRef) = app.workspace.openTable(config, table)

    fun editStructure(table: TableRef) = app.workspace.openTableDesigner(config, namespace, table)

    fun showAround(table: TableRef) = app.workspace.openErDiagram(config, namespace, table)

    fun mermaid(): String = Mermaid.erDiagram(tables, dialect)

    fun copyMermaid() {
        dev.tablegrip.app.ui.copyToClipboard(mermaid())
        app.status = "Copied the Mermaid diagram of ${tables.size} table(s)"
    }

    fun saveMermaid(file: File) {
        runCatching { file.writeText(mermaid()) }
            .onSuccess { app.status = "Saved ${file.path}" }
            .onFailure { error = "Couldn't save ${file.name}: ${it.message}" }
    }

    fun savePng(file: File, dark: Boolean) {
        val current = tables.associate { it.table to box(it.table)!! }
        val details = tables
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ErImage.write(details, current, ErLayout.edges(details), dark, file) }
                .onSuccess { app.status = "Saved ${file.path}" }
                .onFailure { error = "Couldn't save ${file.name}: ${it.message}" }
        }
    }

    override fun dispose() {
        job?.cancel()
    }

    companion object {
        const val MinZoom = 0.1f
        const val MaxZoom = 2.5f

        /** Box width from its text: wide enough for the longest name plus type, within limits. */
        fun boxWidth(table: TableDetails): Float {
            val header = table.table.name.length * 7.6f + 44f
            val rows = table.columns.take(ErLayout.MaxRows).maxOfOrNull { it.name.length * 7f + it.typeName.length * 6.2f + 70f } ?: 0f
            return max(header, rows).coerceIn(170f, 360f)
        }
    }
}
