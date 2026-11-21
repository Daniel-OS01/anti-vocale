package com.antivocale.app.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.service.BulkRedoReport
import com.antivocale.app.service.HistoryBulkRedo
import com.antivocale.app.service.PreparedBatch
import com.antivocale.app.service.SkipReason
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.viewmodel.LogsViewModel.BulkRedoUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bulk redo's ViewModel flow over a mocked runner: Preparing then
 * Summary, confirm enqueues and reaches Done, cancel discards a prepared
 * batch and stops a running prepare before anything is enqueued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LogsViewModelBulkRedoTest : ViewModelTeardownTest() {

    private lateinit var viewModel: LogsViewModel
    private lateinit var bulkRedo: HistoryBulkRedo
    private val rows = MutableStateFlow<List<LogEntity>>(emptyList())

    // A field (not the constructor default) so the harness cancels it.
    private val appScope = register(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // Unknown to the static registry: the display name falls back to the id.
    private val profile = TranscriptionProfile(backendId = "test-backend", language = "ru")
    private val batch = PreparedBatch(
        items = emptyList(),
        skipped = mapOf(SkipReason.FILE_GONE to 2),
        longCount = 0,
        longestSeconds = 0.0,
        restagedPaths = listOf("/tmp/restaged-1"),
        backendId = "test-backend",
    )

    @Before
    fun setUp() {
        // Real unconfined Main: the runner hops to IO, so the test
        // synchronizes on the state flow itself (as the batch-intake suite).
        Dispatchers.setMain(Dispatchers.Unconfined)
        val logDao: LogDao = mockk(relaxed = true)
        every { logDao.getAll() } returns rows
        bulkRedo = mockk(relaxed = true)
        every { bulkRedo.enqueue(any(), any(), any()) } returns BulkRedoReport(queued = 3, failedToStart = 1)
        viewModel = register(LogsViewModel(
            mockk(relaxed = true), logDao, stubPreferencesManager(), staticRegistry(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appContext = ApplicationProvider.getApplicationContext(),
            orchestrator = mockk(relaxed = true),
            duplicateGuard = mockk(relaxed = true),
            scheduledFolderStore = mockk(relaxed = true),
            historyBulkRedo = bulkRedo,
            profileOutcomeSource = mockk(relaxed = true),
            appScope = appScope,
        ))
    }

    private fun entity(id: String) = LogEntity(
        id = id,
        timestamp = 1_000L,
        taskId = "task-$id",
        type = "AUDIO",
        status = "SUCCESS",
    )

    private suspend fun awaitState(predicate: (BulkRedoUiState) -> Boolean): BulkRedoUiState =
        withTimeout(30_000) { viewModel.bulkRedoState.first(predicate) }

    private suspend fun prepareToSummary(): BulkRedoUiState.Summary {
        coEvery { bulkRedo.prepare(any(), any(), any()) } returns batch
        viewModel.redoRows(listOf("a", "b"), profile)
        return awaitState { it is BulkRedoUiState.Summary } as BulkRedoUiState.Summary
    }

    @Test
    fun `redoRows goes Preparing with progress, then Summary`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coEvery { bulkRedo.prepare(any(), any(), any()) } coAnswers {
            thirdArg<(Int, Int) -> Unit>().invoke(1, 2)
            gate.await()
            batch
        }

        viewModel.redoRows(listOf("a", "b"), profile)

        val preparing = awaitState { it is BulkRedoUiState.Preparing && it.done == 1 } as BulkRedoUiState.Preparing
        assertEquals(2, preparing.total)
        gate.complete(Unit)
        val summary = awaitState { it is BulkRedoUiState.Summary } as BulkRedoUiState.Summary
        assertEquals(batch, summary.batch)
        assertEquals(profile, summary.profile)
        assertEquals("test-backend", summary.modelDisplayName)
        coVerify(exactly = 1) { bulkRedo.prepare(listOf("a", "b"), profile, any()) }
    }

    @Test
    fun `confirm enqueues with includeLong, reaches Done and exits selection`() = runBlocking {
        viewModel.enterSelection("a")
        prepareToSummary()

        viewModel.confirmBulkRedo(includeLong = false)

        val done = awaitState { it is BulkRedoUiState.Done } as BulkRedoUiState.Done
        assertEquals(BulkRedoReport(queued = 3, failedToStart = 1), done.report)
        verify(exactly = 1) { bulkRedo.enqueue(batch, profile, false) }
        verify(exactly = 0) { bulkRedo.discard(any()) }
        assertFalse(viewModel.selectionMode.value)

        viewModel.dismissBulkRedoResult()
        assertEquals(BulkRedoUiState.Idle, viewModel.bulkRedoState.value)
    }

    @Test
    fun `cancel during Summary discards the batch`() = runBlocking {
        prepareToSummary()

        viewModel.cancelBulkRedo()

        assertEquals(BulkRedoUiState.Idle, viewModel.bulkRedoState.value)
        verify(timeout = 10_000) { bulkRedo.discard(batch) }
        verify(exactly = 0) { bulkRedo.enqueue(any(), any(), any()) }
    }

    @Test
    fun `cancel during Preparing stops the prepare and enqueues nothing`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        coEvery { bulkRedo.prepare(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        viewModel.redoRows(listOf("a"), profile)
        withTimeout(30_000) { entered.await() }
        assertTrue(viewModel.bulkRedoState.value is BulkRedoUiState.Preparing)

        viewModel.cancelBulkRedo()

        assertEquals(BulkRedoUiState.Idle, viewModel.bulkRedoState.value)
        // Give a wrongly surviving prepare the chance to publish a Summary.
        delay(200)
        assertEquals(BulkRedoUiState.Idle, viewModel.bulkRedoState.value)
        verify(exactly = 0) { bulkRedo.enqueue(any(), any(), any()) }
        verify(exactly = 0) { bulkRedo.discard(any()) }
    }

    @Test
    fun `redoSelected passes the selection's ids`() = runBlocking {
        rows.value = listOf(entity("a"), entity("b"), entity("c"))
        val collector = launch(Dispatchers.Unconfined) { viewModel.selection.collect { } }
        viewModel.enterSelection("a")
        viewModel.toggleSelected("c")
        withTimeout(30_000) { viewModel.selection.first { it == setOf("a", "c") } }
        coEvery { bulkRedo.prepare(any(), any(), any()) } returns batch

        viewModel.redoSelected(profile)

        awaitState { it is BulkRedoUiState.Summary }
        coVerify(exactly = 1) { bulkRedo.prepare(match { it.toSet() == setOf("a", "c") }, profile, any()) }
        collector.cancel()
    }
}
