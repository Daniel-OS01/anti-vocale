package com.antivocale.app.data

import com.antivocale.app.transcription.LanguageSupport
import com.antivocale.app.transcription.LanguageSupportLookup
import com.antivocale.app.transcription.ScopeKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R6: the origin-facts boundary. Real stores over a fake preferences
 * manager; the installed set and the language-support lookup are fakes.
 * Robolectric only for android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
class ScopeProfileSourceTest {

    private val prefs = FakePreferencesManager().apply { _transcriptionBackend.value = "parakeet" }
    private val folders = ScheduledFolderStore(prefs)
    private val scopes = ScopeProfileStore(prefs)

    private var installed = listOf("parakeet", "whisper", "gigaam", "qwen")
    private val activeModels = mockk<BackendLoadability> {
        coEvery { isLoadable(any()) } coAnswers { firstArg<String>() in installed }
    }

    /** Whisper and gigaam honor ru; everything else ignores languages. */
    private val support = mockk<LanguageSupportLookup> {
        coEvery { support(any(), any()) } coAnswers {
            val backend = firstArg<String>()
            val language = secondArg<String>()
            when {
                backend == "gigaam" && language == "ru" -> LanguageSupport.HONORS
                backend == "whisper" -> LanguageSupport.HONORS
                else -> LanguageSupport.IGNORES
            }
        }
    }

    private val source = ScopeProfileSource(prefs, folders, scopes, activeModels, support)

    private val tree = "content://tree/recorder"

    private suspend fun watched(
        profile: TranscriptionProfile? = null,
        rules: Map<String, TranscriptionProfile> = emptyMap(),
    ) {
        folders.add(WatchedFolder(treeUri = tree, displayName = "Recorder"))
        folders.setFolderProfile(tree, profile)
        rules.forEach { (path, rule) -> folders.setSubfolderRule(tree, path, rule) }
    }

    @Test
    fun `a folder rule routes a root file`() = runBlocking {
        watched(profile = TranscriptionProfile(backendId = "whisper"))
        val resolved = source.resolve(OriginFacts(folderTreeUri = tree, subPath = null))
        assertEquals("whisper", resolved.backendOverride)
        assertEquals(ScopeKind.FOLDER, resolved.decidedBy)
    }

    @Test
    fun `a subfolder rule beats the folder and the longest subfolder wins`() = runBlocking {
        watched(
            profile = TranscriptionProfile(backendId = "whisper"),
            rules = mapOf(
                "Calls" to TranscriptionProfile(backendId = "qwen"),
                "Calls/Work" to TranscriptionProfile(backendId = "gigaam"),
            ),
        )
        val shallow = source.resolve(OriginFacts(folderTreeUri = tree, subPath = "Calls/Home"))
        assertEquals("qwen", shallow.backendOverride)
        assertEquals(ScopeKind.SUBFOLDER, shallow.decidedBy)

        val deep = source.resolve(OriginFacts(folderTreeUri = tree, subPath = "Calls/Work/2026"))
        assertEquals("gigaam", deep.backendOverride)
        assertEquals(ScopeKind.SUBFOLDER, deep.decidedBy)

        val outside = source.resolve(OriginFacts(folderTreeUri = tree, subPath = "Other"))
        assertEquals("whisper", outside.backendOverride)
        assertEquals(ScopeKind.FOLDER, outside.decidedBy)
    }

    @Test
    fun `a ru subfolder with a ru map entry routes to gigaam and passes the language`() = runBlocking {
        watched(rules = mapOf("Russian" to TranscriptionProfile(language = "ru")))
        scopes.setLanguageModel("ru", "gigaam")
        val resolved = source.resolve(OriginFacts(folderTreeUri = tree, subPath = "Russian"))
        assertEquals("gigaam", resolved.backendOverride)
        assertEquals("ru", resolved.languageOverride)
        assertEquals(ScopeKind.SUBFOLDER, resolved.decidedBy)
        assertNull(resolved.warning)
    }

    @Test
    fun `a dangling pin degrades to the global model`() = runBlocking {
        watched(profile = TranscriptionProfile(backendId = "external:deleted"))
        val resolved = source.resolve(OriginFacts(folderTreeUri = tree))
        assertNull(resolved.backendOverride)
        assertEquals(ScopeKind.GLOBAL, resolved.decidedBy)
    }

    @Test
    fun `a voice-note source default is the source's own pin`() = runBlocking {
        prefs._voiceNoteBusinessBackendId.value = "whisper"
        val business = source.resolve(OriginFacts(voiceNoteSource = VoiceNoteSource.WHATSAPP_BUSINESS))
        assertEquals("whisper", business.backendOverride)
        assertEquals(ScopeKind.SOURCE_DEFAULT, business.decidedBy)

        val whatsapp = source.resolve(OriginFacts(voiceNoteSource = VoiceNoteSource.WHATSAPP))
        assertNull(whatsapp.backendOverride)
        assertEquals(ScopeKind.GLOBAL, whatsapp.decidedBy)
    }

    @Test
    fun `a sender id hit routes by the sender profile and a miss falls through`() = runBlocking {
        scopes.setSenderProfile("sender-opaque-1", TranscriptionProfile(backendId = "qwen"))
        prefs._voiceNoteBackendId.value = "whisper"

        val hit = source.resolve(
            OriginFacts(voiceNoteSource = VoiceNoteSource.WHATSAPP, senderStorageId = "sender-opaque-1"))
        assertEquals("qwen", hit.backendOverride)
        assertEquals(ScopeKind.SENDER, hit.decidedBy)

        val miss = source.resolve(
            OriginFacts(voiceNoteSource = VoiceNoteSource.WHATSAPP, senderStorageId = "sender-opaque-2"))
        assertEquals("whisper", miss.backendOverride)
        assertEquals(ScopeKind.SOURCE_DEFAULT, miss.decidedBy)
    }

    @Test
    fun `no profile anywhere takes the fast path without installed or support reads`() = runBlocking {
        scopes.setLanguageModel("ru", "gigaam")
        watched()
        val resolved = source.resolve(OriginFacts(folderTreeUri = tree, subPath = "Russian"))
        assertNull(resolved.backendOverride)
        assertNull(resolved.languageOverride)
        assertEquals(ScopeKind.GLOBAL, resolved.decidedBy)
        coVerify(exactly = 0) { activeModels.isLoadable(any()) }
        coVerify(exactly = 0) { support.support(any(), any()) }
    }

    @Test
    fun `an explicit profile still wins over the folder`() = runBlocking {
        watched(profile = TranscriptionProfile(backendId = "whisper"))
        val resolved = source.resolve(
            OriginFacts(folderTreeUri = tree, explicit = TranscriptionProfile.of("qwen", null)))
        assertEquals("qwen", resolved.backendOverride)
        assertEquals(ScopeKind.EXPLICIT, resolved.decidedBy)
    }

    @Test
    fun `only the two WhatsApp packages map back to a voice-note source`() {
        assertEquals(VoiceNoteSource.WHATSAPP,
            ScopeProfileSource.voiceNoteSourceForPackage(VoiceNoteSource.WHATSAPP.packageName))
        assertEquals(VoiceNoteSource.WHATSAPP_BUSINESS,
            ScopeProfileSource.voiceNoteSourceForPackage("com.whatsapp.w4b"))
        assertNull(ScopeProfileSource.voiceNoteSourceForPackage("org.telegram.messenger"))
        assertNull(ScopeProfileSource.voiceNoteSourceForPackage(null))
    }
}
