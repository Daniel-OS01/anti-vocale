package com.antivocale.app.transcription

import android.util.Log
import com.antivocale.app.util.formatFileSize
import java.io.File

private const val TAG = "OrphanedModelDirCleaner"

/**
 * Deletes model subdirectories under [storageDir] whose name is NOT in [validDirNames].
 *
 * This reclaims disk space from stranded old-version model directories left behind by
 * format/variant pivots (e.g. a fp32 model dir superseded by an int8 one). It is
 * deliberately **name-based, not validity-based**: a partial or in-progress download
 * also fails [validateModelDirectory]-style checks, so validating would risk deleting
 * an active download. An active download always targets a current variant dir-name,
 * so matching the dir name against the set of known current names is the safe
 * discriminator.
 *
 * The core invariant: a directory whose name is in [validDirNames] is NEVER deleted,
 * regardless of its contents or completeness.
 *
 * Each deletion is wrapped in its own try/catch so one failure does not abort the
 * rest of the sweep.
 *
 * @param storageDir The parent directory that holds per-variant model subdirectories
 *                   (e.g. [SherpaModelManager.getModelStorageDir]).
 * @param validDirNames The set of currently-known variant directory names for the
 *                      backend that owns [storageDir]. Subdirectories whose name is
 *                      not in this set are considered orphaned.
 * @param protectedDirs Directories that are never deleted even though their name is not a
 *                      variant of this backend. A built-in entry whose storage dir is shared
 *                      with another subsystem MUST list that subsystem's root here: the
 *                      `llm` catalog entry stores Gemma under `files/models`, which is also the
 *                      parent of the external-import root `files/models/external`
 *                      ([com.antivocale.app.data.EXTERNAL_MODELS_DIR_NAME]); without the guard
 *                      every startup sweep deleted all imported models. Compared by canonical path.
 * @return Total bytes reclaimed across all deleted directories.
 */
fun cleanOrphanedModelDirs(
    storageDir: File,
    validDirNames: Set<String>,
    protectedDirs: Set<File> = emptySet(),
): Long {
    if (!storageDir.exists() || !storageDir.isDirectory) return 0L
    val protectedPaths = protectedDirs.mapTo(HashSet()) { it.canonicalFile }

    var totalReclaimed = 0L
    storageDir.listFiles { file -> file.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
            // Core safety invariant: never delete a dir whose name is a known variant.
            if (dir.name in validDirNames) return@forEach
            if (dir.canonicalFile in protectedPaths) return@forEach

            val size = try {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not size orphaned dir ${dir.name}; deleting anyway", e)
                0L
            }

            try {
                if (dir.deleteRecursively()) {
                    totalReclaimed += size
                    Log.i(TAG, "Deleted orphaned model dir '${dir.name}' (${formatFileSize(size)})")
                } else {
                    Log.w(TAG, "Failed to delete orphaned model dir '${dir.name}' (deleteRecursively returned false)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete orphaned model dir '${dir.name}'", e)
            }
        }

    return totalReclaimed
}
