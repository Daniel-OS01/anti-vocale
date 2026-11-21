package com.antivocale.app.service

import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.SourceOrigin
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.data.effectiveSourceOrigin
import com.antivocale.app.ui.viewmodel.LogEntry

/** Why a selected History row cannot be redone. */
enum class SkipReason {
    /** A text entry: there is no audio to re-transcribe. */
    NOT_AUDIO,

    /** The row is still queued or processing. */
    IN_FLIGHT,

    /** The staged copy is gone and the row has no folder to find it in again. */
    FILE_GONE,

    /** The watched folder lost its grant, or its listing failed. */
    FOLDER_UNAVAILABLE,

    /** The folder was listed, but no file in it matches the row. */
    NOT_FOUND_IN_FOLDER,

    /** More than one file in the folder matches the row (a 32-bit hash collision). */
    AMBIGUOUS,
}

/** One selected row's redo plan. */
sealed class BulkRedoItem {
    abstract val row: LogEntry

    /** The staged audio copy still exists at [localPath]. */
    data class Ready(override val row: LogEntry, val localPath: String) : BulkRedoItem()

    /**
     * A folder-watch row whose staged copy was swept: the file is looked up
     * again in [treeUri] by its subfolder and relative-path hash (see
     * [FolderRestage]).
     */
    data class NeedsRestage(
        override val row: LogEntry,
        val treeUri: String,
        val subPath: String?,
        val relPathHash: UInt,
    ) : BulkRedoItem()

    data class Skipped(override val row: LogEntry, val reason: SkipReason) : BulkRedoItem()
}

/**
 * The pure plan of a bulk "redo with model X": which selected rows can run
 * from their staged copy, which must be re-staged from their watched folder,
 * and which are skipped and why. Staged copies in shared_audio are swept after
 * 24h, so a missing file is the common case for old rows.
 */
object BulkRedoPlanner {

    /**
     * One item per row, in input order. [watchedFolders] lets a legacy
     * folder-watch row (no recorded folder key) be attributed through
     * [effectiveSourceOrigin], the same recovery the single-row redo uses.
     */
    fun plan(
        rows: List<LogEntry>,
        fileExists: (String) -> Boolean,
        watchedFolders: List<WatchedFolder> = emptyList(),
    ): List<BulkRedoItem> = rows.map { planOne(it, fileExists, watchedFolders) }

    private fun planOne(
        row: LogEntry,
        fileExists: (String) -> Boolean,
        watchedFolders: List<WatchedFolder>,
    ): BulkRedoItem {
        if (row.type != LogEntry.Type.AUDIO) return BulkRedoItem.Skipped(row, SkipReason.NOT_AUDIO)
        val inFlight = when (row.status) {
            LogEntry.Status.QUEUED, LogEntry.Status.PROCESSING -> true
            LogEntry.Status.SUCCESS, LogEntry.Status.ERROR -> false
        }
        if (inFlight) return BulkRedoItem.Skipped(row, SkipReason.IN_FLIGHT)
        val path = row.filePath
        if (path != null && fileExists(path)) return BulkRedoItem.Ready(row, path)
        val origin = effectiveSourceOrigin(
            row.taskId,
            SourceOrigin(row.sourceKind, row.sourceFolderKey, row.sourceFolderName, row.sourceSubPath),
            watchedFolders,
        )
        val treeUri = origin.folderKey
        val relPathHash = FolderWatchTaskIds.relPathHashOf(row.taskId)
        if (origin.kind == SourceKind.FOLDER_WATCH && !treeUri.isNullOrBlank() && relPathHash != null) {
            return BulkRedoItem.NeedsRestage(row, treeUri, origin.subPath, relPathHash)
        }
        return BulkRedoItem.Skipped(row, SkipReason.FILE_GONE)
    }

    /**
     * A STABLE grouping by [key] (backendId, language): groups appear in the
     * order their key first appears, and items keep their relative order
     * inside a group, so same-profile runs are consecutive.
     */
    fun <T> orderForEnqueue(items: List<T>, key: (T) -> Pair<String?, String?>): List<T> =
        items.groupBy(key).values.flatten()
}
