package com.antivocale.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.util.TreeUris
import com.antivocale.app.work.FolderListing
import kotlinx.coroutines.Dispatchers
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
     * Pure: each target against one tree's [listing] of (relative path, uri)
     * pairs. Generic in the uri so it is testable without Android.
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
     * Lists each target tree ONCE, matches, and copies every found file with
     * [SharedAudioHandler.copyToAppStorage]. A lost grant, or a failed or
     * throwing listing, gives FOLDER_UNAVAILABLE for that tree's targets; a
     * copy that does not succeed gives FILE_GONE.
     */
    suspend fun restage(
        context: Context,
        targets: List<BulkRedoItem.NeedsRestage>,
    ): Map<BulkRedoItem.NeedsRestage, RestageResult> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<BulkRedoItem.NeedsRestage, RestageResult>()
        for ((tree, group) in targets.groupBy { it.treeUri }) {
            val treeUri = Uri.parse(tree)
            val listing = if (TreeUris.holdsPersistableReadGrant(context, treeUri)) {
                listOrNull(context, treeUri)
            } else {
                Log.w(TAG, "event=restage_grant_missing")
                null
            }
            if (listing == null) {
                group.forEach { results[it] = RestageResult.Failed(SkipReason.FOLDER_UNAVAILABLE) }
                continue
            }
            if (listing.truncated) Log.w(TAG, "event=restage_listing_truncated")
            val pairs = listing.children.map { (child, uri) -> child.name to uri }
            for ((target, found) in match(group, pairs)) {
                results[target] = when (found) {
                    is RestageMatch.Found -> copy(context, found.uri)
                    RestageMatch.NotFound -> RestageResult.Failed(SkipReason.NOT_FOUND_IN_FOLDER)
                    RestageMatch.Ambiguous -> RestageResult.Failed(SkipReason.AMBIGUOUS)
                }
            }
        }
        results
    }

    private fun listOrNull(context: Context, treeUri: Uri): FolderListing.Listing? {
        val listing = try {
            FolderListing.listWithStatus(context, treeUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The worker reads a throwing listing as scan-abandoned; a redo
            // reads it as the folder being unavailable, like a null listing.
            Log.w(TAG, "event=restage_listing_threw", e)
            return null
        }
        if (listing == null) Log.w(TAG, "event=restage_listing_failed")
        return listing
    }

    private fun copy(context: Context, uri: Uri): RestageResult =
        when (val copy = SharedAudioHandler.copyToAppStorage(context, uri)) {
            is SharedAudioHandler.CopyResult.Success -> RestageResult.Staged(copy.path)
            else -> RestageResult.Failed(SkipReason.FILE_GONE)
        }
}
