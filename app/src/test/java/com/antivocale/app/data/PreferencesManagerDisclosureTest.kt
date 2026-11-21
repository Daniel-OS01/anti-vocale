package com.antivocale.app.data

import android.app.Application
import com.antivocale.app.testing.TempDataStoreRule
import com.antivocale.app.ui.components.DisclosureIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreferencesManagerDisclosureTest {

    @get:Rule
    val store = TempDataStoreRule("disclosure-state")

    @Test
    fun `tap writes round trip and last write wins per id`() = runTest {
        val settings = DisclosureIds.SETTINGS_ADVANCED
        val models = DisclosureIds.MODELS_ADVANCED
        assertEquals(emptyMap<String, Boolean>(), store.prefs.disclosureState.first())

        store.prefs.setDisclosure(settings, true)
        assertEquals(mapOf(settings to true), store.prefs.disclosureState.first())
        store.prefs.setDisclosure(models, true)
        store.prefs.setDisclosure(settings, false)

        assertEquals(setOf("$settings=0", "$models=1"), store.dataStore.data.first().asMap().values.single())
        val expected = mapOf(settings to false, models to true)
        assertEquals(expected, store.prefs.disclosureState.first())
        val reopened = PreferencesManagerImpl(store.context, store.dataStore).apply { initialize() }
        assertEquals(expected, reopened.cachedDisclosureState)
        assertEquals(expected, reopened.disclosureState.first())
    }

    @Test
    fun `only valid namespaced zero and one entries are decoded`() {
        val entries = setOf("settings.advanced=0", "models.advanced=1", "models.header=2", "bad=id=1")
        assertEquals(
            mapOf("settings.advanced" to false, "models.advanced" to true),
            decodeDisclosureState(entries),
        )
    }
}
