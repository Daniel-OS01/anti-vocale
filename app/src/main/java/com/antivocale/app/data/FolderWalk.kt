package com.antivocale.app.data

/**
 * The pure decision half of the folder watch's recursive listing: which
 * directories are descended, how far, how many rows are read, and how each
 * file's relative path ('/'-separated, from the watched root) is built. The
 * worker's FolderListing supplies the DocumentsContract child query as
 * [walk]'s `listDir` lambda, so the caps and the failure contract are
 * JVM-unit-testable without a ContentResolver.
 *
 * CAPS (a recorder or voice-note tree is shallow and holds hundreds of files;
 * the caps exist for a mis-picked tree such as the whole of Download or a
 * storage root, where an unbounded walk would cost one binder query per
 * directory and rewrite a huge snapshot every scan):
 *  - [MAX_DEPTH] = 6: files up to six directory levels below the root are
 *    listed. Recorder layouts nest by app/year/month/contact at most four
 *    deep; six leaves margin without following a storage tree all the way.
 *  - [MAX_ENTRIES] = 2000 rows in total (files AND directories, audio or
 *    not: the cost is the rows read). This bounds what ONE walk lists; the
 *    persisted snapshot of a capped tree also keeps entries the walk did not
 *    reach, under FolderScanPlanner.DEFAULT_UNSEEN_RETENTION. The ROOT listing is always read whole, as before
 *    recursion existed (a root-only folder of any size keeps working
 *    unchanged); its rows count toward the budget, which then bounds only
 *    what subdirectories add.
 *  - Hidden directories (a name starting with '.', e.g. `.thumbnails`,
 *    `.trashed`, `.pending`) are skipped outright: they hold MediaStore
 *    bookkeeping, never recordings. Skipping them is not truncation.
 *
 * Directory rows are visited in name order (BFS, each directory's rows
 * sorted by name) so a capped walk sees the SAME subset on every scan of an
 * unchanged tree; provider order is not guaranteed stable.
 *
 * FAILURE CONTRACT: a null answer for ANY directory (root or sub) fails the
 * whole walk (null = scan abandoned, the previous snapshot kept verbatim).
 * Treating one unreadable subdirectory as empty would make the planner see
 * that subtree vanish: its awaiting entries drop (stability restarts) and,
 * worse, a later readable scan would read its files as first sights. A
 * throwing `listDir` propagates to the caller, which already treats a throw
 * as scan-abandoned.
 *
 * [Result.truncated] reports that some subtree was NOT walked (the depth cap
 * or the entry budget stopped it). The worker never lets a truncated walk
 * complete the recursive-upgrade baseline (see
 * [FolderScanPlanner.recursiveUpgradeBaseline]).
 */
object FolderWalk {

    const val MAX_DEPTH = 6
    const val MAX_ENTRIES = 2000

    /** One row of a directory's child query, already filtered for a name and a MIME. */
    data class Row(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val documentId: String,
    )

    /** One listed file: [child]'s name is its relative path; [documentId] builds its uri. */
    data class Entry(val child: FolderScanPlanner.Child, val documentId: String)

    data class Result(val entries: List<Entry>, val truncated: Boolean)

    private data class PendingDir(val documentId: String, val path: String, val depth: Int)

    /**
     * Walks the tree under [rootDocId]. Files directly in the root keep their
     * bare names; a file in `Calls/2026` is `Calls/2026/rec.m4a`.
     *
     * @return null when any directory's listing failed (see the class KDoc).
     */
    fun walk(
        rootDocId: String,
        maxDepth: Int = MAX_DEPTH,
        maxEntries: Int = MAX_ENTRIES,
        listDir: (dirDocId: String) -> List<Row>?,
    ): Result? {
        require(maxDepth >= 0) { "maxDepth must be >= 0 (was $maxDepth)" }
        require(maxEntries > 0) { "maxEntries must be > 0 (was $maxEntries)" }
        val rootRows = listDir(rootDocId)?.sortedBy { it.name } ?: return null
        val entries = ArrayList<Entry>()
        val queue = ArrayDeque<PendingDir>()
        var truncated = false
        var budget = maxEntries - rootRows.size

        fun accept(rows: List<Row>, prefix: String, depth: Int) {
            for (row in rows) {
                val path = if (prefix.isEmpty()) row.name else "$prefix/${row.name}"
                if (row.isDirectory) {
                    if (row.name.startsWith('.')) continue
                    if (depth + 1 > maxDepth) {
                        truncated = true
                        continue
                    }
                    queue.addLast(PendingDir(row.documentId, path, depth + 1))
                } else {
                    entries += Entry(FolderScanPlanner.Child(path, row.size, row.lastModified), row.documentId)
                }
            }
        }

        accept(rootRows, prefix = "", depth = 0)
        while (queue.isNotEmpty()) {
            if (budget <= 0) {
                truncated = true
                break
            }
            val dir = queue.removeFirst()
            val rows = listDir(dir.documentId)?.sortedBy { it.name } ?: return null
            val taken = if (rows.size > budget) {
                truncated = true
                rows.take(budget)
            } else {
                rows
            }
            budget -= taken.size
            accept(taken, dir.path, dir.depth)
        }
        return Result(entries, truncated)
    }

    /** [listDirectory]'s answer for one subfolder of the tree. */
    sealed class DirFiles {
        /** The directory's FILE children, fully listed: (relative path, document id). */
        data class Found(val files: List<Pair<String, String>>) : DirFiles()

        /** Some segment of the subfolder has no directory of that name. */
        object NotFound : DirFiles()

        /** Some segment names two or more directories: no single directory to read. */
        object Ambiguous : DirFiles()

        /** A directory's query answered null (no answer, partial or errored). */
        object Unavailable : DirFiles()
    }

    /**
     * One directory of the tree, by the [subPath] that [walk] would give its
     * files (null = the root): resolves it segment by segment from
     * [rootDocId], picking at each level the child DIRECTORY whose name
     * equals the segment, then lists that directory's file children one
     * level deep and uncapped. Relative paths are joined exactly as [walk]
     * joins them ('/' between names, a bare name at the root), so a path the
     * walk recorded round-trips. Not capped by [MAX_ENTRIES]: the work is
     * one query per segment plus one listing.
     *
     * A display name holding '/' joins into the same relative path as
     * nested names (a directory "A/B" beside "A" holding "B" both give
     * "A/B"), so the path no longer names one directory: such a child
     * DIRECTORY matching the remaining path at a segment boundary, or any
     * child holding '/' in the resolved directory, is [DirFiles.Ambiguous].
     */
    fun listDirectory(
        rootDocId: String,
        subPath: String?,
        listDir: (dirDocId: String) -> List<Row>?,
    ): DirFiles {
        var dirDocId = rootDocId
        val segments = subPath?.split('/').orEmpty()
        for ((index, segment) in segments.withIndex()) {
            val rows = listDir(dirDocId) ?: return DirFiles.Unavailable
            val remaining = segments.subList(index, segments.size).joinToString("/")
            val aliased = rows.any {
                it.isDirectory && '/' in it.name &&
                    (remaining == it.name || remaining.startsWith(it.name + "/"))
            }
            if (aliased) return DirFiles.Ambiguous
            val dirs = rows.filter { it.isDirectory && it.name == segment }
            dirDocId = when (dirs.size) {
                0 -> return DirFiles.NotFound
                1 -> dirs.single().documentId
                else -> return DirFiles.Ambiguous
            }
        }
        val rows = listDir(dirDocId) ?: return DirFiles.Unavailable
        if (rows.any { '/' in it.name }) return DirFiles.Ambiguous
        return DirFiles.Found(
            rows.filter { !it.isDirectory }.map { row ->
                (if (subPath == null) row.name else "$subPath/${row.name}") to row.documentId
            },
        )
    }
}
