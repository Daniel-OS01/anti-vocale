package com.antivocale.app.data

import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.LlmTranscriptionBackend
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * "Can this backend id be loaded right now?", answered from persisted state
 * only, for request-time pin checks (a dangling pin degrades to the global
 * model instead of failing the request).
 *
 * Deliberately NOT [ActiveModelRepository.installedBackendsFlow]: that flow
 * (a) counts a built-in or LLM model as installed on a non-blank saved path
 * alone, so a pin whose files were deleted still looked loadable, and
 * (b) reads external records through [ExternalModelRecordsProvider], a
 * StateFlow that starts empty and fills asynchronously, so a request right
 * after process start saw no externals and dropped a valid external pin.
 *
 * - `external:<id>`: the persisted record through [ExternalModelStore.byId]
 *   (record exists, not quarantined, dir exists: the store's one
 *   loadability predicate).
 * - A built-in catalog id or the LLM id: the saved model path is non-blank
 *   and that file or dir exists.
 * - A blank or unknown id: false.
 */
@Singleton
class BackendLoadability @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val externalModelStore: ExternalModelStore,
) {

    suspend fun isLoadable(backendId: String): Boolean {
        val id = backendId.trim()
        if (id.isEmpty()) return false
        if (id.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            return externalModelStore.byId(id.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)) != null
        }
        val path = when {
            id == LlmTranscriptionBackend.BACKEND_ID -> preferencesManager.modelPath.first()
            id in BuiltInBackendIds.ALL -> preferencesManager.sherpaModelPath(id).first()
            else -> return false
        }
        return !path.isNullOrBlank() && File(path).exists()
    }
}
