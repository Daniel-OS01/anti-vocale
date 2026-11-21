package com.antivocale.app.data

import java.text.Normalizer

/**
 * The ONE normal form and matching rule for a path relative to a watched
 * folder's root (R6 subfolder profiles; shared with the History
 * sourceSubPath consumers, so the names here are a public contract).
 *
 * Pure Kotlin: no Android, no I/O.
 */
object SubfolderPaths {

    private val REPEATED_SLASHES = Regex("/{2,}")

    /**
     * Trims, applies Unicode NFC (a decomposed "é" from one file provider
     * matches the composed form typed in a rule), collapses repeated `/`
     * and strips leading and trailing `/`. Blank (or only slashes) gives
     * null: the folder root.
     */
    fun normalize(path: String?): String? {
        if (path == null) return null
        val nfc = Normalizer.normalize(path.trim(), Normalizer.Form.NFC)
        return nfc.replace(REPEATED_SLASHES, "/").trim('/').takeUnless { it.isBlank() }
    }

    /**
     * Segment-prefix match on normalized values: `Russian` matches
     * `Russian` and `Russian/2024`, never `Russians`. A null (root)
     * [subPath] or a rule that normalizes to the root matches nothing:
     * the root's profile is the folder profile, not a rule.
     */
    fun matches(rulePath: String, subPath: String?): Boolean {
        val rule = normalize(rulePath) ?: return false
        val sub = normalize(subPath) ?: return false
        return sub == rule || sub.startsWith("$rule/")
    }

    /** The value of the deepest rule matching [subPath], or null. */
    fun <T> longestMatch(rules: Map<String, T>, subPath: String?): T? =
        rules.entries
            .filter { matches(it.key, subPath) }
            .maxByOrNull { normalize(it.key)?.length ?: 0 }
            ?.value
}
