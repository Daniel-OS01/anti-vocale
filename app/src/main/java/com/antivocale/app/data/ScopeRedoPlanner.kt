package com.antivocale.app.data

import com.antivocale.app.data.local.LogEntity

/**
 * Which existing History rows a just-saved folder or subfolder rule governs,
 * so the "redo N existing files with this rule?" offer redoes exactly those.
 *
 * Pure Kotlin: the caller loads the folder's rows and the current rules and
 * decides [senderRuled] (a sender rule outranks every folder rule, so a row
 * whose sender has one is never this rule's to redo).
 */
object ScopeRedoPlanner {

    /**
     * The ids of [rows] the rule at [scopeSubPath] governs under [subfolderRules]
     * (the folder's rules AFTER the save).
     *
     * - [scopeSubPath] null (the folder default): rows no subfolder rule matches.
     * - A subfolder path: rows whose deepest matching rule is that path. The
     *   path counts as a rule even when the save removed it, so a removal
     *   offers the rows it used to govern; a deeper rule keeps its own rows.
     */
    fun plan(
        rows: List<LogEntity>,
        subfolderRules: Set<String>,
        scopeSubPath: String?,
        senderRuled: (LogEntity) -> Boolean,
    ): List<String> {
        val scopeKey = SubfolderPaths.normalize(scopeSubPath)
        val keys = subfolderRules.mapNotNullTo(HashSet()) { SubfolderPaths.normalize(it) }
        if (scopeKey != null) keys += scopeKey
        return rows
            .filter { deepestRule(keys, it.sourceSubPath) == scopeKey }
            .filterNot(senderRuled)
            .map { it.id }
    }

    /** The normalized key of the deepest rule matching [subPath], or null (none). */
    private fun deepestRule(keys: Set<String>, subPath: String?): String? =
        keys.filter { SubfolderPaths.matches(it, subPath) }.maxByOrNull { it.length }
}
