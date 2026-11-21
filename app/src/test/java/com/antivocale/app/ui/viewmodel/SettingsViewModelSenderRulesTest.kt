package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ScopeProfileStore
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.TranscriptionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R6: the sender rules page's write and read seam. Writes land in the real
 * [ScopeProfileStore] over the fake prefs; the exposure is subscribed first
 * (a WhileSubscribed StateFlow returns its initial value until then).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSenderRulesTest : ViewModelTeardownTest() {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var viewModel: SettingsViewModel

    private val contactId = SenderKey.Call("+15550100").storageId()
    private val profile = TranscriptionProfile(language = "ru")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        harness = register(SettingsViewModelTestHelper())
        viewModel = harness.viewModel
    }

    @Test
    fun `setSenderProfile writes through the store and a null deletes`() = runTest {
        val store = ScopeProfileStore(harness.fakePrefs)
        val job = launch { viewModel.senderProfiles.collect {} }
        runCurrent()

        viewModel.setSenderProfile(contactId, profile)
        runCurrent()
        assertEquals(mapOf(contactId to profile), store.senderProfiles.first())
        assertEquals(mapOf(contactId to profile), viewModel.senderProfiles.value)

        viewModel.setSenderProfile(contactId, null)
        runCurrent()
        assertEquals(emptyMap<String, TranscriptionProfile>(), store.senderProfiles.first())
        assertEquals(emptyMap<String, TranscriptionProfile>(), viewModel.senderProfiles.value)
        job.cancel()
    }
}
