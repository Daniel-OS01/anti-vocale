package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SubfolderPaths

/** A group header's checkbox state over the group's visible rows. */
enum class GroupSelection { ON, OFF, PARTIAL }

/**
 * History multi-select: the pure set algebra behind the selection mode.
 * Row ids are [com.antivocale.app.ui.viewmodel.LogEntry.id]. The ViewModel
 * owns the state; the tab and the group headers only call these, so the
 * header tri-state and the bulk toggles cannot disagree with the VM's set.
 */
object HistorySelection {

    /** Flips [id]'s membership. */
    fun toggle(sel: Set<String>, id: String): Set<String> =
        if (id in sel) sel - id else sel + id

    /** Adds ([on]) or removes every id in [ids]; ids outside [ids] are untouched. */
    fun setAll(sel: Set<String>, ids: Collection<String>, on: Boolean): Set<String> =
        if (on) sel + ids else sel - ids.toSet()

    /** Drops ids that no longer exist (deleted rows leave the selection). */
    fun prune(sel: Set<String>, existingIds: Set<String>): Set<String> =
        if (existingIds.containsAll(sel)) sel else sel.filterTo(LinkedHashSet()) { it in existingIds }

    /** ON when every id of a non-empty group is selected, OFF when none (or the group is empty). */
    fun triState(groupIds: Collection<String>, sel: Set<String>): GroupSelection {
        if (groupIds.isEmpty()) return GroupSelection.OFF
        val selected = groupIds.count { it in sel }
        return when (selected) {
            0 -> GroupSelection.OFF
            groupIds.size -> GroupSelection.ON
            else -> GroupSelection.PARTIAL
        }
    }

    /** The header tap: a fully selected group clears, anything else selects the whole group. */
    fun headerToggle(groupIds: Collection<String>, sel: Set<String>): Set<String> =
        setAll(sel, groupIds, on = triState(groupIds, sel) != GroupSelection.ON)

    /**
     * "Select with subfolders": the row ids of every folder sub-group in
     * [sectionSubGroups] whose subfolder is [fromKey]'s subfolder or below
     * it ([SubfolderPaths.matches], the one segment-prefix rule). Empty when
     * [fromKey] is not a folder sub-group. Pass the section's VISIBLE
     * sub-groups: only the rows the list shows get selected.
     */
    internal fun subfolderTreeIds(fromKey: String, sectionSubGroups: List<SubGroup>): Set<String> {
        val root = folderSubPathOf(fromKey) ?: return emptySet()
        return sectionSubGroups
            .filter { group -> folderSubPathOf(group.key)?.let { SubfolderPaths.matches(root, it) } == true }
            .flatMapTo(LinkedHashSet()) { group -> group.logs.map { it.id } }
    }

    /** Selected ids the current (filtered) list does not show. */
    fun hiddenCount(sel: Set<String>, visibleIds: Set<String>): Int =
        sel.count { it !in visibleIds }
}
