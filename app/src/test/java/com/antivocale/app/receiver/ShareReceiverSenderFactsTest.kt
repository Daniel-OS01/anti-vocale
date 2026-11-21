package com.antivocale.app.receiver

import com.antivocale.app.data.OriginFacts
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.transcription.ResolvedProfile
import com.antivocale.app.transcription.ScopeKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The share path's sender-profile decision: a labeled WhatsApp share
 * resolves with the alias's backend as the explicit profile; every other
 * share gets null, which the dispatch reads as "make no resolve call".
 */
class ShareReceiverSenderFactsTest {

    @Test
    fun `a labeled WhatsApp share with an alias resolves with the alias as explicit`() {
        val facts = ShareReceiverActivity.shareSenderOriginFacts("com.whatsapp", "Test Contact", "whisper")
        assertEquals(SenderKey.WhatsApp("com.whatsapp", "Test Contact").storageId(), facts?.senderStorageId)
        assertEquals(TranscriptionProfile(backendId = "whisper"), facts?.explicit)
        assertNull(facts?.voiceNoteSource)
        assertNull(facts?.folderTreeUri)
    }

    @Test
    fun `a labeled WhatsApp share with no alias has no explicit profile`() {
        val facts = ShareReceiverActivity.shareSenderOriginFacts("com.whatsapp.w4b", "Test Contact", null)
        assertEquals(SenderKey.WhatsApp("com.whatsapp.w4b", "Test Contact").storageId(), facts?.senderStorageId)
        assertNull(facts?.explicit)
    }

    @Test
    fun `an unlabeled share makes no resolve call`() {
        assertNull(ShareReceiverActivity.shareSenderOriginFacts("com.whatsapp", null, "whisper"))
    }

    @Test
    fun `a share from another app makes no resolve call`() {
        assertNull(ShareReceiverActivity.shareSenderOriginFacts("org.example.messenger", "Test Contact", "whisper"))
        assertNull(ShareReceiverActivity.shareSenderOriginFacts(null, "Test Contact", null))
    }

    @Test
    fun `a sender profile that picks a model drives both the duplicate lookup and the intent`() = runTest {
        var calls = 0
        val decision = ShareReceiverActivity.shareSenderDecision("com.whatsapp", "Test Contact", null) { facts ->
            calls++
            assertEquals(SenderKey.WhatsApp("com.whatsapp", "Test Contact").storageId(), facts.senderStorageId)
            ResolvedProfile(backendOverride = "model-b", languageOverride = "he", decidedBy = ScopeKind.SENDER)
        }
        assertEquals(1, calls)
        // The ONE field both offerDuplicateDialogIfNeeded's lookup and
        // dispatch's AudioRequestIntent read: the sender's model, not the
        // global one the share would otherwise default to.
        assertEquals("model-b", decision.backendOverride)
        assertEquals("he", decision.languageOverride)
        assertEquals("Test Contact", decision.senderName)
    }

    @Test
    fun `a share with no sender key keeps the alias backend and makes no resolve call`() = runTest {
        var calls = 0
        val countingResolve: suspend (OriginFacts) -> ResolvedProfile = {
            calls++
            ResolvedProfile(backendOverride = "model-b", languageOverride = "he", decidedBy = ScopeKind.SENDER)
        }
        val unlabeled = ShareReceiverActivity.shareSenderDecision("com.whatsapp", null, "whisper", countingResolve)
        val otherApp = ShareReceiverActivity.shareSenderDecision("org.example.messenger", "Test Contact", "whisper", countingResolve)

        assertEquals(0, calls)
        val expected = ShareReceiverActivity.ShareSenderDecision(
            senderName = null,
            backendOverride = "whisper",
            languageOverride = null,
        )
        assertEquals(expected, unlabeled)
        assertEquals(expected.copy(senderName = "Test Contact"), otherApp)
    }
}
