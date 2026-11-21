package com.antivocale.app.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.BackendLoadability
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.FrameworkPhoneNormalizer
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.data.ScopeProfileSource
import com.antivocale.app.data.ScopeProfileStore
import com.antivocale.app.data.SenderKeys
import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.service.HistoryBulkRedo
import com.antivocale.app.service.PreparedBatch
import com.antivocale.app.transcription.LanguageSupport
import com.antivocale.app.transcription.LanguageSupportLookup
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.tabs.HistoryGroupScope
import com.antivocale.app.ui.viewmodel.LogsViewModel.BulkRedoUiState
import com.antivocale.app.ui.viewmodel.LogsViewModel.ScopeRedoOffer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The redo offer after a History folder or subfolder rule save: the rows the
 * rule governs, redone with the profile the rule RESOLVES to. Real folder and
 * scope stores under a real [ScopeProfileSource]; the DAO and the bulk redo
 * runner are mocks. Fake folder and contact data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LogsViewModelScopeRedoTest : ViewModelTeardownTest() {

    private val tree = "content://tree/recorder"
    private val prefs = FakePreferencesManager().apply { _transcriptionBackend.value = "parakeet" }
    private val folders = ScheduledFolderStore(prefs)
    private val scopes = ScopeProfileStore(prefs)
    private val loadability = mockk<BackendLoadability> {
        coEvery { isLoadable(any()) } coAnswers { firstArg<String>() in setOf("parakeet", "gigaam") }
    }
    private val support = mockk<LanguageSupportLookup> {
        coEvery { support(any(), any()) } coAnswers {
            if (firstArg<String>() == "gigaam") LanguageSupport.HONORS else LanguageSupport.IGNORES
        }
    }
    private val source = ScopeProfileSource(prefs, folders, scopes, loadability, support)

    private lateinit var viewModel: LogsViewModel
    private lateinit var logDao: LogDao
    private lateinit var bulkRedo: HistoryBulkRedo

    // A field (not the constructor default) so the harness cancels it.
    private val appScope = register(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val callerLabel = "Test Contact"

    private fun row(id: String, subPath: String?, sender: String? = null) = LogEntity(
        id = id,
        timestamp = 1L,
        taskId = "task-$id",
        type = "AUDIO",
        status = "SUCCESS",
        senderName = sender,
        sourceKind = SourceKind.FOLDER_WATCH,
        sourceFolderKey = tree,
        sourceSubPath = subPath,
    )

    private val rows = listOf(
        row("root", null),
        row("ru", "Russian"),
        row("ru-2024", "Russian/2024"),
        row("ru-caller", "Russian", sender = callerLabel),
        row("ru-work", "Russian/Work"),
    )

    @Before
    fun setUp() {
        // Real unconfined Main, as the bulk redo suite: the save hops to IO,
        // so the test synchronizes on the state flows themselves.
        Dispatchers.setMain(Dispatchers.Unconfined)
        logDao = mockk(relaxed = true)
        coEvery { logDao.getIdsByFolderKey(tree) } returns rows.map { it.id }
        coEvery { logDao.getByIds(any()) } returns rows
        bulkRedo = mockk(relaxed = true)
        runBlocking {
            folders.add(WatchedFolder(treeUri = tree, displayName = "Recorder"))
            folders.setSubfolderRule(tree, "Russian/Work", TranscriptionProfile(backendId = "parakeet"))
            scopes.setLanguageModel("ru", "gigaam")
            val caller = SenderKeys.forCallRecording(callerLabel, null, FrameworkPhoneNormalizer)!!
            scopes.setSenderProfile(caller.storageId(), TranscriptionProfile(language = "en"))
        }
        viewModel = register(LogsViewModel(
            mockk(relaxed = true), logDao, stubPreferencesManager(), staticRegistry(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appContext = ApplicationProvider.getApplicationContext(),
            orchestrator = mockk(relaxed = true),
            duplicateGuard = mockk(relaxed = true),
            scheduledFolderStore = folders,
            profileOutcomeSource = mockk(relaxed = true),
            scopeProfileStore = scopes,
            historyBulkRedo = bulkRedo,
            scopeProfileSource = source,
            appScope = appScope,
        ))
    }

    private suspend fun awaitOffer(): ScopeRedoOffer =
        withTimeout(30_000) { viewModel.scopeRedoOffer.first { it != null } }!!

    @Test
    fun `a language-only subfolder rule offers its rows with the language map model`() = runBlocking {
        viewModel.saveFolderRule(HistoryGroupScope.Subfolder(tree, "Russian"), TranscriptionProfile(language = "ru"))

        val offer = awaitOffer()
        // Not ru-work (a deeper rule) and not ru-caller (a sender rule outranks).
        assertEquals(listOf("ru", "ru-2024"), offer.rowIds)
        assertEquals(TranscriptionProfile(backendId = "gigaam", language = "ru"), offer.profile)
        assertFalse(offer.ruleRemoved)
    }

    @Test
    fun `confirm redoes exactly the offered rows with the resolved profile`() = runBlocking {
        coEvery { bulkRedo.prepare(any(), any(), any()) } returns PreparedBatch(
            items = emptyList(), skipped = emptyMap(), longCount = 0, longestSeconds = 0.0,
            restagedPaths = emptyList(), backendId = "gigaam",
        )
        viewModel.saveFolderRule(HistoryGroupScope.Subfolder(tree, "Russian"), TranscriptionProfile(language = "ru"))
        awaitOffer()

        viewModel.confirmScopeRedo()

        assertNull(viewModel.scopeRedoOffer.value)
        withTimeout(30_000) { viewModel.bulkRedoState.first { it is BulkRedoUiState.Summary } }
        coVerify(exactly = 1) {
            bulkRedo.prepare(listOf("ru", "ru-2024"), TranscriptionProfile(backendId = "gigaam", language = "ru"), any())
        }
        assertFalse(viewModel.scopeRedoBusy.value)
    }

    @Test
    fun `not now clears the offer and redoes nothing`() = runBlocking {
        viewModel.saveFolderRule(HistoryGroupScope.Subfolder(tree, "Russian"), TranscriptionProfile(language = "ru"))
        awaitOffer()

        viewModel.dismissScopeRedo()

        assertNull(viewModel.scopeRedoOffer.value)
        assertEquals(BulkRedoUiState.Idle, viewModel.bulkRedoState.value)
        coVerify(exactly = 0) { bulkRedo.prepare(any(), any(), any()) }
    }

    @Test
    fun `confirm while another batch runs flags busy`() = runBlocking {
        val gate = CompletableDeferred<PreparedBatch>()
        coEvery { bulkRedo.prepare(any(), any(), any()) } coAnswers { gate.await() }
        assertTrue(viewModel.redoRows(listOf("other"), TranscriptionProfile()))
        viewModel.saveFolderRule(HistoryGroupScope.Subfolder(tree, "Russian"), TranscriptionProfile(language = "ru"))
        awaitOffer()

        viewModel.confirmScopeRedo()

        assertTrue(viewModel.scopeRedoBusy.value)
        assertNull(viewModel.scopeRedoOffer.value)
        viewModel.consumeScopeRedoBusy()
        assertFalse(viewModel.scopeRedoBusy.value)
        coVerify(exactly = 0) { bulkRedo.prepare(listOf("ru", "ru-2024"), any(), any()) }
        viewModel.cancelBulkRedo()
    }

    @Test
    fun `removing the folder default offers the root rows with the global model`() = runBlocking {
        folders.setFolderProfile(tree, TranscriptionProfile(language = "ru"))
        viewModel.saveFolderRule(HistoryGroupScope.Folder(tree), null)

        val offer = awaitOffer()
        // Russian/Work keeps its own rule; the rest of the folder is the default's.
        assertEquals(listOf("root", "ru", "ru-2024"), offer.rowIds)
        assertEquals(TranscriptionProfile(), offer.profile)
        assertTrue(offer.ruleRemoved)
    }
}
