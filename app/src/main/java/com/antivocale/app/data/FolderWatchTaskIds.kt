package com.antivocale.app.data

/**
 * The ONE owner of the folder-watch task id shape:
 * `fwatch_<treeUri.hashCode().toUInt()>_<nowMs>_<relativePath.hashCode().toUInt()>`.
 *
 * The tree uri rides the id so two folders holding a same-named file scanned
 * in the same millisecond never collide (the service drops duplicate task ids
 * silently); the RELATIVE PATH is hashed, not the basename, for the same
 * reason across two subfolders of one tree. The parsers recover the two
 * hashes: [effectiveSourceOrigin] attributes legacy rows by the tree hash, and
 * a bulk redo finds the source file again by the relative-path hash.
 */
object FolderWatchTaskIds {

    /** The legacy-recognition pattern; group 1 is the tree hash, group 3 the relative-path hash. */
    val LEGACY_PATTERN = Regex("""fwatch_(\d+)_\d+_\d+""")

    private val PARSE_PATTERN = Regex("""fwatch_(\d+)_(\d+)_(\d+)""")

    fun mint(treeUri: String, relativePath: String, nowMs: Long): String =
        mintWithHash(treeUri, relativePath.hashCode().toUInt(), nowMs)

    /** For a redo that kept only the relative-path hash of the original id. */
    fun mintWithHash(treeUri: String, relPathHash: UInt, nowMs: Long): String =
        "fwatch_${treeUri.hashCode().toUInt()}_${nowMs}_$relPathHash"

    /** The relative-path hash of a folder-watch [taskId], or null for any other shape. */
    fun relPathHashOf(taskId: String): UInt? =
        PARSE_PATTERN.matchEntire(taskId)?.groupValues?.get(3)?.toUIntOrNull()

    /** The tree-uri hash of a folder-watch [taskId], or null for any other shape. */
    fun treeHashOf(taskId: String): UInt? =
        PARSE_PATTERN.matchEntire(taskId)?.groupValues?.get(1)?.toUIntOrNull()
}
