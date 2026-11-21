package com.antivocale.app.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.requireDisclosureId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

internal object DisclosureIds {
    const val SETTINGS_TRANSCRIPTION = "settings.transcription"
    const val SETTINGS_APPEARANCE = "settings.appearance"
    const val SETTINGS_ADVANCED = "settings.advanced"
    const val MODELS_HEADER = "models.header"
    const val MODELS_ADVANCED = "models.advanced"
    const val MODELS_IMPORT_FAMILY = "models.importFamily"
    const val MODELS_WIPED_IMPORTS = "models.wipedImports"
}

private class DisclosurePreferences(
    val saved: (String) -> Boolean?,
    val write: (String, Boolean) -> Unit,
)

private val LocalDisclosurePreferences = staticCompositionLocalOf<DisclosurePreferences?> { null }

@Composable
internal fun DisclosurePreferencesProvider(
    preferences: PreferencesManager,
    scope: CoroutineScope,
    content: @Composable () -> Unit,
) {
    val saved = preferences.disclosureState.collectAsState(initial = preferences.cachedDisclosureState)
    val disclosures = remember(preferences, saved, scope) {
        DisclosurePreferences(
            saved = { id -> saved.value[id] },
            write = { id, open ->
                scope.launch(Dispatchers.IO) {
                    try {
                        preferences.setDisclosure(id, open)
                    } catch (e: IOException) {
                        Log.w("DisclosurePreferences", "disclosure_write_failed id=$id", e)
                    }
                }
            },
        )
    }
    CompositionLocalProvider(LocalDisclosurePreferences provides disclosures) { content() }
}

internal data class DisclosureChange(val open: Boolean, val persist: Boolean)

internal fun disclosureChange(expanded: Boolean, userToggle: Boolean): DisclosureChange =
    if (userToggle) DisclosureChange(!expanded, persist = true)
    else DisclosureChange(open = true, persist = false)

internal class PersistedExpanded(
    private val saved: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    private var localOverride by mutableStateOf<Boolean?>(null)
    val expanded: Boolean get() = localOverride ?: saved()

    fun toggle() = applyChange(userToggle = true)
    fun forceOpen() = applyChange(userToggle = false)

    private fun applyChange(userToggle: Boolean) {
        val change = disclosureChange(expanded, userToggle)
        localOverride = change.open
        if (change.persist) write(change.open)
    }
}

@Composable
internal fun rememberPersistedExpanded(id: String, default: Boolean): PersistedExpanded {
    val preferences = LocalDisclosurePreferences.current
    return remember(id, default, preferences) {
        requireDisclosureId(id)
        PersistedExpanded(
            saved = { preferences?.saved?.invoke(id) ?: default },
            write = { open -> preferences?.write?.invoke(id, open) },
        )
    }
}
