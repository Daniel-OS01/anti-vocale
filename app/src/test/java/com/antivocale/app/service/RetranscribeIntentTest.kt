package com.antivocale.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.FolderWatchTaskIds
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.SourceOrigin
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** The shared re-transcribe request: the extras the single-row redo always set, and the task id shape. */
@RunWith(RobolectricTestRunner::class)
class RetranscribeIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val relPath = "2026/rec.m4a"

    private val row = LogEntry(
        taskId = "orig",
        type = LogEntry.Type.AUDIO,
        status = LogEntry.Status.SUCCESS,
        prompt = "the original prompt",
        sourcePackageName = "com.whatsapp",
        senderName = "Anna",
    )
    private val origin = SourceOrigin(SourceKind.FOLDER_WATCH, tree, "Calls", "2026")

    @Test
    fun `build sets every extra of the single-row redo`() {
        val intent = RetranscribeIntent.build(
            context, row, origin, "/local/a.m4a", "t1",
            TranscriptionProfile(backendId = "whisper", language = "it"),
        )
        assertEquals(InferenceService::class.java.name, intent.component?.className)
        assertEquals("t1", intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID))
        assertEquals(TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            intent.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals("the original prompt", intent.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT))
        assertEquals("/local/a.m4a", intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertEquals(InferenceService.SOURCE_RETRANSCRIBE, intent.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals("whisper", intent.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
        assertEquals("it", intent.getStringExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE))
        assertEquals("com.whatsapp", intent.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
        assertEquals("Anna", intent.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertEquals(SourceKind.FOLDER_WATCH, intent.getStringExtra(InferenceService.EXTRA_SOURCE_KIND))
        assertEquals(tree, intent.getStringExtra(InferenceService.EXTRA_SOURCE_FOLDER_KEY))
        assertEquals("Calls", intent.getStringExtra(InferenceService.EXTRA_SOURCE_FOLDER_NAME))
        assertEquals("2026", intent.getStringExtra(InferenceService.EXTRA_SOURCE_SUB_PATH))
    }

    @Test
    fun `absent optional fields set no extra`() {
        val bare = RetranscribeIntent.build(
            context,
            row.copy(sourcePackageName = null, senderName = null),
            SourceOrigin(null, null, null, null),
            "/local/b.m4a", "t2",
            TranscriptionProfile(backendId = "whisper"),
        )
        assertEquals("whisper", bare.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
        listOf(
            InferenceService.EXTRA_LANGUAGE_OVERRIDE,
            InferenceService.EXTRA_SOURCE_PACKAGE,
            InferenceService.EXTRA_SENDER_NAME,
            InferenceService.EXTRA_SOURCE_KIND,
            InferenceService.EXTRA_SOURCE_FOLDER_KEY,
            InferenceService.EXTRA_SOURCE_FOLDER_NAME,
            InferenceService.EXTRA_SOURCE_SUB_PATH,
        ).forEach { key -> assertFalse("unexpected extra $key", bare.hasExtra(key)) }
    }

    @Test
    fun `a folder row keeps the folder-watch shape and its relative-path hash`() {
        val folderRow = row.copy(taskId = FolderWatchTaskIds.mint(tree, relPath, 1_000L))
        val id = RetranscribeIntent.taskIdFor(folderRow, origin, 2_000L)
        assertEquals(FolderWatchTaskIds.mint(tree, relPath, 2_000L), id)
        assertEquals(relPath.hashCode().toUInt(), FolderWatchTaskIds.relPathHashOf(id))
        assertEquals(tree.hashCode().toUInt(), FolderWatchTaskIds.treeHashOf(id))
    }

    @Test
    fun `a non-folder row gets a UUID`() {
        val shareId = RetranscribeIntent.taskIdFor(row, SourceOrigin(SourceKind.SHARE, null, null, null), 2_000L)
        assertEquals(shareId, UUID.fromString(shareId).toString())
        // A folder-shaped id without a folder key cannot mint the folder shape.
        val folderRow = row.copy(taskId = FolderWatchTaskIds.mint(tree, relPath, 1_000L))
        val keyless = RetranscribeIntent.taskIdFor(
            folderRow, SourceOrigin(SourceKind.FOLDER_WATCH, null, null, null), 2_000L)
        assertEquals(keyless, UUID.fromString(keyless).toString())
    }

    @Test
    fun `the task clock never repeats a millisecond`() {
        val now = System.currentTimeMillis()
        val a = RetranscribeIntent.nextTaskMs(now)
        val b = RetranscribeIntent.nextTaskMs(now)
        assertNotEquals(a, b)
        assertTrue(b > a)
    }
}
