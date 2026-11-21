package com.antivocale.app.ui.viewmodel

import android.net.Uri
import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.util.TreeUris
import com.antivocale.app.work.FolderListing
import com.antivocale.app.work.ScheduledFolderScheduler
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real android.net.Uri: MockK cannot stub toString(),
// and the VM keys the store on uri.toString() and compares uris by equality.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelFolderAddTest : ViewModelTeardownTest() {
    private val key = "content://tree/Recordings"
    private val otherKey = "content://tree/Calls"
    private lateinit var uri: Uri
    private lateinit var otherUri: Uri
    private lateinit var harness: SettingsViewModelTestHelper
    private val viewModel get() = harness.viewModel
    private val store get() = harness.scheduledFolderStore
    private val recording = FolderScanPlanner.Child("sub/recording.m4a", 42, 1000)
    private val textFile = FolderScanPlanner.Child("notes.txt", 4, 1000)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        harness = register(SettingsViewModelTestHelper())
        uri = Uri.parse(key)
        otherUri = Uri.parse(otherKey)
        mockkObject(FolderListing, ScheduledFolderScheduler, TreeUris)
        every { TreeUris.displayName(harness.application, uri) } returns "Recordings"
        every { TreeUris.displayName(harness.application, otherUri) } returns "Calls"
        every { TreeUris.releasePersistableReadGrant(harness.application, any()) } just Runs
        every { ScheduledFolderScheduler.enqueueFolder(harness.application, any()) } just Runs
    }

    @Test
    fun `only new files persists the listing baseline before enqueue`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns
            listing(recording to uri, textFile to uri)
        var snapshotAtEnqueue: List<FileSnapshot>? = null
        every { ScheduledFolderScheduler.enqueueFolder(harness.application, any()) } answers {
            snapshotAtEnqueue = runBlocking { store.snapshotFor(key) }
        }

        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        val pending = checkNotNull(viewModel.pendingFolderAdd.value)
        assertEquals(1, pending.existingCount)
        assertNull(store.byUri(key))
        withTimeout(5000) { viewModel.confirmPendingFolderAdd(uri, onlyNewFiles = true).join() }

        val snapshot = checkNotNull(snapshotAtEnqueue)
        assertEquals(recording.name, snapshot.single().name)
        assertNotNull(snapshot.single().enqueuedAt)
        assertEquals(pending.baseline, snapshot)
        assertNull(viewModel.pendingFolderAdd.value)
    }

    @Test
    fun `failed listing adds an empty snapshot without asking`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns null
        assertAddedWithoutBaseline()
    }

    @Test
    fun `listing without audio adds an empty snapshot without asking`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns listing(textFile to uri)
        assertAddedWithoutBaseline()
    }

    @Test
    fun `cancel adds nothing and releases the grant`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns listing(recording to uri)
        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        assertNotNull(viewModel.pendingFolderAdd.value)
        withTimeout(5000) { viewModel.cancelPendingFolderAdd(uri).join() }

        assertNull(viewModel.pendingFolderAdd.value)
        assertNull(store.byUri(key))
        verify(exactly = 1) { TreeUris.releasePersistableReadGrant(harness.application, uri) }
        verify(exactly = 0) { ScheduledFolderScheduler.enqueueFolder(any(), any()) }
    }

    @Test
    fun `only new files on a capped listing keeps baselining newly visible subfolder files`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns
            listing(recording to uri, truncated = true)
        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        val pending = checkNotNull(viewModel.pendingFolderAdd.value)
        withTimeout(5000) { viewModel.confirmPendingFolderAdd(uri, onlyNewFiles = true).join() }
        val folder = checkNotNull(store.byUri(key))
        assertFalse(folder.recursiveBaselined)
        // the listing time bounds what later scans may still read as backlog
        assertEquals(pending.listedAt, folder.recursiveBaselineCutoff)
        assertEquals(pending.baseline, folder.snapshot)
    }

    @Test
    fun `a capped listing with no visible audio adds without asking and keeps the unseen part as backlog`() =
        runBlocking {
            every { FolderListing.listWithStatus(harness.application, uri) } returns
                listing(textFile to uri, truncated = true)
            val before = System.currentTimeMillis()
            withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }

            assertNull(viewModel.pendingFolderAdd.value)
            val folder = checkNotNull(store.byUri(key))
            assertFalse(folder.recursiveBaselined)
            val cutoff = checkNotNull(folder.recursiveBaselineCutoff)
            assertTrue(cutoff >= before && cutoff <= System.currentTimeMillis())
            assertTrue(folder.snapshot.isEmpty())
            verify(exactly = 1) { ScheduledFolderScheduler.enqueueFolder(harness.application, any()) }
        }

    @Test
    fun `also transcribing a capped listing scans subfolders normally`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns
            listing(recording to uri, truncated = true)
        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        withTimeout(5000) { viewModel.confirmPendingFolderAdd(uri, onlyNewFiles = false).join() }
        val folder = checkNotNull(store.byUri(key))
        assertTrue(folder.recursiveBaselined)
        assertNull(folder.recursiveBaselineCutoff)
        assertTrue(folder.snapshot.isEmpty())
    }

    @Test
    fun `a confirm for another folder than the pending one does nothing`() = runBlocking {
        every { FolderListing.listWithStatus(harness.application, uri) } returns listing(recording to uri)
        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        withTimeout(5000) { viewModel.confirmPendingFolderAdd(otherUri, onlyNewFiles = false).join() }
        withTimeout(5000) { viewModel.cancelPendingFolderAdd(otherUri).join() }

        assertEquals(uri, viewModel.pendingFolderAdd.value?.uri)
        assertNull(store.byUri(key))
        assertNull(store.byUri(otherKey))
        verify(exactly = 0) { ScheduledFolderScheduler.enqueueFolder(any(), any()) }
        verify(exactly = 0) { TreeUris.releasePersistableReadGrant(any(), any()) }
    }

    @Test
    fun `a second pick replaces the pending add, releases the first grant, and ignores the first confirm`() =
        runBlocking {
            every { FolderListing.listWithStatus(harness.application, uri) } returns listing(recording to uri)
            every { FolderListing.listWithStatus(harness.application, otherUri) } returns
                listing(recording to otherUri)
            withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
            withTimeout(5000) { viewModel.addWatchedFolder(otherUri).join() }

            assertEquals(otherUri, viewModel.pendingFolderAdd.value?.uri)
            verify(exactly = 1) { TreeUris.releasePersistableReadGrant(harness.application, uri) }
            verify(exactly = 0) { TreeUris.releasePersistableReadGrant(harness.application, otherUri) }

            // The tap on the FIRST folder's dialog, queued behind the second
            // pick: it must not apply "also transcribe" to the second folder.
            withTimeout(5000) { viewModel.confirmPendingFolderAdd(uri, onlyNewFiles = false).join() }
            assertEquals(otherUri, viewModel.pendingFolderAdd.value?.uri)
            assertNull(store.byUri(otherKey))
            assertNull(store.byUri(key))
            verify(exactly = 0) { ScheduledFolderScheduler.enqueueFolder(any(), any()) }
        }

    private fun listing(
        vararg children: Pair<FolderScanPlanner.Child, Uri>,
        truncated: Boolean = false,
    ) = FolderListing.Listing(children.toList(), truncated)

    private suspend fun assertAddedWithoutBaseline() {
        withTimeout(5000) { viewModel.addWatchedFolder(uri).join() }
        assertNull(viewModel.pendingFolderAdd.value)
        val folder = checkNotNull(store.byUri(key))
        assertTrue(folder.snapshot.isEmpty())
        // a failed or audio-free complete listing keeps today's fallback
        assertTrue(folder.recursiveBaselined)
        assertNull(folder.recursiveBaselineCutoff)
        verify(exactly = 1) { ScheduledFolderScheduler.enqueueFolder(harness.application, any()) }
    }
}
