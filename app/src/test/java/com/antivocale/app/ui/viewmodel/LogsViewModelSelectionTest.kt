package com.antivocale.app.ui.viewmodel

import android.app.Application
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.transcription.staticRegistry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History multi-select: the selection prunes against the live rows, and the
 * bulk delete removes only terminal rows (an in-flight row the service is
 * still writing is skipped and counted).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LogsViewModelSelectionTest : ViewModelTeardownTest() {

    private lateinit var logDao: LogDao
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val rows = MutableStateFlow<List<LogEntity>>(emptyList())

    // A field (not the constructor default) so the harness cancels it.
    private val appScope = register(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private fun entity(id: String, status: String) = LogEntity(
        id = id,
        timestamp = 1_000L,
        taskId = "task-$id",
        type = "AUDIO",
        status = status,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        logDao = mockk(relaxed = true)
        every { logDao.getAll() } returns rows
        viewModel = register(LogsViewModel(mockk(relaxed = true), logDao, stubPreferencesManager(), staticRegistry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), orchestrator = mockk(relaxed = true), duplicateGuard = mockk(relaxed = true), scheduledFolderStore = mockk(relaxed = true), historyBulkRedo = mockk(relaxed = true, scopeProfileSource = mockk(relaxed = true)), scopeProfileStore = mockk(relaxed = true), profileOutcomeSource = mockk(relaxed = true), appScope = appScope))
    }

    // (deleted, skipped): the id is unique per delete, so tests compare counts.
    private fun LogsViewModel.BulkDeleteResult?.counts() = this?.let { it.deleted to it.skipped }

    @Test
    fun `two consecutive identical delete results publish two distinct values`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"), entity("b", "SUCCESS"))
        coEvery { logDao.deleteByIds(any()) } returns 1
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()

        // No consume in between: the snackbar is still showing the first one.
        // A StateFlow conflates, so a collector is not guaranteed to observe
        // both writes; the contract is that the second result is a DIFFERENT
        // value, which is what re-keys the snackbar effect.
        viewModel.enterSelection("a")
        advanceUntilIdle()
        viewModel.deleteSelected()
        advanceUntilIdle()
        val first = viewModel.bulkDeleteResult.value
        viewModel.enterSelection("b")
        advanceUntilIdle()
        viewModel.deleteSelected()
        advanceUntilIdle()
        val second = viewModel.bulkDeleteResult.value

        assertEquals(1 to 0, first?.let { it.deleted to it.skipped })
        assertEquals(1 to 0, second?.let { it.deleted to it.skipped })
        assertNotEquals(first, second)
    }

    @Test
    fun `deleteSelected deletes only the terminal rows and reports the skipped ones`() = runTest {
        rows.value = listOf(
            entity("ok", "SUCCESS"),
            entity("err", "ERROR"),
            entity("queued", "QUEUED"),
            entity("running", "PROCESSING"),
            entity("legacy", "PENDING"),
        )
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("ok")
        listOf("err", "queued", "running", "legacy").forEach(viewModel::toggleSelected)
        advanceUntilIdle()
        assertEquals(5, viewModel.selection.value.size)
        coEvery { logDao.deleteByIds(any()) } answers { firstArg<List<String>>().size }

        viewModel.deleteSelected()
        advanceUntilIdle()

        assertEquals(2 to 3, viewModel.bulkDeleteResult.value.counts())
        coVerify(exactly = 1) { logDao.deleteByIds(match { it.toSet() == setOf("ok", "err") }) }
        assertFalse(viewModel.selectionMode.value)
        assertTrue(viewModel.selection.value.isEmpty())
    }

    @Test
    fun `deleteSelected with only in-flight rows deletes nothing`() = runTest {
        rows.value = listOf(entity("running", "PROCESSING"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("running")
        advanceUntilIdle()

        viewModel.deleteSelected()
        advanceUntilIdle()

        assertEquals(0 to 1, viewModel.bulkDeleteResult.value.counts())
        coVerify(exactly = 0) { logDao.deleteByIds(any()) }
        viewModel.consumeBulkDeleteResult()
        assertEquals(null, viewModel.bulkDeleteResult.value)
    }

    @Test
    fun `deleteSelected reports the count the DAO deleted, not the selection size`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"), entity("b", "SUCCESS"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("a")
        viewModel.toggleSelected("b")
        advanceUntilIdle()
        // One of the two was already gone by the time the statement ran.
        coEvery { logDao.deleteByIds(any()) } returns 1

        viewModel.deleteSelected()
        advanceUntilIdle()

        assertEquals(1 to 0, viewModel.bulkDeleteResult.value.counts())
    }

    @Test
    fun `a search hit older than the newest rows stays selected and is deleted`() = runTest {
        // logs holds only the newest rows; the literal search reaches past them.
        rows.value = listOf(entity("new", "SUCCESS"))
        val hits = MutableStateFlow(listOf(entity("old", "SUCCESS")))
        // Both: searchAll is a default method delegating to searchAllRaw.
        every { logDao.searchAll(any()) } returns hits
        every { logDao.searchAllRaw(any()) } returns hits
        coEvery { logDao.deleteByIds(any()) } answers { firstArg<List<String>>().size }
        backgroundScope.launch { viewModel.selection.collect { } }
        viewModel.onSearchQueryChanged("old")
        advanceUntilIdle()
        assertEquals(listOf("old"), viewModel.filteredLogs.value.map { it.id })

        viewModel.enterSelection("old")
        advanceUntilIdle()
        assertEquals(setOf("old"), viewModel.selection.value)

        viewModel.deleteSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { logDao.deleteByIds(listOf("old")) }
        assertEquals(1 to 0, viewModel.bulkDeleteResult.value.counts())
    }

    @Test
    fun `selection prunes a row that leaves the list and keeps the mode`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"), entity("b", "SUCCESS"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("a")
        viewModel.toggleSelected("b")
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), viewModel.selection.value)

        rows.value = listOf(entity("a", "SUCCESS"))
        advanceUntilIdle()

        assertEquals(setOf("a"), viewModel.selection.value)
        assertTrue(viewModel.selectionMode.value)
    }

    @Test
    fun `new rows are never auto-selected and an emptied selection keeps the mode`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("a")
        rows.value = listOf(entity("new", "SUCCESS"), entity("a", "SUCCESS"))
        advanceUntilIdle()
        assertEquals(setOf("a"), viewModel.selection.value)

        viewModel.toggleSelected("a")
        advanceUntilIdle()

        assertTrue(viewModel.selection.value.isEmpty())
        assertTrue(viewModel.selectionMode.value)
    }

    @Test
    fun `the selection survives a search query change`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("a")
        viewModel.onSearchQueryChanged("something else")
        advanceUntilIdle()

        assertEquals(setOf("a"), viewModel.selection.value)
    }

    @Test
    fun `a header toggle selects then clears the group`() = runTest {
        rows.value = listOf(entity("a", "SUCCESS"), entity("b", "SUCCESS"), entity("c", "SUCCESS"))
        backgroundScope.launch { viewModel.selection.collect { } }
        advanceUntilIdle()
        viewModel.enterSelection("a")

        viewModel.toggleGroupSelected(listOf("a", "b"))
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), viewModel.selection.value)

        viewModel.toggleGroupSelected(listOf("a", "b"))
        advanceUntilIdle()
        assertEquals(emptySet<String>(), viewModel.selection.value)
    }
}
