package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder watch's recursive listing policy: relative-path joining, the
 * hidden-directory skip, the depth and entry caps (and the truncation flag
 * they raise), and the fail-the-whole-walk contract for a broken subtree.
 */
class FolderWalkTest {

    private fun file(name: String, id: String = name, size: Long = 100) =
        FolderWalk.Row(name, isDirectory = false, size = size, lastModified = 1, documentId = id)

    private fun dir(name: String, id: String) =
        FolderWalk.Row(name, isDirectory = true, size = 0, lastModified = 1, documentId = id)

    /** A fake provider: document id to its children; a missing id answers null. */
    private fun provider(tree: Map<String, List<FolderWalk.Row>>): (String) -> List<FolderWalk.Row>? =
        { id -> tree[id] }

    private fun FolderWalk.Result.names() = entries.map { it.child.name }

    @Test
    fun `root files keep bare names and nested files get relative paths`() {
        val tree = mapOf(
            "root" to listOf(file("rec.m4a", "r1"), dir("Calls", "calls")),
            "calls" to listOf(file("a.m4a", "c1"), dir("2026", "y")),
            "y" to listOf(file("b.opus", "y1", size = 7)),
        )
        val result = FolderWalk.walk("root", listDir = provider(tree))!!
        assertEquals(listOf("rec.m4a", "Calls/a.m4a", "Calls/2026/b.opus"), result.names())
        assertFalse(result.truncated)
        val nested = result.entries.single { it.child.name == "Calls/2026/b.opus" }
        assertEquals("y1", nested.documentId)
        assertEquals(7L, nested.child.size)
    }

    @Test
    fun `hidden directories are skipped without counting as truncation`() {
        val tree = mapOf(
            "root" to listOf(dir(".thumbnails", "t"), dir(".trashed", "tr"), dir("Calls", "c")),
            "c" to listOf(file("a.m4a")),
            // never queried: a query of these would also list their files
            "t" to listOf(file("thumb.m4a")),
            "tr" to listOf(file("deleted.m4a")),
        )
        val queried = mutableListOf<String>()
        val result = FolderWalk.walk("root") { id -> queried += id; tree[id] }!!
        assertEquals(listOf("Calls/a.m4a"), result.names())
        assertEquals(listOf("root", "c"), queried)
        assertFalse(result.truncated)
    }

    @Test
    fun `a failed root query fails the walk`() {
        assertNull(FolderWalk.walk("root", listDir = provider(emptyMap())))
    }

    @Test
    fun `a failed subdirectory query fails the whole walk instead of reading it as empty`() {
        val tree = mapOf(
            "root" to listOf(file("rec.m4a"), dir("Calls", "calls")),
            // "calls" answers null
        )
        assertNull(FolderWalk.walk("root", listDir = provider(tree)))
    }

    @Test
    fun `a throwing subdirectory query propagates`() {
        val tree = mapOf("root" to listOf(dir("Calls", "calls")))
        val thrown = runCatching {
            FolderWalk.walk("root") { id -> tree[id] ?: throw IllegalStateException("provider died") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `the depth cap stops descending and marks the walk truncated`() {
        val tree = mapOf(
            "root" to listOf(file("r.m4a"), dir("d1", "d1")),
            "d1" to listOf(file("a.m4a"), dir("d2", "d2")),
            "d2" to listOf(file("b.m4a")),
        )
        val capped = FolderWalk.walk("root", maxDepth = 1, listDir = provider(tree))!!
        assertEquals(listOf("r.m4a", "d1/a.m4a"), capped.names())
        assertTrue(capped.truncated)
        val full = FolderWalk.walk("root", maxDepth = 2, listDir = provider(tree))!!
        assertEquals(listOf("r.m4a", "d1/a.m4a", "d1/d2/b.m4a"), full.names())
        assertFalse(full.truncated)
        val rootOnly = FolderWalk.walk("root", maxDepth = 0, listDir = provider(tree))!!
        assertEquals(listOf("r.m4a"), rootOnly.names())
        assertTrue(rootOnly.truncated)
    }

    @Test
    fun `the entry cap bounds subdirectory rows but never truncates the root`() {
        val rootFiles = (1..5).map { file("r$it.m4a") }
        val tree = mapOf(
            "root" to rootFiles + dir("sub", "sub"),
            "sub" to listOf(file("c.m4a"), file("a.m4a"), file("b.m4a")),
        )
        // the root's 6 rows alone exceed a budget of 4: all of them are kept,
        // and the subdirectory is never queried
        val rootOver = FolderWalk.walk("root", maxEntries = 4, listDir = provider(tree))!!
        assertEquals(rootFiles.map { it.name }, rootOver.names())
        assertTrue(rootOver.truncated)
        // a budget of 8 leaves 2 rows for "sub": the first two by name
        val partial = FolderWalk.walk("root", maxEntries = 8, listDir = provider(tree))!!
        assertEquals(rootFiles.map { it.name } + listOf("sub/a.m4a", "sub/b.m4a"), partial.names())
        assertTrue(partial.truncated)
        // exactly enough budget is a complete walk
        val exact = FolderWalk.walk("root", maxEntries = 9, listDir = provider(tree))!!
        assertEquals(8, exact.entries.size)
        assertFalse(exact.truncated)
    }

    @Test
    fun `rows are walked in name order so a capped walk sees a stable subset`() {
        val tree = mapOf(
            "root" to listOf(dir("b", "b"), file("z.m4a"), dir("a", "a")),
            "a" to listOf(file("2.m4a"), file("1.m4a")),
            "b" to listOf(file("x.m4a")),
        )
        val result = FolderWalk.walk("root", listDir = provider(tree))!!
        assertEquals(listOf("z.m4a", "a/1.m4a", "a/2.m4a", "b/x.m4a"), result.names())
    }

    @Test
    fun `the default caps are the documented values`() {
        assertEquals(6, FolderWalk.MAX_DEPTH)
        assertEquals(2000, FolderWalk.MAX_ENTRIES)
    }
}
