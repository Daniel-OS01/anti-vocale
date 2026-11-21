package com.antivocale.app.service

import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure bulk-redo plan: one case per rule, order kept, stable enqueue grouping. */
class BulkRedoPlannerTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val relPath = "2026/rec.m4a"
    private val folderTaskId = FolderWatchTaskIds.mint(tree, relPath, 1_700_000_000_000L)
    private val present = setOf("/data/present.m4a")
    private val exists: (String) -> Boolean = { it in present }

    private fun row(
        id: String,
        type: LogEntry.Type = LogEntry.Type.AUDIO,
        status: LogEntry.Status = LogEntry.Status.SUCCESS,
        filePath: String? = "/data/gone.m4a",
        taskId: String = "uuid-$id",
        sourceKind: String? = null,
        sourceFolderKey: String? = null,
        sourceSubPath: String? = null,
    ) = LogEntry(
        id = id,
        taskId = taskId,
        type = type,
        status = status,
        filePath = filePath,
        sourceKind = sourceKind,
        sourceFolderKey = sourceFolderKey,
        sourceSubPath = sourceSubPath,
    )

    private fun planOne(row: LogEntry, folders: List<WatchedFolder> = emptyList()) =
        BulkRedoPlanner.plan(listOf(row), exists, folders).single()

    @Test
    fun `a text row is skipped as not audio`() {
        val r = row("t", type = LogEntry.Type.TEXT, filePath = "/data/present.m4a")
        assertEquals(BulkRedoItem.Skipped(r, SkipReason.NOT_AUDIO), planOne(r))
    }

    @Test
    fun `queued and processing rows are skipped as in flight`() {
        listOf(LogEntry.Status.QUEUED, LogEntry.Status.PROCESSING).forEach { status ->
            val r = row("q", status = status, filePath = "/data/present.m4a")
            assertEquals(BulkRedoItem.Skipped(r, SkipReason.IN_FLIGHT), planOne(r))
        }
    }

    @Test
    fun `a terminal row whose file exists is ready`() {
        listOf(LogEntry.Status.SUCCESS, LogEntry.Status.ERROR).forEach { status ->
            val r = row("ok", status = status, filePath = "/data/present.m4a")
            assertEquals(BulkRedoItem.Ready(r, "/data/present.m4a"), planOne(r))
        }
    }

    @Test
    fun `a folder-watch row whose copy is gone needs a restage`() {
        val r = row(
            "fw", taskId = folderTaskId, sourceKind = SourceKind.FOLDER_WATCH,
            sourceFolderKey = tree, sourceSubPath = "2026",
        )
        assertEquals(
            BulkRedoItem.NeedsRestage(r, tree, "2026", relPath.hashCode().toUInt()),
            planOne(r),
        )
    }

    @Test
    fun `a folder-watch row with no file path at all needs a restage`() {
        val r = row(
            "fw", filePath = null, taskId = folderTaskId,
            sourceKind = SourceKind.FOLDER_WATCH, sourceFolderKey = tree, sourceSubPath = "2026",
        )
        assertEquals(BulkRedoItem.NeedsRestage(r, tree, "2026", relPath.hashCode().toUInt()), planOne(r))
    }

    @Test
    fun `a legacy folder-watch row is attributed through the watched folders`() {
        val r = row("legacy", taskId = folderTaskId)
        val folders = listOf(WatchedFolder(treeUri = tree, displayName = "Calls"))
        assertEquals(BulkRedoItem.NeedsRestage(r, tree, null, relPath.hashCode().toUInt()), planOne(r, folders))
    }

    @Test
    fun `a folder-watch row without a folder key is gone`() {
        val r = row("nokey", taskId = folderTaskId, sourceKind = SourceKind.FOLDER_WATCH)
        assertEquals(BulkRedoItem.Skipped(r, SkipReason.FILE_GONE), planOne(r))
    }

    @Test
    fun `a folder-watch row whose task id has no hash is gone`() {
        // A retried folder row carries a fresh uuid task id.
        val r = row(
            "retried", taskId = "3f0c9a2e-uuid",
            sourceKind = SourceKind.FOLDER_WATCH, sourceFolderKey = tree,
        )
        assertEquals(BulkRedoItem.Skipped(r, SkipReason.FILE_GONE), planOne(r))
    }

    @Test
    fun `a share row whose copy is gone is skipped as gone`() {
        val r = row("share", sourceKind = SourceKind.SHARE)
        assertEquals(BulkRedoItem.Skipped(r, SkipReason.FILE_GONE), planOne(r))
    }

    @Test
    fun `the plan keeps the input order`() {
        val rows = listOf(
            row("a", filePath = "/data/present.m4a"),
            row("b", type = LogEntry.Type.TEXT),
            row("c"),
            row("d", status = LogEntry.Status.QUEUED),
        )
        assertEquals(listOf("a", "b", "c", "d"), BulkRedoPlanner.plan(rows, exists).map { it.row.id })
    }

    @Test
    fun `orderForEnqueue groups by first appearance and keeps order inside a group`() {
        data class Job(val name: String, val backend: String?, val lang: String?)
        val jobs = listOf(
            Job("1", "whisper", "it"),
            Job("2", "parakeet", null),
            Job("3", "whisper", "it"),
            Job("4", "whisper", "en"),
            Job("5", "parakeet", null),
            Job("6", "whisper", "it"),
            Job("7", "whisper", "en"),
        )
        val ordered = BulkRedoPlanner.orderForEnqueue(jobs) { it.backend to it.lang }
        assertEquals(listOf("1", "3", "6", "2", "5", "4", "7"), ordered.map { it.name })
    }

    @Test
    fun `orderForEnqueue of an empty list is empty`() {
        assertEquals(emptyList<String>(), BulkRedoPlanner.orderForEnqueue(emptyList<String>()) { null to null })
    }
}
