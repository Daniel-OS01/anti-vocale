package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.catalog.CatalogEntry

/**
 * R6: what [TranscriptionLanguagePolicy.support] judges a language against.
 * [LanguageSupportLookup] maps a backend id to one at the I/O boundary.
 */
sealed interface LanguageTarget {
    /** A bundled-catalog entry; [offered] is what
     *  [TranscriptionLanguagePolicy.offeredLanguages] returns for the
     *  INSTALLED variant. */
    data class BuiltIn(val entry: CatalogEntry, val offered: Set<String>) : LanguageTarget

    /** A user-imported model. */
    data class External(val record: ExternalModelRecord) : LanguageTarget

    /** No language contract the app can read: the LLM (Gemma), the remote
     *  offload, an unknown id. */
    data object Opaque : LanguageTarget
}

/**
 * Maps the saved transcription-language preference to the language string a
 * backend receives, and derives the language codes the Settings picker offers
 * for the active backend.
 *
 * The preference has two sentinels: "system" (the untouched default) and
 * "auto" (an explicit choice). TASK-457 removed the app-locale pinning the
 * "system" default used to carry (a silent pin is a translation trap on
 * wrong-language audio, GH #84), so both sentinels now mean the same:
 * model-side detection. "system" survives only as the stored default so
 * existing installs keep resolving without a preference migration.
 * A concrete code is pinned and passes through. Single-language variants are
 * NOT resolved here: [SherpaBackend.forcedLanguage] forces them (Whisper
 * Distil-IT → "it") AFTER this mapping, so it keeps winning over anything
 * this policy resolves.
 *
 * Pure Kotlin, no Android imports, so every mapping is JVM-testable
 * (AudioDurationPolicy pattern).
 */
object TranscriptionLanguagePolicy {

    /** Preference sentinel: the untouched default; behaves exactly like [PREF_AUTO]. */
    const val PREF_SYSTEM = "system"

    /** Preference sentinel: explicit model-side auto-detection. */
    const val PREF_AUTO = "auto"

    /**
     * TASK-547: pin to the phone's locale language. The pre-1.12 behavior
     * restored as an explicit choice: no install changes its default; users
     * whose phone matches their speech get the old reliability back by
     * picking this. Resolves through the same offline/stream mappings, with
     * the locale code injected by the caller (the policy stays pure Kotlin).
     */
    const val PREF_PHONE = "phone"

    /** Both sentinels and a blank legacy value mean "no pin" (model-side detection). */
    private fun isAutoDetect(preference: String): Boolean =
        preference == PREF_AUTO || preference == PREF_SYSTEM || preference.isBlank()

    /**
     * Offline passLanguage entries (Whisper): no pin maps to "" (model-side
     * detection); a pinned code passes through; "phone" resolves to the
     * caller-supplied locale code ("" when the locale is unreadable, so the
     * model falls back to detection).
     */
    fun resolveOffline(preference: String, phoneLanguage: String? = null): String = when {
        isAutoDetect(preference) -> ""
        preference == PREF_PHONE -> phoneLanguage ?: ""
        else -> preference
    }

    /**
     * Online languageOption entries (Nemotron): no pin maps to "auto" (the
     * per-stream auto-detect option), a concrete code passes; "phone"
     * resolves to the locale code (auto when unreadable).
     */
    fun resolveStream(preference: String, phoneLanguage: String? = null): String = when {
        isAutoDetect(preference) -> PREF_AUTO
        preference == PREF_PHONE -> phoneLanguage ?: PREF_AUTO
        else -> preference
    }

    /**
     * The per-entry language wiring, shared by every config-builder site (the
     * orchestrator load path and the Model benchmark, so the benchmark always
     * measures what transcription would actually run with): languageOption
     * (online Nemotron) resolves per-stream, passLanguage (offline Whisper)
     * resolves the offline mapping, everything else gets "".
     * [phoneLanguage] carries the device locale for the PREF_PHONE sentinel
     * (null means the locale was unreadable; detection applies).
     */
    fun resolveForEntry(entry: CatalogEntry, preference: String, phoneLanguage: String? = null): String = when {
        entry.flags.languageOption -> resolveStream(preference, phoneLanguage)
        entry.flags.passLanguage -> resolveOffline(preference, phoneLanguage)
        else -> ""
    }

    /**
     * TASK-458: the language codes the ACTIVE backend conditions on, driving
     * the Settings picker (GH #78). Dispatch mirrors [resolveForEntry]'s axis
     * (the catalog flags), not backend ids, so a future entry carrying either
     * flag is picked up without another branch here. passLanguage (Whisper)
     * offers the INSTALLED variant's catalog languages, derived from the saved
     * model path's directory name the same way the load path resolves the
     * variant (unknown directory names and a missing path fall back to the
     * default variant; a variant without its own list falls back to the
     * entry-level list). languageOption (Nemotron) offers the entry's own
     * languages (its prompt-dictionary set). Everything else (Parakeet,
     * Qwen3-ASR, GigaAM, Gemma, external imports, no backend) does not
     * condition on language: the empty set, which the UI renders as a
     * disabled section.
     */
    fun offeredLanguages(modelPath: String?, entry: CatalogEntry?): Set<String> = when {
        entry == null -> emptySet()
        entry.flags.languageOption -> entry.languagesFor(entry.defaultVariant).toSet()
        entry.flags.passLanguage -> {
            entry.languagesFor(entry.variantForSavedPath(modelPath)).toSet()
        }
        else -> emptySet()
    }

    /**
     * TASK-462: the override an EXTERNAL config consumes. The sentinels and
     * "" (auto/phone-unreadable) mean detection: "" (the family defaults
     * apply); a concrete pin passes through ONLY when the record's family
     * conditions on language (a pin on a transducer would be a silent lie,
     * the exact class the empty-set picker rule exists to prevent).
     */
    fun externalOverride(
        record: ExternalModelRecord,
        preference: String,
        phoneLanguage: String? = null,
    ): String =
        when {
            !ModelFamilySupport.forFamily(record.family).languageCapable -> ""
            // Review: ALL sentinels mean detection (the untouched default is
            // "system", and PREF_PHONE resolves through the device locale
            // like the built-in arms; an unreadable locale detects too).
            isAutoDetect(preference) && preference != PREF_PHONE -> ""
            preference == PREF_PHONE -> phoneLanguage?.takeIf { it.isNotBlank() } ?: ""
            else -> preference
        }

    /**
     * TASK-462: the codes an EXTERNAL record conditions on: its own languages
     * list when the family can consume one (Whisper forced decoding,
     * SenseVoice, Canary), else the empty set (the disabled card, truthful).
     */
    fun offeredLanguagesForExternal(record: ExternalModelRecord): Set<String> =
        if (ModelFamilySupport.forFamily(record.family).languageCapable) record.languages.toSet()
        else emptySet()

    /**
     * TASK-458: how the picker renders the stored preference against the
     * offered set. A concrete pin the active model does not support is KEPT
     * (no destructive preference writes) and shown with a note; the dropdown
     * stays usable so the user can move to a supported language or Auto.
     */
    enum class PinState { NOT_PINNED, SUPPORTED_PIN, UNSUPPORTED_PIN }

    fun pinState(
        preference: String,
        offered: Set<String>,
        phoneLanguage: String? = null,
    ): PinState {
        // TASK-547 review fix (round 2): pre-resolve the phone pin so it
        // walks the same arms as a concrete pin (one copy of the matching
        // rules). An unreadable locale is no pin (detection applies at
        // request time); a resolved code the model does not offer shows the
        // unsupported note (distil-it with an English phone must not promise
        // a pin SherpaBackend.forcedLanguage silently overrides).
        val resolved = if (preference == PREF_PHONE) phoneLanguage.orEmpty() else preference
        return when {
            isAutoDetect(preference) || resolved.isBlank() -> PinState.NOT_PINNED
            resolved in offered -> PinState.SUPPORTED_PIN
            else -> PinState.UNSUPPORTED_PIN
        }
    }

    /**
     * R6: how [target] treats a pinned [language] (the profile resolver's
     * support predicate). A model that does not condition on language
     * IGNORES it (so does [LanguageTarget.Opaque]); one that conditions
     * HONORS a language it offers and finds every other one UNSUPPORTED,
     * including the empty offered set (matching [pinState], where an empty
     * set renders every pin unsupported). Codes compare on the primary
     * subtag, case-insensitively (`ru` == `ru-RU`).
     */
    fun support(target: LanguageTarget, language: String): LanguageSupport {
        val offered = when (target) {
            is LanguageTarget.BuiltIn ->
                if (conditionsOnLanguage(target.entry)) target.offered else return LanguageSupport.IGNORES
            is LanguageTarget.External ->
                if (conditionsOnLanguage(target.record)) {
                    offeredLanguagesForExternal(target.record)
                } else {
                    return LanguageSupport.IGNORES
                }
            LanguageTarget.Opaque -> return LanguageSupport.IGNORES
        }
        val wanted = primarySubtag(language)
        return if (wanted.isNotEmpty() && offered.any { primarySubtag(it) == wanted }) {
            LanguageSupport.HONORS
        } else {
            LanguageSupport.UNSUPPORTED
        }
    }

    /** A built-in conditions on language when either catalog language flag
     *  is set (the [resolveForEntry] axis). */
    private fun conditionsOnLanguage(entry: CatalogEntry): Boolean =
        entry.flags.languageOption || entry.flags.passLanguage

    /** An external conditions on language when its family can consume one
     *  (the [externalOverride] axis). */
    private fun conditionsOnLanguage(record: ExternalModelRecord): Boolean =
        ModelFamilySupport.forFamily(record.family).languageCapable

    private fun primarySubtag(code: String): String =
        code.trim().substringBefore('-').substringBefore('_').lowercase()
}
