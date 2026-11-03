package dev.tablegrip.app.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Undo and redo for a model's pending edits. A step keeps the edits as they were before and after a change, so going
 * back never has to know what the change was: a typed cell, a paste that added rows, a record applied from JSON or
 * "Revert all" all go back the same way, and redo puts exactly the same edits back.
 *
 * Models wrap each of their own mutating methods in [change], so every way in (grid keys, menus, toolbar, value panel,
 * record editor) is recorded. Changes nested inside another one become part of it, which is how a caller turns many
 * writes (a pasted block, Set NULL over a selection) into one step. Nothing that leaves the edits as they were is
 * recorded, so Enter-then-Escape or reverting an untouched row doesn't leave empty steps to press through.
 *
 * [capture] returns an immutable copy of the pending edits that compares by value; [restore] puts one back.
 */
class EditHistory(private val capture: () -> Any, private val restore: (Any) -> Unit, private val limit: Int = 200) {
    /** A recorded change: what it was ([label], e.g. "edit of email") and the model cell to show when it's undone or redone. */
    class Step internal constructor(val label: String, val row: Int?, val column: Int?, internal val before: Any, internal val after: Any)

    private val undoSteps = ArrayDeque<Step>()
    private val redoSteps = ArrayDeque<Step>()
    private var depth = 0

    /** Bumped on every change to the stacks, so views showing [canUndo] or [canRedo] follow. */
    private var version by mutableIntStateOf(0)

    val canUndo: Boolean
        get() = version.let { undoSteps.isNotEmpty() }

    val canRedo: Boolean
        get() = version.let { redoSteps.isNotEmpty() }

    /** What ⌘Z would undo, for menus and tooltips. */
    val nextUndo: Step?
        get() = version.let { undoSteps.lastOrNull() }

    val nextRedo: Step?
        get() = version.let { redoSteps.lastOrNull() }

    /**
     * Runs [block] as one step called [label]; [row] and [column] (model indexes) are where it happened. Inside another
     * change it just runs, as part of that one.
     */
    fun <T> change(label: String, row: Int? = null, column: Int? = null, block: () -> T): T {
        if (depth > 0) return block()
        val before = capture()
        depth++
        try {
            return block()
        } finally {
            depth--
            val after = capture()
            if (after != before) {
                undoSteps.addLast(Step(label, row, column, before, after))
                if (undoSteps.size > limit) undoSteps.removeFirst()
                redoSteps.clear()
                version++
            }
        }
    }

    /** Puts the edits back as they were before the last step; returns it, or null when there's nothing to undo. */
    fun undo(): Step? {
        val step = undoSteps.removeLastOrNull() ?: return null
        quietly { restore(step.before) }
        redoSteps.addLast(step)
        version++
        return step
    }

    /** Makes the last undone step again; null when there's nothing to redo. */
    fun redo(): Step? {
        val step = redoSteps.removeLastOrNull() ?: return null
        quietly { restore(step.after) }
        undoSteps.addLast(step)
        version++
        return step
    }

    /** Forgets every step, e.g. once edits carried over from another page are put back. */
    fun clear() {
        undoSteps.clear()
        redoSteps.clear()
        version++
    }

    /** Restores in one snapshot, and without the model's own methods recording what restoring does. */
    private fun quietly(block: () -> Unit) {
        depth++
        try {
            Snapshot.withMutableSnapshot(block)
        } finally {
            depth--
        }
    }
}
