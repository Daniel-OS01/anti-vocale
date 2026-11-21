package com.antivocale.app.transcription

import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.TranscriptionProfile.Companion.LANGUAGE_AUTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared transcription-profile resolver's rules, one case per row:
 * walk order, `auto`, pins vs language-map models, loadability, explicit
 * precedence, language support and the collapse onto the global backend.
 */
class TranscriptionProfileResolverTest {

    private val global = "global"
    private val loadable = setOf("global", "a", "b", "c", "d", "map-it", "map-de")

    private fun resolve(
        chain: ScopeChain,
        languageModels: Map<String, String> = emptyMap(),
        isLoadable: (String) -> Boolean = { it in loadable },
        support: (String, String) -> LanguageSupport = { _, _ -> LanguageSupport.HONORS },
    ) = TranscriptionProfileResolver.resolve(chain, languageModels, global, isLoadable, support)

    private fun p(backendId: String? = null, language: String? = null) =
        TranscriptionProfile(backendId, language)

    private data class Row(
        val name: String,
        val chain: ScopeChain,
        val backend: String?,
        val language: String?,
        val decidedBy: ScopeKind,
        val languageModels: Map<String, String> = emptyMap(),
    )

    @Test
    fun `the walk order decides the backend most specific first`() {
        val all = ScopeChain(sender = p("a"), subfolder = p("b"), folder = p("c"), sourceDefault = p("d"))
        val rows = listOf(
            Row("sender wins", all, "a", null, ScopeKind.SENDER),
            Row("subfolder next", all.copy(sender = null), "b", null, ScopeKind.SUBFOLDER),
            Row("folder next", all.copy(sender = null, subfolder = null), "c", null, ScopeKind.FOLDER),
            Row("source default last",
                all.copy(sender = null, subfolder = null, folder = null), "d", null, ScopeKind.SOURCE_DEFAULT),
            Row("empty profiles are skipped",
                ScopeChain(sender = p(), subfolder = p(), folder = p(), sourceDefault = p("d")),
                "d", null, ScopeKind.SOURCE_DEFAULT),
            Row("explicit beats every scope", all.copy(explicit = p("x")), "x", null, ScopeKind.EXPLICIT),
        )
        rows.forEach { row ->
            val r = resolve(row.chain, row.languageModels)
            assertEquals(row.name, row.backend, r.backendOverride)
            assertEquals(row.name, row.language, r.languageOverride)
            assertEquals(row.name, row.decidedBy, r.decidedBy)
        }
    }

    @Test
    fun `the language walk takes the first scope language`() {
        val r = resolve(ScopeChain(subfolder = p(language = "it"), folder = p(language = "de")))
        assertEquals("it", r.languageOverride)
        assertEquals(ScopeKind.GLOBAL, r.decidedBy)
        assertNull(r.backendOverride)
    }

    @Test
    fun `scope auto stops inheritance and overrides the global language with detection`() {
        var supportAsked = false
        val r = resolve(
            ScopeChain(sender = p(language = LANGUAGE_AUTO), folder = p(language = "de")),
            languageModels = mapOf("de" to "map-de", LANGUAGE_AUTO to "map-it"),
            support = { _, _ -> supportAsked = true; LanguageSupport.UNSUPPORTED },
        )
        assertEquals("scope auto rides as auto, never the global pin", LANGUAGE_AUTO, r.languageOverride)
        assertNull("no map lookup for auto, and the folder's language never set L", r.backendOverride)
        assertEquals(ScopeKind.GLOBAL, r.decidedBy)
        assertNull("no support check for auto", r.warning)
        assertEquals("no support check for auto", false, supportAsked)
    }

    @Test
    fun `an explicit auto passes through as auto`() {
        val r = resolve(ScopeChain(explicit = p(language = LANGUAGE_AUTO), folder = p(language = "de")))
        assertEquals(LANGUAGE_AUTO, r.languageOverride)
    }

    @Test
    fun `a scope pin beats the language map at the same scope`() {
        val r = resolve(
            ScopeChain(folder = p("c", "it")),
            languageModels = mapOf("it" to "map-it"),
        )
        assertEquals("c", r.backendOverride)
        assertEquals(ScopeKind.FOLDER, r.decidedBy)
        assertEquals("it", r.languageOverride)
    }

    @Test
    fun `the language map applies only at the scope that set the language`() {
        val atSettingScope = resolve(
            ScopeChain(folder = p(language = "it"), sourceDefault = p("d")),
            languageModels = mapOf("it" to "map-it"),
        )
        assertEquals("map-it", atSettingScope.backendOverride)
        assertEquals(ScopeKind.FOLDER, atSettingScope.decidedBy)

        // A more specific scope with a pin decides before the map is reached.
        val pinFirst = resolve(
            ScopeChain(sender = p("a"), folder = p(language = "it")),
            languageModels = mapOf("it" to "map-it"),
        )
        assertEquals("a", pinFirst.backendOverride)
        assertEquals(ScopeKind.SENDER, pinFirst.decidedBy)

        // The language came from the sender; the folder below has no say.
        val belowSettingScope = resolve(
            ScopeChain(sender = p(language = "it"), sourceDefault = p("d")),
            languageModels = mapOf("it" to "map-it"),
        )
        assertEquals("map-it", belowSettingScope.backendOverride)
        assertEquals(ScopeKind.SENDER, belowSettingScope.decidedBy)
    }

    @Test
    fun `an unloadable map model falls through to the next scope`() {
        val r = resolve(
            ScopeChain(folder = p(language = "it"), sourceDefault = p("d")),
            languageModels = mapOf("it" to "not-installed"),
        )
        assertEquals("d", r.backendOverride)
        assertEquals(ScopeKind.SOURCE_DEFAULT, r.decidedBy)
    }

    @Test
    fun `a dangling scope pin falls through`() {
        val toNext = resolve(ScopeChain(sender = p("gone"), folder = p("c")))
        assertEquals("c", toNext.backendOverride)
        assertEquals(ScopeKind.FOLDER, toNext.decidedBy)

        val toGlobal = resolve(ScopeChain(sourceDefault = p("gone")))
        assertNull(toGlobal.backendOverride)
        assertEquals(ScopeKind.GLOBAL, toGlobal.decidedBy)
    }

    @Test
    fun `the explicit backend is neither loadability-checked nor collapsed`() {
        var checked = 0
        val unloadable = resolve(
            ScopeChain(explicit = p("not-installed")),
            isLoadable = { checked++; it in loadable },
        )
        assertEquals("not-installed", unloadable.backendOverride)
        assertEquals(ScopeKind.EXPLICIT, unloadable.decidedBy)
        assertEquals("the explicit backend is never checked", 0, checked)

        val equalToGlobal = resolve(ScopeChain(explicit = p(global)))
        assertEquals(global, equalToGlobal.backendOverride)
        assertEquals(ScopeKind.EXPLICIT, equalToGlobal.decidedBy)
    }

    @Test
    fun `a language-derived model never replaces an explicit backend`() {
        val r = resolve(
            ScopeChain(explicit = p("x"), folder = p(language = "it")),
            languageModels = mapOf("it" to "map-it"),
        )
        assertEquals("x", r.backendOverride)
        assertEquals(ScopeKind.EXPLICIT, r.decidedBy)
        assertEquals("it", r.languageOverride)
    }

    @Test
    fun `an explicit language passes through as given`() {
        var supportCalls = 0
        val r = resolve(
            ScopeChain(explicit = p(language = "xx-unknown"), sender = p("a", "de")),
            support = { _, _ -> supportCalls++; LanguageSupport.UNSUPPORTED },
        )
        assertEquals("xx-unknown", r.languageOverride)
        assertNull(r.warning)
        assertEquals("an explicit language is never validated", 0, supportCalls)
        assertEquals("the scope still fills the backend", "a", r.backendOverride)
        assertEquals(ScopeKind.SENDER, r.decidedBy)
    }

    @Test
    fun `explicit fields override per field`() {
        val r = resolve(ScopeChain(explicit = p("x"), sender = p("a", "de")))
        assertEquals("x", r.backendOverride)
        assertEquals("the scope fills the language the explicit profile left null", "de", r.languageOverride)
    }

    @Test
    fun `language support decides whether a scope language is passed`() {
        val chain = ScopeChain(folder = p("c", "it"))
        val asked = mutableListOf<Pair<String, String>>()

        val honors = resolve(chain, support = { b, l -> asked += b to l; LanguageSupport.HONORS })
        assertEquals("it", honors.languageOverride)
        assertNull(honors.warning)
        assertEquals(listOf("c" to "it"), asked)

        val ignores = resolve(chain, support = { _, _ -> LanguageSupport.IGNORES })
        assertNull(ignores.languageOverride)
        assertNull(ignores.warning)

        val unsupported = resolve(chain, support = { _, _ -> LanguageSupport.UNSUPPORTED })
        assertNull(unsupported.languageOverride)
        assertEquals(ResolutionWarning.SCOPE_LANGUAGE_UNSUPPORTED, unsupported.warning)
        assertEquals("c", unsupported.backendOverride)
    }

    @Test
    fun `support is asked about the global backend when no scope decides`() {
        val asked = mutableListOf<String>()
        resolve(ScopeChain(sourceDefault = p(language = "it")), support = { b, _ -> asked += b; LanguageSupport.HONORS })
        assertEquals(listOf(global), asked)
    }

    @Test
    fun `a scope or map backend equal to global collapses to no override`() {
        val pin = resolve(ScopeChain(folder = p(global)))
        assertNull(pin.backendOverride)
        assertEquals("decidedBy still names the scope", ScopeKind.FOLDER, pin.decidedBy)

        val mapped = resolve(
            ScopeChain(sender = p(language = "it")),
            languageModels = mapOf("it" to global),
        )
        assertNull(mapped.backendOverride)
        assertEquals(ScopeKind.SENDER, mapped.decidedBy)
    }

    @Test
    fun `an empty chain resolves to global with no overrides`() {
        val r = resolve(ScopeChain())
        assertEquals(ResolvedProfile(null, null, ScopeKind.GLOBAL, null), r)
    }

    @Test
    fun `TranscriptionProfile of normalizes blank to null and trims`() {
        assertEquals(TranscriptionProfile(null, null), TranscriptionProfile.of("", "   "))
        assertEquals(TranscriptionProfile(null, null), TranscriptionProfile.of(null, null))
        assertEquals(TranscriptionProfile("whisper", "it"), TranscriptionProfile.of("  whisper ", " it"))
        assertTrue(TranscriptionProfile.of(" ", "").isEmpty)
    }
}
