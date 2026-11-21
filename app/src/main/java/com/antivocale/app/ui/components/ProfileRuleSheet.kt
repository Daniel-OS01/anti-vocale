package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.InstalledBackend
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.ui.viewmodel.ProfileOutcomeUi
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.LocaleManager

/**
 * R6: the ONE editor for a single [TranscriptionProfile] (a watched
 * folder's default, a subfolder rule; sender rules and the History
 * long-press reuse it). Stateless about storage: the caller owns the
 * write and the outcome computation.
 *
 * @param title the dialog title (names the scope being edited).
 * @param initial the stored profile, or null when the scope has no rule.
 * @param installedBackends the "Advanced: pin a model" choices (the
 *   [com.antivocale.app.ui.viewmodel.SettingsViewModel.installedBackends] list).
 * @param languageCodes the concrete language codes the picker offers
 *   (lowercase; the language-model map lookup is exact). "No rule
 *   (inherit)" and "Auto-detect (don't pin)" are added here.
 * @param outcome the resolved preview for the current edit, computed by
 *   the caller from [onPreview]; null hides the line.
 * @param onPreview called with the edited profile (null = no rule) on
 *   open and on every edit, so the caller recomputes [outcome].
 * @param onSave called with the edited profile; null removes the rule.
 * @param onDismiss called on cancel and after [onSave].
 * @param onSetLanguageModel the warning's action ("Set a model for this
 *   language"), with the unsupported language code; null hides it.
 */
@Composable
fun ProfileRuleSheet(
    title: String,
    initial: TranscriptionProfile?,
    installedBackends: List<InstalledBackend>,
    languageCodes: List<String>,
    outcome: ProfileOutcomeUi?,
    onPreview: (TranscriptionProfile?) -> Unit,
    onSave: (TranscriptionProfile?) -> Unit,
    onDismiss: () -> Unit,
    onSetLanguageModel: ((String) -> Unit)? = null,
) {
    // "" = unset at this scope for both pickers (TranscriptionProfile.of
    // maps blank to null).
    var language by rememberSaveable { mutableStateOf(initial?.language.orEmpty()) }
    var backendId by rememberSaveable { mutableStateOf(initial?.backendId.orEmpty()) }
    var advancedOpen by rememberSaveable { mutableStateOf(!initial?.backendId.isNullOrBlank()) }

    val edited = TranscriptionProfile.of(backendId, language.lowercase()).takeUnless { it.isEmpty }
    LaunchedEffect(edited) { onPreview(edited) }

    val picker = remember(languageCodes) {
        transcriptionPickerFor(languageCodes.toSet(), LocaleManager.effectiveLocale())
    }
    val inheritLabel = stringResource(R.string.scope_rule_language_inherit)
    val autoLabels = mapOf(TranscriptionProfile.LANGUAGE_AUTO to R.string.scope_rule_language_auto)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                SettingsDropdown(
                    currentValue = language,
                    options = listOf("") + picker.codes,
                    currentValueDisplay = if (language.isBlank()) inheritLabel
                        else languageOptionLabel(language, autoLabels, picker.optionByCode),
                    optionDisplay = { code ->
                        if (code.isBlank()) inheritLabel
                        else languageOptionLabel(code, autoLabels, picker.optionByCode)
                    },
                    onOptionSelected = { language = it },
                    label = stringResource(R.string.scope_rule_language_label),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (advancedOpen) {
                    ModelPinDropdown(
                        pinned = backendId,
                        installed = installedBackends,
                        onSelected = { backendId = it },
                    )
                } else {
                    TextButton(onClick = { advancedOpen = true }) {
                        Text(stringResource(R.string.scope_rule_advanced_pin))
                    }
                }

                outcome?.let { ProfileOutcomeLine(it, onSetLanguageModel) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(edited)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null && !initial.isEmpty) {
                    TextButton(onClick = {
                        onSave(null)
                        onDismiss()
                    }) { Text(stringResource(R.string.scope_rule_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * The refine-pin dropdown pattern (SettingsTab's refinement-model card):
 * "" = use the language model or the default, and a stale pin (model
 * deleted after pinning) labels through its catalog family, never the raw id.
 */
@Composable
private fun ModelPinDropdown(
    pinned: String,
    installed: List<InstalledBackend>,
    onSelected: (String) -> Unit,
) {
    val defaultLabel = stringResource(R.string.scope_rule_model_default)
    val staleTitle = rememberStaleModelTitle(pinned) ?: defaultLabel
    fun labelFor(id: String) =
        if (id.isBlank()) defaultLabel
        else installed.firstOrNull { it.backendId == id }?.displayName ?: staleTitle
    SettingsDropdown(
        currentValue = pinned,
        options = listOf("") + installed.map { it.backendId },
        currentValueDisplay = labelFor(pinned),
        optionDisplay = { id -> labelFor(id) },
        onOptionSelected = onSelected,
        label = stringResource(R.string.scope_rule_advanced_pin),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The catalog family title for a backend id that is not installed, or null. */
@Composable
internal fun rememberStaleModelTitle(backendId: String): String? {
    val ui = remember(backendId) {
        if (backendId.isBlank()) null else runCatching { CatalogVariantUi.of(backendId) }.getOrNull()
    }
    return ui?.titleRes()
}

@Composable
private fun ProfileOutcomeLine(outcome: ProfileOutcomeUi, onSetLanguageModel: ((String) -> Unit)?) {
    val name = outcome.modelDisplayName
        ?: rememberStaleModelTitle(outcome.backendId)
        ?: stringResource(R.string.scope_rule_model_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (outcome.twoPassOff) stringResource(R.string.scope_rule_outcome_two_pass_off, name)
                else stringResource(R.string.scope_rule_outcome, name),
            style = MaterialTheme.typography.bodyMedium,
        )
        outcome.unsupportedLanguage?.let { code ->
            Text(
                text = stringResource(R.string.scope_rule_unsupported, LanguageNames.nativeLanguageName(code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onSetLanguageModel != null) {
                TextButton(onClick = { onSetLanguageModel(code) }) {
                    Text(stringResource(R.string.scope_rule_set_language_model))
                }
            }
        }
    }
}
