package com.antivocale.app.service

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure restage matcher: subfolder AND relative-path hash, never a guess. */
class FolderRestageMatchTest {

    private val tree = "content://tree/primary%3ACalls"

    private fun target(relPath: String, subPath: String?) = BulkRedoItem.NeedsRestage(
        row = LogEntry(id = relPath, taskId = "t-$relPath", type = LogEntry.Type.AUDIO, status = LogEntry.Status.SUCCESS),
        treeUri = tree,
        subPath = subPath,
        relPathHash = relPath.hashCode().toUInt(),
    )

    @Test
    fun `the one file with the same subfolder and hash is found`() {
        val t = target("2026/rec.m4a", "2026")
        val listing = listOf("2026/other.m4a" to "uri-other", "2026/rec.m4a" to "uri-rec", "root.m4a" to "uri-root")
        assertEquals(FolderRestage.RestageMatch.Found("uri-rec"), FolderRestage.match(listOf(t), listing)[t])
    }

    @Test
    fun `a root file matches a null subfolder`() {
        val t = target("rec.m4a", null)
        assertEquals(
            FolderRestage.RestageMatch.Found("uri-root"),
            FolderRestage.match(listOf(t), listOf("rec.m4a" to "uri-root"))[t],
        )
    }

    @Test
    fun `a file missing from the listing is not found`() {
        val t = target("2026/rec.m4a", "2026")
        val listing = listOf("2026/other.m4a" to "uri-other")
        assertEquals(FolderRestage.RestageMatch.NotFound, FolderRestage.match(listOf(t), listing)[t])
    }

    @Test
    fun `two files with one hash in the same subfolder are ambiguous`() {
        // "Aa" and "BB" share String.hashCode (2112): 'A'*31+'a' = 65*31+97 = 2112
        // and 'B'*31+'B' = 66*31+66 = 2112. A common prefix and suffix keep the
        // equality (h(p+s) = h(p)*31^|s| + h(s)), so the two full paths collide.
        val a = "2026/Aa.m4a"
        val b = "2026/BB.m4a"
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals(a.hashCode(), b.hashCode())
        val t = target(a, "2026")
        assertEquals(
            FolderRestage.RestageMatch.Ambiguous,
            FolderRestage.match(listOf(t), listOf(a to "uri-a", b to "uri-b"))[t],
        )
    }

    @Test
    fun `a same-hash file in another subfolder is not matched`() {
        // The relative paths differ only by the subfolder, so craft a hash
        // twin across subfolders: "Aa/x.m4a" and "BB/x.m4a" collide too.
        val mine = "Aa/x.m4a"
        val other = "BB/x.m4a"
        assertEquals(mine.hashCode(), other.hashCode())
        val t = target(mine, "Aa")
        assertEquals(
            FolderRestage.RestageMatch.Found("uri-mine"),
            FolderRestage.match(listOf(t), listOf(other to "uri-other", mine to "uri-mine"))[t],
        )
        // And with only the other subfolder's twin listed, nothing matches.
        assertEquals(
            FolderRestage.RestageMatch.NotFound,
            FolderRestage.match(listOf(t), listOf(other to "uri-other"))[t],
        )
    }

    @Test
    fun `every target gets a verdict`() {
        val found = target("a.m4a", null)
        val missing = target("b.m4a", null)
        val result = FolderRestage.match(listOf(found, missing), listOf("a.m4a" to 1))
        assertEquals(setOf(found, missing), result.keys)
        assertEquals(FolderRestage.RestageMatch.Found(1), result[found])
        assertEquals(FolderRestage.RestageMatch.NotFound, result[missing])
    }
}
