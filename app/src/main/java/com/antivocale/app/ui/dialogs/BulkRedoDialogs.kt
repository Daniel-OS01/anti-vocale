package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.antivocale.app.R
import com.antivocale.app.service.PreparedBatch
import com.antivocale.app.service.SkipReason
import com.antivocale.app.ui.viewmodel.LogsViewModel.BulkRedoUiState
import com.antivocale.app.util.AudioDurationFormat

/**
 * The bulk redo's state dialogs: Preparing and Summary. Idle, Enqueuing and
 * Done render nothing here (Done is the tab's snackbar).
 */
@Composable
fun BulkRedoDialogs(
    state: BulkRedoUiState,
    onConfirm: (includeLong: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is BulkRedoUiState.Preparing -> BulkRedoPreparingDialog(state.done, state.total, onCancel)
        is BulkRedoUiState.Summary -> BulkRedoSummaryDialog(state.batch, state.modelDisplayName, onConfirm, onCancel)
        // Enqueuing is brief and not cancellable: no dialog of its own.
        BulkRedoUiState.Idle, is BulkRedoUiState.Enqueuing, is BulkRedoUiState.Done -> Unit
    }
}

/** Non-dismissable: only Cancel leaves it (a stray tap must not orphan the prepare). */
@Composable
private fun BulkRedoPreparingDialog(done: Int, total: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.bulk_redo_preparing, done, total)) },
        text = {
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.logs_cancel)) }
        },
    )
}

@Composable
private fun BulkRedoSummaryDialog(
    batch: PreparedBatch,
    modelDisplayName: String,
    onConfirm: (includeLong: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val ready = batch.items.size
    val considered = ready + batch.skipped.values.sum()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(pluralStringResource(R.plurals.bulk_redo_title, considered, considered)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ready > 0) {
                    Text(pluralStringResource(R.plurals.bulk_redo_summary_ready, ready, ready, modelDisplayName))
                }
                // SkipReason order: a stable reading order across batches.
                SkipReason.entries.forEach { reason ->
                    val count = batch.skipped[reason] ?: 0
                    if (count > 0) {
                        Text(
                            text = pluralStringResource(skipReasonPlural(reason), count, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (batch.longCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.bulk_redo_long,
                            batch.longCount,
                            batch.longCount,
                            AudioDurationFormat.format(batch.longestSeconds),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (ready == 0) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.model_info_close)) }
            } else {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = { onConfirm(true) }) {
                        Text(stringResource(R.string.bulk_redo_all))
                    }
                    // Only when it leaves something to run.
                    if (batch.longCount in 1 until ready) {
                        TextButton(onClick = { onConfirm(false) }) {
                            Text(stringResource(R.string.bulk_redo_skip_long))
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (ready > 0) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.logs_cancel)) }
            }
        },
    )
}

/** The human reason per skip; exhaustive so a new reason fails the build here. */
private fun skipReasonPlural(reason: SkipReason): Int = when (reason) {
    SkipReason.NOT_AUDIO -> R.plurals.bulk_redo_skip_not_audio
    SkipReason.IN_FLIGHT -> R.plurals.bulk_redo_skip_in_flight
    SkipReason.FILE_GONE -> R.plurals.bulk_redo_skip_file_gone
    SkipReason.FOLDER_UNAVAILABLE -> R.plurals.bulk_redo_skip_folder_unavailable
    SkipReason.NOT_FOUND_IN_FOLDER -> R.plurals.bulk_redo_skip_not_found_in_folder
    SkipReason.AMBIGUOUS -> R.plurals.bulk_redo_skip_ambiguous
}
