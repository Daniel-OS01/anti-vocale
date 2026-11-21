package com.antivocale.app.service

import android.content.Context
import android.content.Intent
import com.antivocale.app.receiver.TaskerRequestReceiver

/**
 * TASK-776: the ONE builder of the identity-carrying audio request sent to
 * [InferenceService]. ShareReceiverActivity (share and subtitle import),
 * ScheduledFolderWorker (folder watch), VoiceNoteAutoTranscriber
 * (automatic voice notes) and LogsViewModel's browse intake each
 * hand-rolled this exact shape; the copies had already begun drifting and
 * the next origin would have diverged silently (a missing extra here is
 * invisible until a row renders without its label).
 *
 * The taskId stays each caller's business: its derivation IS the caller's
 * contract (the PTT-name id keeps a redelivered note from double-running;
 * the folder-watch id folds the tree uri; the share and browse ids are
 * fresh per request).
 *
 * R6: the resolved scope profile rides here too: [make] carries both the
 * backend override and the language override
 * ([InferenceService.EXTRA_LANGUAGE_OVERRIDE]; null or blank sets no
 * extra), resolved by each origin through
 * [com.antivocale.app.data.ScopeProfileSource] before the enqueue.
 *
 * Deliberately NOT composed through this factory, each for a stated
 * reason: the retranscribe class ([RetranscribeIntent], shared by the single-row and bulk History redo;
 * NotificationActionReceiver's rerun-suspended arm) re-runs with a PROMPT
 * beyond this factory's surface (the rerun arm still applies the
 * re-resolved profile's two overrides itself); NotificationActionReceiver's subtitle-CHOICE arm forwards the
 * fixed extra list of the choice prompt it answers AND builds the real
 * SUBTITLES request with its track index (the only site allowed to: the
 * index is the user's tap, not a composed value); BenchmarkActivity is
 * the prompt-carrying shape with no identity; the Tasker intake
 * (TaskerRequestReceiver) defaults to the text request type, pins its
 * own reply-sink package extra, and sets no source; TaskerTrampoline-
 * Activity blindly rebroadcasts received extras. Folding any of them in
 * would grow a flags-shaped parameter, the sprawl this extraction exists
 * to avoid.
 */
object AudioRequestIntent {

    fun make(
        context: Context,
        source: String,
        taskId: String,
        filePath: String,
        sourcePackage: String? = null,
        senderName: String? = null,
        contentHash: String? = null,
        backendOverride: String? = null,
        /** TASK-889: the request came from the cold-start catch-up sweep
         *  (the per-note result notification is suppressed for it). */
        catchup: Boolean = false,
        /** R3: the persisted origin ([com.antivocale.app.data.SourceKind]); each
         *  caller names its own (the factory never derives it from [source]). */
        sourceKind: String? = null,
        sourceFolderKey: String? = null,
        sourceFolderName: String? = null,
        sourceSubPath: String? = null,
        /** R6: the resolved profile's language (null or blank = no extra). */
        languageOverride: String? = null,
    ): Intent = make(
        context, source, taskId, filePath,
        requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
        sourcePackage = sourcePackage,
        senderName = senderName,
        contentHash = contentHash,
        backendOverride = backendOverride,
        catchup = catchup,
        sourceKind = sourceKind,
        sourceFolderKey = sourceFolderKey,
        sourceFolderName = sourceFolderName,
        sourceSubPath = sourceSubPath,
        languageOverride = languageOverride)

    /**
     * Review F1: the subtitle-import arm as its OWN entry point. An open
     * requestType parameter let a caller pass REQUEST_TYPE_SUBTITLES and
     * silently produce a half-built request (no track index); one factory
     * function per request shape keeps the shape unbuildable when its
     * distinguishing extra is missing.
     */
    fun subtitleImport(
        context: Context,
        taskId: String,
        filePath: String,
        sourcePackage: String? = null,
        sourceKind: String? = null,
    ): Intent = make(
        context, InferenceService.SOURCE_SHARE, taskId, filePath,
        requestType = TaskerRequestReceiver.REQUEST_TYPE_SUBTITLE_IMPORT,
        sourcePackage = sourcePackage,
        sourceKind = sourceKind)

    private fun make(
        context: Context,
        source: String,
        taskId: String,
        filePath: String,
        requestType: String,
        sourcePackage: String? = null,
        senderName: String? = null,
        contentHash: String? = null,
        backendOverride: String? = null,
        catchup: Boolean = false,
        sourceKind: String? = null,
        sourceFolderKey: String? = null,
        sourceFolderName: String? = null,
        sourceSubPath: String? = null,
        languageOverride: String? = null,
    ): Intent = Intent(context, InferenceService::class.java).apply {
        putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, requestType)
        putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
        putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
        // No prompt extra: the service fills the settings default when the
        // extra is absent (the share path's long-standing contract).
        putExtra(InferenceService.EXTRA_SOURCE, source)
        sourcePackage?.let { putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it) }
        senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        contentHash?.let { putExtra(InferenceService.EXTRA_CONTENT_HASH, it) }
        backendOverride?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        if (catchup) putExtra(InferenceService.EXTRA_CATCHUP, true)
        sourceKind?.let { putExtra(InferenceService.EXTRA_SOURCE_KIND, it) }
        sourceFolderKey?.let { putExtra(InferenceService.EXTRA_SOURCE_FOLDER_KEY, it) }
        sourceFolderName?.let { putExtra(InferenceService.EXTRA_SOURCE_FOLDER_NAME, it) }
        sourceSubPath?.let { putExtra(InferenceService.EXTRA_SOURCE_SUB_PATH, it) }
        languageOverride?.takeIf { it.isNotBlank() }?.let {
            putExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE, it)
        }
    }
}
