package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.ui.viewmodel.LogEntry

/**
 * R3: what a History section is titled after. A descriptor, not a string:
 * the resolver stays Context-free (unit-testable) and the render site
 * localizes it, so a locale switch never leaves a stale baked title.
 */
internal sealed interface HistoryGroupTitle {
    /** The source app; the render site looks up its label. */
    data class App(val packageName: String) : HistoryGroupTitle

    /** A watched folder, by its display name. */
    data class Folder(val name: String) : HistoryGroupTitle

    /** Folder-watch rows whose folder cannot be named any more. */
    data object FolderSyncLegacy : HistoryGroupTitle

    data object Imported : HistoryGroupTitle

    data object Shared : HistoryGroupTitle

    data object Automation : HistoryGroupTitle

    data object Other : HistoryGroupTitle
}

/**
 * The row's second-level placement inside its section. [key] is the
 * segment the expansion key appends to the group key (see senderGroupKey);
 * [label] is what the sub-header shows.
 */
internal data class HistorySubGroupRef(val key: String, val label: String)

/**
 * Where one History row sits when grouping by origin. [subGroup] null means
 * the row is listed directly under the section header.
 */
internal data class HistoryGroupPlacement(
    val groupKey: String,
    val title: HistoryGroupTitle,
    val subGroup: HistorySubGroupRef?,
)

/**
 * R3: the ONE origin-to-section mapping. The grouping builder and the
 * highlight auto-expand both call it, so the section a row renders in and
 * the section the notification tap opens cannot diverge.
 *
 * Pure: [watchedFolders] is the current ScheduledFolderStore list, used to
 * name folder sections and to classify legacy folder-watch rows.
 */
internal fun resolveHistoryGroup(
    entry: LogEntry,
    watchedFolders: List<WatchedFolder>,
): HistoryGroupPlacement {
    val sender = senderSubGroup(entry)
    // The app fact wins over the kind: a share from WhatsApp stays in the
    // WhatsApp section exactly as before R3.
    entry.sourcePackageName?.let { packageName ->
        return HistoryGroupPlacement(packageName, HistoryGroupTitle.App(packageName), sender)
    }
    return when (entry.sourceKind) {
        SourceKind.FOLDER_WATCH -> folderWatchPlacement(entry, watchedFolders)
        SourceKind.BROWSE -> HistoryGroupPlacement(
            IMPORTED_GROUP_KEY,
            HistoryGroupTitle.Imported,
            folderSubGroup(entry.sourceFolderName),
        )
        SourceKind.SHARE -> HistoryGroupPlacement(SHARED_GROUP_KEY, HistoryGroupTitle.Shared, sender)
        SourceKind.AUTOMATION, SourceKind.OPENTRANSCRIBE ->
            HistoryGroupPlacement(AUTOMATION_GROUP_KEY, HistoryGroupTitle.Automation, sender)
        // Pre-R3 rows carry no kind; only folder-watch ones are recognizable
        // (by their task id). Old browse rows recorded nothing and stay Other.
        null -> legacyFolderWatchPlacement(entry, watchedFolders) ?: otherPlacement(sender)
        // voice_note without a package, or a token a newer build wrote.
        else -> otherPlacement(sender)
    }
}

private fun otherPlacement(sender: HistorySubGroupRef?) =
    HistoryGroupPlacement(UNKNOWN_GROUP_KEY, HistoryGroupTitle.Other, sender)

/** Notification-identity sender (app rows) or caller label (folder rows). */
private fun senderSubGroup(entry: LogEntry): HistorySubGroupRef? =
    entry.senderName?.takeIf { it.isNotBlank() }?.let { HistorySubGroupRef(it, it) }

/**
 * A folder-path second level. The key carries [FOLDER_SUBGROUP_PREFIX] so a
 * subfolder named like a caller label in the same folder section stays a
 * separate sub-header (the two second-level kinds share one section).
 */
private fun folderSubGroup(path: String?): HistorySubGroupRef? =
    path?.takeIf { it.isNotBlank() }?.let { HistorySubGroupRef(FOLDER_SUBGROUP_PREFIX + it, it) }

private fun folderWatchPlacement(
    entry: LogEntry,
    watchedFolders: List<WatchedFolder>,
): HistoryGroupPlacement {
    val treeUri = entry.sourceFolderKey?.takeIf { it.isNotBlank() }
        // A folder-watch row that lost its folder key is classified like a
        // pre-R3 one: by the tree hash its task id carries.
        ?: return legacyFolderWatchPlacement(entry, watchedFolders) ?: folderSyncPlacement(entry)
    val name = entry.sourceFolderName?.takeIf { it.isNotBlank() }
        ?: watchedFolders.firstOrNull { it.treeUri == treeUri }?.displayName
    return HistoryGroupPlacement(
        folderGroupKey(treeUri),
        name?.let { HistoryGroupTitle.Folder(it) } ?: HistoryGroupTitle.FolderSyncLegacy,
        folderSubGroup(entry.sourceSubPath) ?: senderSubGroup(entry),
    )
}

/**
 * A pre-R3 folder-watch row, recognized by ScheduledFolderWorker's task id
 * `fwatch_<treeUri.hashCode().toUInt()>_<ts>_<nameHash>`. It joins a folder
 * section only when exactly ONE watched folder has that hash (a 32-bit hash
 * can collide; a wrong folder is worse than the generic section); any other
 * fwatch row goes to Folder sync. Null when the id is not an fwatch id.
 */
private fun legacyFolderWatchPlacement(
    entry: LogEntry,
    watchedFolders: List<WatchedFolder>,
): HistoryGroupPlacement? {
    val treeHash = LEGACY_FOLDER_WATCH_TASK_ID.matchEntire(entry.taskId)?.groupValues?.get(1)
        ?: return null
    val folder = watchedFolders
        .filter { it.treeUri.hashCode().toUInt().toString() == treeHash }
        .singleOrNull()
        ?: return folderSyncPlacement(entry)
    return HistoryGroupPlacement(
        folderGroupKey(folder.treeUri),
        HistoryGroupTitle.Folder(folder.displayName),
        senderSubGroup(entry),
    )
}

private fun folderSyncPlacement(entry: LogEntry) =
    HistoryGroupPlacement(FOLDER_SYNC_GROUP_KEY, HistoryGroupTitle.FolderSyncLegacy, senderSubGroup(entry))

private val LEGACY_FOLDER_WATCH_TASK_ID = Regex("""fwatch_(\d+)_\d+_\d+""")

/*
 * Section keys. App sections key on the bare package name; Android package
 * names never contain ':' and always contain a '.', so the `folder:` prefix
 * and the dot-free `__name__` sentinels below cannot collide with one.
 */

/** The section key for rows with no recognized origin. */
internal const val UNKNOWN_GROUP_KEY = "__unknown__"
private const val IMPORTED_GROUP_KEY = "__imported__"
private const val SHARED_GROUP_KEY = "__shared__"
private const val AUTOMATION_GROUP_KEY = "__automation__"
private const val FOLDER_SYNC_GROUP_KEY = "__folder_sync__"
private const val FOLDER_SUBGROUP_PREFIX = "dir:"

/** One watched folder's section: new rows (by sourceFolderKey) and matched
 *  legacy rows (by treeUri) share it. */
private fun folderGroupKey(treeUri: String) = "folder:$treeUri"
