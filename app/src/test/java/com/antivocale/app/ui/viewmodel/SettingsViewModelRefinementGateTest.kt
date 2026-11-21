package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.SherpaModelManager
import io.mockk.coEvery
import io.mockk.mockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-932 (GH #176): the two-pass toggle's availability gate. ANY two
 * installed models pair (the streaming entry is no longer required); the
 * toggle unlocks when a fast arm resolves, which by the probe's exclusion
 * of the accurate side (the pin, else the selected backend) is always a
 * second model besides the refinement target. The resolution itself is the
 * shared owner's (SherpaModelManager.installedFastEntryId, covered by
 * ModelManagerCatalogTest); here it is stubbed so the gate's own logic is
 * isolated.
 *
 * The gate combine launches on the real Dispatchers.Default (the
 * TASK-603 F6 block it replaced launched there too), so the settled state
 * is polled with the bounded busy-wait the LitertLmImport suite
 * established. Every predicate names the state that proves the combine
 * actually ran (the gate's initial value is the same false/null a locked
 * gate reports).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelRefinementGateTest : ViewModelTeardownTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val externalRecords = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // TASK-945: the mock lives for the whole class, not per test body.
        // The ViewModel's combine coroutines hop to the real Default
        // dispatcher, so a refresh can still be in flight when a test
        // returns; unmocking inside the body let that late call hit the
        // real installedFastEntryId on fake /models paths, and the uncaught
        // exception was attributed to the NEXT test (the shifting
        // UncaughtExceptionsBeforeTest victims on CI).
        mockkObject(SherpaModelManager.Companion)
        harness = register(SettingsViewModelTestHelper(externalRecords = externalRecords))
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    /** Installs two built-in models through the saved-path preference. */
    private fun installTwoBackends() {
        fakePrefs._sherpaModelPath("sherpa-onnx").value = "/models/parakeet"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
    }

    /**
     * Polls until [settled] holds (30s cap - the CI-runner-slow ceiling the
     * flake family documents; a 5s cap flaked on run 44825349290 on this
     * exact line - then the caller's assertion names the failure): the
     * combine hops to the real Default dispatcher, which the virtual-time
     * scheduler cannot drain.
     */
    private fun TestScope.awaitGate(settled: () -> Boolean) {
        runCurrent()
        val deadline = System.currentTimeMillis() + 30_000
        while (!settled() && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(20)
        }
        // A timeout must fail here: a caller asserting only part of the
        // predicate would otherwise pass on a half-published state.
        assertTrue("gate did not settle within 30s", settled())
    }

    /**
     * Waits (bounded, via [awaitGate]) until the fast arm, the refinement
     * target AND availability all hold their expected values, so the
     * assertions that follow never race the collector's later writes on
     * Dispatchers.Default.
     */
    private fun TestScope.awaitGateState(
        fast: String?,
        target: String?,
        available: Boolean,
        previewChoice: Boolean? = null,
    ) = awaitGate {
        viewModel.fastBackendId.value == fast &&
            viewModel.refinementTargetBackendId.value == target &&
            viewModel.refinementAvailable.value == available &&
            (previewChoice == null || viewModel.previewChoiceNeeded.value == previewChoice)
    }

    @Test
    fun `two installed backends with a distinct fast arm unlock the toggle`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGateState(fast = "whisper", target = "sherpa-onnx", available = true)
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("whisper", viewModel.fastBackendId.value)
    }

    @Test
    fun `a single installed backend keeps the toggle locked`() = runTest {
        // The only install IS the accurate side, so the exclusion-aware
        // probe finds nothing left and answers null (the real manager's
        // behavior for this shape).
        coEvery { SherpaModelManager.installedFastEntryId(any(), "whisper") } returns null
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
        fakePrefs._transcriptionBackend.value = "whisper"
        // The target is published last: waiting for it proves the whisper
        // emission landed, not the VM's initial locked state.
        awaitGateState(fast = null, target = "whisper", available = false)
        assertFalse(viewModel.refinementAvailable.value)
    }

    @Test
    fun `gh 176 - the selected backend is excluded from the fast arm so two installed models unlock the toggle`() = runTest {
        // The probe answers ONLY the exclusion of the selected backend:
        // the gate must pass the accurate side, and with parakeet
        // selected plus whisper installed the arm is whisper (the
        // pre-fix gate compared fast != selected instead and locked).
        coEvery {
            SherpaModelManager.installedFastEntryId(any(), "sherpa-onnx")
        } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGateState(fast = "whisper", target = "sherpa-onnx", available = true)
        assertTrue(viewModel.refinementAvailable.value)
    }

    @Test
    fun `streaming selected still unlocks and the fast arm resolves to the smallest other install`() = runTest {
        coEvery {
            SherpaModelManager.installedFastEntryId(any(), "nemotron-streaming")
        } returns "whisper"
        installTwoBackends()
        // The selected streaming model is the accurate side, so it must be
        // installed too (an uninstalled accurate side locks the gate).
        fakePrefs._sherpaModelPath("nemotron-streaming").value = "/models/nemotron"
        fakePrefs._transcriptionBackend.value = "nemotron-streaming"
        awaitGateState(fast = "whisper", target = "nemotron-streaming", available = true)
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("whisper", viewModel.fastBackendId.value)
    }

    @Test
    fun `pinning the only other model keeps the gate consistent with the runtime`() = runTest {
        // The pin becomes the accurate side, so the exclusion is the
        // PIN (not the selection): the probe excludes whisper and the
        // smallest other install, parakeet, is the fast arm.
        coEvery { SherpaModelManager.installedFastEntryId(any(), "whisper") } returns
            "sherpa-onnx"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        fakePrefs._refinementModelBackendId.value = "whisper"
        awaitGateState(fast = "sherpa-onnx", target = "whisper", available = true)
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("sherpa-onnx", viewModel.fastBackendId.value)
    }

    @Test
    fun `a stale pin keeps the toggle locked even when a fast arm resolves`() = runTest {
        // The pin names a model that was deleted after pinning: the probe
        // still finds a fast arm besides it, but the accurate side itself
        // is unusable and every run would degrade to SKIP_REFINE_LOAD_FAILED,
        // so the gate locks on the pin, not on the arm.
        coEvery { SherpaModelManager.installedFastEntryId(any(), "gigaam") } returns
            "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._refinementModelBackendId.value = "gigaam"
        awaitGateState(fast = "whisper", target = "gigaam", available = false)
        assertEquals("whisper", viewModel.fastBackendId.value)
        assertFalse(viewModel.refinementAvailable.value)
    }

    /** A loadable external record (the provider's snapshot is the valid set). */
    private fun external(
        id: String,
        family: ModelFamily,
        streaming: Boolean = false,
    ) = ExternalModelRecord(
        id = id,
        displayName = "Imported $id",
        dir = "/models/external/$id",
        family = family,
        modelType = if (family == ModelFamily.TRANSDUCER) "zipformer2" else "",
        languages = emptyList(),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
        streaming = streaming,
    )

    private val importedStreaming = external("zip-stream", ModelFamily.TRANSDUCER, streaming = true)
    private val importedWhisper = external("whisper-big", ModelFamily.WHISPER)

    @Test
    fun `only imported models - a streaming transducer and a pinned whisper unlock two-pass`() = runTest {
        // No built-in model is installed: the built-in probe finds nothing.
        // The fast arm must come from the imports (the streaming transducer,
        // which ExternalSherpaBackend runs in batch mode), so the toggle is
        // available with zero configuration.
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedStreaming, importedWhisper)
        fakePrefs._transcriptionBackend.value = importedStreaming.backendId
        fakePrefs._refinementModelBackendId.value = importedWhisper.backendId
        awaitGateState(
            fast = importedStreaming.backendId,
            target = importedWhisper.backendId,
            available = true,
        )
        assertEquals(importedWhisper.backendId, viewModel.refinementTargetBackendId.value)
        assertEquals(importedStreaming.backendId, viewModel.fastBackendId.value)
        assertTrue(viewModel.refinementAvailable.value)
    }

    @Test
    fun `only imported models - a selected whisper import pairs with the streaming import`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedStreaming, importedWhisper)
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        awaitGateState(
            fast = importedStreaming.backendId,
            target = importedWhisper.backendId,
            available = true,
        )
        assertEquals(importedStreaming.backendId, viewModel.fastBackendId.value)
        assertTrue(viewModel.refinementAvailable.value)
    }

    private val importedCtc = external("ctc-small", ModelFamily.CTC)

    @Test
    fun `an imported preview pin becomes the fast arm`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedCtc, importedWhisper)
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        fakePrefs._previewModelBackendId.value = importedCtc.backendId
        awaitGateState(
            fast = importedCtc.backendId,
            target = importedWhisper.backendId,
            available = true,
            previewChoice = false,
        )
        assertEquals(importedCtc.backendId, viewModel.fastBackendId.value)
        assertTrue(viewModel.refinementAvailable.value)
        assertFalse(viewModel.previewChoiceNeeded.value)
    }

    @Test
    fun `a stale preview pin falls through to the automatic streaming import`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedStreaming, importedWhisper)
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        fakePrefs._previewModelBackendId.value = "external:deleted-model"
        awaitGateState(
            fast = importedStreaming.backendId,
            target = importedWhisper.backendId,
            available = true,
        )
        assertEquals(importedStreaming.backendId, viewModel.fastBackendId.value)
        assertTrue(viewModel.refinementAvailable.value)
    }

    @Test
    fun `no automatic arm but another install asks for a preview choice`() = runTest {
        // Neither import streams, so automatic finds nothing: the toggle
        // stays locked, and the picker offers the other import.
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedCtc, importedWhisper)
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        awaitGate { viewModel.previewChoiceNeeded.value }
        assertTrue(viewModel.previewChoiceNeeded.value)
        assertFalse(viewModel.refinementAvailable.value)
        assertNull(viewModel.fastBackendId.value)
        assertEquals(listOf(importedCtc.backendId),
            viewModel.previewCandidates.value.map { it.backendId })
    }

    @Test
    fun `losing the fast arm re-locks the toggle and publishes null`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGateState(fast = "whisper", target = "sherpa-onnx", available = true)
        assertTrue(viewModel.refinementAvailable.value)

        // The uninstall shape: the resolution goes away, a backend
        // switch forces the next combine emission, and the gate locks.
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        fakePrefs._transcriptionBackend.value = "qwen3-asr"
        awaitGateState(fast = null, target = "qwen3-asr", available = false)
        assertFalse(viewModel.refinementAvailable.value)
        assertNull(viewModel.fastBackendId.value)
    }

    @Test
    fun `an uninstalled default accurate side with one offline import keeps the original locked reason`() = runTest {
        // Parakeet (the default) is selected but NOT installed, the refine
        // pin is blank, and the only model is one offline Whisper import:
        // automatic finds no fast arm, and phase two (Parakeet) could never
        // run, so the gate stays locked with the plain locked reason rather
        // than prompting for a preview pick.
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedWhisper)
        // The target is published last; switching it through the import and
        // back to Parakeet makes seeing "sherpa-onnx" prove that the final
        // emission (not the VM's initial one) fully landed.
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        awaitGateState(fast = null, target = importedWhisper.backendId, available = false)
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGateState(fast = null, target = "sherpa-onnx", available = false, previewChoice = false)
        assertEquals("sherpa-onnx", viewModel.refinementTargetBackendId.value)
        assertFalse(viewModel.previewChoiceNeeded.value)
        assertFalse(viewModel.refinementAvailable.value)
        assertNull(viewModel.fastBackendId.value)
    }

    @Test
    fun `picking the import as preview does not unlock an uninstalled default accurate side`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        externalRecords.value = listOf(importedWhisper)
        // While the import is selected the pin equals the accurate side and
        // is ignored; switching to the uninstalled Parakeet makes the pin
        // the fast arm in the same (final) emission as the new target.
        fakePrefs._transcriptionBackend.value = importedWhisper.backendId
        fakePrefs._previewModelBackendId.value = importedWhisper.backendId
        awaitGateState(fast = null, target = importedWhisper.backendId, available = false)
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGateState(
            fast = importedWhisper.backendId,
            target = "sherpa-onnx",
            available = false,
            previewChoice = false,
        )
        assertEquals(importedWhisper.backendId, viewModel.fastBackendId.value)
        assertEquals("sherpa-onnx", viewModel.refinementTargetBackendId.value)
        assertFalse(viewModel.refinementAvailable.value)
        assertFalse(viewModel.previewChoiceNeeded.value)
    }
}
