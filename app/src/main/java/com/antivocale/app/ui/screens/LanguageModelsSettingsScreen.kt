package com.antivocale.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.ui.AppNavigation
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.components.SubPageScrollScaffold
import com.antivocale.app.ui.components.rememberStaleModelTitle
import com.antivocale.app.ui.components.transcriptionPickerFor
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.LocaleManager

/**
 * R6: the "Model per language" subpage (the N>=2 subpage rule: one row per
 * mapped language). A rule that sets a language without pinning a model
 * runs on the model chosen here. Writes go through
 * [SettingsViewModel.setLanguageModel] to the ScopeProfileStore map.
 *
 * SEARCH COMPATIBILITY CONTRACT: the main-tree hub's registry vocabulary
 * (LANGUAGE_MODELS_HUB) is the static union of this page's strings.
 */
@Composable
fun LanguageModelsSettingsScreen(
    viewModel: SettingsViewModel,
    initialScroll: AppNavigation.ScrollRequest? = null,
    onScrollConsumed: () -> Unit = {},
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
) {
    val rows by viewModel.languageModelRows.collectAsStateWithLifecycle()
    val codes by viewModel.ruleLanguageCodes.collectAsStateWithLifecycle()

    SubPageScrollScaffold(
        titleRes = R.string.language_models_title,
        onBack = onBack,
        scrollRequest = initialScroll,
        onScrollConsumed = onScrollConsumed,
        bodySpacing = Arrangement.spacedBy(12.dp),
    ) { _ ->
        Text(
            text = stringResource(R.string.language_models_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard(
            icon = Icons.Default.Translate,
            title = stringResource(R.string.language_models_title),
            description = stringResource(R.string.language_models_summary),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.language_models_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEach { row ->
                    LanguageModelRowItem(
                        row = row,
                        onSelect = { id -> viewModel.setLanguageModel(row.language, id) },
                        onRemove = {
                            if (row.backendId == null) viewModel.startLanguageModelDraft(null)
                            else viewModel.setLanguageModel(row.language, null)
                        },
                        onOpenModels = onOpenModels,
                    )
                }

                // Collated by native name, the auto sentinel dropped (a
                // map entry is always a concrete language).
                val addable = remember(codes, rows) {
                    val taken = rows.mapTo(HashSet()) { it.language }
                    transcriptionPickerFor(codes.filterNot { it in taken }.toSet(), LocaleManager.effectiveLocale())
                        .codes.filter { it != TranscriptionProfile.LANGUAGE_AUTO }
                }
                if (addable.isNotEmpty()) {
                    val addLabel = stringResource(R.string.language_models_add)
                    SettingsDropdown(
                        currentValue = "",
                        options = addable,
                        currentValueDisplay = addLabel,
                        optionDisplay = { LanguageNames.nativeLanguageName(it) },
                        onOptionSelected = { viewModel.startLanguageModelDraft(it) },
                        label = addLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageModelRowItem(
    row: SettingsViewModel.LanguageModelRow,
    onSelect: (String) -> Unit,
    onRemove: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val languageName = LanguageNames.nativeLanguageName(row.language)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = languageName, style = MaterialTheme.typography.labelLarge)
        if (row.candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.language_models_no_model, languageName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onOpenModels) { Text(stringResource(R.string.language_models_open_models)) }
        } else {
            val chooseLabel = stringResource(R.string.language_models_choose_model)
            val current = row.backendId.orEmpty()
            // A mapped model that is no longer installed (or no longer a
            // candidate) labels through its catalog family, never the raw id.
            val staleTitle = rememberStaleModelTitle(current) ?: chooseLabel
            fun labelFor(id: String) =
                if (id.isBlank()) chooseLabel
                else row.candidates.firstOrNull { it.backendId == id }?.displayName ?: staleTitle
            SettingsDropdown(
                currentValue = current,
                options = row.candidates.map { it.backendId },
                currentValueDisplay = labelFor(current),
                optionDisplay = { id -> labelFor(id) },
                onOptionSelected = onSelect,
                label = stringResource(R.string.language_models_model_label),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.language_models_remove)) }
    }
}
