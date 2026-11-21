package com.antivocale.app.data

import com.antivocale.app.data.TranscriptionPinScrubber.Pin
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
}
