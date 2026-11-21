package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The ONE delete-time scrub of every preference that pins a model by
 * backend id. Deleting a model blanks each pin naming it, so the pinned
 * feature falls back to its default (the active model, or automatic for
 * the preview pin) instead of carrying a dead id: a dead refinement pin
 * silently degrades every two-pass run (TASK-761), a dead voice-note pin
 * would make every note resolve through a dangling scope.
 *
 * Callers: [ExternalModelDeleter] (imported models) and both built-in
 * delete paths in ModelViewModel. [DanglingBackendCleaner] stays the
 * startup net for any path that misses this.
 *
 * A new single-preference pin is one more [Slot] in [slots]; the R6
 * profile maps (folder profiles and subfolder rules, sender profiles, the
 * language-model map) scrub through their stores' own locks. Every caller
 * picks up both.
 */
object TranscriptionPinScrubber {

    enum class Pin {
        REFINEMENT,
        PREVIEW,
        VOICE_NOTE,
        VOICE_NOTE_BUSINESS,
        /** A folder profile or subfolder rule named the model (its backendId
         *  is blanked; a profile left empty is removed). */
        FOLDER_PROFILE,
        /** A sender profile named the model (same blanking). */
        SENDER_PROFILE,
        /** A language-model map entry named the model (entry removed). */
        LANGUAGE_MODEL,
    }

    private class Slot(
        val pin: Pin,
        val read: (PreferencesManager) -> Flow<String>,
        val clear: suspend (PreferencesManager) -> Unit,
    )

    private val slots = listOf(
        Slot(Pin.REFINEMENT, { it.refinementModelBackendId }, { it.saveRefinementModelBackendId("") }),
        Slot(Pin.PREVIEW, { it.previewModelBackendId }, { it.savePreviewModelBackendId("") }),
        Slot(Pin.VOICE_NOTE, { it.voiceNoteBackendId }, { it.saveVoiceNoteBackendId("") }),
        Slot(
            Pin.VOICE_NOTE_BUSINESS,
            { it.voiceNoteBusinessBackendId },
            { it.saveVoiceNoteBusinessBackendId("") },
        ),
    )

    /** Blanks every pin equal to [deletedBackendId]; returns the pins it
     *  cleared. A blank id names no model, so it clears nothing. */
    suspend fun scrub(prefs: PreferencesManager, deletedBackendId: String): Set<Pin> {
        if (deletedBackendId.isBlank()) return emptySet()
        val cleared = mutableSetOf<Pin>()
        for (slot in slots) {
            if (slot.read(prefs).first() == deletedBackendId) {
                slot.clear(prefs)
                cleared += slot.pin
            }
        }
        if (ScheduledFolderStore.scrubBackendIn(prefs, deletedBackendId)) cleared += Pin.FOLDER_PROFILE
        for (location in ScopeProfileStore.scrubBackendIn(prefs, deletedBackendId)) {
            cleared += when (location) {
                ScopeProfileStore.Location.SENDER_PROFILE -> Pin.SENDER_PROFILE
                ScopeProfileStore.Location.LANGUAGE_MODEL -> Pin.LANGUAGE_MODEL
            }
        }
        return cleared
    }
}
