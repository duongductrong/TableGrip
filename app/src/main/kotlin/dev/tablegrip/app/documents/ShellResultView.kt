package dev.tablegrip.app.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.grid.RecordEditorHost
import dev.tablegrip.app.grid.ResultJsonView
import dev.tablegrip.app.grid.ValuePanel
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.UndoRedoButtons
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.ui.ReviewChangesPopup
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A console result's documents as the SQL console shows rows: the grid, JSON or a tree above, and a footer with what
 * was read, the edit tools when the documents can be edited, and the view switch.
 */
@Composable
internal fun ShellResultView(result: ShellResult, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        result.message?.let { message ->
            if (message.isError) ErrorBanner(message.text, onDismiss = { result.message = null })
            else Text(message.text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = JewelTheme.globalColors.text.info, maxLines = 2)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val model = result.model
            LaunchedEffect(result) { result.grid.verticalScroll.scrollToItem(0) }
            when {
                result.view == DocumentView.Json -> ResultJsonView(model, result.grid, asTree = false)
                result.view == DocumentView.Tree -> ResultJsonView(model, result.grid, asTree = true)
                model.rowCount == 0 -> Text(tr("No documents."), Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
                else -> {
                    val grid =
                        @Composable {
                            DataGrid(model, result.grid, actions = GridActions(onSubmit = result::submit))
                        }
                    if (result.showValuePanel) {
                        HorizontalSplitLayout(
                            first = { grid() },
                            second = { ValuePanel(model, result.grid) },
                            modifier = Modifier.fillMaxSize(),
                            firstPaneMinWidth = 300.dp,
                            secondPaneMinWidth = 220.dp,
                            state = rememberSplitLayoutState(0.72f),
                        )
                    } else grid()
                }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Footer(result)
    }
    RecordEditorHost(result.model, result.grid, "${result.database}.${result.collection ?: result.title}")
}

/** What was read on the left, then editing; the view switch, value panel, pin and export on the right. */
@Composable
private fun Footer(result: ShellResult) {
    val info = JewelTheme.globalColors.text.info
    val model = result.model
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = result.documents.size
        Text(if (result.hasMore) tr("{0}+ docs", count) else tr("{0} docs", count), Modifier.padding(horizontal = 4.dp))
        ToolButton(AllIconsKeys.Actions.Refresh, if (result.hasChanges) tr("Submit or revert the edits to run the command again") else tr("Run the command again"), enabled = result.canRefresh && !result.hasChanges && !result.loading && !result.submitting) {
            result.refresh()
        }
        Separator()
        val editable = result.editable && result.view != DocumentView.Json && result.view != DocumentView.Tree
        if (result.editable) {
            ToolButton(AllIconsKeys.General.Add, tr("Add document ({0})", Shortcuts.AddRow), enabled = editable) { result.grid.revealEditable(model, model.addRow()) }
            ToolButton(AllIconsKeys.General.Remove, tr("Delete selected documents ({0})", Shortcuts.DeleteRows), enabled = editable && result.grid.selection != null) {
                result.grid.selection?.rows?.let { model.deleteRows(it.toList()) }
                result.grid.clamp(model.rowCount)
            }
            UndoRedoButtons(result.grid, model)
            ToolButton(AllIconsKeys.Actions.Rollback, tr("Revert all changes"), enabled = result.hasChanges) { result.revert() }
            ToolButton(AllIconsKeys.Actions.Preview, tr("Preview pending writes"), enabled = result.hasChanges) { result.openPreview() }
            Box {
                ToolButton(AllIconsKeys.Actions.Commit, tr("Submit changes ({0})", Shortcuts.Submit), enabled = result.hasChanges && !result.submitting) { result.submit() }
                result.preview?.let { ReviewPopup(result, it) }
            }
            if (result.hasChanges) Text(tr("{0} pending", model.changeCount), Modifier.padding(start = 4.dp), color = info)
            Separator()
        }
        val note =
            when {
                result.submitting -> tr("Saving…")
                result.loading -> tr("Loading…")
                else -> buildString {
                    append(tr("{0} ms", result.millis))
                    if (result.hasMore) append(tr(" · more matched; add .limit(n) or .skip(n)"))
                    result.whyReadOnly?.let { append(" · ").append(it) }
                }
            }
        Text(note, Modifier.weight(1f).padding(horizontal = 4.dp), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ViewChipGroup {
            DocumentView.entries.forEach { mode -> ViewChip(mode.label, result.view == mode) { result.view = mode } }
        }
        Separator()
        ToolButton(
            AllIconsKeys.Actions.PreviewDetails,
            if (result.showValuePanel) tr("Hide value panel") else tr("Show value panel"),
            enabled = result.view == DocumentView.Grid,
        ) { result.showValuePanel = !result.showValuePanel }
        ToolButton(
            if (result.pinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
            if (result.pinned) tr("Unpin tab") else tr("Pin tab: keep this result when the console runs again"),
        ) { result.pinned = !result.pinned }
        ToolButton(AllIconsKeys.Actions.Download, tr("Export result…"), enabled = result.documents.isNotEmpty()) { result.export() }
    }
}

/** The pending writes above the Submit button, to check before they run. */
@Composable
private fun ReviewPopup(result: ShellResult, commands: String) {
    ReviewChangesPopup(
        changeCount = result.model.changeCount,
        target = "${result.collection} · ${result.config.name}",
        statements = AnnotatedString(commands),
        note =
            tr("Each document is written on its own. An update only applies while the fields still hold the values they had when loaded; ") +
                tr("one that doesn't stays pending."),
        canSkip = result.canSkipReview,
        onCancel = result::closePreview,
        onSubmit = result::submitReviewed,
    )
}
