package com.antivocale.app.work

import com.antivocale.app.data.SourceKind
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.service.InferenceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-803: the Scan-now one-shot must stay a plain request. Expedited
 * routing is the API 26-30 failure: WorkManager's foreground fallback calls
 * getForegroundInfo() there, the CoroutineWorker default throws, and the
 * tapped scan resolves FAILED without a trace. Dispatch-path decisions land
 * pinned (PR #28 precedent).
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledFolderWorkerScanNowTest {

    @Test
    fun `the scan-now request is not expedited`() {
        val request = ScheduledFolderScheduler.scanNowRequest("content://tree/primary%3ADownload")
        assertFalse(request.workSpec.expedited)
    }

    @Test
    fun `the scan-now request carries the folder and the manual flag`() {
        val uri = "content://tree/primary%3ADownload"
        val request = ScheduledFolderScheduler.scanNowRequest(uri)
        assertEquals(uri, request.workSpec.input.getString(ScheduledFolderWorker.KEY_TREE_URI))
        assertEquals(true, request.workSpec.input.getBoolean(ScheduledFolderWorker.KEY_SCAN_NOW, false))
        assertTrue(request.workSpec.isPeriodic.not())
    }

    @Test
    fun `the media trigger wakes on audio saves only, coalesced within seconds`() {
        val c = ScheduledFolderScheduler.mediaTriggerRequest().workSpec.constraints
        val uris = c.contentUriTriggers.map { it.uri }
        assertTrue(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI in uris)
        assertTrue(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI in uris)
        assertFalse(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI in uris)
        assertTrue(c.contentUriTriggers.all { it.isTriggeredForDescendants })
        assertTrue(c.contentTriggerMaxDelayMillis <= 5_000)
    }

    @Test
    fun `a follow-up scan carries its backoff step and delay`() {
        val uri = "content://tree/primary%3AMusic"
        val request = ScheduledFolderScheduler.followUpRequest(uri, attempt = 2, delaySeconds = 10)
        assertEquals(uri, request.workSpec.input.getString(ScheduledFolderWorker.KEY_TREE_URI))
        assertEquals(2, request.workSpec.input.getInt(ScheduledFolderWorker.KEY_RECHECK_ATTEMPT, -1))
        assertEquals(10_000L, request.workSpec.initialDelay)
        assertFalse(request.workSpec.expedited)
    }

    @Test
    fun `a subfolder file carries its relative directory as the History subfolder`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tree = "content://tree/primary%3ARecordings"
        val intent = ScheduledFolderWorker.buildServiceIntent(
            context, "/data/copy.m4a", "Calls/2026/rec.m4a", tree, "Recordings",
        )
        assertEquals("Calls/2026", intent.getStringExtra(InferenceService.EXTRA_SOURCE_SUB_PATH))
        assertEquals(SourceKind.FOLDER_WATCH, intent.getStringExtra(InferenceService.EXTRA_SOURCE_KIND))
        assertEquals(tree, intent.getStringExtra(InferenceService.EXTRA_SOURCE_FOLDER_KEY))
        assertEquals("Recordings", intent.getStringExtra(InferenceService.EXTRA_SOURCE_FOLDER_NAME))
    }

    @Test
    fun `a root file carries no subfolder and same-named files in two subfolders get distinct task ids`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tree = "content://tree/primary%3ARecordings"
        val root = ScheduledFolderWorker.buildServiceIntent(context, "/data/a.m4a", "rec.m4a", tree, "Recordings")
        assertFalse(root.hasExtra(InferenceService.EXTRA_SOURCE_SUB_PATH))
        val a = ScheduledFolderWorker.buildServiceIntent(context, "/data/a.m4a", "A/rec.m4a", tree, "Recordings")
        val b = ScheduledFolderWorker.buildServiceIntent(context, "/data/b.m4a", "B/rec.m4a", tree, "Recordings")
        val idA = checkNotNull(a.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID))
        val idB = checkNotNull(b.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID))
        // The trailing hash is the per-file part; the timestamp may coincide.
        assertTrue(idA.substringAfterLast('_') != idB.substringAfterLast('_'))
    }

    @Test
    fun `removing one folder never cancels a sibling folder's work`() {
        val calls = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
        val sibling = calls + "_now"
        val callsNames = ScheduledFolderScheduler.uniqueNamesOf(calls).toSet()
        val siblingNames = ScheduledFolderScheduler.uniqueNamesOf(sibling).toSet()
        assertTrue("names overlap: ${callsNames intersect siblingNames}", (callsNames intersect siblingNames).isEmpty())
        for (kind in listOf("_trigger", "_recheck")) {
            val other = ScheduledFolderScheduler.uniqueNamesOf(calls + kind).toSet()
            assertTrue((callsNames intersect other).isEmpty())
        }
    }
}
