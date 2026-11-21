package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.ScopeProfileSource
import com.antivocale.app.data.ScopeProfileStore
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.tabs.HistoryGroupScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R6: the History header long-press editor reads and writes the folder
 * default and the subfolder rules through ScheduledFolderStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelFolderRuleTest : ViewModelTeardownTest() {

    private val callsUri = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val folderRule = TranscriptionProfile(language = "ru")
    private val subRule = TranscriptionProfile(backendId = "sherpa:gigaam", language = "ru")

    private val folders = MutableStateFlow(
        listOf(
            WatchedFolder(
                treeUri = callsUri,
                displayName = "Calls",
                profile = folderRule,
                subfolderRules = mapOf("Russian" to subRule),
            ),
        ),
    )
    private lateinit var store: ScheduledFolderStore
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()

    // A field (not the constructor default) so the harness cancels it.
    private val appScope = register(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        store = mockk(relaxed = true) {
            every { foldersFlow } returns folders
            coEvery { setFolderProfile(any(), any()) } returns emptyList()
            coEvery { setSubfolderRule(any(), any(), any()) } returns emptyList()
        }
        viewModel = register(LogsViewModel(mockk(relaxed = true), mockk(relaxed = true), stubPreferencesManager(), staticRegistry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), orchestrator = mockk(relaxed = true), duplicateGuard = mockk(relaxed = true), scheduledFolderStore = store, profileOutcomeSource = mockk(relaxed = true), historyBulkRedo = mockk(relaxed = true), scopeProfileSource = mockk<ScopeProfileSource>(relaxed = true), scopeProfileStore = senderStore, appScope = appScope))
    }

    // Fake sender data only.
    private val senderId = "wa:com.whatsapp:Test Contact"
    private val senderStore: ScopeProfileStore = mockk(relaxed = true) {
        coEvery { senderProfile(senderId) } returns TranscriptionProfile(language = "en")
    }

    @Test
    fun `saving a sender rule writes the sender profile, and null removes it`() = runTest {
        val edited = TranscriptionProfile(language = "it")
        viewModel.saveSenderRule(senderId, edited)
        viewModel.saveSenderRule(senderId, null)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { senderStore.setSenderProfile(senderId, edited) }
        coVerify(exactly = 1) { senderStore.setSenderProfile(senderId, null) }
        coVerify(exactly = 0) { store.setFolderProfile(any(), any()) }
        coVerify(exactly = 0) { store.setSubfolderRule(any(), any(), any()) }
    }

    @Test
    fun `a sender scope loads its rule from the sender store and edits no folder`() = runTest {
        assertEquals(TranscriptionProfile(language = "en"), viewModel.senderRuleFor(senderId))
        assertNull(viewModel.folderRuleFor(HistoryGroupScope.Sender(senderId)))
    }

    @Test
    fun `the stored folder default and subfolder rule load per scope`() = runTest {
        // History collects watchedFolders while it shows the headers.
        backgroundScope.launch { viewModel.watchedFolders.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(folderRule, viewModel.folderRuleFor(HistoryGroupScope.Folder(callsUri)))
        assertEquals(subRule, viewModel.folderRuleFor(HistoryGroupScope.Subfolder(callsUri, "Russian")))
        assertNull(viewModel.folderRuleFor(HistoryGroupScope.Subfolder(callsUri, "Italian")))
        assertNull(viewModel.folderRuleFor(HistoryGroupScope.None))
    }

    @Test
    fun `a folder no longer watched has no rule to load`() = runTest {
        backgroundScope.launch { viewModel.watchedFolders.collect {} }
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.folderRuleFor(HistoryGroupScope.Folder("content://gone/tree/x")))
    }

    @Test
    fun `saving a folder rule writes the folder default`() = runTest {
        val edited = TranscriptionProfile(language = "it")
        viewModel.saveFolderRule(HistoryGroupScope.Folder(callsUri), edited)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { store.setFolderProfile(callsUri, edited) }
        coVerify(exactly = 0) { store.setSubfolderRule(any(), any(), any()) }
    }

    @Test
    fun `saving a subfolder rule writes that subfolder, and null removes it`() = runTest {
        val edited = TranscriptionProfile(language = "it")
        val scope = HistoryGroupScope.Subfolder(callsUri, "Russian")
        viewModel.saveFolderRule(scope, edited)
        viewModel.saveFolderRule(scope, null)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { store.setSubfolderRule(callsUri, "Russian", edited) }
        coVerify(exactly = 1) { store.setSubfolderRule(callsUri, "Russian", null) }
        coVerify(exactly = 0) { store.setFolderProfile(any(), any()) }
    }

    @Test
    fun `a failed write is contained`() = runTest {
        coEvery { store.setSubfolderRule(any(), any(), any()) } throws IllegalArgumentException("root")
        viewModel.saveFolderRule(HistoryGroupScope.Subfolder(callsUri, "Russian"), subRule)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { store.setSubfolderRule(callsUri, "Russian", subRule) }
    }
}
