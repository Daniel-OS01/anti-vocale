package com.antivocale.app.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R6: the portable scope profiles, two JSON preferences that ride the
 * backup archive:
 * - per-sender profiles, `sender_profiles_json`:
 *   `{ senderStorageId: {backendId?, language?} }`;
 * - the global model-per-language map, `language_model_map_json`:
 *   `{ languageCode: backendId }` (e.g. `ru` -> `gigaam`).
 *
 * The sender storage id is an opaque key owned by the voice-note identity
 * feature: it is never parsed here and never logged (it can carry a
 * contact's identity).
 *
 * Malformed JSON reads as empty with a structured warning; it never throws.
 * Read-modify-writes serialize on one process-wide lock (companion), which
 * the delete-time scrub ([scrubBackendIn], reached through
 * [TranscriptionPinScrubber] with only a [PreferencesManager]) shares.
 */
@Singleton
class ScopeProfileStore @Inject constructor(
    private val preferencesManager: PreferencesManager,
) {
    val senderProfiles: Flow<Map<String, TranscriptionProfile>> =
        preferencesManager.senderProfilesJson.map(ScopeProfilesJson::decodeSenders)

    val languageModelsFlow: Flow<Map<String, String>> =
        preferencesManager.languageModelMapJson.map(ScopeProfilesJson::decodeLanguageModels)

    suspend fun senderProfile(storageId: String): TranscriptionProfile? = senderProfiles.first()[storageId]

    /** Null or an empty profile removes the sender's entry. */
    suspend fun setSenderProfile(storageId: String, profile: TranscriptionProfile?) {
        require(storageId.isNotBlank()) { "sender storage id must not be blank" }
        lock.withLock {
            val current = ScopeProfilesJson.decodeSenders(preferencesManager.senderProfilesJson.first())
            val next = if (profile == null || profile.isEmpty) current - storageId else current + (storageId to profile)
            preferencesManager.saveSenderProfilesJson(ScopeProfilesJson.encodeSenders(next))
        }
    }

    suspend fun languageModels(): Map<String, String> = languageModelsFlow.first()

    /** A null or blank [backendId] removes the language's entry. */
    suspend fun setLanguageModel(language: String, backendId: String?) {
        val key = language.trim()
        require(key.isNotEmpty()) { "language must not be blank" }
        val value = backendId?.trim()?.takeUnless { it.isEmpty() }
        lock.withLock {
            val current = ScopeProfilesJson.decodeLanguageModels(preferencesManager.languageModelMapJson.first())
            val next = if (value == null) current - key else current + (key to value)
            preferencesManager.saveLanguageModelMapJson(ScopeProfilesJson.encodeLanguageModels(next))
        }
    }

    /** Where [scrubBackendIn] found the deleted id. */
    enum class Location { SENDER_PROFILE, LANGUAGE_MODEL }

    companion object {
        /** Process-wide: see the class KDoc. */
        private val lock = Mutex()

        /**
         * Delete-time scrub: blanks [backendId] in every sender profile (an
         * entry left empty is removed) and removes every language-map entry
         * naming it. Writes only what changed.
         */
        suspend fun scrubBackendIn(prefs: PreferencesManager, backendId: String): Set<Location> {
            if (backendId.isBlank()) return emptySet()
            val cleared = mutableSetOf<Location>()
            lock.withLock {
                val senders = ScopeProfilesJson.decodeSenders(prefs.senderProfilesJson.first())
                val nextSenders = senders.mapNotNull { (id, profile) ->
                    TranscriptionProfileJson.withoutBackend(profile, backendId)?.let { id to it }
                }.toMap()
                if (nextSenders != senders) {
                    prefs.saveSenderProfilesJson(ScopeProfilesJson.encodeSenders(nextSenders))
                    cleared += Location.SENDER_PROFILE
                }
                val languages = ScopeProfilesJson.decodeLanguageModels(prefs.languageModelMapJson.first())
                val nextLanguages = languages.filterValues { it != backendId }
                if (nextLanguages != languages) {
                    prefs.saveLanguageModelMapJson(ScopeProfilesJson.encodeLanguageModels(nextLanguages))
                    cleared += Location.LANGUAGE_MODEL
                }
            }
            return cleared
        }

        /** Every backend id a sender profile or language-map entry names. */
        suspend fun referencedBackendIdsIn(prefs: PreferencesManager): Set<String> =
            ScopeProfilesJson.decodeSenders(prefs.senderProfilesJson.first()).values
                .mapNotNullTo(mutableSetOf()) { it.backendId } +
                ScopeProfilesJson.decodeLanguageModels(prefs.languageModelMapJson.first()).values
    }
}

/** The two preferences' codecs; pure apart from the warning log. */
object ScopeProfilesJson {
    private const val TAG = "ScopeProfilesJson"

    fun encodeSenders(map: Map<String, TranscriptionProfile>): String =
        if (map.isEmpty()) "" else TranscriptionProfileJson.encodeMap(map).toString()

    fun decodeSenders(raw: String?): Map<String, TranscriptionProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { TranscriptionProfileJson.decodeMap(JSONObject(raw)) }
            // Never the payload: its keys are sender storage ids.
            .onFailure { Log.w(TAG, "scope_profiles_decode_failed key=sender_profiles_json") }
            .getOrDefault(emptyMap())
    }

    fun encodeLanguageModels(map: Map<String, String>): String =
        if (map.isEmpty()) "" else JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    /** A non-string or blank value drops that entry alone. */
    fun decodeLanguageModels(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                for (key in o.keys()) {
                    val language = key.trim().takeUnless { it.isEmpty() } ?: continue
                    val backendId = (o.opt(key) as? String)?.trim()?.takeUnless { it.isEmpty() } ?: continue
                    put(language, backendId)
                }
            }
        }.onFailure { Log.w(TAG, "scope_profiles_decode_failed key=language_model_map_json") }
            .getOrDefault(emptyMap())
    }
}
