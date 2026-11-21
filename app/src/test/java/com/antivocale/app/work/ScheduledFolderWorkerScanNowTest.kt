package com.antivocale.app.work

import com.antivocale.app.data.PhoneNormalizer
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.SourceKind
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.service.InferenceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // ---- R6: the resolved scope profile ----

    @Test
    fun `the folder-watch intent carries both resolved overrides`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tree = "content://tree/primary%3ARecordings"
        val profile = com.antivocale.app.transcription.ResolvedProfile(
            backendOverride = "gigaam",
            languageOverride = "ru",
            decidedBy = com.antivocale.app.transcription.ScopeKind.SUBFOLDER,
        )
        val intent = ScheduledFolderWorker.buildServiceIntent(
            context, "/data/copy.m4a", "Russian/rec.m4a", tree, "Recordings", profile)
        assertEquals("gigaam", intent.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
        assertEquals("ru", intent.getStringExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE))
        // The task-id format is another feature's contract.
        assertTrue(checkNotNull(intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)).startsWith("fwatch_"))

        val none = ScheduledFolderWorker.buildServiceIntent(context, "/data/copy.m4a", "rec.m4a", tree, "Recordings")
        assertFalse(none.hasExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
        assertFalse(none.hasExtra(InferenceService.EXTRA_LANGUAGE_OVERRIDE))
    }

    @Test
    fun `the scan order groups files by resolved backend, global first, stable inside each group`() {
        val files = listOf(
            "a" to "whisper",
            "b" to null,
            "c" to "gigaam",
            "d" to "whisper",
            "e" to null,
            "f" to "gigaam",
        )
        val ordered = ScheduledFolderWorker.orderByBackend(files) { it.second }.map { it.first }
        assertEquals(listOf("b", "e", "c", "f", "a", "d"), ordered)
    }

    /** A fake E.164 normalizer: prefixes the country's test code to the digits. */
    private val fakePhone = PhoneNormalizer { raw, countryIso ->
        if (countryIso == "it") "+39" + raw.filter { it.isDigit() } else null
    }

    @Test
    fun `two callers in one subfolder resolve to different sender ids`() {
        val first = ScheduledFolderWorker.fileOriginKey(
            "Calls/Call from Test Contact_20240101_123456.m4a", "it", fakePhone)
        val second = ScheduledFolderWorker.fileOriginKey(
            "Calls/Call from Other Contact_20240101_123456.m4a", "it", fakePhone)
        assertEquals("Calls", first.subPath)
        assertEquals("Calls", second.subPath)
        assertEquals(SenderKey.Call("Test Contact").storageId(), first.senderStorageId)
        assertEquals(SenderKey.Call("Other Contact").storageId(), second.senderStorageId)
        assertNotEquals(first, second)
    }

    @Test
    fun `a phone-number caller is keyed through the normalizer`() {
        val key = ScheduledFolderWorker.fileOriginKey("chiamata_06123456_20240101.amr", "it", fakePhone)
        assertNull(key.subPath)
        assertEquals(SenderKey.Call("+3906123456").storageId(), key.senderStorageId)
    }

    @Test
    fun `a non-call file name gives no sender id`() {
        val key = ScheduledFolderWorker.fileOriginKey("Memos/groceries.m4a", "it", fakePhone)
        assertEquals("Memos", key.subPath)
        assertNull(key.senderStorageId)
    }
}
