package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-741 slice 1: the folder-watch decision policy. Every rule the worker
 * will rely on is pinned here: stability wait, size-change reset, processed
 * dedup, vanished-file cleanup, batch cap, retention eviction, and the
 * extension filter.
 */
class FolderScanPlannerTest {

    private fun child(name: String, size: Long = 100, mtime: Long = 1) =
        FolderScanPlanner.Child(name, size, mtime)

    private fun plan(
        children: List<FolderScanPlanner.Child>,
        previous: List<FileSnapshot> = emptyList(),
        now: Long = 1000,
        batchCap: Int = FolderScanPlanner.DEFAULT_BATCH_CAP,
        processedRetention: Int = FolderScanPlanner.DEFAULT_PROCESSED_RETENTION,
    ) = FolderScanPlanner.plan(children, previous, now, batchCap, processedRetention)

    @Test
    fun `first sight waits for stability instead of enqueuing`() {
        val p = plan(listOf(child("rec.m4a")))
        assertEquals(emptyList<FolderScanPlanner.Child>(), p.toEnqueue)
        assertEquals(listOf("rec.m4a"), p.awaitingStability.map { it.name })
        // the snapshot records the seen-but-unprocessed fact
        assertEquals(1, p.nextSnapshot.size)
        assertEquals(null, p.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `second sight with unchanged size enqueues`() {
        val first = plan(listOf(child("rec.m4a", size = 42)))
        val second = plan(listOf(child("rec.m4a", size = 42)), first.nextSnapshot, now = 2000)
        assertEquals(listOf("rec.m4a"), second.toEnqueue.map { it.name })
        assertEquals(2000L, second.nextSnapshot.single().enqueuedAt)
        assertFalse(second.morePending)
    }

    @Test
    fun `a size change between sightings resets the wait`() {
        val first = plan(listOf(child("rec.m4a", size = 42)))
        val grown = plan(listOf(child("rec.m4a", size = 60)), first.nextSnapshot, now = 2000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), grown.toEnqueue)
        assertEquals(60L, grown.nextSnapshot.single().size)
        // stability against the NEW baseline enqueues on the third scan
        val stable = plan(listOf(child("rec.m4a", size = 60)), grown.nextSnapshot, now = 3000)
        assertEquals(listOf("rec.m4a"), stable.toEnqueue.map { it.name })
    }

    @Test
    fun `processed files are never re-enqueued`() {
        val first = plan(listOf(child("rec.m4a")))
        val second = plan(listOf(child("rec.m4a")), first.nextSnapshot, now = 2000)
        val third = plan(listOf(child("rec.m4a")), second.nextSnapshot, now = 3000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), third.toEnqueue)
        assertEquals(emptyList<FolderScanPlanner.Child>(), third.awaitingStability)
        // the dedup fact survives (stamped by the scan that enqueued it)
        assertEquals(2000L, third.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `an enqueued file that later grows stays deduped`() {
        val first = plan(listOf(child("rec.m4a")))
        val second = plan(listOf(child("rec.m4a")), first.nextSnapshot, now = 2000)
        // the file is REWRITTEN in place (size moves) after it was already
        // enqueued once: the dedup fact wins over the stability branch
        val rewritten = plan(listOf(child("rec.m4a", size = 999)), second.nextSnapshot, now = 3000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), rewritten.toEnqueue)
        assertEquals(emptyList<FolderScanPlanner.Child>(), rewritten.awaitingStability)
        assertEquals(2000L, rewritten.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `retention never evicts the facts of still-present files`() {
        val children = (1..5).map { child("rec$it.m4a") }
        val first = plan(children)
        val second = plan(children, first.nextSnapshot, now = 2000)
        // all five enqueued; a retention of 2 must NOT evict present files
        val kept = plan(children, second.nextSnapshot, now = 3000, batchCap = 5, processedRetention = 2)
        assertEquals(5, kept.nextSnapshot.size)
        // the cap does bound VANISHED facts (simulate by removing files)
        val shrunk = children.drop(2)
        val third = plan(shrunk, second.nextSnapshot, now = 3000, batchCap = 5, processedRetention = 1)
        assertEquals(3, third.nextSnapshot.size) // 2 present + 1 newest vanished
    }

    @Test
    fun `vanished files drop their unenqueued entries but keep enqueued facts`() {
        val first = plan(listOf(child("a.m4a"), child("b.m4a")))
        val second = plan(listOf(child("a.m4a"), child("b.m4a")), first.nextSnapshot, now = 2000)
        // both stable and enqueued on scan 2
        val third = plan(listOf(child("a.m4a")), second.nextSnapshot, now = 3000)
        // b.m4a vanished AFTER being enqueued: its dedup fact survives
        // (moved-out-and-back must not re-transcribe)...
        assertEquals(setOf("a.m4a", "b.m4a"), third.nextSnapshot.map { it.name }.toSet())
        assertEquals(2000L, third.nextSnapshot.first { it.name == "b.m4a" }.enqueuedAt)
        // ...while an UNENQUEUED vanish (never stable) is forgotten
        val gone = plan(listOf(child("c.m4a")))
        val after = plan(emptyList(), gone.nextSnapshot, now = 2000)
        assertEquals(0, after.nextSnapshot.size)
    }

    @Test
    fun `non-audio files are ignored entirely`() {
        val p = plan(listOf(child("cover.jpg"), child("notes.txt"), child("rec.opus")))
        assertEquals(listOf("rec.opus"), p.awaitingStability.map { it.name })
        assertEquals(listOf("rec.opus"), p.nextSnapshot.map { it.name })
    }

    @Test
    fun `the extension filter is case-insensitive and covers video containers`() {
        assertTrue(FolderScanPlanner.isAudioName("REC.M4A"))
        assertTrue(FolderScanPlanner.isAudioName("clip.MP4"))
        assertFalse(FolderScanPlanner.isAudioName("cover.png"))
        assertFalse(FolderScanPlanner.isAudioName("noext"))
    }

    @Test
    fun `the batch cap defers the tail to the next run`() {
        val children = (1..25).map { child("rec%02d.m4a".format(it)) }
        val first = plan(children)
        val second = plan(children, first.nextSnapshot, now = 2000, batchCap = 10)
        assertEquals(10, second.toEnqueue.size)
        assertTrue(second.morePending)
        // the deferred 15 stay unprocessed and eligible for scan 3
        val third = plan(children, second.nextSnapshot, now = 3000, batchCap = 10)
        assertEquals(10, third.toEnqueue.size)
        assertTrue(third.morePending)
        val fourth = plan(children, third.nextSnapshot, now = 4000, batchCap = 10)
        assertEquals(5, fourth.toEnqueue.size)
        assertFalse(fourth.morePending)
    }


    @Test
    fun `hidden pending and trashed files are never planned`() {
        val names = listOf(".pending-1700000000-note.aac", ".trashed-1700000000-old.aac", "note.aac")
        val first = plan(names.map { child(it) })
        assertEquals(listOf("note.aac"), first.awaitingStability.map { it.name })
        val second = plan(names.map { child(it) }, first.nextSnapshot, now = 2000)
        assertEquals(listOf("note.aac"), second.toEnqueue.map { it.name })
    }

    @Test
    fun `the add-time baseline marks the backlog handled so only later saves enqueue`() {
        val backlog = listOf(child("old.m4a"), child(".pending-1-x.aac"), child("cover.png"))
        val base = FolderScanPlanner.baseline(backlog, now = 500)
        assertEquals(listOf(FileSnapshot("old.m4a", 100, 500)), base)

        val withNew = backlog + child("signal-voice.aac", size = 7)
        val first = plan(withNew, base, now = 1000)
        assertTrue(first.toEnqueue.isEmpty())
        val second = plan(withNew, first.nextSnapshot, now = 1003)
        assertEquals(listOf("signal-voice.aac"), second.toEnqueue.map { it.name })
    }

    @Test
    fun `relative-path keys plan exactly like bare names`() {
        val first = plan(listOf(child("Calls/2026/rec.m4a", size = 42)))
        assertTrue(first.toEnqueue.isEmpty())
        assertEquals(listOf("Calls/2026/rec.m4a"), first.awaitingStability.map { it.name })
        val second = plan(listOf(child("Calls/2026/rec.m4a", size = 42)), first.nextSnapshot, now = 2000)
        assertEquals(listOf("Calls/2026/rec.m4a"), second.toEnqueue.map { it.name })
        assertEquals(FileSnapshot("Calls/2026/rec.m4a", 42, 2000), second.nextSnapshot.single())
    }

    @Test
    fun `same-named files in different subfolders are distinct entries`() {
        val children = listOf(child("a/rec.m4a"), child("b/rec.m4a"), child("rec.m4a"))
        val first = plan(children)
        val second = plan(children, first.nextSnapshot, now = 2000)
        assertEquals(setOf("a/rec.m4a", "b/rec.m4a", "rec.m4a"), second.toEnqueue.map { it.name }.toSet())
    }

    @Test
    fun `hidden and extension checks judge the basename`() {
        assertFalse(FolderScanPlanner.isCandidate("sub/.pending-1-x.aac"))
        assertFalse(FolderScanPlanner.isCandidate("a/b/.trashed-1-old.m4a"))
        // only the basename decides: a dotted parent does not hide the file
        assertTrue(FolderScanPlanner.isCandidate(".config/rec.m4a"))
        assertTrue(FolderScanPlanner.isCandidate("Calls/2026/rec.m4a"))
        assertFalse(FolderScanPlanner.isAudioName("audio.m4a/readme"))
        assertFalse(FolderScanPlanner.isCandidate("audio.m4a/readme"))
        assertTrue(FolderScanPlanner.isAudioName("v1.2/REC.MP3"))
    }

    @Test
    fun `subPathOf and baseNameOf split root, one-level and multi-level paths`() {
        assertNull(FolderScanPlanner.subPathOf("rec.m4a"))
        assertEquals("rec.m4a", FolderScanPlanner.baseNameOf("rec.m4a"))
        assertEquals("Calls", FolderScanPlanner.subPathOf("Calls/rec.m4a"))
        assertEquals("rec.m4a", FolderScanPlanner.baseNameOf("Calls/rec.m4a"))
        assertEquals("Calls/2026", FolderScanPlanner.subPathOf("Calls/2026/rec.m4a"))
        assertEquals("rec.m4a", FolderScanPlanner.baseNameOf("Calls/2026/rec.m4a"))
    }

    @Test
    fun `the recursive upgrade baselines only unknown subfolder files`() {
        val previous = listOf(
            FileSnapshot("root-awaiting.m4a", 10, null),
            FileSnapshot("root-done.m4a", 11, 300),
        )
        val children = listOf(
            child("root-awaiting.m4a", size = 10),
            child("root-done.m4a", size = 11),
            child("root-new.m4a", size = 12),
            child("Calls/old.m4a", size = 13),
            child("Calls/2025/older.opus", size = 14),
            child("Calls/.pending-1-x.aac", size = 15),
            child("Calls/cover.jpg", size = 16),
        )
        val upgraded = FolderScanPlanner.recursiveUpgradeBaseline(children, previous, now = 500, cutoff = 500)
        // existing entries kept verbatim, in front
        assertEquals(previous, upgraded.take(2))
        // depth>0 candidates recorded as handled at now; root-new and the
        // non-candidates are untouched
        assertEquals(
            listOf(FileSnapshot("Calls/old.m4a", 13, 500), FileSnapshot("Calls/2025/older.opus", 14, 500)),
            upgraded.drop(2),
        )

        // planning from the result: the backlog never enqueues, root files
        // plan normally (the awaiting one is a stable second sight)
        val first = plan(children, upgraded, now = 1000)
        assertEquals(listOf("root-awaiting.m4a"), first.toEnqueue.map { it.name })
        assertEquals(listOf("root-new.m4a"), first.awaitingStability.map { it.name })
        val second = plan(children, first.nextSnapshot, now = 2000)
        assertEquals(listOf("root-new.m4a"), second.toEnqueue.map { it.name })
        val third = plan(children, second.nextSnapshot, now = 3000)
        assertTrue(third.toEnqueue.isEmpty())
        assertTrue(third.awaitingStability.isEmpty())
    }

    @Test
    fun `the recursive upgrade leaves a known subfolder entry alone`() {
        val previous = listOf(FileSnapshot("Calls/rec.m4a", 13, null))
        val upgraded = FolderScanPlanner.recursiveUpgradeBaseline(
            listOf(child("Calls/rec.m4a", size = 13)), previous, now = 500, cutoff = 500,
        )
        assertEquals(previous, upgraded)
    }

    @Test
    fun `the recursive upgrade baselines only files at or before the cutoff, unknown mtime included`() {
        val children = listOf(
            child("Calls/old.m4a", size = 13, mtime = 400),
            child("Calls/at-cutoff.m4a", size = 14, mtime = 500),
            child("Calls/unknown.m4a", size = 15, mtime = 0),
            child("Calls/new.m4a", size = 16, mtime = 600),
        )
        val upgraded = FolderScanPlanner.recursiveUpgradeBaseline(children, emptyList(), now = 700, cutoff = 500)
        assertEquals(
            listOf("Calls/old.m4a", "Calls/at-cutoff.m4a", "Calls/unknown.m4a"),
            upgraded.map { it.name },
        )
        assertTrue(upgraded.all { it.enqueuedAt == 700L })

        // the save made after the cutoff plans normally: it waits, then
        // enqueues on its stable second sight; the backlog never does
        val first = plan(children, upgraded, now = 1000)
        assertTrue(first.toEnqueue.isEmpty())
        assertEquals(listOf("Calls/new.m4a"), first.awaitingStability.map { it.name })
        // a later still-truncated scan re-runs the upgrade with the SAME
        // cutoff: the awaiting entry is known now and is not swallowed
        val again = FolderScanPlanner.recursiveUpgradeBaseline(children, first.nextSnapshot, now = 2000, cutoff = 500)
        assertEquals(first.nextSnapshot, again)
        val second = plan(children, again, now = 2000)
        assertEquals(listOf("Calls/new.m4a"), second.toEnqueue.map { it.name })
    }

    @Test
    fun `an incomplete listing keeps every unseen entry verbatim, outside the retention cap`() {
        val previous = listOf(
            FileSnapshot("root.m4a", 10, 50),
            FileSnapshot("Calls/deep-done.m4a", 11, 100),
            FileSnapshot("Calls/deep-awaiting.m4a", 12, null),
        )
        val children = listOf(child("root.m4a", size = 10))

        val capped = FolderScanPlanner.plan(
            children, previous, now = 1000, processedRetention = 1, listingComplete = false,
        )
        assertEquals(previous.toSet(), capped.nextSnapshot.toSet())
        assertEquals(previous.size, capped.nextSnapshot.size)
        assertTrue(capped.toEnqueue.isEmpty())

        // the same listing read as complete: the awaiting entry drops and the
        // retention cap (1, spent on the present fact) evicts the vanished one
        val complete = FolderScanPlanner.plan(children, previous, now = 1000, processedRetention = 1)
        assertEquals(listOf(FileSnapshot("root.m4a", 10, 50)), complete.nextSnapshot)
    }

    @Test
    fun `the stability recheck backoff starts within seconds and ends`() {
        assertEquals(3L, FolderScanPlanner.recheckDelaySeconds(0))
        var total = 0L
        var attempt = 0
        while (true) {
            total += FolderScanPlanner.recheckDelaySeconds(attempt) ?: break
            attempt++
        }
        assertTrue("backoff must be finite and bounded, was $attempt steps", attempt in 1..10)
        assertTrue("backoff must stop within ~10 minutes, was ${total}s", total <= 600)
    }
}
