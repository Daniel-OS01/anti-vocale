package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ScopeProfileStore
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.LanguageSupportLookup
import com.antivocale.app.transcription.ScopeChain
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** What a rule editor is previewing: [profile] placed at [scope] over [base]. */
data class ProfilePreview(
    val scope: EditedScope,
    val base: ScopeChain,
    val profile: TranscriptionProfile?,
)

/**
 * R6: the reactive inputs every [com.antivocale.app.ui.components.ProfileRuleSheet]
 * host needs (the outcome line and the language picker's codes), shared by
 * the Settings rules pages and the History long-press so the two editors
 * cannot drift. Stateless: each host owns its preview state and its scope.
 */
class ProfileOutcomeSource @Inject constructor(
    private val scopeProfileStore: ScopeProfileStore,
    private val preferencesManager: PreferencesManager,
    private val activeModelRepository: ActiveModelRepository,
    private val languageSupportLookup: LanguageSupportLookup,
    private val externalRecordsProvider: ExternalModelRecordsProvider,
) {

    /**
     * The open editor's outcome line for each [previews] emission (null
     * while no editor previews). [twoPassEnabled] is the host's two-pass
     * gate (the toggle, plus availability where the host knows it).
     */
    fun outcomes(previews: Flow<ProfilePreview?>, twoPassEnabled: Flow<Boolean>): Flow<ProfileOutcomeUi?> =
        combine(
            previews,
            scopeProfileStore.languageModelsFlow,
            preferencesManager.transcriptionBackend,
            activeModelRepository.installedBackendsFlow,
            twoPassEnabled,
        ) { preview, languageModels, global, installed, twoPass ->
            preview?.let { p ->
                val chain = ProfileOutcome.chainWith(p.base, p.scope, p.profile)
                val support = ProfileOutcome.supportPairs(chain, languageModels, global)
                    .associateWith { (backend, language) -> languageSupportLookup.support(backend, language) }
                ProfileOutcome.compute(chain, languageModels, global, installed, twoPass, support)
            }
        }

    /**
     * The concrete language codes the rule pickers offer, lowercase:
     * every built-in catalog language (any variant) plus every imported
     * model's. Installed-agnostic on purpose: a rule for a language no
     * installed model handles is still writable, and its outcome line says so.
     */
    fun ruleLanguageCodes(): Flow<List<String>> =
        externalRecordsProvider.records.map { records ->
            buildSet {
                BundledCatalog.entries().forEach { entry ->
                    entry.variants.forEach { v -> entry.languagesFor(v).forEach { add(it.lowercase()) } }
                }
                records.forEach { record -> record.languages.forEach { add(it.lowercase()) } }
            }.sorted()
        }
}
