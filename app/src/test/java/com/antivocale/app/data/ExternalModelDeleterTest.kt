package com.antivocale.app.data

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared external-model delete semantics: a pin on the deleted record
 * (refinement or preview) comes back to its blank default, any other pin
 * is left alone.
 */
class ExternalModelDeleterTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: java.io.File

    private fun record(id: String) = ExternalModelRecord(
        id = id,
        displayName = "Model $id",
        dir = java.io.File(filesRoot, id).apply { mkdirs() }.absolutePath,
        family = ModelFamily.TRANSDUCER,
        modelType = "zipformer2",
        languages = emptyList(),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
        streaming = true,
    )

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("external-deleter").toFile()
    }

    @After
    fun tearDown() {
        filesRoot.deleteRecursively()
    }

    @Test
    fun `deleting the pinned preview model clears the preview pin`() = runTest {
        val saved = record("preview-id")
        store.add(saved)
        prefs._previewModelBackendId.value = saved.backendId

        val outcome = ExternalModelDeleter(store, prefs).delete(saved)

        assertTrue(outcome.clearedPreviewPin)
        assertFalse(outcome.clearedRefinementPin)
        assertEquals("", prefs._previewModelBackendId.value)
    }

    @Test
    fun `a preview pin on another model survives the delete`() = runTest {
        val saved = record("deleted-id")
        store.add(saved)
        prefs._previewModelBackendId.value = "external:other-id"

        val outcome = ExternalModelDeleter(store, prefs).delete(saved)

        assertFalse(outcome.clearedPreviewPin)
        assertEquals("external:other-id", prefs._previewModelBackendId.value)
    }

    @Test
    fun `deleting a pinned voice-note model clears both voice-note pins`() = runTest {
        val saved = record("voice-id")
        store.add(saved)
        prefs._voiceNoteBackendId.value = saved.backendId
        prefs._voiceNoteBusinessBackendId.value = saved.backendId

        val outcome = ExternalModelDeleter(store, prefs).delete(saved)

        assertTrue(outcome.clearedVoiceNotePin)
        assertTrue(outcome.clearedVoiceNoteBusinessPin)
        assertFalse(outcome.clearedPreviewPin)
        assertEquals("", prefs._voiceNoteBackendId.value)
        assertEquals("", prefs._voiceNoteBusinessBackendId.value)
    }

    @Test
    fun `voice-note pins on another model survive the delete`() = runTest {
        val saved = record("deleted-id")
        store.add(saved)
        prefs._voiceNoteBackendId.value = "external:other-id"
        prefs._voiceNoteBusinessBackendId.value = "whisper"

        val outcome = ExternalModelDeleter(store, prefs).delete(saved)

        assertFalse(outcome.clearedVoiceNotePin)
        assertFalse(outcome.clearedVoiceNoteBusinessPin)
        assertEquals("external:other-id", prefs._voiceNoteBackendId.value)
        assertEquals("whisper", prefs._voiceNoteBusinessBackendId.value)
    }
}
