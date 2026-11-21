package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.catalog.BundledCatalog

/**
 * The ONE owner of the two-pass fast (preview) arm resolution: the
 * orchestrator's runtime first pass and the Settings toggle's availability
 * gate both call [fastBackendId], so the toggle can never advertise an arm
 * the runtime would not run (the TASK-932 drift-prevention rule, widened
 * from the built-in catalog to imported models).
 *
 * Callers pass the LOADABLE external records (the orchestrator reads
 * [com.antivocale.app.data.ExternalModelStore.validRecords], Settings the
 * records provider's snapshot of the same predicate) and a saved-path
 * lookup (the backend descriptor's saved model path, the fallback the
 * runtime loader honours), so this object holds no store and stays
 * JVM-testable.
 */
object TwoPassArms {

    /**
     * The fast first-pass backend id, or null when none qualifies. Order:
     * 1. the preview pin, when set, usable and not the [accurate] side;
     * 2. the built-in choice ([SherpaModelManager.installedFastEntryId]);
     * 3. a loadable STREAMING external transducer besides [accurate] (the
     *    imports-only setup works with zero configuration;
     *    ExternalSherpaBackend decodes streaming records batch-wise).
     * The DualRefinementPolicy degenerate guard (fast == accurate) stays
     * the final check at the runtime call site.
     *
     * [savedModelPath] answers a backend id's saved model path (null when
     * none): a built-in pin installed OUTSIDE its standard catalog
     * directory resolves through it exactly as the runtime loader does
     * (TranscriptionOrchestrator.loadCatalogBackend's fallbackPath).
     */
    suspend fun fastBackendId(
        context: Context,
        previewPin: String,
        accurate: String?,
        loadableExternals: List<ExternalModelRecord>,
        savedModelPath: suspend (backendId: String) -> String?,
    ): String? {
        if (previewPin.isNotBlank() && previewPin != accurate &&
            isUsablePreview(context, previewPin, loadableExternals, savedPath = {
                runCatching { savedModelPath(previewPin) }.getOrNull()
            })
        ) {
            return previewPin
        }
        SherpaModelManager.installedFastEntryId(context, accurate)?.let { return it }
        return loadableExternals
            .filter { it.streaming && it.family == ModelFamily.TRANSDUCER }
            .filter { it.backendId != accurate }
            .minByOrNull { it.sizeBytes }
            ?.backendId
    }

    /**
     * Whether [backendId] can serve as the preview arm: a loadable external
     * record, or a built-in catalog entry that resolves on disk (its
     * standard catalog directory, else [savedPath], the same fallback the
     * runtime loader passes). The LLM is never a fast arm, and a
     * stubbed-engine backend cannot load (the same exclusions as the
     * built-in probe).
     */
    private suspend fun isUsablePreview(
        context: Context,
        backendId: String,
        loadableExternals: List<ExternalModelRecord>,
        savedPath: suspend () -> String?,
    ): Boolean {
        if (backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            return loadableExternals.any { it.backendId == backendId }
        }
        if (BuiltInBackendIds.isLlm(backendId)) return false
        if (BuiltInBackendIds.hiddenOnStubBuild(backendId)) return false
        if (runCatching { BundledCatalog.byId(backendId) }.getOrNull() == null) return false
        val fallback = savedPath()?.takeIf { it.isNotBlank() }
        return runCatching {
            SherpaModelManager.of(backendId)
                .resolveActiveModelPath(context, fallbackPath = fallback) != null
        }.getOrDefault(false)
    }
}
