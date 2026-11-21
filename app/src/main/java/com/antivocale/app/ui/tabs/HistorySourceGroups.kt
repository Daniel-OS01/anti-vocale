package com.antivocale.app.ui.tabs

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
    val packageName = entry.sourcePackageName
    val sender = entry.senderName?.takeIf { it.isNotBlank() }?.let { HistorySubGroupRef(it, it) }
    return if (packageName != null) {
        HistoryGroupPlacement(packageName, HistoryGroupTitle.App(packageName), sender)
    } else {
        HistoryGroupPlacement(UNKNOWN_GROUP_KEY, HistoryGroupTitle.Other, sender)
    }
}

/** The section key for rows with no recognized origin. */
internal const val UNKNOWN_GROUP_KEY = "__unknown__"
