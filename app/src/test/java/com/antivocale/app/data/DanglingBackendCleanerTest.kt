package com.antivocale.app.data

import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the startup hygiene pass over the persisted transcription backend
 * (TASK-342 defect 2): a backend preference that points at an external record
 * which no longer resolves (deleted record, or record whose directory vanished)
 * must be reset to the default backend, otherwise every transcription request
 * fails on a backend id nothing can load.
 *
 * Uses [FakePreferencesManager] and a real temp directory for the dirExists
 * check, mirroring [ExternalModelStoreTest].
 */
class DanglingBackendCleanerTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: java.io.File

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

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("dangling-cleaner").toFile()
    }

    @Test
    fun `dangling external id with no record is reset to default`() = runTest {
        prefs._transcriptionBackend.value = "external:gone-id"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `dangling refinement pin is cleared to inherit (TASK-761)`() = runTest {
        prefs._refinementModelBackendId.value = "external:gone-pin"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals("", prefs._refinementModelBackendId.value)
    }

    @Test
    fun `valid refinement pin is left in place (TASK-761)`() = runTest {
        val dir = java.io.File(filesRoot, "pinned").apply { mkdirs() }
        val saved = record("pinned-id", dir.absolutePath)
        store.add(saved)
        prefs._refinementModelBackendId.value = saved.backendId

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(saved.backendId, prefs._refinementModelBackendId.value)
    }

    @Test
    fun `dangling preview pin is cleared to automatic`() = runTest {
        prefs._previewModelBackendId.value = "external:gone-preview"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals("", prefs._previewModelBackendId.value)
    }

    @Test
    fun `valid preview pin is left in place`() = runTest {
        val dir = java.io.File(filesRoot, "preview").apply { mkdirs() }
        val saved = record("preview-id", dir.absolutePath)
        store.add(saved)
        prefs._previewModelBackendId.value = saved.backendId

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(saved.backendId, prefs._previewModelBackendId.value)
    }

    @Test
    fun `external id whose record exists but directory vanished is reset to default`() = runTest {
        val dir = java.io.File(filesRoot, "vanished")
        val saved = record("half-gone", dir.absolutePath)
        store.add(saved)
        prefs._transcriptionBackend.value = saved.backendId
        // Directory never created: byId() uses validRecords, so the record is unreachable.

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `valid external record is left active`() = runTest {
        val dir = java.io.File(filesRoot, "present").apply { mkdirs() }
        val saved = record("present-id", dir.absolutePath)
        store.add(saved)
        prefs._transcriptionBackend.value = saved.backendId

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(saved.backendId, prefs._transcriptionBackend.value)
    }

    @Test
    fun `builtin backend is left untouched`() = runTest {
        prefs._transcriptionBackend.value = "whisper"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals("whisper", prefs._transcriptionBackend.value)
    }

    @Test
    fun `retired gguf backend id is reset to default`() = runTest {
        // TASK-639: the loader is gone; without the reset the stale id would
        // fall through to the LLM loader and cold-reload it on every request.
        prefs._transcriptionBackend.value = "gemma4_gguf"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `dangling voice-note pins are cleared to the active model`() = runTest {
        prefs._voiceNoteBackendId.value = "external:gone-voice"
        prefs._voiceNoteBusinessBackendId.value = "external:gone-business"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals("", prefs._voiceNoteBackendId.value)
        assertEquals("", prefs._voiceNoteBusinessBackendId.value)
    }

    @Test
    fun `valid and built-in voice-note pins are left in place`() = runTest {
        val dir = java.io.File(filesRoot, "voice").apply { mkdirs() }
        val saved = record("voice-id", dir.absolutePath)
        store.add(saved)
        prefs._voiceNoteBackendId.value = saved.backendId
        // Built-in pins dangle through deletion (scrubbed at delete time),
        // not record rot: the startup net leaves them alone.
        prefs._voiceNoteBusinessBackendId.value = "whisper"

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(saved.backendId, prefs._voiceNoteBackendId.value)
        assertEquals("whisper", prefs._voiceNoteBusinessBackendId.value)
    }

    // ---- R6: the profile-map locations ----

    private suspend fun seedProfileMaps(backendId: String) {
        val folders = ScheduledFolderStore(prefs)
        folders.add(WatchedFolder(treeUri = "a", displayName = "A"))
        folders.setFolderProfile("a", TranscriptionProfile(backendId = backendId, language = "ru"))
        folders.setSubfolderRule("a", "Russian", TranscriptionProfile(backendId = backendId))
        val scopes = ScopeProfileStore(prefs)
        scopes.setSenderProfile("s1", TranscriptionProfile(backendId = backendId))
        scopes.setLanguageModel("ru", backendId)
    }

    @Test
    fun `profile maps naming an external id with no record are scrubbed`() = runTest {
        seedProfileMaps("external:gone-id")

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()

        val a = ScheduledFolderStore(prefs).byUri("a")!!
        assertEquals(TranscriptionProfile(language = "ru"), a.profile)
        assertEquals(emptyMap<String, TranscriptionProfile>(), a.subfolderRules)
        assertEquals(emptyMap<String, TranscriptionProfile>(), ScopeProfileStore(prefs).senderProfiles.first())
        assertEquals(emptyMap<String, String>(), ScopeProfileStore(prefs).languageModels())
    }

    @Test
    fun `profile maps naming a wiped but repairable record are kept`() = runTest {
        // The record survives, its directory does not: ExternalImportRepairer
        // can bring it back, and the rules must still be there when it does.
        val saved = record("wiped", java.io.File(filesRoot, "wiped").absolutePath)
        store.add(saved)
        seedProfileMaps(saved.backendId)

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()

        val a = ScheduledFolderStore(prefs).byUri("a")!!
        assertEquals(saved.backendId, a.profile?.backendId)
        assertEquals(saved.backendId, a.subfolderRules["Russian"]?.backendId)
        assertEquals(saved.backendId, ScopeProfileStore(prefs).senderProfile("s1")?.backendId)
        assertEquals(mapOf("ru" to saved.backendId), ScopeProfileStore(prefs).languageModels())
    }

    @Test
    fun `profile maps naming built-ins are left alone`() = runTest {
        seedProfileMaps("gigaam")

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()

        assertEquals("gigaam", ScheduledFolderStore(prefs).byUri("a")?.profile?.backendId)
        assertEquals(mapOf("ru" to "gigaam"), ScopeProfileStore(prefs).languageModels())
    }
}
