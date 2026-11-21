package com.antivocale.app.transcription

import com.antivocale.app.data.EXTERNAL_MODELS_DIR_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The startup orphan sweep must never delete the external-import root.
 *
 * Regression: the `llm` catalog entry stores its Gemma variants under `files/models`, the
 * parent of `files/models/external` (every user-imported model). The name-based sweep
 * treated `external` as a stale llm variant and deleted all imports whenever the Models
 * screen's ViewModel was created (observed on 1.16.1: "Deleted orphaned model dir 'external'
 * (1.6 GB) ... llm=1.6 GB").
 */
class OrphanedModelDirCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirWithFile(parent: File, name: String): File =
        File(parent, name).apply { mkdirs(); File(this, "model.onnx").writeBytes(ByteArray(16)) }

    @Test
    fun `protected dir survives while true orphans are deleted`() {
        val storage = tmp.newFolder("models")
        val variant = dirWithFile(storage, "gemma-4-e2b")
        val external = dirWithFile(storage, "external")
        dirWithFile(external, "Hebrew-Zipformer2-66M-Streaming-cfd4dd")
        val stale = dirWithFile(storage, "stale-variant")

        val reclaimed = cleanOrphanedModelDirs(storage, setOf("gemma-4-e2b"), setOf(external))

        assertTrue(variant.exists())
        assertTrue(File(external, "Hebrew-Zipformer2-66M-Streaming-cfd4dd/model.onnx").exists())
        assertFalse(stale.exists())
        assertEquals(16L, reclaimed)
    }

    @Test
    fun `protected dirs are matched by canonical path, not by spelling`() {
        val storage = tmp.newFolder("models")
        val external = dirWithFile(storage, "external")
        val spelledDifferently = File(storage, "./sub/../external")

        cleanOrphanedModelDirs(storage, emptySet(), setOf(spelledDifferently))

        assertTrue(external.exists())
    }

    @Test
    fun `real catalog - no built-in sweep deletes the external import root`() {
        val filesDir = tmp.newFolder("files")
        val externalRoot = File(filesDir, EXTERNAL_MODELS_DIR_NAME)
        dirWithFile(externalRoot, "Imported-Model-abc123")
        val entries = bundledCatalogEntriesForTest()

        // Precondition that makes the guard necessary: some built-in entry's storage dir
        // is the parent of the external root (today: llm -> "models").
        val sharing = entries.filter { File(filesDir, it.storageDir ?: it.id).canonicalFile == externalRoot.parentFile!!.canonicalFile }
        assertTrue("expected a built-in entry sharing files/models (llm)", sharing.isNotEmpty())

        // Without the guard the shared entry's sweep deletes the imports (the bug).
        val unguarded = tmp.newFolder("unguarded")
        dirWithFile(File(unguarded, EXTERNAL_MODELS_DIR_NAME), "Imported-Model-abc123")
        val shared = sharing.first()
        cleanOrphanedModelDirs(File(unguarded, shared.storageDir ?: shared.id), shared.variants.map { it.dirName }.toSet())
        assertFalse(File(unguarded, EXTERNAL_MODELS_DIR_NAME).exists())

        // With the guard, as ModelViewModel now passes it, every entry's sweep keeps them.
        for (entry in entries) {
            cleanOrphanedModelDirs(
                File(filesDir, entry.storageDir ?: entry.id),
                entry.variants.map { it.dirName }.toSet(),
                protectedDirs = setOf(externalRoot),
            )
        }
        assertTrue(File(externalRoot, "Imported-Model-abc123/model.onnx").exists())
    }
}
