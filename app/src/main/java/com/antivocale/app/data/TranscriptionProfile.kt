package com.antivocale.app.data

/**
 * One scope's transcription preferences: which backend and which language
 * a request arriving through that scope should use. A null field is
 * "unset at this scope" (inherit from the next scope in the
 * [com.antivocale.app.transcription.ScopeChain]); [LANGUAGE_AUTO] in
 * [language] is "no pin, and stop inheriting".
 */
data class TranscriptionProfile(val backendId: String? = null, val language: String? = null) {
    val isEmpty: Boolean get() = backendId == null && language == null

    companion object {
        /** Blank → null for both fields; trims. */
        fun of(backendId: String?, language: String?): TranscriptionProfile =
            TranscriptionProfile(
                backendId = backendId?.trim()?.takeUnless { it.isEmpty() },
                language = language?.trim()?.takeUnless { it.isEmpty() },
            )

        /** "No pin": stops inheritance (the model detects the language). */
        const val LANGUAGE_AUTO = "auto"
    }
}
