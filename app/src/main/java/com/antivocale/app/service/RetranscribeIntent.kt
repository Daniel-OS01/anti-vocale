package com.antivocale.app.service

import android.content.Context
import android.content.Intent
import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.SourceOrigin
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.ui.viewmodel.LogEntry
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONE builder of the History re-transcribe request, shared by the
 * single-row redo (LogsViewModel) and the bulk redo ([HistoryBulkRedo]).
 * Hand-built rather than through [AudioRequestIntent.make] because it carries
 * the row's original PROMPT (see that factory's KDoc).
 */
object RetranscribeIntent {

    private val lastTaskMs = AtomicLong(0L)

    /**
     * A strictly increasing millisecond clock for [taskIdFor]: the same file
     * redone twice in one millisecond would otherwise mint the same folder-watch
     * id, and InferenceService drops a duplicate task id silently.
     */
    fun nextTaskMs(nowMs: Long = System.currentTimeMillis()): Long =
        lastTaskMs.updateAndGet { last -> maxOf(nowMs, last + 1) }

    /**
     * The redo's task id. A folder-watch row keeps the folder-watch shape
     * (tree uri of [origin], the original relative-path hash, [nowMs]) so a
     * redone row can be found again in its folder; any other row gets a UUID.
     */
    fun taskIdFor(row: LogEntry, origin: SourceOrigin, nowMs: Long): String {
        val treeUri = origin.folderKey
        val relPathHash = FolderWatchTaskIds.relPathHashOf(row.taskId)
        return if (origin.kind == SourceKind.FOLDER_WATCH && !treeUri.isNullOrBlank() && relPathHash != null) {
            FolderWatchTaskIds.mintWithHash(treeUri, relPathHash, nowMs)
        } else {
            UUID.randomUUID().toString()
        }
    }

    /**
     * [origin] is the row's effective origin (effectiveSourceOrigin), copied to
     * the new row. The backend and language overrides ride only when set in
     * [profile]; the single-row redo always sets the backend.
     */
    fun build(
        context: Context,
        row: LogEntry,
        origin: SourceOrigin,
        localPath: String,
        taskId: String,
        profile: TranscriptionProfile,
    ): Intent = Intent(context, InferenceService::class.java).apply {
        putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
        putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
        putExtra(TaskerRequestReceiver.EXTRA_PROMPT, row.prompt)
        putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
        putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_RETRANSCRIBE)
        profile.backendId?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        profile.language?.let { putExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE, it) }
        row.sourcePackageName?.let { putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it) }
        // TASK-736: the sender label must survive a re-run (the fresh row is
        // a new task; without this the label is one-shot).
        row.senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        origin.kind?.let { putExtra(InferenceService.EXTRA_SOURCE_KIND, it) }
        origin.folderKey?.let { putExtra(InferenceService.EXTRA_SOURCE_FOLDER_KEY, it) }
        origin.folderName?.let { putExtra(InferenceService.EXTRA_SOURCE_FOLDER_NAME, it) }
        origin.subPath?.let { putExtra(InferenceService.EXTRA_SOURCE_SUB_PATH, it) }
    }
}
