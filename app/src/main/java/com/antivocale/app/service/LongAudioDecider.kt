package com.antivocale.app.service

import android.content.Context
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.RemoteOmnivoiceBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * TASK-432's long-audio DECISION (duration probe, ceiling, estimate,
 * [AudioDurationPolicy.warnDecision]), shared by the single-row gate in
 * LogsViewModel (which owns the dialog) and the bulk redo. Stateless.
 */
class LongAudioDecider @Inject constructor(
    private val audioPreprocessor: AudioPreprocessor,
    private val backendRegistry: BackendRegistry,
    private val preferencesManager: PreferencesManager,
    private val transcriptionBackendManager: TranscriptionBackendManager,
    private val transcriptionCalibrator: TranscriptionCalibrator,
) {

    data class LongAudioDecision(
        /** The probed duration; 0 when the probe failed (fail-open). */
        val durationSeconds: Double,
        val warn: AudioDurationPolicy.WarnDecision,
    )

    /**
     * The duration probe fails OPEN (0s, no advisory): the hard ceilings
     * inside preprocessing still protect every path.
     */
    suspend fun decide(backendId: String?, filePath: String, context: Context): LongAudioDecision {
        // Off-main ALWAYS: callers arrive on either dispatcher and the probe
        // is a container parse.
        val duration = withContext(Dispatchers.IO) {
            runCatching { audioPreprocessor.getAudioDuration(filePath) }.getOrDefault(0.0)
        }
        val descriptor = backendId?.let { backendRegistry.byBackendId(it) }
        val modelPath = descriptor?.modelPathFlow(preferencesManager)?.first()
        // TASK-873: the llm row keys on the SELECTED model path (read above
        // for the calibrator), so the decision predicts the run the user is
        // about to start, not the engine that happens to be resident.
        val decodePath = backendId
            ?.let { transcriptionBackendManager.gateInputsFor(it, modelPath) }
            ?.decodePath(preferencesManager.vadEnabled.first())
            ?: AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM
        // TASK-681: an offload run never decodes on the phone, so the
        // RAM-derived ceilings do not apply; the honest bound is the
        // backend's wall-clock budget over the server throughput
        // (budget-seconds x rtf audio-seconds).
        val ceiling = if (backendId != null && BuiltInBackendIds.isRemoteOmnivoice(backendId)) {
            (RemoteOmnivoiceBackend.WALL_CLOCK_BUDGET_MS / 1000L) *
                (descriptor?.rtfEstimate?.toLong() ?: 1L)
        } else {
            AudioDurationPolicy.ceilingSeconds(
                decodePath, MemoryReadings.availableRamBytes(context), MemoryReadings.maxHeapBytes())
        }
        val profile = backendId?.let { transcriptionCalibrator.getEstimate(it, modelPath ?: "") }
        val calibrated = profile?.hasEstimate == true
        val estimate = AudioDurationPolicy.resolveEstimateMsPerSec(
            profile?.msPerSecondOfAudio, calibrated, descriptor?.rtfEstimate ?: 1f)
        val decision = AudioDurationPolicy.warnDecision(
            duration.toLong(), ceiling, estimate, dialogCapable = true, calibrated = calibrated)
        return LongAudioDecision(duration, decision)
    }
}
