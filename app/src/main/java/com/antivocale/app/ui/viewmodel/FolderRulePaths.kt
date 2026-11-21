package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.data.SubfolderPaths

/**
 * R6: the subfolders a watched folder's last scan saw, for the "Add
 * subfolder rule" picker and the "Not found in the last scan" mark.
 * Source: the folder's dedup snapshot only (its names are paths relative
 * to the root). History's sourceSubPath is not consulted: no DAO query
 * lists it per folder today.
 */
object FolderRulePaths {

    /**
     * Every distinct normalized subfolder of [snapshot] plus each one's
     * parent prefixes (`Calls/Russian` also offers `Calls`), sorted.
     * Root files contribute nothing.
     */
    fun discovered(snapshot: List<FileSnapshot>): List<String> =
        buildSet {
            snapshot.forEach { snap ->
                val sub = SubfolderPaths.normalize(FolderScanPlanner.subPathOf(snap.name)) ?: return@forEach
                val segments = sub.split('/')
                for (depth in 1..segments.size) add(segments.take(depth).joinToString("/"))
            }
        }.sorted()

    /** True when [rulePath] names a subfolder the last scan saw. */
    fun seenInLastScan(rulePath: String, discovered: Collection<String>): Boolean =
        SubfolderPaths.normalize(rulePath)?.let { it in discovered } ?: false
}
