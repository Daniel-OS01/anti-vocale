package com.antivocale.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.transcription.ScopeChain
import com.antivocale.app.ui.AppNavigation
import com.antivocale.app.ui.components.ProfileRuleSheet
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SubPageScrollScaffold
import com.antivocale.app.ui.viewmodel.EditedScope
import com.antivocale.app.ui.viewmodel.SettingsViewModel

/** Where a sender rule's key comes from; also the page's section order. */
internal enum class SenderRuleSource { WHATSAPP, WHATSAPP_BUSINESS, CALL }

/** One stored rule; [key] is the parsed [storageId] (its value is shown, never logged). */
internal data class SenderRule(
    val storageId: String,
    val key: SenderKey,
    val profile: TranscriptionProfile,
)

internal data class SenderRuleSection(val source: SenderRuleSource, val rules: List<SenderRule>)

/**
 * The stored sender rules grouped for the page: sections in
 * [SenderRuleSource] order, empty ones omitted, rows sorted by the key's
 * value. An id [SenderKey.parse] rejects is skipped (the page has no
 * section to show it under, and it never matches a sender anyway).
 */
internal fun groupSenderRules(map: Map<String, TranscriptionProfile>): List<SenderRuleSection> {
    val rules = map.mapNotNull { (id, profile) ->
        SenderKey.parse(id)?.let { SenderRule(id, it, profile) }
    }
    val bySource = rules.groupBy { rule ->
        when (val key = rule.key) {
            is SenderKey.WhatsApp ->
                if (key.packageName == PerAppPreferencesManager.WHATSAPP_BUSINESS) SenderRuleSource.WHATSAPP_BUSINESS
                else SenderRuleSource.WHATSAPP
            is SenderKey.Call -> SenderRuleSource.CALL
        }
    }
    return SenderRuleSource.entries.mapNotNull { source ->
        bySource[source]
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key.value })
            ?.let { SenderRuleSection(source, it) }
    }
}

/**
 * R6: the "Sender rules" subpage (the N>=2 subpage rule: one row per
 * sender). Rules are created from History's sender long-press; this page
 * reviews, edits and deletes them through the shared [ProfileRuleSheet].
 * Writes go through [SettingsViewModel.setSenderProfile].
 *
 * A row shows the key's value (a contact name or a phone number): it is
 * personal data, rendered here and never logged.
 *
 * SEARCH COMPATIBILITY CONTRACT: the main-tree hub's registry vocabulary
 * (SENDER_RULES_HUB) is the hub card's title and summary.
 */
@Composable
fun SenderRulesScreen(
    viewModel: SettingsViewModel,
    initialScroll: AppNavigation.ScrollRequest? = null,
    onScrollConsumed: () -> Unit = {},
    onOpenLanguageModels: () -> Unit,
    onBack: () -> Unit,
) {
    val profiles by viewModel.senderProfiles.collectAsStateWithLifecycle()
    val installed by viewModel.installedBackends.collectAsStateWithLifecycle()
    val codes by viewModel.ruleLanguageCodes.collectAsStateWithLifecycle()
    val outcome by viewModel.profileOutcome.collectAsStateWithLifecycle()
    val sections = remember(profiles) { groupSenderRules(profiles) }

    // null = no editor; otherwise the storage id of the rule being edited.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    SubPageScrollScaffold(
        titleRes = R.string.sender_rules_title,
        onBack = onBack,
        scrollRequest = initialScroll,
        onScrollConsumed = onScrollConsumed,
        bodySpacing = Arrangement.spacedBy(12.dp),
    ) { _ ->
        Text(
            text = stringResource(R.string.sender_rules_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sections.isEmpty()) {
            Text(
                text = stringResource(R.string.sender_rules_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sections.forEach { section ->
            SectionCard(
                icon = if (section.source == SenderRuleSource.CALL) Icons.Default.Call else Icons.Default.Person,
                title = stringResource(section.source.labelRes()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    section.rules.forEach { rule ->
                        RuleRow(
                            title = rule.key.value,
                            summary = profileSummary(rule.profile, installed),
                            notFound = false,
                            onEdit = { editingId = rule.storageId },
                            onDelete = { viewModel.setSenderProfile(rule.storageId, null) },
                        )
                    }
                }
            }
        }
    }

    editingId?.let { id ->
        val close = {
            editingId = null
            viewModel.clearProfilePreview()
        }
        ProfileRuleSheet(
            title = SenderKey.parse(id)?.value.orEmpty(),
            initial = profiles[id],
            installedBackends = installed,
            languageCodes = codes,
            outcome = outcome,
            onPreview = { edited -> viewModel.previewProfile(EditedScope.SENDER, ScopeChain(), edited) },
            onSave = { edited -> viewModel.setSenderProfile(id, edited) },
            onDismiss = close,
            onSetLanguageModel = { _ ->
                close()
                onOpenLanguageModels()
            },
        )
    }
}

private fun SenderRuleSource.labelRes(): Int = when (this) {
    SenderRuleSource.WHATSAPP -> R.string.voice_note_source_whatsapp
    SenderRuleSource.WHATSAPP_BUSINESS -> R.string.voice_note_source_whatsapp_business
    SenderRuleSource.CALL -> R.string.sender_rules_section_calls
}
