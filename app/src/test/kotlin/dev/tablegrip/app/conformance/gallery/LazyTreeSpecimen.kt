package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.theme.treeStyle

/**
 * The explorer's LazyTree with its own metrics (`ExplorerPanel.explorerTreeStyle`): `public` open with `users`
 * selected (the tree unfocused), `audit` closed, `readme` a leaf. Leaves are inset by the chevron's width, as the
 * explorer does.
 */
object LazyTreeSpecimen : GallerySpecimen {
    override val name = "lazy-tree"
    override val size = DpSize(220.dp, 140.dp)

    @Composable
    override fun Content() {
        val treeState = remember { TreeState(SelectableLazyListState(LazyListState())) }
        LaunchedEffect(Unit) {
            treeState.openNodes(listOf("public"))
            treeState.selectedKeys = setOf("users")
        }
        val tree =
            buildTree {
                addNode("public", "public") {
                    addLeaf("users", "users")
                    addLeaf("orders", "orders")
                }
                addNode("audit", "audit") { addLeaf("log", "log") }
                addLeaf("readme", "readme")
            }
        val base = JewelTheme.treeStyle
        val style =
            LazyTreeStyle(
                colors = base.colors,
                metrics =
                    LazyTreeMetrics.defaults(
                        indentSize = 16.dp,
                        elementBackgroundCornerSize = CornerSize(4.dp),
                        elementPadding = PaddingValues(horizontal = 6.dp),
                        elementContentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        elementMinHeight = 26.dp,
                        chevronContentGap = 4.dp,
                    ),
                icons = base.icons,
            )
        Island(Modifier.size(220.dp, 140.dp)) {
            LazyTree(tree = tree, treeState = treeState, modifier = Modifier.size(220.dp, 140.dp).padding(vertical = 4.dp), style = style) { element ->
                val leafInset = if (element is Tree.Element.Leaf) 20.dp else 0.dp
                Box(Modifier.fillMaxWidth().padding(start = leafInset)) { Text(element.data, Modifier.testTag("text:lazy-tree.${element.data}")) }
            }
        }
    }
}
