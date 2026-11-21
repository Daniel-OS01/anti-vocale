package com.antivocale.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.util.TreeUris
import com.antivocale.app.work.FolderListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds a folder-watch row's source file again in its watched folder and
 * copies it back into app storage, for a bulk redo whose staged copy was
 * swept. The row keeps only its subfolder and the relative path's hash, so a
 * file matches when both agree; two matches are ambiguous and never guessed.
 */
object FolderRestage {

    private const val TAG = "FolderRestage"

    sealed class RestageMatch<out U> {
        data class Found<U>(val uri: U) : RestageMatch<U>()
        object NotFound : RestageMatch<Nothing>()
        object Ambiguous : RestageMatch<Nothing>()
    }

    sealed class RestageResult {
        data class Staged(val localPath: String) : RestageResult()
        data class Failed(val reason: SkipReason) : RestageResult()
    }

    /**
     * Pure: each target against a [listing] of (relative path, uri) pairs
     * (in [restage], its one directory's files). Generic in the uri so it is testable without Android.
     */
    fun <U> match(
        targets: List<BulkRedoItem.NeedsRestage>,
        listing: List<Pair<String, U>>,
    ): Map<BulkRedoItem.NeedsRestage, RestageMatch<U>> = targets.associateWith { target ->
        val hits = listing.filter { (relPath, _) ->
            FolderScanPlanner.subPathOf(relPath) == target.subPath &&
                relPath.hashCode().toUInt() == target.relPathHash
        }
        when (hits.size) {
            0 -> RestageMatch.NotFound
            1 -> RestageMatch.Found(hits.single().second)
            else -> RestageMatch.Ambiguous
        }
    }

    /**
     * Lists each target's ONE directory (its subfolder in its tree) once,
     * fully and one level deep, matches inside it, and copies every found
     * file with [SharedAudioHandler.copyToAppStorage]. Every candidate for a
     * target lives in that directory, so no capped recursive walk can hide a
     * same-hash twin. A lost grant, or a failed or throwing listing, gives
     * FOLDER_UNAVAILABLE; a missing directory NOT_FOUND_IN_FOLDER; two
     * same-named directories AMBIGUOUS; a copy that does not succeed FILE_GONE.
     *
     * [onStaged] gets each copy's path as soon as it exists, so a caller
     * can reclaim the copies of a restage that throws or is cancelled
     * partway. The loop checks for cancellation before every listing and
     * every copy, and deletes a copy that finished after cancellation.
     */
    suspend fun restage(
        context: Context,
        targets: List<BulkRedoItem.NeedsRestage>,
        onStaged: (String) -> Unit = {},
    ): Map<BulkRedoItem.NeedsRestage, RestageResult> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<BulkRedoItem.NeedsRestage, RestageResult>()
        for ((tree, treeGroup) in targets.groupBy { it.treeUri }) {
            val treeUri = Uri.parse(tree)
            if (!TreeUris.holdsPersistableReadGrant(context, treeUri)) {
                Log.w(TAG, "event=restage_grant_missing")
                treeGroup.forEach { results[it] = RestageResult.Failed(SkipReason.FOLDER_UNAVAILABLE) }
                continue
            }
            for ((subPath, group) in treeGroup.groupBy { it.subPath }) {
                ensureActive()
                val failure = when (val dir = listOrUnavailable(context, treeUri, subPath)) {
                    is FolderListing.DirListing.Files -> {
                        for ((target, found) in match(group, dir.files)) {
                            results[target] = when (found) {
                                is RestageMatch.Found -> {
                                    ensureActive()
                                    val copied = copy(context, found.uri)
                                    if (copied is RestageResult.Staged) {
                                        if (!isActive) {
                                            SharedAudioHandler.deleteStagedCopy(copied.localPath)
                                            ensureActive()
                                        }
                                        onStaged(copied.localPath)
                                    }
                                    copied
                                }
                                RestageMatch.NotFound -> RestageResult.Failed(SkipReason.NOT_FOUND_IN_FOLDER)
                                RestageMatch.Ambiguous -> RestageResult.Failed(SkipReason.AMBIGUOUS)
                            }
                        }
                        null
                    }
                    FolderListing.DirListing.NotFound -> SkipReason.NOT_FOUND_IN_FOLDER
                    FolderListing.DirListing.Ambiguous -> SkipReason.AMBIGUOUS
                    FolderListing.DirListing.Unavailable -> SkipReason.FOLDER_UNAVAILABLE
                }
                if (failure != null) group.forEach { results[it] = RestageResult.Failed(failure) }
            }
        }
        results
    }

    private fun listOrUnavailable(context: Context, treeUri: Uri, subPath: String?): FolderListing.DirListing {
        val listing = try {
            FolderListing.listDirectory(context, treeUri, subPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A redo reads a throwing listing as the folder being unavailable.
            Log.w(TAG, "event=restage_listing_threw", e)
            return FolderListing.DirListing.Unavailable
        }
        when (listing) {
            FolderListing.DirListing.Unavailable -> Log.w(TAG, "event=restage_listing_failed")
            FolderListing.DirListing.Ambiguous -> Log.w(TAG, "event=restage_dir_ambiguous")
            else -> Unit
        }
        return listing
    }

    private fun copy(context: Context, uri: Uri): RestageResult =
        when (val copy = SharedAudioHandler.copyToAppStorage(context, uri)) {
            is SharedAudioHandler.CopyResult.Success -> RestageResult.Staged(copy.path)
            else -> RestageResult.Failed(SkipReason.FILE_GONE)
        }
}
