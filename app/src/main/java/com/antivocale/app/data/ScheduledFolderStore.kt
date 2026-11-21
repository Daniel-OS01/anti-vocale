package com.antivocale.app.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TASK-741 (GH #125) slice 2: the persisted half of the scheduled folder
 * watch - which folders are watched (tree URI, display name, scan period)
 * and each folder's [FileSnapshot] dedup memory, as ONE JSON preference
 * (the ExternalModelStore shape: a single key, a decode that drops
 * malformed elements instead of the whole list, and a mutex-guarded
 * read-modify-write so a snapshot write never clobbers a concurrent
 * record edit on another folder).
 *
 * SIZE CONTRACT: the JSON's worst case is retention-1000 vanished facts
 * PLUS one entry per audio file still in the folder (the planner keeps
 * present files' facts uncapped). Tens to hundreds of recorder files
 * stay in the tens-of-KB range; a pathological 10k-file folder would
 * rewrite roughly 1MB per scan. Past that ceiling, migrate the snapshots
 * to a Room table (incremental writes) instead of widening this key.
 *
 * Unlike ExternalModelStore (whose defaulted dirExists param is invisible to
 * Dagger and needs an AppModule provider), the constructor is fully
 * injectable. The read-modify-write lock is process-wide (companion), so
 * the delete-time scrub ([scrubBackendIn], reached through
 * [TranscriptionPinScrubber] with only a [PreferencesManager]) serializes
 * with the store's own writes; @Singleton stays so every site shares one
 * flow.
 */
@Singleton
class ScheduledFolderStore @Inject constructor(
    private val preferencesManager: PreferencesManager,
) {
    val foldersFlow: Flow<List<WatchedFolder>> =
        preferencesManager.scheduledFoldersJson.map(ScheduledFoldersJson::decode)

    suspend fun folders(): List<WatchedFolder> = foldersFlow.first()

    suspend fun byUri(treeUri: String): WatchedFolder? =
        folders().firstOrNull { it.treeUri == treeUri }

    /** @return the resulting list (the added folder reflects idempotence). */
    suspend fun add(folder: WatchedFolder): List<WatchedFolder> = mutate { list ->
        if (list.any { it.treeUri == folder.treeUri }) list else list + folder
    }

    suspend fun remove(treeUri: String): List<WatchedFolder> = mutate { list ->
        list.filterNot { it.treeUri == treeUri }
    }

    suspend fun updatePeriod(treeUri: String, periodHours: Int): List<WatchedFolder> = mutate { list ->
        require(periodHours in 1..24) { "periodHours must be 1..24 (was $periodHours)" }
        list.map { if (it.treeUri == treeUri) it.copy(periodHours = periodHours) else it }
    }

    /**
     * R6: the folder's own profile (null or empty clears it). Writes
     * through the current record like [updatePeriod].
     */
    suspend fun setFolderProfile(treeUri: String, profile: TranscriptionProfile?): List<WatchedFolder> =
        mutate { list ->
            list.map {
                if (it.treeUri == treeUri) it.copy(profile = profile?.takeUnless { p -> p.isEmpty }) else it
            }
        }

    /**
     * R6: one subfolder rule, keyed by [SubfolderPaths.normalize] of [path];
     * a null or empty [profile] removes it. A path that normalizes to the
     * root is rejected: the root's profile is [setFolderProfile].
     */
    suspend fun setSubfolderRule(
        treeUri: String,
        path: String,
        profile: TranscriptionProfile?,
    ): List<WatchedFolder> {
        val key = requireNotNull(SubfolderPaths.normalize(path)) {
            "a subfolder rule needs a non-root path; use setFolderProfile for the root"
        }
        return mutate { list ->
            list.map {
                if (it.treeUri != treeUri) {
                    it
                } else if (profile == null || profile.isEmpty) {
                    it.copy(subfolderRules = it.subfolderRules - key)
                } else {
                    it.copy(subfolderRules = it.subfolderRules + (key to profile))
                }
            }
        }
    }

    /** Blanks [backendId] in every folder profile and subfolder rule. */
    suspend fun scrubBackend(backendId: String): Boolean = scrubBackendIn(preferencesManager, backendId)

    /** The scan memory for one folder (empty until the first scan lands). */
    suspend fun snapshotFor(treeUri: String): List<FileSnapshot> =
        byUri(treeUri)?.snapshot ?: emptyList()

    /**
     * Slice 3's worker write: replaces ONE folder's snapshot through the
     * current record (a stale [WatchedFolder] holder writing a period
     * change concurrently survives, same rationale as
     * ExternalModelStore.updateDir).
     *
     * A non-null [recursiveBaselined] sets [WatchedFolder.recursiveBaselined]
     * in the SAME write: the recursive-upgrade baseline and its flag flip
     * must land atomically, or a crash between two writes either re-runs the
     * baseline (harmless) or, worse, flips the flag without the baseline (the
     * subfolder backlog then transcribes as new saves). Null keeps the flag.
     *
     * [recursiveBaselineCutoff] rides the same write: a non-null value sets
     * [WatchedFolder.recursiveBaselineCutoff], null keeps it, and flipping
     * the flag to true clears it (a baselined folder has no cutoff to apply).
     */
    suspend fun saveSnapshot(
        treeUri: String,
        snapshot: List<FileSnapshot>,
        recursiveBaselined: Boolean? = null,
        recursiveBaselineCutoff: Long? = null,
    ) = mutate { list ->
        list.map {
            if (it.treeUri != treeUri) {
                it
            } else {
                it.copy(
                    snapshot = snapshot,
                    recursiveBaselined = recursiveBaselined ?: it.recursiveBaselined,
                    recursiveBaselineCutoff = when {
                        recursiveBaselined == true -> null
                        recursiveBaselineCutoff != null -> recursiveBaselineCutoff
                        else -> it.recursiveBaselineCutoff
                    },
                )
            }
        }
    }

    private suspend fun mutate(block: (List<WatchedFolder>) -> List<WatchedFolder>): List<WatchedFolder> =
        mutateIn(preferencesManager, block)

    companion object {
        /** Process-wide: see the class KDoc. */
        private val mutateMutex = Mutex()

        private suspend fun mutateIn(
            prefs: PreferencesManager,
            block: (List<WatchedFolder>) -> List<WatchedFolder>,
        ): List<WatchedFolder> = mutateMutex.withLock {
            val next = block(ScheduledFoldersJson.decode(prefs.scheduledFoldersJson.first()))
            prefs.saveScheduledFoldersJson(ScheduledFoldersJson.encode(next))
            next
        }

        /**
         * R6 delete-time scrub: [backendId] is blanked in every folder
         * profile and subfolder rule (a profile left empty is removed).
         * Writes only when something changed; returns whether it did.
         */
        suspend fun scrubBackendIn(prefs: PreferencesManager, backendId: String): Boolean {
            if (backendId.isBlank()) return false
            return mutateMutex.withLock {
                val current = ScheduledFoldersJson.decode(prefs.scheduledFoldersJson.first())
                val next = current.map { it.withoutBackend(backendId) }
                if (next != current) {
                    prefs.saveScheduledFoldersJson(ScheduledFoldersJson.encode(next))
                    true
                } else {
                    false
                }
            }
        }

        /** Every backend id a folder profile or subfolder rule names. */
        suspend fun referencedBackendIdsIn(prefs: PreferencesManager): Set<String> =
            ScheduledFoldersJson.decode(prefs.scheduledFoldersJson.first()).flatMapTo(mutableSetOf()) { folder ->
                listOfNotNull(folder.profile?.backendId) +
                    folder.subfolderRules.values.mapNotNull { it.backendId }
            }

        private fun WatchedFolder.withoutBackend(backendId: String): WatchedFolder = copy(
            profile = TranscriptionProfileJson.withoutBackend(profile, backendId),
            subfolderRules = subfolderRules.mapNotNull { (path, rule) ->
                TranscriptionProfileJson.withoutBackend(rule, backendId)?.let { path to it }
            }.toMap(),
        )
    }
}

/**
 * One watched folder and its dedup memory; [periodHours] feeds the worker schedule.
 *
 * [recursiveBaselined] is false only for a record persisted before the scan
 * walked subfolders (the JSON decode reads a MISSING key as false): its
 * subfolders may hold a backlog no scan ever saw, so the worker records
 * every not-yet-known subfolder file as handled
 * ([FolderScanPlanner.recursiveUpgradeBaseline]) and flips the flag, both in
 * one write. A folder created from now on defaults to true: it is listed
 * recursively from its first scan, and the add flow owns its backlog choice.
 *
 * [recursiveBaselineCutoff] (meaningful only while the flag is false) is the
 * instant that separates backlog from new saves for the upgrade baseline: an
 * unknown subfolder file whose mtime is at or before it (or unknown) is
 * backlog, a newer one is a save made after the watch began and plans
 * normally. The add flow sets it to the listing time when a capped listing
 * leaves part of the tree unseen; a legacy record gets it from its first
 * upgrade scan (that scan's time), persisted with the snapshot so later scans
 * of a still-truncated walk do not move it forward and swallow new saves.
 */
data class WatchedFolder(
    val treeUri: String,
    val displayName: String,
    val periodHours: Int = DEFAULT_PERIOD_HOURS,
    val snapshot: List<FileSnapshot> = emptyList(),
    val recursiveBaselined: Boolean = true,
    val recursiveBaselineCutoff: Long? = null,
    /** R6: the folder's own profile (null = none). Device-bound, like the
     *  whole record (scheduled_folders_json is excluded from backup). */
    val profile: TranscriptionProfile? = null,
    /** R6: subfolder profiles keyed by [SubfolderPaths.normalize]d relative
     *  path; the deepest match wins ([SubfolderPaths.longestMatch]). */
    val subfolderRules: Map<String, TranscriptionProfile> = emptyMap(),
) {
    companion object {
        const val DEFAULT_PERIOD_HOURS = 6

        /** The UI's preset intervals; a subset of the store's 1..24 envelope
         *  (hand-seeded values outside it still render). */
        val PERIOD_CHOICES = listOf(1, 3, 6, 12, 24)
    }
}

object ScheduledFoldersJson {
    private const val TAG = "ScheduledFoldersJson"

    fun encode(folders: List<WatchedFolder>): String =
        JSONArray(folders.map { it.toJson() }).toString()

    fun decode(raw: String?): List<WatchedFolder> =
        decodeJsonList(raw, TAG, "watched-folder record") { fromJson(it) }

    private fun WatchedFolder.toJson(): JSONObject = JSONObject()
        .put("treeUri", treeUri)
        .put("displayName", displayName)
        .put("periodHours", periodHours)
        .put("recursiveBaselined", recursiveBaselined)
        .put("snapshot", JSONArray(snapshot.map { snap ->
            JSONObject().put("name", snap.name).put("size", snap.size)
                .put("enqueuedAt", snap.enqueuedAt ?: JSONObject.NULL)
        }))
        .apply { recursiveBaselineCutoff?.let { put("recursiveBaselineCutoff", it) } }
        // R6: written only when set, so a record without profiles keeps
        // its pre-R6 shape.
        .apply { profile?.takeUnless { it.isEmpty }?.let { put("profile", TranscriptionProfileJson.toJson(it)) } }
        .apply {
            if (subfolderRules.isNotEmpty()) {
                put("subfolderRules", TranscriptionProfileJson.encodeMap(subfolderRules))
            }
        }

    private fun fromJson(o: JSONObject): WatchedFolder? {
        val uri = o.optString("treeUri")
        if (uri.isBlank()) return null
        val folder = runCatching {
            val snaps = o.optJSONArray("snapshot") ?: JSONArray()
            WatchedFolder(
                treeUri = uri,
                displayName = o.optString("displayName").ifBlank { uri },
                // Corrupt or hand-seeded values coerce into range: the worker
                // scheduler would crash on a 0 period (review F4).
                periodHours = o.optInt("periodHours", WatchedFolder.DEFAULT_PERIOD_HOURS)
                    .coerceIn(1, 24),
                snapshot = buildList {
                    for (i in 0 until snaps.length()) {
                        val s = snaps.getJSONObject(i)
                        val name = s.optString("name")
                        if (name.isBlank()) continue
                        add(FileSnapshot(name, s.optLong("size", 0), enqueuedAt(s)))
                    }
                },
                // A missing key is a pre-recursion record: false makes the
                // worker baseline its subfolder backlog once (see WatchedFolder).
                recursiveBaselined = o.optBoolean("recursiveBaselined", false),
                // Written only when set; a missing key (every record before
                // the cutoff existed) reads as null.
                recursiveBaselineCutoff = if (o.has("recursiveBaselineCutoff") &&
                    !o.isNull("recursiveBaselineCutoff")
                ) {
                    o.getLong("recursiveBaselineCutoff")
                } else {
                    null
                },
            )
        }.getOrNull() ?: return null
        // R6: the profile fields parse in their OWN containment: a malformed
        // profile or rule map reads as unset instead of dropping the folder
        // record (and with it the folder's scan memory) on the next write.
        val profile = runCatching { TranscriptionProfileJson.fromJson(o.optJSONObject("profile")) }
            .onFailure { Log.w(TAG, "watched_folder_profile_malformed") }
            .getOrNull()
        val rules = runCatching {
            TranscriptionProfileJson.decodeMap(o.optJSONObject("subfolderRules"), SubfolderPaths::normalize)
        }.onFailure { Log.w(TAG, "watched_folder_subfolder_rules_malformed") }
            .getOrDefault(emptyMap())
        return folder.copy(profile = profile, subfolderRules = rules)
    }

    private fun enqueuedAt(o: JSONObject): Long? =
        if (o.isNull("enqueuedAt")) null else o.optLong("enqueuedAt")
}
