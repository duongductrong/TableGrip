package dev.tablegrip.app.documents

import dev.tablegrip.core.driver.DocumentConnection
import dev.tablegrip.core.i18n.tr
import kotlinx.coroutines.CancellationException

/** What saving a grid's document edits came to: the rows that failed, with why, and those worth retrying. */
internal class DocumentWriteOutcome(val total: Int, val failed: Map<Int, String>, val alreadyDeleted: Set<Int>) {
    val saved: Int
        get() = total - failed.size

    /** Failed edits to keep pending: a deletion that found nothing has nothing left to retry. */
    val retry: Set<Int>
        get() = failed.keys - alreadyDeleted

    /** "Saved 3 changes.", or which rows failed and why; [firstRow] numbers the rows as the user sees them. */
    fun message(firstRow: Long = 1): Pair<String, Boolean> =
        if (failed.isEmpty()) tr("Saved {0} {1}.", saved, if (saved == 1) tr("change") else tr("changes")) to false
        else (
            tr("Saved {0} of {1}. ", saved, total) +
                failed.entries.take(3).joinToString("; ") { (row, why) -> tr("Row {0}: {1}", firstRow + row, why) } +
                (if (failed.size > 3) "; …" else "") +
                if (retry.isNotEmpty()) tr(". Those edits are still pending, over the current values.") else ""
            ) to true
}

/**
 * Writes a grid's edits document by document: each one stands alone, so one that fails (changed by someone else since it
 * was loaded, a duplicate `_id`…) doesn't stop the others.
 */
internal suspend fun writeDocumentChanges(
    connection: DocumentConnection,
    database: String,
    collection: String,
    changes: List<DocumentEditModel.Change>,
): DocumentWriteOutcome {
    val failed = linkedMapOf<Int, String>()
    val gone = mutableSetOf<Int>()
    for (change in changes) {
        try {
            when (change) {
                is DocumentEditModel.Change.Delete ->
                    if (connection.delete(database, collection, listOf(change.id)) == 0L) {
                        failed[change.row] = tr("already deleted")
                        gone += change.row
                    }
                is DocumentEditModel.Change.Update ->
                    if (!connection.update(database, collection, change.id, change.set, change.unset, change.expected)) {
                        failed[change.row] = tr("changed or deleted by someone else since it was loaded")
                    }
                is DocumentEditModel.Change.Insert -> connection.insert(database, collection, change.document)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed[change.row] = e.message ?: e.toString()
        }
    }
    return DocumentWriteOutcome(changes.size, failed, gone)
}
