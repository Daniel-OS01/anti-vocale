package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogFlags
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R6: [TranscriptionLanguagePolicy.support], the profile resolver's
 * language predicate, per family. The offered set of a built-in comes from
 * [TranscriptionLanguagePolicy.offeredLanguages] over the installed path,
 * exactly as [LanguageSupportLookup] derives it.
 */
class TranscriptionLanguageSupportTest {

    private fun variant(dirName: String, languages: List<String> = emptyList()) = CatalogVariant(
        name = dirName,
        dirName = dirName,
        estimatedSizeMB = 0,
        languages = languages,
        source = CatalogSource(kind = "url", template = "https://example.test/{file}"),
        files = emptyList(),
    )

    private fun entry(
        id: String,
        flags: CatalogFlags,
        variants: List<CatalogVariant>,
        languages: List<String> = emptyList(),
    ) = CatalogEntry(
        id = id,
        runtime = "offline",
        modelType = "",
        family = "",
        display = CatalogDisplay.Literal(id),
        flags = flags,
        languages = languages,
        variants = variants,
    )

    private val whisper = entry(
        BuiltInBackendIds.WHISPER,
        CatalogFlags(passLanguage = true),
        listOf(
            variant("sherpa-onnx-whisper-small", Language.WHISPER_MULTILINGUAL.toList()),
            variant("sherpa-onnx-whisper-distil-large-v3-it", Language.WHISPER_DISTIL_IT.toList()),
        ),
    )

    private fun builtIn(e: CatalogEntry, installedDir: String) = LanguageTarget.BuiltIn(
        e,
        offered = TranscriptionLanguagePolicy.offeredLanguages("/models/$installedDir", e),
        declared = TranscriptionLanguagePolicy.declaredLanguages("/models/$installedDir", e),
    )

    private fun external(family: ModelFamily, languages: List<String>) = LanguageTarget.External(
        ExternalModelRecord(
            id = "x",
            displayName = "X",
            dir = "/models/external/x",
            family = family,
            modelType = "",
            languages = languages,
            source = ExternalModelSource.URL,
            sourceUrl = null,
            files = emptyMap(),
            sizeBytes = 1L,
            importedAt = 0L,
        ),
    )

    private fun support(target: LanguageTarget, language: String) =
        TranscriptionLanguagePolicy.support(target, language)

    @Test
    fun `multilingual whisper honors ru`() {
        assertEquals(LanguageSupport.HONORS, support(builtIn(whisper, "sherpa-onnx-whisper-small"), "ru"))
    }

    @Test
    fun `distil-it whisper finds ru unsupported and honors it`() {
        val distil = builtIn(whisper, "sherpa-onnx-whisper-distil-large-v3-it")
        assertEquals(LanguageSupport.UNSUPPORTED, support(distil, "ru"))
        assertEquals(LanguageSupport.HONORS, support(distil, "it"))
    }

    private val parakeet = entry(BuiltInBackendIds.PARAKEET, CatalogFlags(), listOf(variant("parakeet")),
        Language.PARAKEET.toList())
    private val gigaam = entry("gigaam", CatalogFlags(), listOf(variant("gigaam")), Language.GIGAAM.toList())

    @Test
    fun `non-conditioning models ignore a language they declare`() {
        val qwen = entry("qwen3-asr", CatalogFlags(), listOf(variant("qwen")), listOf("en", "zh"))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(parakeet, "parakeet"), "ru"))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(gigaam, "gigaam"), "ru"))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(qwen, "qwen"), "zh"))
    }

    @Test
    fun `gigaam finds he unsupported and ignores ru`() {
        assertEquals(LanguageSupport.UNSUPPORTED, support(builtIn(gigaam, "gigaam"), "he"))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(gigaam, "gigaam"), "ru"))
    }

    @Test
    fun `parakeet v3 finds he unsupported`() {
        assertEquals(LanguageSupport.UNSUPPORTED, support(builtIn(parakeet, "parakeet"), "he"))
    }

    @Test
    fun `a variant's own declared list wins over the entry-level one`() {
        val e = entry("x", CatalogFlags(), listOf(variant("x-ru", listOf("ru")), variant("x-he", listOf("he"))),
            listOf("ru", "he"))
        assertEquals(LanguageSupport.UNSUPPORTED, support(builtIn(e, "x-he"), "ru"))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(e, "x-he"), "he"))
    }

    @Test
    fun `a non-conditioning model with no declared languages ignores any language`() {
        val unknown = entry("unknown", CatalogFlags(), listOf(variant("unknown")))
        assertEquals(LanguageSupport.IGNORES, support(builtIn(unknown, "unknown"), "he"))
        assertEquals(LanguageSupport.IGNORES, support(external(ModelFamily.TRANSDUCER, emptyList()), "he"))
    }

    @Test
    fun `nemotron honors a listed language`() {
        val nemotron = entry(
            BuiltInBackendIds.NEMOTRON,
            CatalogFlags(languageOption = true),
            listOf(variant("nemotron-3.5-asr-streaming-0.6b-1120ms-int8")),
            Language.NEMOTRON.toList(),
        )
        val listed = Language.NEMOTRON.first()
        assertEquals(LanguageSupport.HONORS, support(builtIn(nemotron, "nemotron-3.5-asr-streaming-0.6b-1120ms-int8"), listed))
    }

    @Test
    fun `whistle finds he unsupported and honors a listed language`() {
        val whistle = entry(
            BuiltInBackendIds.WHISTLE,
            CatalogFlags(passLanguage = true),
            listOf(variant("whistle-v1")),
            listOf("en", "de", "fr", "es", "it", "nl", "pl"),
        )
        assertEquals(LanguageSupport.UNSUPPORTED, support(builtIn(whistle, "whistle-v1"), "he"))
        assertEquals(LanguageSupport.HONORS, support(builtIn(whistle, "whistle-v1"), "de"))
    }

    @Test
    fun `an external hebrew-only whisper honors he and finds ru unsupported`() {
        val he = external(ModelFamily.WHISPER, listOf("he"))
        assertEquals(LanguageSupport.HONORS, support(he, "he"))
        assertEquals(LanguageSupport.UNSUPPORTED, support(he, "ru"))
    }

    @Test
    fun `a conditioning model with an empty offered set finds every language unsupported`() {
        assertEquals(LanguageSupport.UNSUPPORTED, support(external(ModelFamily.WHISPER, emptyList()), "ru"))
    }

    @Test
    fun `an external hebrew-only transducer ignores he and finds ru unsupported`() {
        val transducer = external(ModelFamily.TRANSDUCER, listOf("he"))
        assertEquals(LanguageSupport.UNSUPPORTED, support(transducer, "ru"))
        assertEquals(LanguageSupport.IGNORES, support(transducer, "he"))
    }

    @Test
    fun `opaque targets ignore any language`() {
        assertEquals(LanguageSupport.IGNORES, support(LanguageTarget.Opaque, "ru"))
    }

    @Test
    fun `codes compare on the primary subtag, case-insensitively`() {
        val small = builtIn(whisper, "sherpa-onnx-whisper-small")
        assertEquals(LanguageSupport.HONORS, support(small, "ru-RU"))
        assertEquals(LanguageSupport.HONORS, support(small, "RU"))
        assertEquals(LanguageSupport.HONORS, support(external(ModelFamily.WHISPER, listOf("he-IL")), "HE"))
        assertEquals(LanguageSupport.HONORS, support(external(ModelFamily.WHISPER, listOf("pt")), "pt_BR"))
    }
}
