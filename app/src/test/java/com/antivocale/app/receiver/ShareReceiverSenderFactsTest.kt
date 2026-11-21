package com.antivocale.app.receiver

import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.TranscriptionProfile
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
}
