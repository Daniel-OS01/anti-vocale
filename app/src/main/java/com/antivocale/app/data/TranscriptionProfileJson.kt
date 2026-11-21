package com.antivocale.app.data

import org.json.JSONObject

/**
 * The ONE JSON shape of a [TranscriptionProfile], shared by the watched
 * folder records ([ScheduledFoldersJson]) and [ScopeProfileStore]:
 * `{"backendId": ..., "language": ...}` with null fields omitted.
 */
object TranscriptionProfileJson {

    private const val BACKEND_ID = "backendId"
    private const val LANGUAGE = "language"

    fun toJson(profile: TranscriptionProfile): JSONObject = JSONObject().apply {
        profile.backendId?.let { put(BACKEND_ID, it) }
        profile.language?.let { put(LANGUAGE, it) }
    }

    /**
     * Blank or non-string fields read as unset ([TranscriptionProfile.of]);
     * a profile left empty is null, so "no profile" has one representation.
     */
    fun fromJson(o: JSONObject?): TranscriptionProfile? {
        if (o == null) return null
        return TranscriptionProfile.of(o.opt(BACKEND_ID) as? String, o.opt(LANGUAGE) as? String)
            .takeUnless { it.isEmpty }
    }

    /** Encodes a keyed profile map; empty profiles are not written. */
    fun encodeMap(map: Map<String, TranscriptionProfile>): JSONObject = JSONObject().apply {
        map.forEach { (key, profile) -> if (!profile.isEmpty) put(key, toJson(profile)) }
    }

    /**
     * Decodes a keyed profile map entry by entry: a malformed entry (not an
     * object, or empty) is dropped alone. [normalizeKey] returning null
     * drops the entry too.
     */
    fun decodeMap(
        o: JSONObject?,
        normalizeKey: (String) -> String? = { it },
    ): Map<String, TranscriptionProfile> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, TranscriptionProfile>()
        for (key in o.keys()) {
            val normalized = normalizeKey(key) ?: continue
            val profile = runCatching { fromJson(o.optJSONObject(key)) }.getOrNull() ?: continue
            out[normalized] = profile
        }
        return out
    }

    /** [profile] with [backendId] blanked; null when nothing is left. */
    fun withoutBackend(profile: TranscriptionProfile?, backendId: String): TranscriptionProfile? =
        when {
            profile == null -> null
            profile.backendId != backendId -> profile
            else -> profile.copy(backendId = null).takeUnless { it.isEmpty }
        }
}
