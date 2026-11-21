package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendRetentionTest {

    @Test
    fun `keepLoaded only when both overrides are present and equal`() {
        val cases = listOf(
            Triple(null, null, false),
            Triple(null, "parakeet", false),
            Triple("parakeet", null, false),
            Triple("parakeet", "whisper", false),
            Triple("parakeet", "parakeet", true),
            Triple("external:abc", "external:abc", true),
            Triple("external:abc", "external:abd", false),
        )
        cases.forEach { (current, next, expected) ->
            assertEquals(
                "keepLoaded($current, $next)",
                expected,
                BackendRetention.keepLoaded(current, next),
            )
        }
    }
}
