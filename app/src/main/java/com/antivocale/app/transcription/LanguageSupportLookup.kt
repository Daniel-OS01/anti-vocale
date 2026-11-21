package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R6: the I/O boundary of [TranscriptionLanguagePolicy.support]: maps a
 * backend id to its [LanguageTarget] and asks the policy. Holds no state;
 * every read goes to the existing owners.
 *
 * - `external:` ids: the [ExternalModelStore] record (any record: whether
 *   it is loadable is the resolver's separate question).
 * - built-ins: the bundled catalog entry, with the offered set derived
 *   from the INSTALLED variant's saved model path through the registry
 *   descriptor (the [com.antivocale.app.data.ActiveModelRepository]
 *   derivation, for an arbitrary backend instead of the active one).
 * - the LLM, the remote offload and unknown ids: [LanguageTarget.Opaque].
 */
@Singleton
class LanguageSupportLookup @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
) {
    suspend fun support(backendId: String, language: String): LanguageSupport =
        TranscriptionLanguagePolicy.support(target(backendId), language)

    private suspend fun target(backendId: String): LanguageTarget {
        if (backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            val id = backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)
            return externalModelStore.records().firstOrNull { it.id == id }
                ?.let { LanguageTarget.External(it) }
                ?: LanguageTarget.Opaque
        }
        if (backendId == LlmTranscriptionBackend.BACKEND_ID || backendId == RemoteOmnivoiceBackend.BACKEND_ID) {
            return LanguageTarget.Opaque
        }
        val entry = BundledCatalog.byId(backendId) ?: return LanguageTarget.Opaque
        val modelPath = backendRegistry.byBackendId(backendId)?.modelPathFlow?.invoke(preferencesManager)?.first()
        return LanguageTarget.BuiltIn(entry, TranscriptionLanguagePolicy.offeredLanguages(modelPath, entry))
    }
}
