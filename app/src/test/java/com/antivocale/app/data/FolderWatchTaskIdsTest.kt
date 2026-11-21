package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ONE owner of the folder-watch task id shape: minting and both parsers. */
class FolderWatchTaskIdsTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val relPath = "2026/11/rec.m4a"

    @Test
    fun `mint keeps the exact pre-extraction format`() {
        val now = 1_700_000_000_000L
        // The inline format ScheduledFolderWorker built before the extraction.
        val legacy = "fwatch_${tree.hashCode().toUInt()}_${now}_${relPath.hashCode().toUInt()}"
        assertEquals(legacy, FolderWatchTaskIds.mint(tree, relPath, now))
    }

    @Test
    fun `both hashes round trip through a minted id`() {
        val id = FolderWatchTaskIds.mint(tree, relPath, 42L)
        assertEquals(relPath.hashCode().toUInt(), FolderWatchTaskIds.relPathHashOf(id))
        assertEquals(tree.hashCode().toUInt(), FolderWatchTaskIds.treeHashOf(id))
    }

    @Test
    fun `mintWithHash round trips and equals mint for the same path`() {
        val hash = relPath.hashCode().toUInt()
        val id = FolderWatchTaskIds.mintWithHash(tree, hash, 7L)
        assertEquals(hash, FolderWatchTaskIds.relPathHashOf(id))
        assertEquals(tree.hashCode().toUInt(), FolderWatchTaskIds.treeHashOf(id))
        assertEquals(FolderWatchTaskIds.mint(tree, relPath, 7L), id)
    }

    @Test
    fun `a hash above Int MAX_VALUE survives the unsigned round trip`() {
        val big = UInt.MAX_VALUE
        assertEquals(big, FolderWatchTaskIds.relPathHashOf(FolderWatchTaskIds.mintWithHash(tree, big, 1L)))
    }

    @Test
    fun `a non-fwatch id parses to null`() {
        listOf(
            "3f0c9a2e-uuid",
            "voicenote_abc",
            "fwatch_notanumber_1_2",
            "fwatch_1_2",
            "fwatch_${tree.hashCode().toUInt()}",
            "fwatch_1_2_99999999999", // relative hash beyond UInt
        ).forEach { id ->
            assertNull(id, FolderWatchTaskIds.relPathHashOf(id))
        }
        assertNull(FolderWatchTaskIds.treeHashOf("3f0c9a2e-uuid"))
    }

    @Test
    fun `the legacy pattern still recognizes a minted id`() {
        val id = FolderWatchTaskIds.mint(tree, relPath, 1_700_000_000_000L)
        val match = FolderWatchTaskIds.LEGACY_PATTERN.matchEntire(id)
        assertEquals(tree.hashCode().toUInt().toString(), match?.groupValues?.get(1))
        // And effectiveSourceOrigin, its consumer, recovers the folder from it.
        val origin = effectiveSourceOrigin(
            id,
            SourceOrigin(kind = null, folderKey = null, folderName = null, subPath = null),
            listOf(WatchedFolder(treeUri = tree, displayName = "Calls")),
        )
        assertEquals(SourceOrigin(SourceKind.FOLDER_WATCH, tree, "Calls", null), origin)
    }
}
