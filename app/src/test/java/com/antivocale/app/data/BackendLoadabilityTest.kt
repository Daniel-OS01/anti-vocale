package com.antivocale.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [BackendLoadability]: request-time loadability read from persisted
 * state (the store's records and the saved model paths), never from the
 * cold-start-empty records StateFlow or a path's non-blankness alone.
 */
class BackendLoadabilityTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var root: File
    private lateinit var loadability: BackendLoadability

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        root = Files.createTempDirectory("backend-loadability").toFile()
        loadability = BackendLoadability(prefs, store)
    }

    private fun record(id: String, dir: String) = ExternalModelRecord(
        id = id,
        displayName = "Model $id",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = emptyList(),
        source = ExternalModelSource.URL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
    )

    @Test
    fun `an external id with a persisted record and dir is loadable`() = runTest {
        store.add(record("a", File(root, "a").apply { mkdirs() }.absolutePath))
        assertTrue(loadability.isLoadable("external:a"))
    }

    @Test
    fun `an external id with no record, a missing dir, or a quarantine is not loadable`() = runTest {
        assertFalse(loadability.isLoadable("external:none"))
        store.add(record("gone", File(root, "gone").absolutePath))
        assertFalse(loadability.isLoadable("external:gone"))
        store.add(record("q", File(root, "q").apply { mkdirs() }.absolutePath))
        store.quarantine("q")
        assertFalse(loadability.isLoadable("external:q"))
    }

    @Test
    fun `a built-in id needs a saved path that exists`() = runTest {
        assertFalse("no saved path", loadability.isLoadable("whisper"))
        prefs._sherpaModelPath("whisper").value = File(root, "missing").absolutePath
        assertFalse("saved path, files deleted", loadability.isLoadable("whisper"))
        prefs._sherpaModelPath("whisper").value = File(root, "whisper").apply { mkdirs() }.absolutePath
        assertTrue(loadability.isLoadable("whisper"))
    }

    @Test
    fun `the LLM id reads the generic model path and needs the file`() = runTest {
        prefs._modelPath.value = File(root, "gone.litertlm").absolutePath
        assertFalse(loadability.isLoadable("llm"))
        val file = File(root, "gemma.litertlm").apply { writeText("x") }
        prefs._modelPath.value = file.absolutePath
        assertTrue(loadability.isLoadable("llm"))
    }

    @Test
    fun `blank and unknown ids are not loadable`() = runTest {
        assertFalse(loadability.isLoadable(""))
        assertFalse(loadability.isLoadable("   "))
        assertFalse(loadability.isLoadable("no-such-backend"))
    }
}
