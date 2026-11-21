package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FakePreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R5: the two per-source voice-note model pins round-trip through the
 * ViewModel to the preference and back, independently of each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelVoiceNotePinTest : ViewModelTeardownTest() {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val harness = register(SettingsViewModelTestHelper())
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    @Test
    fun `each source pin is written to its own preference and exposed`() = runTest {
        viewModel.saveVoiceNoteBackendId("whisper")
        runCurrent()
        assertEquals("whisper", fakePrefs._voiceNoteBackendId.value)
        assertEquals("whisper", viewModel.voiceNoteBackendId.value)
        assertEquals("", fakePrefs._voiceNoteBusinessBackendId.value)

        viewModel.saveVoiceNoteBusinessBackendId("parakeet")
        runCurrent()
        assertEquals("parakeet", fakePrefs._voiceNoteBusinessBackendId.value)
        assertEquals("parakeet", viewModel.voiceNoteBusinessBackendId.value)
        assertEquals("whisper", viewModel.voiceNoteBackendId.value)
    }

    @Test
    fun `a stored pin is read back and blank clears it`() = runTest {
        fakePrefs._voiceNoteBackendId.value = "external:abc"
        runCurrent()
        assertEquals("external:abc", viewModel.voiceNoteBackendId.value)

        viewModel.saveVoiceNoteBackendId("")
        runCurrent()
        assertEquals("", fakePrefs._voiceNoteBackendId.value)
        assertEquals("", viewModel.voiceNoteBackendId.value)
    }
}
