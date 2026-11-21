package com.antivocale.app.data

/** R3: a History row's four persisted origin facts (LogEntity.source*). */
data class SourceOrigin(
    val kind: String?,
    val folderKey: String?,
    val folderName: String?,
    val subPath: String?,
)

/**
 * R3: the origin a row stands for, recorded or recovered. The ONE place a
 * pre-R3 folder-watch row is recognized: the History grouping classifies
 * with it at display time, and both retry paths (the History re-transcribe
 * and the suspension re-run) copy its result, so a retried legacy row keeps
 * the section it was shown in although its fresh task id loses the evidence.
 *
 * A row is recovered only when it recorded no kind, or recorded folder_watch
 * without its folder key; any other recorded origin is returned unchanged.
 * Recognition is by ScheduledFolderWorker's task id
 * `fwatch_<treeUri.hashCode().toUInt()>_<ts>_<nameHash>`: the folder key and
 * name are filled only when exactly ONE of [watchedFolders] has that hash (a
 * 32-bit hash can collide, and a wrong folder is worse than the generic
 * Folder sync section); otherwise the kind alone is set. A task id of any
 * other shape leaves the origin unchanged.
 */
fun effectiveSourceOrigin(
    taskId: String,
    recorded: SourceOrigin,
    watchedFolders: List<WatchedFolder>,
): SourceOrigin {
    val recoverable = recorded.kind == null ||
        (recorded.kind == SourceKind.FOLDER_WATCH && recorded.folderKey.isNullOrBlank())
    if (!recoverable) return recorded
    val treeHash = LEGACY_FOLDER_WATCH_TASK_ID.matchEntire(taskId)?.groupValues?.get(1)
        ?: return recorded
    val folder = watchedFolders
        .filter { it.treeUri.hashCode().toUInt().toString() == treeHash }
        .singleOrNull()
        ?: return recorded.copy(kind = SourceKind.FOLDER_WATCH)
    return recorded.copy(
        kind = SourceKind.FOLDER_WATCH,
        folderKey = folder.treeUri,
        folderName = folder.displayName,
    )
}

private val LEGACY_FOLDER_WATCH_TASK_ID = Regex("""fwatch_(\d+)_\d+_\d+""")
