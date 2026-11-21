package com.antivocale.app.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.ui.viewmodel.LogEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The bulk redo end to end over a mocked DAO: one started service per
 * eligible row, in History order and grouped by profile, skips start
 * nothing, and the re-staged copies are deleted exactly when nothing will
 * read them.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryBulkRedoTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ACalls"

    private lateinit var shortFile: File
    private lateinit var longFile: File
    private lateinit var restagedLong: File
    private val longPaths = mutableSetOf<String>()

    private lateinit var rows: List<LogEntry>
    private val restageTargets = mutableListOf<BulkRedoItem.NeedsRestage>()

    @Before
    fun setUp() {
        shortFile = stagedFile("short.m4a")
        longFile = stagedFile("long.m4a")
        restagedLong = stagedFile("restaged.m4a")
        longPaths += longFile.path
        longPaths += restagedLong.path
        rows = listOf(
            // Deliberately out of History order: prepare sorts newest first.
            audioRow("restage-ok", 300L, filePath = "/gone/a.m4a",
                taskId = FolderWatchTaskIds.mint(tree, "a.m4a", 1L), folder = true),
            audioRow("short", 500L, filePath = shortFile.path),
            LogEntry(id = "text", timestamp = 450L, taskId = "t-text",
                type = LogEntry.Type.TEXT, status = LogEntry.Status.SUCCESS),
            audioRow("long", 400L, filePath = longFile.path),
            audioRow("restage-missing", 200L, filePath = "/gone/b.m4a",
                taskId = FolderWatchTaskIds.mint(tree, "b.m4a", 2L), folder = true),
        )
    }

    private fun stagedFile(name: String): File =
        File(app.cacheDir, name).apply { writeText("audio") }

    private fun audioRow(
        id: String,
        timestamp: Long,
        filePath: String,
        taskId: String = "uuid-$id",
        folder: Boolean = false,
    ) = LogEntry(
        id = id,
        timestamp = timestamp,
        taskId = taskId,
        type = LogEntry.Type.AUDIO,
        status = LogEntry.Status.SUCCESS,
        prompt = "p-$id",
        filePath = filePath,
        sourceKind = if (folder) SourceKind.FOLDER_WATCH else SourceKind.SHARE,
        sourceFolderKey = if (folder) tree else null,
    )

    private val decider: LongAudioDecider = mockk {
        coEvery { decide(any(), any(), any()) } answers {
            val long = secondArg<String>() in longPaths
            LongAudioDecider.LongAudioDecision(
                durationSeconds = if (long) 3_600.0 else 30.0,
                warn = AudioDurationPolicy.WarnDecision(long, 0L, 0L, false),
            )
        }
    }

    private fun defaultRestage(
        targets: List<BulkRedoItem.NeedsRestage>,
    ): Map<BulkRedoItem.NeedsRestage, FolderRestage.RestageResult> {
        restageTargets += targets
        return targets.associateWith { target ->
            if (target.row.id == "restage-ok") FolderRestage.RestageResult.Staged(restagedLong.path)
            else FolderRestage.RestageResult.Failed(SkipReason.NOT_FOUND_IN_FOLDER)
        }
    }

    private fun redo(
        selected: List<LogEntry> = rows,
        restage: suspend (
            List<BulkRedoItem.NeedsRestage>,
            (String) -> Unit,
        ) -> Map<BulkRedoItem.NeedsRestage, FolderRestage.RestageResult> = { targets, _ -> defaultRestage(targets) },
        start: ((Intent) -> InferenceEnqueue.Outcome)? = null,
    ): HistoryBulkRedo {
        val logDao: LogDao = mockk {
            coEvery { getByIds(any()) } returns selected.map { it.toEntity() }
        }
        val store: ScheduledFolderStore = mockk { coEvery { folders() } returns emptyList() }
        val prefs: PreferencesManager = mockk { every { transcriptionBackend } returns flowOf("active-backend") }
        return if (start == null) {
            HistoryBulkRedo(app, logDao, store, prefs, decider, restage = restage)
        } else {
            HistoryBulkRedo(app, logDao, store, prefs, decider, restage = restage, start = start)
        }
    }

    /** Rows whose staged copies were swept, re-staged one per row into [restagedFileOf]. */
    private fun folderRows(count: Int): List<LogEntry> = (1..count).map { n ->
        audioRow("f$n", 1_000L - n, filePath = "/gone/f$n.m4a",
            taskId = FolderWatchTaskIds.mint(tree, "f$n.m4a", n.toLong()), folder = true)
    }

    private fun restagedFileOf(rowId: String): File = File(app.cacheDir, "restaged-$rowId.m4a")

    private fun startedServices(): List<Intent> {
        val shadow = shadowOf(app)
        return generateSequence { shadow.nextStartedService }.toList()
    }

    private val profile = TranscriptionProfile(backendId = "whisper", language = "it")

    @Test
    fun `one started service per eligible row, newest first, all on the chosen profile`() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        val bulk = redo()
        val batch = bulk.prepare(rows.map { it.id }, profile) { done, total -> progress += done to total }

        assertEquals(listOf("short", "long", "restage-ok"), batch.items.map { it.row.id })
        assertEquals(mapOf(SkipReason.NOT_AUDIO to 1, SkipReason.NOT_FOUND_IN_FOLDER to 1), batch.skipped)
        assertEquals(2, batch.longCount)
        assertEquals(3_600.0, batch.longestSeconds, 0.0)
        assertEquals(listOf(restagedLong.path), batch.restagedPaths)
        assertEquals(5 to 5, progress.last())
        assertEquals(setOf("restage-ok", "restage-missing"), restageTargets.map { it.row.id }.toSet())

        val report = bulk.enqueue(batch, profile, includeLong = true)
        assertEquals(BulkRedoReport(queued = 3, failedToStart = 0), report)

        val started = startedServices()
        assertEquals(listOf("p-short", "p-long", "p-restage-ok"),
            started.map { it.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) })
        started.forEach {
            assertEquals(InferenceService.SOURCE_RETRANSCRIBE, it.getStringExtra(InferenceService.EXTRA_SOURCE))
            assertEquals("whisper" to "it",
                it.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE) to
                    it.getStringExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE))
        }
        // The re-staged row runs from its fresh copy, under a folder-watch id
        // that keeps its relative-path hash.
        val restagedIntent = started.last()
        assertEquals(restagedLong.path, restagedIntent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertEquals("a.m4a".hashCode().toUInt(), FolderWatchTaskIds.relPathHashOf(
            restagedIntent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)!!))
        assertEquals(3, started.map { it.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID) }.toSet().size)
        // The queue reads the staged copies: nothing is deleted.
        assertTrue(restagedLong.exists())
        assertTrue(longFile.exists())
    }

    @Test
    fun `the same file redone twice in one batch gets two task ids`() = runBlocking {
        val folderRow = rows.first { it.id == "restage-ok" }.copy(filePath = shortFile.path)
        val twin = folderRow.copy(id = "twin", timestamp = 299L)
        val bulk = redo(listOf(folderRow, twin))
        bulk.enqueue(bulk.prepare(listOf("restage-ok", "twin"), profile) { _, _ -> }, profile, includeLong = true)
        val ids = startedServices().map { it.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID) }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `excluding long items drops them and deletes only their re-staged copies`() = runBlocking {
        val bulk = redo()
        val batch = bulk.prepare(rows.map { it.id }, profile) { _, _ -> }
        val report = bulk.enqueue(batch, profile, includeLong = false)

        assertEquals(BulkRedoReport(queued = 1, failedToStart = 0), report)
        assertEquals(listOf("p-short"),
            startedServices().map { it.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) })
        assertFalse("the dropped long item's re-staged copy is reclaimed", restagedLong.exists())
        assertTrue("a row's own staged file is never deleted", longFile.exists())
        assertTrue(shortFile.exists())
    }

    @Test
    fun `discard deletes only the re-staged copies and starts nothing`() = runBlocking {
        val bulk = redo()
        bulk.discard(bulk.prepare(rows.map { it.id }, profile) { _, _ -> })

        assertFalse(restagedLong.exists())
        assertTrue(shortFile.exists())
        assertTrue(longFile.exists())
        assertEquals(emptyList<Intent>(), startedServices())
    }

    @Test
    fun `skipped rows start nothing`() = runBlocking {
        val skippedOnly = rows.filter { it.id == "text" || it.id == "restage-missing" }
        val bulk = redo(skippedOnly)
        val batch = bulk.prepare(skippedOnly.map { it.id }, profile) { _, _ -> }
        val report = bulk.enqueue(batch, profile, includeLong = true)

        assertEquals(BulkRedoReport(queued = 0, failedToStart = 0), report)
        assertEquals(emptyList<Intent>(), startedServices())
    }

    @Test
    fun `an unset backend runs and probes on the active one`() = runBlocking {
        val unset = TranscriptionProfile(language = "it")
        val selected = rows.filter { it.id == "short" }
        val bulk = redo(selected)
        val batch = bulk.prepare(selected.map { it.id }, unset) { _, _ -> }
        bulk.enqueue(batch, unset, includeLong = true)

        coVerify { decider.decide("active-backend", shortFile.path, any()) }
        assertEquals("active-backend",
            startedServices().single().getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
    }

    @Test
    fun `a cancelled restage deletes the copies it already staged and propagates`() = runBlocking {
        val selected = folderRows(3)
        val staged = selected.take(2).map { restagedFileOf(it.id) }
        val bulk = redo(selected, restage = { _, onStaged ->
            staged.forEach { file ->
                file.writeText("audio")
                onStaged(file.path)
            }
            throw CancellationException("cancelled mid-restage")
        })

        val thrown = runCatching { bulk.prepare(selected.map { it.id }, profile) { _, _ -> } }.exceptionOrNull()

        assertTrue("the cancellation propagates, got $thrown", thrown is CancellationException)
        staged.forEach { assertFalse("${it.name} is reclaimed", it.exists()) }
        assertEquals(emptyList<Intent>(), startedServices())
    }

    @Test
    fun `the first background-start fallback defers that item and starts none after it`() = runBlocking {
        val selected = folderRows(5)
        val bulk = redo(
            selected,
            restage = { targets, onStaged ->
                targets.associateWith { target ->
                    val file = restagedFileOf(target.row.id).apply { writeText("audio") }
                    onStaged(file.path)
                    FolderRestage.RestageResult.Staged(file.path)
                }
            },
            start = run {
                val outcomes = ArrayDeque(listOf(
                    InferenceEnqueue.Outcome.Started,
                    InferenceEnqueue.Outcome.Started,
                    InferenceEnqueue.Outcome.FallbackNotificationPosted,
                ))
                val fake: (Intent) -> InferenceEnqueue.Outcome = {
                    outcomes.removeFirstOrNull() ?: error("an item after the fallback was started")
                }
                fake
            },
        )
        val batch = bulk.prepare(selected.map { it.id }, profile) { _, _ -> }
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5"), batch.items.map { it.row.id })

        val report = bulk.enqueue(batch, profile, includeLong = true)

        assertEquals(BulkRedoReport(queued = 2, failedToStart = 0, deferred = 1, notStarted = 2), report)
        listOf("f1", "f2").forEach { assertTrue("$it was started: its copy is the queue's input", restagedFileOf(it).exists()) }
        assertTrue("the deferred item's notification still needs its copy", restagedFileOf("f3").exists())
        listOf("f4", "f5").forEach { assertFalse("$it never started: its copy is reclaimed", restagedFileOf(it).exists()) }
    }
}
