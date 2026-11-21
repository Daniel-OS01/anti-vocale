package com.antivocale.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopeProfileStoreTest {

    private val prefs = FakePreferencesManager()
    private val store = ScopeProfileStore(prefs)
    private val ru = TranscriptionProfile(backendId = "gigaam", language = "ru")

    @Test
    fun `sender profiles set, get and remove`() = runTest {
        store.setSenderProfile("sender-a", ru)
        store.setSenderProfile("sender-b", TranscriptionProfile(language = "it"))
        assertEquals(ru, store.senderProfile("sender-a"))
        assertEquals(ru, ScopeProfileStore(prefs).senderProfile("sender-a"))
        assertEquals(2, store.senderProfiles.first().size)

        store.setSenderProfile("sender-a", null)
        store.setSenderProfile("sender-b", TranscriptionProfile())
        assertNull(store.senderProfile("sender-a"))
        assertEquals(emptyMap<String, TranscriptionProfile>(), store.senderProfiles.first())
        assertEquals("", prefs._senderProfilesJson.value)
    }

    @Test
    fun `language models set, get and remove`() = runTest {
        store.setLanguageModel("ru", "gigaam")
        store.setLanguageModel(" it ", "whisper")
        assertEquals(mapOf("ru" to "gigaam", "it" to "whisper"), store.languageModels())
        assertEquals(mapOf("ru" to "gigaam", "it" to "whisper"), store.languageModelsFlow.first())

        store.setLanguageModel("ru", null)
        store.setLanguageModel("it", "  ")
        assertEquals(emptyMap<String, String>(), store.languageModels())
    }

    @Test
    fun `malformed JSON reads as empty and never throws`() = runTest {
        prefs._senderProfilesJson.value = "not json"
        prefs._languageModelMapJson.value = "[1,2]"
        assertEquals(emptyMap<String, TranscriptionProfile>(), store.senderProfiles.first())
        assertNull(store.senderProfile("x"))
        assertEquals(emptyMap<String, String>(), store.languageModels())
        // A write over malformed state starts from empty.
        store.setLanguageModel("ru", "gigaam")
        assertEquals(mapOf("ru" to "gigaam"), store.languageModels())
    }

    @Test
    fun `a malformed entry drops alone`() = runTest {
        prefs._senderProfilesJson.value = """{"a":{"backendId":"gigaam"},"b":"x","c":{}}"""
        prefs._languageModelMapJson.value = """{"ru":"gigaam","it":3,"de":" "}"""
        assertEquals(mapOf("a" to TranscriptionProfile(backendId = "gigaam")), store.senderProfiles.first())
        assertEquals(mapOf("ru" to "gigaam"), store.languageModels())
    }

    @Test
    fun `scrubBackendIn blanks sender pins and removes language entries`() = runTest {
        store.setSenderProfile("a", ru)
        store.setSenderProfile("b", TranscriptionProfile(backendId = "gigaam"))
        store.setSenderProfile("c", TranscriptionProfile(backendId = "whisper"))
        store.setLanguageModel("ru", "gigaam")
        store.setLanguageModel("it", "whisper")

        val cleared = ScopeProfileStore.scrubBackendIn(prefs, "gigaam")

        assertEquals(
            setOf(ScopeProfileStore.Location.SENDER_PROFILE, ScopeProfileStore.Location.LANGUAGE_MODEL),
            cleared,
        )
        assertEquals(
            mapOf("a" to TranscriptionProfile(language = "ru"), "c" to TranscriptionProfile(backendId = "whisper")),
            store.senderProfiles.first(),
        )
        assertEquals(mapOf("it" to "whisper"), store.languageModels())
        assertEquals(emptySet<ScopeProfileStore.Location>(), ScopeProfileStore.scrubBackendIn(prefs, "gigaam"))
    }
}
