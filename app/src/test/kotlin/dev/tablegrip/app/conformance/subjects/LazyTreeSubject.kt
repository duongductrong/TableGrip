package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

/**
 * The explorer's LazyTree, over a fixed tree: `a` (children `a1`, `a2` which holds `a2x`), `b` (children `b1`, `b2`),
 * leaf `c`. With `lazy`, `b` starts with a single `loading` child and gets its real children the first time it is
 * opened, as the explorer does. Rows are tagged
 * `row:<id>`. Reports `open` and `selected` (ids, sorted, comma separated), `activated` (last double-clicked id),
 * `loaded` (lazy nodes whose children arrived) and `rows` (the ids in the order they are listed), `first` and `last` (the indexes of the first and last row on screen).
 */
object LazyTreeSubject : ConformanceSubject {
    override val component = "lazy-tree"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val lazy = setup.bool("lazy")
        val many = setup["many"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() } ?: 0
        val treeState = remember { TreeState(SelectableLazyListState(LazyListState())) }
        var loaded by remember { mutableStateOf(emptySet<String>()) }
        var activated by remember { mutableStateOf("") }
        val tree: Tree<String> =
            buildTree {
                addNode("a", "a") {
                    addLeaf("a1", "a1")
                    addNode("a2", "a2") { addLeaf("a2x", "a2x") }
                }
                addNode("b", "b") {
                    if (lazy && "b" !in loaded) {
                        addLeaf("loading", "loading")
                    } else {
                        addLeaf("b1", "b1")
                        addLeaf("b2", "b2")
                    }
                }
                addLeaf("c", "c")
                repeat(many) { addLeaf("r$it", "r$it") }
            }
        // As the explorer does: loading starts when a node shows up among the open ones.
        LaunchedEffect(Unit) { snapshotFlow { treeState.openNodes }.collect { open -> if (lazy && "b" in open) loaded = loaded + "b" } }
        probe.readers["open"] = { treeState.openNodes.map { it.toString() }.sorted().joinToString(",") }
        probe.readers["selected"] = { treeState.selectedKeys.map { it.toString() }.sorted().joinToString(",") }
        probe.readers["activated"] = { activated }
        probe.readers["loaded"] = { loaded.sorted().joinToString(",") }
        probe.readers["first"] = { treeState.lazyListState.lazyListState.firstVisibleItemIndex.toString() }
        probe.readers["last"] = { treeState.lazyListState.lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.toString() ?: "" }
        probe.readers["rows"] = { visibleRows(tree, treeState.openNodes).joinToString(",") }
        Box(Modifier.width(220.dp).height(if (many > 0) 120.dp else 300.dp)) {
            LazyTree(
                tree = tree,
                treeState = treeState,
                modifier = Modifier.fillMaxSize().testTag(SubjectTag),
                onElementDoubleClick = { activated = it.data },
            ) { element ->
                Text(element.data, Modifier.fillMaxWidth())
            }
            // Jewel merges a row's semantics and drops tags inside it, so the rows are tagged by markers laid over them.
            // Markers take no pointer input: a click on one lands on the row below.
            Column(Modifier.fillMaxSize()) {
                visibleRows(tree, treeState.openNodes).take(12).forEach { id -> Box(Modifier.fillMaxWidth().height(RowHeight).testTag("row:$id")) }
            }
        }
    }

    private val RowHeight = 24.dp

    /** The ids a tree lists, depth first, with only the open nodes' children. */
    private fun visibleRows(tree: Tree<String>, open: Set<Any>): List<String> {
        val out = mutableListOf<String>()
        fun walk(elements: List<Tree.Element<String>>) {
            for (e in elements) {
                out += e.data
                if (e is Tree.Element.Node<String> && e.id in open) walk(e.children.orEmpty())
            }
        }
        walk(tree.roots)
        return out
    }
}
