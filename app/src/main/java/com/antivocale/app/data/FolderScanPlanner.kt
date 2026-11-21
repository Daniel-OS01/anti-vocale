package com.antivocale.app.data

import com.antivocale.app.util.SharedAudioHandler

/**
 * TASK-741 (GH #125) slice 1: the pure decision core of the scheduled
 * folder watch. Given one scan's listing of a user-picked recorder folder
 * and the snapshot the previous scans built, it decides which files to
 * enqueue, which to hold for a stability check, and what the next snapshot
 * is. No Android types: the worker maps the recursive listing
 * ([FolderWalk]) to [Child] and feeds this, so the whole policy is
 * JVM-unit-testable.
 *
 * IDENTITY: a file's name here is its RELATIVE PATH from the watched root,
 * '/'-separated (`Calls/2026/rec.m4a`); a file directly in the root keeps
 * its bare name, so snapshots persisted before subfolders were scanned stay
 * valid unchanged. Name-based rules below ("same name", "renamed") read the
 * path: a file moved between subfolders is a rename. Filters that judge a
 * file NAME (hidden, extension) read the basename ([baseNameOf]).
 *
 * POLICY (the contract the worker and the tests pin):
 *  - FIRST SIGHT of a file is never enqueued: a recorder still writing the
 *    file would be transcribed truncated. The file enters the snapshot as
 *    seen-but-unenqueued and becomes eligible once a LATER scan sees the
 *    same name with the same size (the recorder finished). Stability is
 *    SIZE-ONLY; [Child.lastModified] is carried for enqueue-time logging
 *    and routing, never for a policy decision.
 *  - A size change between sightings resets the wait (the file was still
 *    growing); the new size becomes the wait baseline.
 *  - Enqueued files are deduped by NAME (relative path) within the retention window: a
 *    renamed file re-enters the stability wait and re-transcribes (the
 *    task's documented rename risk; content-hashing every file would cost
 *    more than the occasional duplicate transcription). Vanished files
 *    drop their unenqueued entries but KEEP their enqueued entries (a
 *    moved-out-and-back file must not re-transcribe), subject to the
 *    retention cap.
 *  - Files that vanished from the listing drop out of the snapshot (their
 *    enqueued facts survive per the rule above).
 *  - An INCOMPLETE listing ([plan]'s listingComplete false: the walk hit a
 *    depth or entry cap) proves nothing about the files it did not reach:
 *    unseen is not vanished. Every previous entry whose name the listing
 *    lacks is kept verbatim (enqueued or awaiting) and does not count
 *    against the retention cap, so a capped walk never forgets a dedup fact
 *    or a stability baseline. Those unseen entries have their OWN bound,
 *    [DEFAULT_UNSEEN_RETENTION]: a tree that stays capped would otherwise
 *    carry every file it ever held (deleted ones included) forever. Past
 *    the bound the awaiting entries go first (they only restart a
 *    stability wait), then the oldest enqueued facts.
 *  - At most [batchCap] files enqueue per run (huge folders drain over
 *    successive runs); the deferred rest stay eligible for the next scan.
 *
 * WORKER DUTIES the planner cannot enforce (slice 3 owns them; named here
 * so they are contract, not folklore):
 *  - Overlapping scans of the SAME folder must not run: the read side of
 *    the store's snapshot sits OUTSIDE its mutation mutex, so two scans
 *    planning from one base snapshot both enqueue the same stable files
 *    and the second write erases the first's dedup facts (double
 *    transcription). The worker serializes per folder with an in-process
 *    mutex (a periodic run and a manual scan-now cannot share a periodic
 *    unique slot without replacing the schedule); the store dedupes
 *    nothing across the read-plan-write window.
 *  - An EMPTY OR FAILED listing is scan-abandoned: keep the previous
 *    snapshot verbatim and retry later. Feeding a provider error in as an
 *    empty list would wipe the awaiting entries and force every file back
 *    through the stability wait (or worse, mass re-enqueue after retention
 *    churn). Only a listing the worker actually read may become a Plan.
 *  - [Plan.nextSnapshot] stamps every enqueued file with this run's [now].
 *    A file whose InferenceEnqueue outcome was [com.antivocale.app.service.InferenceEnqueue.Outcome.Failed]
 *    must have its new snapshot entry DEMOTED to enqueuedAt = null before
 *    persisting: the entry stays (so the next scan reads it as a stable
 *    second sight, not a first sight) and retries in ONE scan. A fallback
 *    notification counts as enqueued: TASK-500's trampoline preserves the
 *    full request.
 */
object FolderScanPlanner {

    /** One scanned child; [name] is its relative path from the watched root
     *  (see IDENTITY in the class KDoc), unique within one listing. */
    data class Child(val name: String, val size: Long, val lastModified: Long)

    data class Plan(
        /** Stable, unenqueued files the worker should enqueue this run. */
        val toEnqueue: List<Child>,
        /** First-sight or grown files now recorded for the stability check. */
        val awaitingStability: List<Child>,
        /** The full replacement snapshot for this folder. */
        val nextSnapshot: List<FileSnapshot>,
        /** True when [toEnqueue] hit the cap and eligible files remain. */
        val morePending: Boolean,
    )

    const val DEFAULT_BATCH_CAP = 20
    const val DEFAULT_PROCESSED_RETENTION = 1000

    /**
     * The bound on entries a capped walk did not reach (see POLICY). Generous
     * against the walk's 2000-row budget so a capped tree's facts survive
     * several walks' worth of churn, and small enough that the folder's JSON
     * stays a few hundred KB at worst.
     */
    const val DEFAULT_UNSEEN_RETENTION = 5000

    /** Audio-only containers; the video containers come from the share
     *  flow's set (the audio track is extracted at decode time), so the
     *  two surfaces cannot drift on what counts as input. */
    // Deliberately excludes wma/aiff/aif: the decode path is
    // MediaExtractor-only, and it has no ASF or AIFF demuxer - an
    // autonomous scan must not enqueue files that are guaranteed to fail.
    private val AUDIO_ONLY_EXTENSIONS = setOf(
        "m4a", "m4b", "aac", "mp3", "wav", "opus", "ogg", "oga", "amr", "flac",
    )

    val AUDIO_EXTENSIONS = AUDIO_ONLY_EXTENSIONS + SharedAudioHandler.VIDEO_EXTENSIONS

    /** Judges the basename: `audio.m4a/readme` is not audio. */
    fun isAudioName(name: String): Boolean {
        val ext = baseNameOf(name).substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /** The file-name part of a relative path (the whole string for a root file). */
    fun baseNameOf(relativePath: String): String = relativePath.substringAfterLast('/')

    /**
     * The parent directory part of a relative path (`Calls/2026` for
     * `Calls/2026/rec.m4a`), or null for a file directly in the watched root.
     */
    fun subPathOf(relativePath: String): String? =
        relativePath.substringBeforeLast('/', "").ifEmpty { null }

    /**
     * A file the scan may consider: an audio name that is not hidden. Hidden
     * names are MediaStore's in-flight and trash shapes (".pending-<ts>-x.aac"
     * while a saver still writes, ".trashed-<ts>-x.aac" after a delete on
     * API 30+): the pending one renames to its real name when published, and
     * the trashed one must never re-transcribe a file the user deleted. The
     * BASENAME is judged, so `sub/.pending-1-x.aac` is hidden too.
     */
    fun isCandidate(name: String): Boolean = !baseNameOf(name).startsWith('.') && isAudioName(name)

    /**
     * The add-time baseline: every candidate already in the folder is
     * recorded as handled, so the watch transcribes only files saved AFTER
     * the folder was added (a voice-note folder's backlog is not a request).
     * The manual Scan now does not bypass this; it plans like any scan.
     */
    fun baseline(children: List<Child>, now: Long): List<FileSnapshot> =
        children.filter { isCandidate(it.name) }.map { FileSnapshot(it.name, it.size, now) }

    /**
     * The one-shot upgrade for a folder added BEFORE subfolders were scanned
     * ([WatchedFolder.recursiveBaselined] false): its subfolders may already
     * hold a backlog no scan ever saw, and the first recursive scan must not
     * read that backlog as new saves. Every candidate below the root (a
     * relative path with a parent) that has no entry in [previous] is
     * recorded as handled, the same shape [baseline] produces. Root files are
     * left to plan normally, and existing entries are kept verbatim (an
     * awaiting root file keeps its stability baseline). The worker plans
     * from the result and persists it together with the flag flip.
     *
     * Only files at or before [cutoff] are backlog
     * ([WatchedFolder.recursiveBaselineCutoff]): while a capped walk keeps
     * the flag false this runs on every scan, and a subfolder file saved
     * after the cutoff is a genuine new save that must plan normally, not be
     * swallowed as backlog. An unknown mtime (<= 0) counts as backlog: the
     * conservative side of the invariant (a backlog never transcribes).
     */
    fun recursiveUpgradeBaseline(
        children: List<Child>,
        previous: List<FileSnapshot>,
        now: Long,
        cutoff: Long,
    ): List<FileSnapshot> {
        val known = previous.mapTo(HashSet()) { it.name }
        val backlog = children
            .filter { isCandidate(it.name) && subPathOf(it.name) != null && it.name !in known }
            .filter { it.lastModified <= cutoff || it.lastModified <= 0 }
            .map { FileSnapshot(it.name, it.size, now) }
        return previous + backlog
    }

    /**
     * The stability re-check backoff (seconds): a scan that leaves files
     * awaiting stability schedules a follow-up instead of waiting for the
     * next periodic run, so a freshly saved file is transcribed seconds
     * later. Null past the last step: a file still growing after ~9 minutes
     * is a live recording, and the next trigger or periodic scan owns it.
     */
    fun recheckDelaySeconds(attempt: Int): Long? = RECHECK_DELAYS_SECONDS.getOrNull(attempt)

    private val RECHECK_DELAYS_SECONDS = listOf(3L, 5L, 10L, 20L, 40L, 60L, 120L, 300L)

    fun plan(
        children: List<Child>,
        previous: List<FileSnapshot>,
        now: Long,
        batchCap: Int = DEFAULT_BATCH_CAP,
        processedRetention: Int = DEFAULT_PROCESSED_RETENTION,
        /** False when the walk was capped: absent entries are unseen, not vanished (see POLICY). */
        listingComplete: Boolean = true,
        unseenRetention: Int = DEFAULT_UNSEEN_RETENTION,
    ): Plan {
        require(batchCap > 0) { "batchCap must drain (was $batchCap)" }
        require(processedRetention > 0) { "processedRetention must keep facts (was $processedRetention)" }
        val prevByName = previous.associateBy { it.name }
        val eligible = mutableListOf<Child>()
        val awaiting = mutableListOf<Child>()
        val enqueuedKept = mutableListOf<FileSnapshot>()

        for (child in children.filter { isCandidate(it.name) }) {
            val prev = prevByName[child.name]
            when {
                // Already handed over: keep the dedup fact, never re-enqueue.
                prev?.enqueuedAt != null -> enqueuedKept += prev
                // Second sight, size unchanged since the previous scan:
                // the writer is done, the file is eligible.
                prev != null && prev.size == child.size -> eligible += child
                // First sight, or the size moved since the last scan.
                else -> awaiting += child
            }
        }

        // A snapshot entry carries exactly what the policy re-reads next
        // scan: the identity (relative path) and the stability baseline (size).
        fun Child.snap(enqueuedAt: Long?) = FileSnapshot(name, size, enqueuedAt)

        val capped = eligible.take(batchCap)
        // Cap-deferred and first-sight files share the seen-unenqueued
        // shape; the deferred ones re-enter the eligible pool next scan
        // instead of restarting the stability wait.
        val unprocessed = awaiting + eligible.drop(batchCap)
        // Enqueued facts of files no longer in the listing survive here:
        // the moved-out-and-back rule. The retention cap bounds ONLY these
        // vanished facts (present files' facts are bounded by the folder
        // itself): capping present facts would re-transcribe the tail of a
        // big folder forever, scan after scan (741 review F1).
        val listed = childNames(children)
        val absent = previous.filter { it.name !in listed }
        // A capped walk did not reach the absent entries: all of them stay
        // verbatim, outside the retention cap (see POLICY).
        val unseenKept = if (listingComplete) {
            emptyList()
        } else {
            // Awaiting entries sort first (MIN), so the bound drops them
            // before any enqueued fact, then the oldest facts.
            absent.sortedBy { it.enqueuedAt ?: Long.MIN_VALUE }.takeLast(unseenRetention.coerceAtLeast(0))
        }
        val vanishedFacts = if (listingComplete) {
            absent.filter { it.enqueuedAt != null }.sortedBy { it.enqueuedAt!! }
        } else {
            emptyList()
        }
        val keptVanished = vanishedFacts.takeLast((processedRetention - enqueuedKept.size).coerceAtLeast(0))
        val kept = enqueuedKept + keptVanished + unseenKept

        return Plan(
            toEnqueue = capped,
            awaitingStability = awaiting,
            nextSnapshot = kept + capped.map { it.snap(now) } + unprocessed.map { it.snap(null) },
            morePending = eligible.size > capped.size,
        )
    }

    private fun childNames(children: List<Child>) = children.mapTo(mutableSetOf()) { it.name }
}

/**
 * The per-folder dedup snapshot (one JSON list per watched folder; see the
 * store in slice 2). Only the fields the policy re-reads survive here;
 * enqueue-time logging reads [FolderScanPlanner.Child] instead. [name] is the
 * relative path from the watched root (a bare name for a root file; the JSON
 * field keeps the name `name`, so pre-recursion records decode unchanged).
 */
data class FileSnapshot(
    val name: String,
    val size: Long,
    val enqueuedAt: Long?,
)
