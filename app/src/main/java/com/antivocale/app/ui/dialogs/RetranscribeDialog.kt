package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.ui.components.transcriptionPickerFor
import com.antivocale.app.ui.viewmodel.LogsViewModel
import com.antivocale.app.util.LocaleManager

/** The single-row picker: backend only, no language step. */
@Composable
fun RetranscribeDialog(
    availableBackends: List<LogsViewModel.BackendOption>,
    onBackendSelected: (backendId: String) -> Unit,
    onDismiss: () -> Unit
) {
    RetranscribeDialog(
        availableBackends = availableBackends,
        onProfileSelected = { profile -> profile.backendId?.let(onBackendSelected) },
        onDismiss = onDismiss,
    )
}

/**
 * The model picker, with an optional language step. [rowCount] set = the
 * bulk redo ("Redo N recordings", plus the single-pass hint).
 * [offeredLanguages] set = after a backend is picked, a non-empty offered
 * set shows a language choice ("Model default" = null language first).
 */
@Composable
fun RetranscribeDialog(
    availableBackends: List<LogsViewModel.BackendOption>,
    onProfileSelected: (TranscriptionProfile) -> Unit,
    onDismiss: () -> Unit,
    rowCount: Int? = null,
    offeredLanguages: (suspend (backendId: String) -> Set<String>)? = null,
) {
    var selectedBackendId by remember { mutableStateOf<String?>(null) }
    // Null while loading (or when there is no language step).
    var languages by remember { mutableStateOf<Set<String>?>(null) }
    var languageStep by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedBackendId) {
        languages = null
        val id = selectedBackendId ?: return@LaunchedEffect
        if (offeredLanguages != null) languages = offeredLanguages(id)
    }
    val waitingForLanguages = offeredLanguages != null && selectedBackendId != null && languages == null
    val stepLanguages = languages

    if (languageStep && stepLanguages != null && stepLanguages.isNotEmpty()) {
        LanguageStepDialog(
            rowCount = rowCount,
            offered = stepLanguages,
            selectedLanguage = selectedLanguage,
            onLanguageSelected = { selectedLanguage = it },
            onConfirm = {
                selectedBackendId?.let { onProfileSelected(TranscriptionProfile(it, selectedLanguage)) }
            },
            onDismiss = onDismiss,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(retranscribeTitle(rowCount)) },
        text = {
            if (availableBackends.isEmpty()) {
                Text(
                    text = stringResource(R.string.retranscribe_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.retranscribe_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (rowCount != null) SinglePassHint()
                    Column(Modifier.selectableGroup()) {
                        availableBackends.forEach { backend ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedBackendId == backend.backendId,
                                        onClick = { selectedBackendId = backend.backendId }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedBackendId == backend.backendId,
                                    onClick = null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = backend.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                if (backend.isCurrentBackend) {
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = stringResource(R.string.retranscribe_active_badge),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = selectedBackendId
                    if (id != null) {
                        if (stepLanguages.isNullOrEmpty()) {
                            onProfileSelected(TranscriptionProfile(backendId = id))
                        } else {
                            selectedLanguage = null
                            languageStep = true
                        }
                    }
                },
                enabled = selectedBackendId != null && !waitingForLanguages
            ) {
                Text(stringResource(R.string.retranscribe_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun retranscribeTitle(rowCount: Int?): String =
    if (rowCount != null) pluralStringResource(R.plurals.bulk_redo_title, rowCount, rowCount)
    else stringResource(R.string.retranscribe_title)

@Composable
private fun SinglePassHint() {
    Text(
        text = stringResource(R.string.bulk_redo_single_pass_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The language step: "Model default" (null) first, then the offered codes by native name. */
@Composable
private fun LanguageStepDialog(
    rowCount: Int?,
    offered: Set<String>,
    selectedLanguage: String?,
    onLanguageSelected: (String?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The Settings picker's model (locale-collated native names); its
    // sentinels are dropped, "Model default" stands in for them here.
    val options = remember(offered) {
        transcriptionPickerFor(offered, LocaleManager.effectiveLocale()).options
            .filter { it.code in offered }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(retranscribeTitle(rowCount)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.transcription_language_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .selectableGroup()
                        .heightIn(max = 384.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    LanguageRow(
                        label = stringResource(R.string.bulk_redo_language_model_default),
                        selected = selectedLanguage == null,
                        onClick = { onLanguageSelected(null) },
                    )
                    options.forEach { option ->
                        LanguageRow(
                            label = option.displayName,
                            selected = selectedLanguage == option.code,
                            onClick = { onLanguageSelected(option.code) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.retranscribe_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
