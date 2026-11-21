package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.InstalledBackend
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.transcription.LanguageSupport
import com.antivocale.app.transcription.ScopeChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6: the rule editor's outcome line. The product case: Hebrew is the
 * global model, Russian calls sit in one subfolder, ru maps to GigaAM.
 */
class ProfileOutcomeTest {

    private val hebrew = InstalledBackend("whisper", "Whisper Hebrew")
    private val gigaam = InstalledBackend("gigaam", "GigaAM v3")
    private val installed = listOf(hebrew, gigaam)

    /** Whisper conditions on language and lacks ru; GigaAM ignores pins. */
    private fun supportOf(backend: String, language: String): LanguageSupport = when (backend) {
        "whisper" -> if (language == "he") LanguageSupport.HONORS else LanguageSupport.UNSUPPORTED
        else -> LanguageSupport.IGNORES
    }

    private fun outcome(
        chain: ScopeChain,
        languageModels: Map<String, String> = mapOf("ru" to "gigaam"),
        twoPassEnabled: Boolean = true,
        installedSet: List<InstalledBackend> = installed,
    ): ProfileOutcomeUi {
        val pairs = ProfileOutcome.supportPairs(chain, languageModels, "whisper")
        return ProfileOutcome.compute(
            chain = chain,
            languageModels = languageModels,
            globalBackendId = "whisper",
            installed = installedSet,
            twoPassEnabled = twoPassEnabled,
            support = pairs.associateWith { (b, l) -> supportOf(b, l) },
        )
    }

    private fun subfolder(profile: TranscriptionProfile?) =
        ProfileOutcome.chainWith(ScopeChain(), EditedScope.SUBFOLDER, profile)

    @Test
    fun `a language-only rule uses the mapped model and turns two-pass off`() {
        val result = outcome(subfolder(TranscriptionProfile(language = "ru")))
        assertEquals("gigaam", result.backendId)
        assertEquals("GigaAM v3", result.modelDisplayName)
        assertTrue(result.twoPassOff)
        assertNull(result.unsupportedLanguage)
    }

    @Test
    fun `two-pass off is reported only when two-pass is on`() {
        assertFalse(outcome(subfolder(TranscriptionProfile(language = "ru")), twoPassEnabled = false).twoPassOff)
    }

    @Test
    fun `an unmapped language the global model cannot transcribe warns with its code`() {
        val result = outcome(subfolder(TranscriptionProfile(language = "ru")), languageModels = emptyMap())
        assertEquals("whisper", result.backendId)
        assertEquals("ru", result.unsupportedLanguage)
        assertFalse(result.twoPassOff)
    }

    @Test
    fun `pinning the global model collapses and keeps two-pass`() {
        val result = outcome(subfolder(TranscriptionProfile(backendId = "whisper", language = "he")))
        assertEquals("whisper", result.backendId)
        assertFalse(result.twoPassOff)
        assertNull(result.unsupportedLanguage)
    }

    @Test
    fun `no rule at the edited scope inherits the folder profile below it`() {
        val base = ScopeChain(folder = TranscriptionProfile(language = "ru"))
        val chain = ProfileOutcome.chainWith(base, EditedScope.SUBFOLDER, null)
        assertNull(chain.subfolder)
        assertEquals("gigaam", outcome(chain).backendId)
    }

    @Test
    fun `auto at the edited scope stops the inherited language`() {
        val base = ScopeChain(folder = TranscriptionProfile(language = "ru"))
        val chain = ProfileOutcome.chainWith(
            base, EditedScope.SUBFOLDER, TranscriptionProfile(language = TranscriptionProfile.LANGUAGE_AUTO),
        )
        val result = outcome(chain)
        assertEquals("whisper", result.backendId)
        assertNull(result.unsupportedLanguage)
    }

    @Test
    fun `an empty edited profile counts as no rule`() {
        assertNull(ProfileOutcome.chainWith(ScopeChain(), EditedScope.FOLDER, TranscriptionProfile()).folder)
    }

    @Test
    fun `a mapped model that is not installed degrades to the global one`() {
        val result = outcome(
            subfolder(TranscriptionProfile(language = "ru")),
            installedSet = listOf(hebrew),
        )
        assertEquals("whisper", result.backendId)
        assertEquals("ru", result.unsupportedLanguage)
    }

    @Test
    fun `the global model's name is null when it is not installed`() {
        val result = outcome(ScopeChain(), installedSet = listOf(gigaam))
        assertEquals("whisper", result.backendId)
        assertNull(result.modelDisplayName)
    }

    @Test
    fun `support pairs cover every candidate backend for every scope language`() {
        val chain = ScopeChain(
            subfolder = TranscriptionProfile(backendId = "x", language = "ru"),
            folder = TranscriptionProfile(language = "he"),
        )
        assertEquals(
            setOf("whisper", "x", "gigaam").flatMap { b -> listOf(b to "ru", b to "he") }.toSet(),
            ProfileOutcome.supportPairs(chain, mapOf("ru" to "gigaam"), "whisper"),
        )
        assertTrue(ProfileOutcome.supportPairs(ScopeChain(), mapOf("ru" to "gigaam"), "whisper").isEmpty())
    }

    @Test
    fun `a missing support pair is a broken invariant, not a default`() {
        assertThrows(IllegalStateException::class.java) {
            ProfileOutcome.compute(
                chain = subfolder(TranscriptionProfile(language = "ru")),
                languageModels = emptyMap(),
                globalBackendId = "whisper",
                installed = installed,
                twoPassEnabled = true,
                support = emptyMap(),
            )
        }
    }
}
