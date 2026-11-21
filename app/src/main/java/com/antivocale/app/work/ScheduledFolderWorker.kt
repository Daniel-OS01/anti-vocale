package com.antivocale.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.antivocale.app.data.CallRecordingFilenames
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.FolderWalk
import com.antivocale.app.data.OriginFacts
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.ScopeProfileSource
import com.antivocale.app.service.AudioRequestIntent
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.ResolvedProfile
import com.antivocale.app.util.SharedAudioHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * TASK-741 (GH #125) slice 3: the scheduled folder scan. One run lists one
 * watched folder through SAF (subfolders included, capped by [FolderWalk]),
 * feeds the listing to [FolderScanPlanner],
 * copies every stable unenqueued file into app storage (the service
 * consumes LOCAL paths, exactly like a share), and enqueues it through
 * [InferenceEnqueue] with [InferenceService.SOURCE_FOLDER_WATCH]. The
 * demoted snapshot (Failed enqueues back to enqueuedAt = null, per the
 * planner's worker-duty contract) is persisted before returning.
 *
 * The worker duties the planner's KDoc names as contract:
 *  - an empty or failed listing is scan-abandoned: the persisted-grant
 *    check below skips folders whose tree permission is gone, and a grant
 *    that is held but lists nothing is treated as a genuinely-empty
 *    folder (the enqueued-facts-kept policy bounds the damage of a
 *    provider hiccup to the awaiting entries' stability wait);
 *  - overlapping scans of the SAME folder must not run: [scanMutexes]
 *    serializes per folder across the periodic run and the manual
 *    scan-now (a second arrival schedules a backoff re-check instead of
 *    dropping its wake: the in-flight scan may have listed before the
 *    file that woke it landed).
 *
 * Wired for DI via [HiltWorker] like [SubtitleChoiceTimeoutWorker].
 */
@HiltWorker
class ScheduledFolderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ScheduledFolderStore,
    private val scopeProfileSource: ScopeProfileSource,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(KEY_TREE_URI)
            ?: return Result.failure()
        val scanNow = inputData.getBoolean(KEY_SCAN_NOW, false)
        val attempt = inputData.getInt(KEY_RECHECK_ATTEMPT, 0)
        return scanFolder(treeUri, scanNow, attempt)
    }

    /** scanNow is slice 4's seam: the manual trigger distinguishes its runs
     *  (logging/UX); the serialization treats both identically. */
    private suspend fun scanFolder(treeUriString: String, scanNow: Boolean, attempt: Int): Result {
        // Per-folder serialization: the planner's read-plan-write window is
        // not protected by the store's mutation mutex, so a periodic run and
        // a manual scan-now of the same folder must not interleave.
        val mutex = scanMutexes.computeIfAbsent(treeUriString) { Mutex() }
        if (!mutex.tryLock()) {
            // The in-flight scan may have listed BEFORE the file that woke
            // this run landed: a follow-up re-check keeps it from waiting
            // for the next periodic run.
            Log.i(TAG, "Scan of $treeUriString already in flight; re-checking shortly")
            ScheduledFolderScheduler.recheck(applicationContext, treeUriString, attempt)
            return Result.success()
        }
        try {
            val folder = store.byUri(treeUriString) ?: run {
                // Removed between schedule and run: drop the schedule with it.
                ScheduledFolderScheduler.cancel(applicationContext, treeUriString)
                return Result.success()
            }
            // Uri.parse is lenient (never throws for non-null); a garbage
            // string simply matches no persisted grant below.
            val treeUri = Uri.parse(treeUriString)
            if (!grantHeld(treeUri)) {
                Log.w(TAG, "Persisted grant lost for $treeUriString; scan abandoned (record kept)")
                return Result.success()
            }
            // ONE projection query replaces DocumentFile's 4N+1 binder
            // round trips, and (the load-bearing part) makes a BROKEN
            // listing distinguishable from an EMPTY folder: a null cursor
            // or a throwing query is scan-abandoned with the snapshot kept
            // verbatim, while an empty cursor with the grant held is a
            // genuinely-empty folder and plans normally (review F1).
            // The walk descends subfolders (FolderWalk owns the caps); a
            // failed SUBdirectory query fails the whole listing the same way.
            // Sampled BEFORE the walk: a legacy upgrade's first cutoff must not
            // postdate a save that lands in an already-visited directory
            // while the walk is still running (it would be read as backlog).
            val scanStartedAt = System.currentTimeMillis()
            val listing = try {
                FolderListing.listWithStatus(applicationContext, treeUri) ?: run {
                    Log.w(TAG, "Listing failed for $treeUriString; scan abandoned, snapshot kept")
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Listing threw for $treeUriString; scan abandoned, snapshot kept", e)
                return Result.success()
            }
            val childrenWithUris = listing.children
            val children = childrenWithUris.map { it.first }
            if (listing.truncated) {
                Log.w(TAG, "Walk of $treeUriString hit the depth/entry cap; files beyond it are not scanned")
            }
            val now = System.currentTimeMillis()
            // The record already carries the snapshot; a second store read
            // would re-decode the same JSON for one field.
            val previous = if (folder.recursiveBaselined) {
                folder.snapshot
            } else {
                // A pre-recursion folder (or a capped add): its subfolder
                // backlog is recorded as handled before planning, persisted
                // with the flag flip in ONE write. A truncated walk saw only
                // part of the subfolders, so the flag stays false and the next
                // scans keep baselining what they newly see, but ONLY files at
                // or before the persisted cutoff: a file beyond the cap that
                // later comes into view is backlog, while a save made after the
                // watch began plans normally. The first upgrade scan of a
                // legacy record fixes the cutoff at its own time and persists
                // it in the same write, so it never moves forward.
                val cutoff = folder.recursiveBaselineCutoff ?: scanStartedAt
                val upgraded = FolderScanPlanner.recursiveUpgradeBaseline(children, folder.snapshot, now, cutoff)
                val complete = !listing.truncated
                if (complete || upgraded != folder.snapshot || folder.recursiveBaselineCutoff == null) {
                    store.saveSnapshot(
                        treeUriString,
                        upgraded,
                        recursiveBaselined = complete,
                        // A complete walk flips the flag, which clears it.
                        recursiveBaselineCutoff = cutoff,
                    )
                }
                Log.i(
                    TAG,
                    "Recursive upgrade of $treeUriString: ${upgraded.size - folder.snapshot.size} " +
                        "subfolder files baselined, flag ${if (complete) "flipped" else "kept (walk truncated)"}",
                )
                upgraded
            }
            // A capped walk did not reach every file: what it did not list
            // is unseen, not vanished, and keeps its snapshot entry.
            val plan = FolderScanPlanner.plan(children, previous, now = now, listingComplete = !listing.truncated)
            // A first-sight file waits for a second same-size sighting; the
            // follow-up brings that sighting seconds later instead of a
            // period later (deferred batch-cap tails ride it too).
            if (plan.awaitingStability.isNotEmpty() || plan.morePending) {
                ScheduledFolderScheduler.recheck(applicationContext, treeUriString, attempt)
            }
            if (plan.toEnqueue.isEmpty()) {
                // Quiet scan: skip the no-op write when nothing moved.
                if (plan.nextSnapshot != previous) {
                    store.saveSnapshot(treeUriString, plan.nextSnapshot)
                }
                return Result.success()
            }

            // The snapshot is persisted per successful enqueue, not once at
            // the end: a worker cancellation (the 10-minute stop limit, an
            // OEM freezer kill) mid-batch must not re-open the double-
            // transcription race the per-folder mutex closed (review F2).
            val working = plan.nextSnapshot.toMutableList()
            var enqueued = 0
            var failed = 0
            // R6: each file's scope profile (folder profile, deepest
            // subfolder rule, the language map), resolved before the
            // enqueue. The inputs are the tree and the subfolder only, so one
            // resolution per distinct subfolder covers every file in it.
            val profiles: Map<String?, ResolvedProfile> = plan.toEnqueue
                .map { FolderScanPlanner.subPathOf(it.name) }
                .distinct()
                .associateWith { subPath ->
                    scopeProfileSource.resolve(OriginFacts(folderTreeUri = treeUriString, subPath = subPath))
                }
            fun profileOf(child: FolderScanPlanner.Child): ResolvedProfile? =
                profiles[FolderScanPlanner.subPathOf(child.name)]
            // Same-model files run back to back (fewer model swaps in the
            // service queue); the global model's group goes first.
            val ordered = orderByBackend(plan.toEnqueue) { profileOf(it)?.backendOverride }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (child in ordered) {
                    val uri = childrenWithUris.firstOrNull { it.first.name == child.name }?.second
                    val stamped: FileSnapshot
                    if (uri == null) {
                        // Defensive: a planner name the listing lacks is a
                        // retry, never a silent consume of the dedup fact.
                        Log.w(TAG, "No uri for planned ${child.name}; demoting")
                        stamped = FileSnapshot(child.name, child.size, null)
                        failed++
                    } else {
                        val copy = SharedAudioHandler.copyToAppStorage(applicationContext, uri)
                        val outcome = (copy as? SharedAudioHandler.CopyResult.Success)
                            ?.let {
                                InferenceEnqueue.start(
                                    applicationContext,
                                    buildServiceIntent(
                                        applicationContext, it.path, child.name, treeUriString,
                                        folder.displayName, profileOf(child),
                                    ),
                                )
                            }
                        if (copy is SharedAudioHandler.CopyResult.Success &&
                            outcome !is InferenceEnqueue.Outcome.Failed
                        ) {
                            stamped = FileSnapshot(child.name, child.size, System.currentTimeMillis())
                            enqueued++
                        } else {
                            // The demote-not-drop contract: the entry keeps
                            // its size baseline and retries next scan.
                            Log.w(TAG, "Copy/enqueue failed for ${child.name}; demoting to retry next scan")
                            stamped = FileSnapshot(child.name, child.size, null)
                            failed++
                        }
                    }
                    val idx = working.indexOfFirst { it.name == child.name }
                    if (idx >= 0) working[idx] = stamped
                    store.saveSnapshot(treeUriString, working)
                }
            }
            // In the background-only regime weeks can pass without an app
            // open, and the share-dir cleanup runs only at process start:
            // the scan tail owns its copies' hygiene.
            runCatching { SharedAudioHandler.cleanupOldFiles(applicationContext) }
            Log.i(
                TAG,
                "Scanned $treeUriString${if (scanNow) " (manual)" else ""}: " +
                    "$enqueued enqueued, $failed failed and demoted, " +
                    "${plan.awaitingStability.size} awaiting stability" +
                    if (plan.morePending) ", batch cap deferred the tail" else "",
            )
            return Result.success()
        } finally {
            mutex.unlock()
        }
    }

    private fun grantHeld(treeUri: Uri): Boolean =
        com.antivocale.app.util.TreeUris.holdsPersistableReadGrant(applicationContext, treeUri)

    companion object {
        private const val TAG = "ScheduledFolderWorker"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_SCAN_NOW = "scan_now"
        /** Stability re-checks already spent in this lineage (backoff index). */
        const val KEY_RECHECK_ATTEMPT = "recheck_attempt"

        /**
         * The share-shaped request (local path + request type), with the folder
         * watch as the source so History rows and the skip logic can tell the
         * origin apart; no source package (no calling app). [relativePath] is the
         * planner's name (see FolderScanPlanner's IDENTITY); the caller lookup
         * reads its basename, the file name a recorder actually wrote, and its
         * parent directory rides as the History subfolder (null for a root file).
         * [profile] is the file's already-resolved scope profile (R6; resolved
         * by the scan, so this stays a pure builder); null sets no override.
         */
        internal fun buildServiceIntent(
            context: Context,
            localPath: String,
            relativePath: String,
            treeUri: String,
            folderName: String,
            profile: ResolvedProfile? = null,
        ): Intent =
            // TASK-776: the shared audio-request factory.
            AudioRequestIntent.make(
                context,
                InferenceService.SOURCE_FOLDER_WATCH,
                // The id shape (tree + relative-path hashes) is owned by
                // FolderWatchTaskIds; see its KDoc for why both ride it.
                FolderWatchTaskIds.mint(treeUri, relativePath, System.currentTimeMillis()),
                localPath,
                senderName = CallRecordingFilenames.callerFromFileName(FolderScanPlanner.baseNameOf(relativePath)),
                sourceKind = SourceKind.FOLDER_WATCH,
                sourceFolderKey = treeUri,
                sourceFolderName = folderName,
                sourceSubPath = FolderScanPlanner.subPathOf(relativePath),
                backendOverride = profile?.backendOverride,
                languageOverride = profile?.languageOverride,
            )

        /**
         * R6: the scan's enqueue order, stably sorted by resolved backend
         * override (null, the global model, first), so same-model files
         * run back to back; the planner's order holds inside each group.
         */
        internal fun <T> orderByBackend(items: List<T>, backendOf: (T) -> String?): List<T> =
            items.sortedWith(compareBy<T, String?>(nullsFirst(naturalOrder<String>())) { backendOf(it) })

        /** The per-folder serialization (see the class KDoc). */
        private val scanMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        /**
         * Runs [block] holding [treeUri]'s scan lock, the same mutex a scan
         * holds from its record read to its last snapshot write. Settings
         * wraps its record add and remove in it: a scan that read the old
         * record must finish before a remove lands, or it could outlive the
         * remove and overwrite a re-add's fresh baseline (the store's writes
         * match on the tree uri only). A scan arriving while Settings holds
         * the lock takes its usual tryLock miss and schedules a re-check.
         */
        internal suspend fun <T> withFolderScanLock(treeUri: String, block: suspend () -> T): T =
            scanMutexes.computeIfAbsent(treeUri) { Mutex() }.withLock { block() }
    }
}

/**
 * The folder listing the worker scans and the add-time baseline records
 * (one owner, so both see the same children for the same tree).
 */
internal object FolderListing {

    /** One recursive listing: the (child, uri) pairs and whether a cap cut the walk. */
    data class Listing(
        val children: List<Pair<FolderScanPlanner.Child, Uri>>,
        val truncated: Boolean,
    )

    /**
     * The recursive listing: (child, uri) pairs whose child name is the
     * relative path from the tree root (a bare name for a root file), plus
     * the truncation flag, or NULL when the provider answered nothing for
     * the root OR for any subdirectory (scan-abandoned; a broken subtree is
     * never read as an empty one). The walk policy (depth/entry caps,
     * hidden-directory skip, path joining, the failure contract) lives in
     * [FolderWalk]; this only supplies one DocumentsContract child query per
     * directory. A throwing query propagates (the worker reads it as
     * scan-abandoned, as before recursion).
     */
    fun listWithStatus(context: Context, treeUri: Uri): Listing? {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val walk = FolderWalk.walk(root) { dirDocId -> queryChildren(context, treeUri, dirDocId) }
            ?: return null
        return Listing(
            children = walk.entries.map { entry ->
                entry.child to DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            },
            truncated = walk.truncated,
        )
    }

    /** [listDirectory]'s answer: [FolderWalk.DirFiles] with uris in place of document ids. */
    sealed class DirListing {
        /** The directory's file children: (relative path from the tree root, uri). */
        data class Files(val files: List<Pair<String, Uri>>) : DirListing()
        object NotFound : DirListing()
        object Ambiguous : DirListing()
        object Unavailable : DirListing()
    }

    /**
     * ONE directory of the tree, the one whose files [listWithStatus] names
     * under [subPath] (null = the root), fully listed one level deep with no
     * cap. The segment resolution and path joining are [FolderWalk.listDirectory].
     * A throwing query propagates, as in [listWithStatus].
     */
    fun listDirectory(context: Context, treeUri: Uri, subPath: String?): DirListing {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        return when (val dir = FolderWalk.listDirectory(root, subPath) { id -> queryChildren(context, treeUri, id) }) {
            is FolderWalk.DirFiles.Found -> DirListing.Files(
                dir.files.map { (relPath, docId) ->
                    relPath to DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                },
            )
            FolderWalk.DirFiles.NotFound -> DirListing.NotFound
            FolderWalk.DirFiles.Ambiguous -> DirListing.Ambiguous
            FolderWalk.DirFiles.Unavailable -> DirListing.Unavailable
        }
    }

    /**
     * A child cursor is a COMPLETE listing only when its extras carry
     * neither [DocumentsContract.EXTRA_LOADING] = true (the provider is still
     * fetching and returned a partial result) nor [DocumentsContract.EXTRA_ERROR].
     * A partial or errored cursor read as a full one would make unseen files
     * look vanished, and an empty partial subfolder would let the recursive
     * upgrade complete without baselining that subtree.
     */
    internal fun isCompleteListing(extras: android.os.Bundle?): Boolean {
        if (extras == null) return true
        return !extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
            !extras.containsKey(DocumentsContract.EXTRA_ERROR)
    }

    /**
     * One directory's children as walk rows, or NULL when the provider
     * answered nothing, or only a partial (loading) or errored cursor
     * ([isCompleteListing]): the walk then fails as a whole and the scan is
     * abandoned and retried. A missing name, MIME or document id filters the row.
     */
    private fun queryChildren(context: Context, treeUri: Uri, dirDocId: String): List<FolderWalk.Row>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            ),
            null, null, null,
        ) ?: return null
        cursor.use { c ->
            if (!isCompleteListing(c.extras)) {
                Log.w("FolderListing", "Partial or errored listing of $dirDocId; walk abandoned")
                return null
            }
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val out = ArrayList<FolderWalk.Row>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(nameIx) ?: continue
                val mime = c.getString(mimeIx) ?: continue
                val docId = c.getString(idIx) ?: continue
                val size = if (c.isNull(sizeIx)) 0L else c.getLong(sizeIx)
                val mtime = if (c.isNull(mtimeIx)) 0L else c.getLong(mtimeIx)
                out += FolderWalk.Row(
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = size,
                    lastModified = mtime,
                    documentId = docId,
                )
            }
            return out
        }
    }
}

/**
 * The scheduling half of slice 3: keeps one unique periodic name per folder
 * (the store's period drives the interval), cancels schedules for removed
 * folders, and posts the manual scan-now one-shot under the same per-folder
 * name family.
 */
object ScheduledFolderScheduler {

    private const val TAG = "scheduled_folder_watch"
    private const val MEDIA_TRIGGER_NAME = "scheduled_folder_media_trigger"
    private const val URI_TAG_PREFIX = "fw:"

    private fun uniqueName(treeUri: String) = "scheduled_folder_scan:$treeUri"

    /**
     * A folder's one-shot names. The kind rides after '|', which never occurs
     * in an encoded tree URI: a plain "_now" suffix let folder "Calls" own
     * the name "...Calls_now", the PERIODIC name of a sibling folder
     * "Calls_now", so removing one folder cancelled the other's schedule.
     */
    private fun oneShotName(treeUri: String, kind: String) = "${uniqueName(treeUri)}|$kind"

    /**
     * The two-directional reconcile: enqueues (or updates) one unique
     * periodic per watched folder AND cancels schedules whose folder record
     * is gone (a zombie wake per period otherwise survives every restart,
     * review F6; enumeration rides the TAG, the tree uri comes from the
     * work's own input data).
     */
    fun sync(context: Context, folders: List<com.antivocale.app.data.WatchedFolder>) {
        val wm = WorkManager.getInstance(context)
        val wanted = folders.associateBy { it.treeUri }
        for (folder in wanted.values) {
            enqueuePeriodic(wm, folder)
        }
        if (wanted.isEmpty()) disarmMediaTrigger(context) else armMediaTrigger(context)
        val infos: List<androidx.work.WorkInfo> = kotlinx.coroutines.runBlocking {
            wm.getWorkInfosByTag(TAG).get()
        }
        for (info in infos) {
            if (info.state.isFinished) continue
            val treeUri = info.tags.firstOrNull { it.startsWith(URI_TAG_PREFIX) }?.removePrefix(URI_TAG_PREFIX)
            if (treeUri != null && treeUri !in wanted) {
                Log.w(TAG, "Cancelling zombie folder-watch schedule (folder record gone): ${info.id}")
                wm.cancelWorkById(info.id)
            }
        }
    }

    /**
     * Cancels every unique work of one folder when its record goes away: the
     * periodic AND the scan-now, trigger and re-check one-shots (a queued
     * follow-up would otherwise still wake after the remove).
     */
    fun cancel(context: Context, treeUri: String) {
        val wm = WorkManager.getInstance(context)
        for (name in uniqueNamesOf(treeUri)) wm.cancelUniqueWork(name)
    }

    /** Every unique work name one folder can hold; [cancel] clears them all. */
    internal fun uniqueNamesOf(treeUri: String): List<String> = listOf(
        uniqueName(treeUri),
        oneShotName(treeUri, "now"),
        oneShotName(treeUri, "trigger"),
        oneShotName(treeUri, "recheck"),
    )

    /**
     * The targeted reconcile for one mutation (TASK-741 slice 4): the caller
     * holds the changed folder, so the full sync (every folder re-enqueued
     * plus the zombie sweep) is strictly more work than the one enqueue.
     * The sweep stays owned by remove (a direct cancel) and by the app-start
     * self-heal.
     */
    fun enqueueFolder(context: Context, folder: com.antivocale.app.data.WatchedFolder) {
        enqueuePeriodic(WorkManager.getInstance(context), folder)
        armMediaTrigger(context)
    }

    /**
     * The scan-on-save wake (the periodic run stays as the safety net). A
     * content-URI-triggered one-shot: the system runs it when MediaStore
     * reports a change under the audio or downloads collections (a saver
     * like Signal publishes through MediaStore, and the media index picks
     * up raw-path writes on API 30+), so nothing runs or polls in between
     * and photos or videos never wake it. A trigger job fires ONCE: the
     * trigger worker re-arms itself (append) while folders remain.
     *
     * [policy] is KEEP for the arm-if-missing callers (app start, folder
     * add: a pending trigger must not be duplicated) and APPEND_OR_REPLACE
     * from the running trigger worker (KEEP would drop the re-arm because
     * the running work itself still holds the name).
     */
    fun armMediaTrigger(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        WorkManager.getInstance(context).enqueueUniqueWork(MEDIA_TRIGGER_NAME, policy, mediaTriggerRequest())
    }

    fun disarmMediaTrigger(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MEDIA_TRIGGER_NAME)
    }

    internal fun mediaTriggerRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<FolderMediaTriggerWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            addContentUriTrigger(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true)
                        }
                    }
                    // One save posts several changes (insert, publish,
                    // metadata): coalesce them into one wake, yet never hold
                    // a save longer than a few seconds.
                    .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                    .build(),
            )
            .addTag(TAG)
            .build()

    /**
     * The scan a media change asks for: immediate, appended behind a running
     * one (KEEP would drop it while a scan that may have listed too early
     * still runs; the per-folder mutex serializes the rest).
     */
    fun triggeredScan(context: Context, treeUri: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            oneShotName(treeUri, "trigger"),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            followUpRequest(treeUri, attempt = 0, delaySeconds = 0),
        )
    }

    /**
     * The stability follow-up: re-scan [treeUri] after the backoff step for
     * [attempt], or nothing once the backoff is spent. Its own name so a
     * long backoff step never delays a fresh trigger scan.
     */
    fun recheck(context: Context, treeUri: String, attempt: Int) {
        val delay = FolderScanPlanner.recheckDelaySeconds(attempt) ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            oneShotName(treeUri, "recheck"),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            followUpRequest(treeUri, attempt + 1, delay),
        )
    }

    internal fun followUpRequest(treeUri: String, attempt: Int, delaySeconds: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ScheduledFolderWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(ScheduledFolderWorker.KEY_TREE_URI, treeUri)
                    .putInt(ScheduledFolderWorker.KEY_RECHECK_ATTEMPT, attempt)
                    .build(),
            )
            .build()

    /**
     * The one enqueue arm [sync] and [enqueueFolder] share (review F3): the
     * unique name, the UPDATE policy, both tags, and the input data are one
     * contract with two callers - a future change landing in only one site
     * would silently break the other (the zombie sweep enumerates by TAG,
     * the worker resolves its folder from KEY_TREE_URI).
     */
    private fun enqueuePeriodic(wm: WorkManager, folder: com.antivocale.app.data.WatchedFolder) {
        wm.enqueueUniquePeriodicWork(
            uniqueName(folder.treeUri),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScheduledFolderWorker>(folder.periodHours.toLong(), TimeUnit.HOURS)
                // The family tag enumerates every folder-watch work for
                // the zombie sweep; the per-uri tag identifies WHICH
                // folder (WorkInfo 2.10 exposes tags but not input data).
                .addTag(TAG)
                .addTag("$URI_TAG_PREFIX${folder.treeUri}")
                .setInputData(
                    Data.Builder().putString(ScheduledFolderWorker.KEY_TREE_URI, folder.treeUri).build(),
                )
                .build(),
        )
    }

    /**
     * The manual trigger (slice 4's Scan now button): a plain one-shot.
     * Deliberately NOT expedited (TASK-803): on API 26-30 WorkManager routes
     * every expedited request through the foreground fallback and calls
     * getForegroundInfo(), whose CoroutineWorker default throws; the work
     * then resolves FAILED and the tapped scan silently never runs. Expedited
     * buys nothing here (on 31+ this policy already falls back to a normal
     * request under quota pressure), and overlap with a running periodic
     * scan of the same folder is prevented by the worker's per-folder mutex,
     * not by the name (a one-shot cannot share a periodic's unique slot
     * without replacing the schedule).
     */
    fun scanNow(context: Context, treeUri: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            oneShotName(treeUri, "now"),
            ExistingWorkPolicy.KEEP,
            scanNowRequest(treeUri),
        )
    }

    internal fun scanNowRequest(treeUri: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ScheduledFolderWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScheduledFolderWorker.KEY_TREE_URI, treeUri)
                    .putBoolean(ScheduledFolderWorker.KEY_SCAN_NOW, true)
                    .build(),
            )
            .build()
}

/**
 * The scan-on-save wake (see [ScheduledFolderScheduler.armMediaTrigger]):
 * runs when the system reports a new or changed audio/download item, posts
 * one immediate scan per watched folder, and re-arms itself. With no folder
 * left it simply does not re-arm, so removing the last folder ends the wakes.
 */
@HiltWorker
class FolderMediaTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ScheduledFolderStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val folders = store.folders()
        if (folders.isEmpty()) return Result.success()
        for (folder in folders) {
            ScheduledFolderScheduler.triggeredScan(applicationContext, folder.treeUri)
        }
        ScheduledFolderScheduler.armMediaTrigger(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
