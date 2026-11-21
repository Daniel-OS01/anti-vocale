package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.data.SubfolderPaths
import com.antivocale.app.data.TranscriptionProfile

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

    /**
     * The subfolder profile a scan of [path] would apply while its rule is
     * being edited: [edited] when it sets anything, else the deepest
     * ANCESTOR rule among [rules] (the scan's [SubfolderPaths.longestMatch]
     * with the edited path's own rule left out, since clearing it is what
     * the editor is previewing). A parent rule on `Calls` therefore shows
     * for `Calls/2024`.
     */
    fun effectiveSubfolderProfile(
        rules: Map<String, TranscriptionProfile>,
        path: String,
        edited: TranscriptionProfile?,
    ): TranscriptionProfile? {
        edited?.takeUnless { it.isEmpty }?.let { return it }
        val own = SubfolderPaths.normalize(path)
        return SubfolderPaths.longestMatch(rules.filterKeys { SubfolderPaths.normalize(it) != own }, path)
    }
}
