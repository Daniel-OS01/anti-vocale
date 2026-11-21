package com.antivocale.app.transcription

import com.antivocale.app.data.TranscriptionProfile

/** Which scope decided a request's backend (GLOBAL = nothing did). */
enum class ScopeKind { EXPLICIT, SENDER, SUBFOLDER, FOLDER, SOURCE_DEFAULT, GLOBAL }

/**
 * Every scope that can carry a [TranscriptionProfile] for one request.
 * Walk order, most specific first: explicit, sender, subfolder, folder,
 * sourceDefault. A null scope is "no profile there".
 */
data class ScopeChain(
    val explicit: TranscriptionProfile? = null,
    val sender: TranscriptionProfile? = null,
    val subfolder: TranscriptionProfile? = null,
    val folder: TranscriptionProfile? = null,
    val sourceDefault: TranscriptionProfile? = null,
)

/** How a backend treats a pinned language. */
enum class LanguageSupport { HONORS, IGNORES, UNSUPPORTED }

enum class ResolutionWarning { SCOPE_LANGUAGE_UNSUPPORTED }

data class ResolvedProfile(
    /** null = use the global backend (no override extra). */
    val backendOverride: String?,
    /** null = no language extra; "auto" = detect (a scope said auto). */
    val languageOverride: String?,
    /** Which scope decided the backend. */
    val decidedBy: ScopeKind,
    val warning: ResolutionWarning? = null,
)

/**
 * The ONE resolver from a request's scope chain to its backend and
 * language overrides. Pure (no Android, no I/O): callers read the scopes,
 * the language→model map and the loadable set, and pass them in.
 *
 * Rules:
 * 1. Explicit fields (share alias, Tasker, bulk redo) override per field:
 *    an explicit backend is used as given (no loadability check, no
 *    collapse onto the global backend), an explicit language is passed as
 *    given, and scopes still fill whatever field the explicit profile
 *    leaves null. A language-derived model never replaces an explicit
 *    backend. An explicit language (including "auto") is deliberately
 *    unvalidated and never consults the language map: an explicit
 *    request means exactly what it says.
 * 2. A scope backend that is not loadable counts as unset: a dangling pin
 *    degrades to the global model, it never fails a request.
 * 3. The language L is the first non-null language in walk order. A
 *    first language-bearing scope saying
 *    [TranscriptionProfile.LANGUAGE_AUTO] stops the walk and yields
 *    `languageOverride = "auto"` (detect, never the global language),
 *    with no support check (the language policy maps "auto" to detection
 *    or "" per model) and no language-map lookup.
 * 4. The backend B, absent an explicit one: the first scope (sender →
 *    sourceDefault) with a loadable backend, or, at the scope that set L
 *    only, the loadable `languageModels[L]`; else the global backend.
 * 5. A scope-derived L is passed only when B honors it; an unsupported L
 *    is dropped with [ResolutionWarning.SCOPE_LANGUAGE_UNSUPPORTED].
 * 6. A scope- or map-derived B equal to the global backend collapses to a
 *    null override (keeps two-pass, residency and the warm model).
 */
object TranscriptionProfileResolver {

    fun resolve(
        chain: ScopeChain,
        languageModels: Map<String, String>,
        globalBackendId: String,
        isLoadable: (String) -> Boolean,
        support: (backendId: String, language: String) -> LanguageSupport,
    ): ResolvedProfile {
        val scopes: List<Pair<ScopeKind, TranscriptionProfile?>> = listOf(
            ScopeKind.SENDER to chain.sender,
            ScopeKind.SUBFOLDER to chain.subfolder,
            ScopeKind.FOLDER to chain.folder,
            ScopeKind.SOURCE_DEFAULT to chain.sourceDefault,
        )

        val explicitLanguage = chain.explicit?.language.nonBlank()
        // Rule 3 over the scopes; an explicit language short-circuits it.
        var scopeLanguage: String? = null
        var languageScope: ScopeKind? = null
        var scopeAuto = false
        if (explicitLanguage == null) {
            for ((kind, profile) in scopes) {
                val language = profile?.language.nonBlank() ?: continue
                if (language == TranscriptionProfile.LANGUAGE_AUTO) {
                    scopeAuto = true
                } else {
                    scopeLanguage = language
                    languageScope = kind
                }
                break
            }
        }

        val explicitBackend = chain.explicit?.backendId.nonBlank()
        val backend: String
        val decidedBy: ScopeKind
        if (explicitBackend != null) {
            backend = explicitBackend
            decidedBy = ScopeKind.EXPLICIT
        } else {
            var picked: Pair<String, ScopeKind>? = null
            for ((kind, profile) in scopes) {
                val pin = profile?.backendId.nonBlank()?.takeIf(isLoadable)
                if (pin != null) {
                    picked = pin to kind
                    break
                }
                if (kind == languageScope && scopeLanguage != null) {
                    val mapped = languageModels[scopeLanguage].nonBlank()?.takeIf(isLoadable)
                    if (mapped != null) {
                        picked = mapped to kind
                        break
                    }
                }
            }
            backend = picked?.first ?: globalBackendId
            decidedBy = picked?.second ?: ScopeKind.GLOBAL
        }

        var warning: ResolutionWarning? = null
        val languageOverride: String? = when {
            explicitLanguage != null -> explicitLanguage
            // Scope "auto" overrides a global pin with detection; the
            // policy decides per model what "auto" reaches the recognizer as.
            scopeAuto -> TranscriptionProfile.LANGUAGE_AUTO
            scopeLanguage != null -> when (support(backend, scopeLanguage)) {
                LanguageSupport.HONORS -> scopeLanguage
                LanguageSupport.IGNORES -> null
                LanguageSupport.UNSUPPORTED -> {
                    warning = ResolutionWarning.SCOPE_LANGUAGE_UNSUPPORTED
                    null
                }
            }
            else -> null
        }

        val backendOverride = when (decidedBy) {
            ScopeKind.EXPLICIT -> backend
            ScopeKind.GLOBAL -> null
            else -> backend.takeUnless { it == globalBackendId }
        }
        return ResolvedProfile(
            backendOverride = backendOverride,
            languageOverride = languageOverride,
            decidedBy = decidedBy,
            warning = warning,
        )
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeUnless { it.isEmpty() }
}
