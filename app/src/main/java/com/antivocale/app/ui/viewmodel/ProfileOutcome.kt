package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.InstalledBackend
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.transcription.LanguageSupport
import com.antivocale.app.transcription.ResolutionWarning
import com.antivocale.app.transcription.ScopeChain
import com.antivocale.app.transcription.TranscriptionProfileResolver

/** The scope a rule editor writes (where the edited profile sits in the chain). */
enum class EditedScope { SENDER, SUBFOLDER, FOLDER }

/**
 * R6: the one line a rule editor shows under its pickers ("These files:
 * GigaAM v3 · two-pass off"), computed from the same resolver the enqueue
 * origins use, so the preview cannot promise what the request will not do.
 */
data class ProfileOutcomeUi(
    /** The backend the request would run on (the global one when no scope decided). */
    val backendId: String,
    /** Its installed display name; null when the id is not installed (the UI labels it). */
    val modelDisplayName: String?,
    /** True when the rule moves the request off the global model while two-pass is on. */
    val twoPassOff: Boolean,
    /** The scope language the resulting model cannot transcribe (dropped by the resolver), or null. */
    val unsupportedLanguage: String? = null,
)

/**
 * Pure helpers behind [ProfileOutcomeUi]. The precedence rules live in
 * [TranscriptionProfileResolver]; nothing here decides which scope wins.
 * The resolver's support predicate is synchronous while the support
 * lookup is suspend, so the caller pre-resolves [supportPairs] (the
 * [com.antivocale.app.data.ScopeProfileSource] pattern: every candidate
 * backend × every scope language) and passes the table to [compute].
 */
object ProfileOutcome {

    /** [base] with [edited] placed at [scope] (null = no rule there). */
    fun chainWith(base: ScopeChain, scope: EditedScope, edited: TranscriptionProfile?): ScopeChain {
        val profile = edited?.takeUnless { it.isEmpty }
        return when (scope) {
            EditedScope.SENDER -> base.copy(sender = profile)
            EditedScope.SUBFOLDER -> base.copy(subfolder = profile)
            EditedScope.FOLDER -> base.copy(folder = profile)
        }
    }

    /** Every (backend, language) pair [compute] can ask about for [chain]. */
    fun supportPairs(
        chain: ScopeChain,
        languageModels: Map<String, String>,
        globalBackendId: String,
    ): Set<Pair<String, String>> {
        val scopes = scopes(chain)
        val languages = scopes
            .mapNotNull { it?.language.nonBlank() }
            .filter { it != TranscriptionProfile.LANGUAGE_AUTO }
            .toSet()
        if (languages.isEmpty()) return emptySet()
        val backends = buildSet {
            add(globalBackendId)
            chain.explicit?.backendId.nonBlank()?.let { add(it) }
            scopes.forEach { scope -> scope?.backendId.nonBlank()?.let { add(it) } }
            languages.forEach { language -> languageModels[language].nonBlank()?.let { add(it) } }
        }
        return buildSet { for (b in backends) for (l in languages) add(b to l) }
    }

    /**
     * Resolves [chain] and describes the result. [support] must hold every
     * pair of [supportPairs] for the same inputs; a missing pair is a
     * broken caller invariant and throws (never a silent default).
     */
    fun compute(
        chain: ScopeChain,
        languageModels: Map<String, String>,
        globalBackendId: String,
        installed: List<InstalledBackend>,
        twoPassEnabled: Boolean,
        support: Map<Pair<String, String>, LanguageSupport>,
    ): ProfileOutcomeUi {
        val installedIds = installed.mapTo(HashSet()) { it.backendId }
        val resolved = TranscriptionProfileResolver.resolve(
            chain = chain,
            languageModels = languageModels,
            globalBackendId = globalBackendId,
            isLoadable = { id -> id.isNotBlank() && id in installedIds },
            support = { backend, language ->
                support[backend to language]
                    ?: error("profile outcome support pair was not pre-resolved")
            },
        )
        val backendId = resolved.backendOverride ?: globalBackendId
        // The resolver raises the warning only for the scope language (the
        // first non-blank, non-auto one in walk order); name it for the copy.
        val unsupported = if (resolved.warning == ResolutionWarning.SCOPE_LANGUAGE_UNSUPPORTED) {
            scopes(chain).firstNotNullOfOrNull { it?.language.nonBlank() }
        } else {
            null
        }
        return ProfileOutcomeUi(
            backendId = backendId,
            modelDisplayName = installed.firstOrNull { it.backendId == backendId }?.displayName,
            twoPassOff = twoPassEnabled && resolved.backendOverride != null,
            unsupportedLanguage = unsupported,
        )
    }

    private fun scopes(chain: ScopeChain): List<TranscriptionProfile?> =
        listOf(chain.sender, chain.subfolder, chain.folder, chain.sourceDefault)

    private fun String?.nonBlank(): String? = this?.trim()?.takeUnless { it.isEmpty() }
}
