package com.antivocale.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.data.InstalledBackend
import com.antivocale.app.data.SubfolderPaths
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.transcription.ScopeChain
import com.antivocale.app.ui.components.ProfileRuleSheet
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SubPageScrollScaffold
import com.antivocale.app.ui.components.rememberStaleModelTitle
import com.antivocale.app.ui.viewmodel.EditedScope
import com.antivocale.app.ui.viewmodel.FolderRulePaths
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.util.LanguageNames

/**
 * R6: one watched folder's transcription rules: the folder default and
 * its subfolder rules, each edited through the shared [ProfileRuleSheet].
 * Opened from the folder's row on the automation page; it is a nested
 * state of that page (keyed by tree uri), not an AppNavigation sub-page
 * key, because sub-page keys are literal and carry no parameter.
 */
@Composable
fun FolderTranscriptionRulesScreen(
    viewModel: SettingsViewModel,
    folder: WatchedFolder,
    folderLabel: String,
    onOpenLanguageModels: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val installed by viewModel.installedBackends.collectAsStateWithLifecycle()
    val codes by viewModel.ruleLanguageCodes.collectAsStateWithLifecycle()
    val outcome by viewModel.profileOutcome.collectAsStateWithLifecycle()
    val discovered = remember(folder.snapshot) { FolderRulePaths.discovered(folder.snapshot) }

    // null = no editor; "" = the folder default; a path = that subfolder rule.
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var addingRule by rememberSaveable { mutableStateOf(false) }

    SubPageScrollScaffold(
        titleRes = R.string.scope_rule_page_title,
        onBack = onBack,
        scrollRequest = null,
        bodySpacing = Arrangement.spacedBy(12.dp),
    ) { _ ->
        Text(text = folderLabel, style = MaterialTheme.typography.labelLarge)

        SectionCard(
            icon = Icons.Default.FolderOpen,
            title = stringResource(R.string.scope_rule_folder_default_title),
        ) {
            RuleRow(
                title = null,
                summary = profileSummary(folder.profile, installed),
                notFound = false,
                onEdit = { editingPath = "" },
                onDelete = null,
            )
        }

        SectionCard(
            icon = Icons.Default.Translate,
            title = stringResource(R.string.scope_rule_subfolders_title),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (folder.subfolderRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.scope_rule_subfolders_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                folder.subfolderRules.toSortedMap().forEach { (path, profile) ->
                    RuleRow(
                        title = path,
                        summary = profileSummary(profile, installed),
                        notFound = !FolderRulePaths.seenInLastScan(path, discovered),
                        onEdit = { editingPath = path },
                        onDelete = { viewModel.setSubfolderRule(folder.treeUri, path, null) },
                    )
                }
                OutlinedButton(onClick = { addingRule = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.scope_rule_add_subfolder))
                }
            }
        }

        Text(
            text = stringResource(R.string.scope_rule_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (addingRule) {
        AddSubfolderRuleDialog(
            discovered = discovered.filterNot { it in folder.subfolderRules },
            onConfirm = { path ->
                addingRule = false
                editingPath = path
            },
            onDismiss = { addingRule = false },
        )
    }

    editingPath?.let { path ->
        val isFolder = path.isEmpty()
        val close = {
            editingPath = null
            viewModel.clearProfilePreview()
        }
        ProfileRuleSheet(
            title = if (isFolder) stringResource(R.string.scope_rule_folder_default_title) else path,
            initial = if (isFolder) folder.profile else folder.subfolderRules[path],
            installedBackends = installed,
            languageCodes = codes,
            outcome = outcome,
            onPreview = { edited ->
                if (isFolder) {
                    viewModel.previewProfile(EditedScope.FOLDER, ScopeChain(), edited)
                } else {
                    // A subfolder rule sits over the folder default.
                    viewModel.previewProfile(EditedScope.SUBFOLDER, ScopeChain(folder = folder.profile), edited)
                }
            },
            onSave = { edited ->
                if (isFolder) viewModel.setFolderProfile(folder.treeUri, edited)
                else viewModel.setSubfolderRule(folder.treeUri, path, edited)
            },
            onDismiss = close,
            onSetLanguageModel = { _ ->
                close()
                onOpenLanguageModels()
            },
        )
    }
}

@Composable
private fun RuleRow(
    title: String?,
    summary: String,
    notFound: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onEdit)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            title?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notFound) {
                Text(
                    text = stringResource(R.string.scope_rule_not_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        onDelete?.let { delete ->
            TextButton(onClick = delete) { Text(stringResource(R.string.delete)) }
        }
    }
}

/** "Language: Русский · Model: GigaAM v3", or "No rule". */
@Composable
private fun profileSummary(profile: TranscriptionProfile?, installed: List<InstalledBackend>): String {
    if (profile == null || profile.isEmpty) return stringResource(R.string.scope_rule_no_rule)
    val language = profile.language?.let { code ->
        if (code == TranscriptionProfile.LANGUAGE_AUTO) stringResource(R.string.scope_rule_language_auto)
        else LanguageNames.nativeLanguageName(code)
    }
    val model = profile.backendId?.let { id ->
        installed.firstOrNull { it.backendId == id }?.displayName
            ?: rememberStaleModelTitle(id)
            ?: stringResource(R.string.scope_rule_model_unknown)
    }
    return when {
        language != null && model != null -> stringResource(R.string.scope_rule_summary_both, language, model)
        language != null -> stringResource(R.string.scope_rule_summary_language, language)
        else -> stringResource(R.string.scope_rule_summary_model, model.orEmpty())
    }
}

/**
 * Pick a subfolder the last scan saw, or type one; the path is stored in
 * the [SubfolderPaths.normalize] form (a root-only path is not a rule).
 */
@Composable
private fun AddSubfolderRuleDialog(
    discovered: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val normalized = SubfolderPaths.normalize(typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scope_rule_add_subfolder)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (discovered.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.scope_rule_choose_subfolder),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    discovered.forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onConfirm(path) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.scope_rule_type_path)) },
                    placeholder = { Text(stringResource(R.string.scope_rule_path_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { normalized?.let(onConfirm) }, enabled = normalized != null) {
                Text(stringResource(R.string.scope_rule_next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
