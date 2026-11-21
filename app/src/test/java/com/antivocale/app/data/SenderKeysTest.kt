package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-sender rule key: WhatsApp names and call-recording labels map to
 * one stable, normalized key, and storageId/parse round-trips it. Fake data
 * only; the phone normalizer is a fake so these run on the plain JVM.
 */
class SenderKeysTest {

    private val fakeE164 = PhoneNormalizer { _, _ -> "+15550100" }
    private val failingE164 = PhoneNormalizer { _, _ -> null }

    @Test
    fun `WhatsApp voice note gets a WhatsApp key`() {
        assertEquals(
            SenderKey.WhatsApp("com.whatsapp", "Test Contact"),
            SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP, "Test Contact"))
    }

    @Test
    fun `Business voice note gets a Business key`() {
        assertEquals(
            SenderKey.WhatsApp("com.whatsapp.w4b", "Test Contact"),
            SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP_BUSINESS, "Test Contact"))
    }

    @Test
    fun `watched folder voice note has no key`() {
        assertNull(SenderKeys.forVoiceNote(VoiceNoteSource.WATCHED_FOLDER, "Test Contact"))
    }

    @Test
    fun `blank or missing name has no key`() {
        assertNull(SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP, "   "))
        assertNull(SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP, null))
    }

    @Test
    fun `composed and decomposed names give the same key`() {
        val composed = SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP, "José Test")
        val decomposed = SenderKeys.forVoiceNote(VoiceNoteSource.WHATSAPP, "José Test")
        assertEquals(composed, decomposed)
        assertEquals("José Test", composed?.value)
    }

    @Test
    fun `whitespace is trimmed and collapsed`() {
        assertEquals("Test Contact", SenderKeys.normalizeName("  Test \t  Contact \n"))
    }

    @Test
    fun `case is preserved`() {
        assertEquals("test CONTACT", SenderKeys.normalizeName("test CONTACT"))
    }

    @Test
    fun `share from either WhatsApp client gets a key`() {
        assertEquals(
            SenderKey.WhatsApp("com.whatsapp", "Test Contact"),
            SenderKeys.forShare("com.whatsapp", "Test Contact"))
        assertEquals(
            SenderKey.WhatsApp("com.whatsapp.w4b", "Test Contact"),
            SenderKeys.forShare("com.whatsapp.w4b", "Test Contact"))
    }

    @Test
    fun `share from another app or no package has no key`() {
        assertNull(SenderKeys.forShare("org.telegram.messenger", "Test Contact"))
        assertNull(SenderKeys.forShare(null, "Test Contact"))
    }

    @Test
    fun `phone-shaped call label uses the E164 form`() {
        assertEquals(
            SenderKey.Call("+15550100"),
            SenderKeys.forCallRecording("(555) 010-0", "us", fakeE164))
    }

    @Test
    fun `phone label passes the original label and country to the normalizer`() {
        var seen: Pair<String, String?>? = null
        val recording = PhoneNormalizer { raw, iso -> seen = raw to iso; null }
        SenderKeys.forCallRecording("050 123 4567", "il", recording)
        assertEquals("050 123 4567" to "il", seen)
    }

    @Test
    fun `unnormalizable phone label falls back to digits keeping the plus`() {
        assertEquals(
            SenderKey.Call("+15550100"),
            SenderKeys.forCallRecording("+1 555-0100", null, failingE164))
        assertEquals(
            SenderKey.Call("0501234567"),
            SenderKeys.forCallRecording("050.123.4567", "il", failingE164))
    }

    @Test
    fun `name-shaped call label is a normalized name`() {
        assertEquals(
            SenderKey.Call("Test Contact"),
            SenderKeys.forCallRecording("  Test   Contact ", "il", fakeE164))
    }

    @Test
    fun `too short digit run is a name not a number`() {
        assertEquals(SenderKey.Call("1234"), SenderKeys.forCallRecording("1234", "il", fakeE164))
    }

    @Test
    fun `blank call label has no key`() {
        assertNull(SenderKeys.forCallRecording(" ", "il", fakeE164))
        assertNull(SenderKeys.forCallRecording(null, "il", fakeE164))
    }

    @Test
    fun `storage ids round trip`() {
        listOf(
            SenderKey.WhatsApp("com.whatsapp", "Test Contact"),
            SenderKey.WhatsApp("com.whatsapp.w4b", "Test: Contact"),
            SenderKey.Call("+15550100"),
            SenderKey.Call("Test:Contact:Two"),
        ).forEach { key ->
            assertEquals(key, SenderKey.parse(key.storageId()))
        }
    }

    @Test
    fun `storage id format is stable`() {
        assertEquals("wa:com.whatsapp:Test Contact",
            SenderKey.WhatsApp("com.whatsapp", "Test Contact").storageId())
        assertEquals("call:+15550100", SenderKey.Call("+15550100").storageId())
    }

    @Test
    fun `malformed or unknown ids parse to null`() {
        listOf(
            "",
            "garbage",
            "wa:",
            "wa:com.whatsapp",
            "wa:com.whatsapp:",
            "wa:org.telegram.messenger:Test Contact",
            "call:",
            "sms:+15550100",
        ).forEach { id ->
            assertNull(id, SenderKey.parse(id))
        }
    }
}
