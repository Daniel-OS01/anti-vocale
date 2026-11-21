package com.antivocale.app.transcription

import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-family offline stream options: Whisper lifts sherpa's default
 * per-stream token cap (output was cut short, logcat "token limit"); every
 * other family gets no per-stream option.
 */
class OfflineStreamOptionsTest {

    @Test
    fun `whisper streams lift the per-second token cap`() {
        assertEquals(
            mapOf("max_tokens_per_second" to "30"),
            offlineStreamOptions(ModelFamily.WHISPER),
        )
    }

    @Test
    fun `every other family and built-in non-whisper entries get no stream options`() {
        ModelFamily.entries.filter { it != ModelFamily.WHISPER }.forEach { family ->
            assertEquals("family=$family", emptyMap<String, String>(), offlineStreamOptions(family))
        }
        assertEquals(emptyMap<String, String>(), offlineStreamOptions(null))
    }
}
