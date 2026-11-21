package com.antivocale.app.service

import android.content.Context
import android.util.Log
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.SourceOrigin
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.effectiveSourceOrigin
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.util.SharedAudioHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One selected row that can be redone, with its audio at [localPath]. */
data class BulkRedoReady(
    val row: LogEntry,
    /** The row's effective origin (effectiveSourceOrigin), copied to the new row. */
    val origin: SourceOrigin,
    val localPath: String,
    /** True when [localPath] is a copy this batch re-staged from the watched folder. */
    val restaged: Boolean,
    /** Above the long-audio threshold for the batch's backend (the single-row dialog's rule). */
    val isLong: Boolean,
    val durationSeconds: Double,
)

/** The result of [HistoryBulkRedo.prepare]; hand it to enqueue or discard, never both. */
data class PreparedBatch(
    /** In History order (newest first). */
    val items: List<BulkRedoReady>,
    val skipped: Map<SkipReason, Int>,
    val longCount: Int,
    /** The longest long item's duration; 0 when [longCount] is 0. */
    val longestSeconds: Double,
    /** Every copy this batch re-staged (the batch owns them until enqueue). */
    val restagedPaths: List<String>,
    /** The backend the long-audio probe used: the profile's, else the active one. */
    val backendId: String,
)

data class BulkRedoReport(val queued: Int, val failedToStart: Int)

/**
 * The History bulk "redo with model X / language Y": load the selected rows,
 * plan them ([BulkRedoPlanner]), re-stage swept folder-watch files
 * ([FolderRestage]), probe each for long audio, then enqueue one
 * [RetranscribeIntent] per item through [InferenceEnqueue] (the ONE start
 * path). Enqueue order groups same-profile requests so the service can keep
 * the model loaded between them. No notifications of its own:
 * SOURCE_RETRANSCRIBE posts no result notification.
 */
@Singleton
class HistoryBulkRedo internal constructor(
    private val context: Context,
    private val logDao: LogDao,
    private val scheduledFolderStore: ScheduledFolderStore,
    private val preferencesManager: PreferencesManager,
    private val longAudioDecider: LongAudioDecider,
    /** [FolderRestage.restage] in production; a seam for tests. */
    private val restage: suspend (List<BulkRedoItem.NeedsRestage>) -> Map<BulkRedoItem.NeedsRestage, FolderRestage.RestageResult>,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        logDao: LogDao,
        scheduledFolderStore: ScheduledFolderStore,
        preferencesManager: PreferencesManager,
        longAudioDecider: LongAudioDecider,
    ) : this(
        context, logDao, scheduledFolderStore, preferencesManager, longAudioDecider,
        { targets -> FolderRestage.restage(context, targets) },
    )

    /**
     * Loads, plans, re-stages and probes [rowIds]. [onProgress] reports rows
     * handled out of rows found (skips count as handled).
     */
    suspend fun prepare(
        rowIds: Collection<String>,
        profile: TranscriptionProfile,
        onProgress: (done: Int, total: Int) -> Unit,
    ): PreparedBatch {
        val rows = logDao.getByIds(rowIds.toList())
            .sortedByDescending { it.timestamp }
            .map { it.toLogEntry() }
        val total = rows.size
        val folders = scheduledFolderStore.folders()
        val plan = withContext(Dispatchers.IO) {
            BulkRedoPlanner.plan(rows, { File(it).exists() }, folders)
        }
        val skipped = LinkedHashMap<SkipReason, Int>()
        fun skip(reason: SkipReason) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        plan.filterIsInstance<BulkRedoItem.Skipped>().forEach { skip(it.reason) }
        var done = skipped.values.sum()
        onProgress(done, total)

        val restageResults = restage(plan.filterIsInstance<BulkRedoItem.NeedsRestage>())
        val restagedPaths = restageResults.values
            .filterIsInstance<FolderRestage.RestageResult.Staged>()
            .map { it.localPath }

        val backendId = profile.backendId ?: preferencesManager.transcriptionBackend.first()
        val items = ArrayList<BulkRedoReady>()
        // Plan order is History order, so the ready list keeps it.
        for (item in plan) {
            val (localPath, restaged) = when (item) {
                is BulkRedoItem.Skipped -> continue
                is BulkRedoItem.Ready -> item.localPath to false
                is BulkRedoItem.NeedsRestage -> when (val result = restageResults[item]) {
                    is FolderRestage.RestageResult.Staged -> result.localPath to true
                    is FolderRestage.RestageResult.Failed -> {
                        skip(result.reason)
                        onProgress(++done, total)
                        continue
                    }
                    // A restager that dropped a target: nothing to run it from.
                    null -> {
                        skip(SkipReason.FILE_GONE)
                        onProgress(++done, total)
                        continue
                    }
                }
            }
            val decision = longAudioDecider.decide(backendId, localPath, context)
            items += BulkRedoReady(
                row = item.row,
                origin = originOf(item.row, folders),
                localPath = localPath,
                restaged = restaged,
                isLong = decision.warn.showDialog,
                durationSeconds = decision.durationSeconds,
            )
            onProgress(++done, total)
        }
        val long = items.filter { it.isLong }
        Log.i(TAG, "event=bulk_redo_prepared total=$total ready=${items.size} long=${long.size} restaged=${restagedPaths.size}")
        return PreparedBatch(
            items = items,
            skipped = skipped,
            longCount = long.size,
            longestSeconds = long.maxOfOrNull { it.durationSeconds } ?: 0.0,
            restagedPaths = restagedPaths,
            backendId = backendId,
        )
    }

    /**
     * Starts one request per item (long items only when [includeLong]),
     * grouped by (backend, language). The started items' staged copies are
     * the queue's inputs and stay; the re-staged copies of items that do not
     * start (dropped long items, failed starts) are deleted.
     */
    fun enqueue(batch: PreparedBatch, profile: TranscriptionProfile, includeLong: Boolean): BulkRedoReport {
        val effective = profile.copy(backendId = profile.backendId ?: batch.backendId)
        val (run, dropped) = batch.items.partition { includeLong || !it.isLong }
        dropped.filter { it.restaged }.forEach { SharedAudioHandler.deleteStagedCopy(it.localPath) }
        val ordered = BulkRedoPlanner.orderForEnqueue(run) { effective.backendId to effective.language }
        var queued = 0
        var failed = 0
        for (item in ordered) {
            val taskId = RetranscribeIntent.taskIdFor(item.row, item.origin, RetranscribeIntent.nextTaskMs())
            val intent = RetranscribeIntent.build(context, item.row, item.origin, item.localPath, taskId, effective)
            when (InferenceEnqueue.start(context, intent)) {
                InferenceEnqueue.Outcome.Started,
                InferenceEnqueue.Outcome.FallbackNotificationPosted -> queued++
                is InferenceEnqueue.Outcome.Failed -> {
                    failed++
                    if (item.restaged) SharedAudioHandler.deleteStagedCopy(item.localPath)
                }
            }
        }
        Log.i(TAG, "event=bulk_redo_enqueued queued=$queued failed=$failed dropped_long=${dropped.size}")
        return BulkRedoReport(queued = queued, failedToStart = failed)
    }

    /** The user cancelled before enqueue: deletes every copy the batch re-staged. */
    fun discard(batch: PreparedBatch) {
        batch.restagedPaths.forEach { SharedAudioHandler.deleteStagedCopy(it) }
    }

    private fun originOf(row: LogEntry, folders: List<com.antivocale.app.data.WatchedFolder>): SourceOrigin =
        effectiveSourceOrigin(
            row.taskId,
            SourceOrigin(row.sourceKind, row.sourceFolderKey, row.sourceFolderName, row.sourceSubPath),
            folders,
        )

    private companion object {
        const val TAG = "HistoryBulkRedo"
    }
}
