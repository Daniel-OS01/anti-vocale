package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.SourceOrigin
import com.antivocale.app.data.SubfolderPaths
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.data.effectiveSourceOrigin
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
    // Pre-R3 rows carry no kind; only folder-watch ones are recognizable (by
    // their task id), and the retry paths recover them through the same
    // function. Old browse rows recorded nothing and stay Other.
    val origin = effectiveSourceOrigin(
        entry.taskId,
        SourceOrigin(entry.sourceKind, entry.sourceFolderKey, entry.sourceFolderName, entry.sourceSubPath),
        watchedFolders,
    )
    return when (origin.kind) {
        SourceKind.FOLDER_WATCH -> folderWatchPlacement(origin, sender, watchedFolders)
        SourceKind.BROWSE -> HistoryGroupPlacement(
            IMPORTED_GROUP_KEY,
            HistoryGroupTitle.Imported,
            folderSubGroup(origin.folderName),
        )
        SourceKind.SHARE -> HistoryGroupPlacement(SHARED_GROUP_KEY, HistoryGroupTitle.Shared, sender)
        SourceKind.AUTOMATION, SourceKind.OPENTRANSCRIBE ->
            HistoryGroupPlacement(AUTOMATION_GROUP_KEY, HistoryGroupTitle.Automation, sender)
        // No kind and no fwatch id, voice_note without a package, or a token
        // a newer build wrote.
        else -> otherPlacement(sender)
    }
}

private fun otherPlacement(sender: HistorySubGroupRef?) =
    HistoryGroupPlacement(UNKNOWN_GROUP_KEY, HistoryGroupTitle.Other, sender)

/*
 * The two second-level kinds can share one folder section (a subfolder and
 * a caller label), so each key carries its own prefix: a sender literally
 * named "dir:Calls" and the subfolder "Calls" stay separate sub-headers.
 * The label stays the bare text, so app sections look exactly as before.
 */

/** Notification-identity sender (app rows) or caller label (folder rows). */
private fun senderSubGroup(entry: LogEntry): HistorySubGroupRef? =
    entry.senderName?.takeIf { it.isNotBlank() }?.let { HistorySubGroupRef(SENDER_SUBGROUP_PREFIX + it, it) }

/** A folder-path second level (watched subfolder or imported parent folder). */
private fun folderSubGroup(path: String?): HistorySubGroupRef? =
    path?.takeIf { it.isNotBlank() }?.let { HistorySubGroupRef(FOLDER_SUBGROUP_PREFIX + it, it) }

private fun folderWatchPlacement(
    origin: SourceOrigin,
    sender: HistorySubGroupRef?,
    watchedFolders: List<WatchedFolder>,
): HistoryGroupPlacement {
    // No folder key even after recovery: an unattributable folder-watch row.
    val treeUri = origin.folderKey?.takeIf { it.isNotBlank() }
        ?: return HistoryGroupPlacement(FOLDER_SYNC_GROUP_KEY, HistoryGroupTitle.FolderSyncLegacy, sender)
    val name = origin.folderName?.takeIf { it.isNotBlank() }
        ?: watchedFolders.firstOrNull { it.treeUri == treeUri }?.displayName
    return HistoryGroupPlacement(
        folderGroupKey(treeUri),
        name?.let { HistoryGroupTitle.Folder(it) } ?: HistoryGroupTitle.FolderSyncLegacy,
        folderSubGroup(origin.subPath) ?: sender,
    )
}

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
private const val SENDER_SUBGROUP_PREFIX = "who:"

private const val FOLDER_GROUP_PREFIX = "folder:"

/** One watched folder's section: new rows (by sourceFolderKey) and matched
 *  legacy rows (by treeUri) share it. */
private fun folderGroupKey(treeUri: String) = FOLDER_GROUP_PREFIX + treeUri

/**
 * R6: the transcription-rule scope a History group header stands for, read
 * back off the keys the grouping wrote (the ONE reader of those prefixes
 * outside this file). Sender sub-groups ("who:") are [None] here: sender
 * rules are a separate scope with their own editor.
 */
internal sealed interface HistoryGroupScope {
    /** A watched folder's section header: its default profile. */
    data class Folder(val treeUri: String) : HistoryGroupScope

    /** A subfolder sub-header under a folder section; [subPath] is normalized. */
    data class Subfolder(val treeUri: String, val subPath: String) : HistoryGroupScope

    /** Any other header (app, imported, shared, sender, unknown). */
    data object None : HistoryGroupScope

    companion object {
        /**
         * @param groupKey the section key ([HistoryGroupPlacement.groupKey]).
         * @param subKey the sub-group key ([HistorySubGroupRef.key]), or null
         *   for the section header itself.
         */
        fun parse(groupKey: String, subKey: String? = null): HistoryGroupScope {
            if (!groupKey.startsWith(FOLDER_GROUP_PREFIX)) return None
            // Only the first prefix is stripped: a tree uri carries ':' and '/'.
            val treeUri = groupKey.removePrefix(FOLDER_GROUP_PREFIX).takeUnless { it.isBlank() } ?: return None
            if (subKey == null) return Folder(treeUri)
            if (!subKey.startsWith(FOLDER_SUBGROUP_PREFIX)) return None
            val subPath = SubfolderPaths.normalize(subKey.removePrefix(FOLDER_SUBGROUP_PREFIX)) ?: return None
            return Subfolder(treeUri, subPath)
        }
    }
}

/** The tree uri [this] scope writes to, or null for [HistoryGroupScope.None]. */
internal val HistoryGroupScope.treeUri: String?
    get() = when (val scope = this) {
        is HistoryGroupScope.Folder -> scope.treeUri
        is HistoryGroupScope.Subfolder -> scope.treeUri
        HistoryGroupScope.None -> null
    }

/**
 * The watched folder [this] scope edits, or null: [HistoryGroupScope.None],
 * or a folder no longer watched (its section survives in History, but an
 * unwatched folder has no rules to edit).
 */
internal fun HistoryGroupScope.watchedFolderIn(folders: List<WatchedFolder>): WatchedFolder? {
    val uri = treeUri ?: return null
    return folders.firstOrNull { it.treeUri == uri }
}

/** The rule stored at [this] scope in [folder] (null = no rule there). */
internal fun HistoryGroupScope.storedRuleIn(folder: WatchedFolder): TranscriptionProfile? =
    when (val scope = this) {
        is HistoryGroupScope.Folder -> folder.profile
        is HistoryGroupScope.Subfolder -> folder.subfolderRules[scope.subPath]
        HistoryGroupScope.None -> null
    }
