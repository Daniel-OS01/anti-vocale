package com.antivocale.app.data

import com.antivocale.app.data.TranscriptionPinScrubber.Pin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one delete-time pin scrub: every pin naming the deleted model is
 *  blanked, every other pin is left alone. */
class TranscriptionPinScrubberTest {

    private val prefs = FakePreferencesManager()

    @Test
    fun `every pin on the deleted model is blanked and reported`() = runTest {
        prefs._refinementModelBackendId.value = "whisper"
        prefs._previewModelBackendId.value = "whisper"
        prefs._voiceNoteBackendId.value = "whisper"
        prefs._voiceNoteBusinessBackendId.value = "whisper"

        val cleared = TranscriptionPinScrubber.scrub(prefs, "whisper")

        assertEquals(setOf(Pin.REFINEMENT, Pin.PREVIEW, Pin.VOICE_NOTE, Pin.VOICE_NOTE_BUSINESS), cleared)
        assertEquals("", prefs._refinementModelBackendId.value)
        assertEquals("", prefs._previewModelBackendId.value)
        assertEquals("", prefs._voiceNoteBackendId.value)
        assertEquals("", prefs._voiceNoteBusinessBackendId.value)
    }

    @Test
    fun `pins on other models survive`() = runTest {
        prefs._refinementModelBackendId.value = "parakeet"
        prefs._previewModelBackendId.value = ""
        prefs._voiceNoteBackendId.value = "whisper"
        prefs._voiceNoteBusinessBackendId.value = "external:other"

        val cleared = TranscriptionPinScrubber.scrub(prefs, "whisper")

        assertEquals(setOf(Pin.VOICE_NOTE), cleared)
        assertEquals("parakeet", prefs._refinementModelBackendId.value)
        assertEquals("external:other", prefs._voiceNoteBusinessBackendId.value)
    }

    @Test
    fun `a blank deleted id clears nothing`() = runTest {
        assertEquals(emptySet<Pin>(), TranscriptionPinScrubber.scrub(prefs, ""))
    }

    // ---- R6: the profile-map locations ----

    private val folders = ScheduledFolderStore(prefs)
    private val scopes = ScopeProfileStore(prefs)

    @Test
    fun `deleting a model blanks it in folder profiles and subfolder rules`() = runTest {
        folders.add(WatchedFolder(treeUri = "a", displayName = "A"))
        folders.setFolderProfile("a", TranscriptionProfile(backendId = "gigaam", language = "ru"))
        folders.setSubfolderRule("a", "Russian", TranscriptionProfile(backendId = "gigaam"))
        folders.setSubfolderRule("a", "Italian", TranscriptionProfile(backendId = "whisper"))

        val cleared = TranscriptionPinScrubber.scrub(prefs, "gigaam")

        assertEquals(setOf(Pin.FOLDER_PROFILE), cleared)
        val a = folders.byUri("a")!!
        assertEquals(TranscriptionProfile(language = "ru"), a.profile)
        assertEquals(mapOf("Italian" to TranscriptionProfile(backendId = "whisper")), a.subfolderRules)
    }

    @Test
    fun `deleting a model blanks it in sender profiles`() = runTest {
        scopes.setSenderProfile("s1", TranscriptionProfile(backendId = "gigaam", language = "ru"))
        scopes.setSenderProfile("s2", TranscriptionProfile(backendId = "gigaam"))

        val cleared = TranscriptionPinScrubber.scrub(prefs, "gigaam")

        assertEquals(setOf(Pin.SENDER_PROFILE), cleared)
        assertEquals(mapOf("s1" to TranscriptionProfile(language = "ru")), scopes.senderProfiles.first())
    }

    @Test
    fun `deleting a model removes its language-model entries`() = runTest {
        scopes.setLanguageModel("ru", "gigaam")
        scopes.setLanguageModel("it", "whisper")

        val cleared = TranscriptionPinScrubber.scrub(prefs, "gigaam")

        assertEquals(setOf(Pin.LANGUAGE_MODEL), cleared)
        assertEquals(mapOf("it" to "whisper"), scopes.languageModels())
    }

    @Test
    fun `profile maps naming other models are left untouched`() = runTest {
        folders.add(WatchedFolder(treeUri = "a", displayName = "A"))
        folders.setFolderProfile("a", TranscriptionProfile(backendId = "whisper"))
        scopes.setSenderProfile("s1", TranscriptionProfile(backendId = "whisper"))
        scopes.setLanguageModel("it", "whisper")
        val before = Triple(prefs._scheduledFoldersJson.value, prefs._senderProfilesJson.value, prefs._languageModelMapJson.value)

        assertEquals(emptySet<Pin>(), TranscriptionPinScrubber.scrub(prefs, "gigaam"))
        assertEquals(
            before,
            Triple(prefs._scheduledFoldersJson.value, prefs._senderProfilesJson.value, prefs._languageModelMapJson.value),
        )
    }
}
