package com.apextuner.feature.contacts

import androidx.compose.ui.res.stringResource
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactMergeRoute(
    onBack: () -> Unit,
    viewModel: ContactMergeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingMerge by remember { mutableStateOf<ContactDuplicateCandidate?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        viewModel.onPermissionResult(
            grants[Manifest.permission.READ_CONTACTS] == true &&
                grants[Manifest.permission.WRITE_CONTACTS] == true,
        )
    }

    pendingMerge?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text(stringResource(R.string.ui_merge_these_contacts)) },
            text = {
                Text(
                    stringResource(
                        R.string.contacts_merge_confirmation_body,
                        candidate.first.displayName,
                        candidate.second.displayName,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingMerge = null
                        viewModel.merge(candidate)
                    },
                ) { Text(stringResource(R.string.ui_merge)) }
            },
            dismissButton = { TextButton(onClick = { pendingMerge = null }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_duplicate_contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.ui_back)) }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            ContactToolUiState.NeedsPermission -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.ui_contacts_access_is_used_only_while_this_tool_is_open_or))
                    Text(stringResource(R.string.ui_names_phone_numbers_and_email_addresses_remain_on_this))
                    Button(
                        onClick = {
                            permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                        },
                    ) { Text(stringResource(R.string.ui_grant_contacts_access)) }
                }
            }
            ContactToolUiState.Loading -> {
                Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) { Text(stringResource(R.string.ui_analyzing_contacts_on_device)) }
            }
            is ContactToolUiState.Error -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::scan) { Text(stringResource(R.string.ui_retry)) }
                }
            }
            is ContactToolUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.contacts_review_candidate_count, current.candidates.size), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.ui_similarity_is_only_a_review_heuristic_apextuner_never_a),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (current.undoAvailable) {
                                    OutlinedButton(onClick = viewModel::undoLastMerge) { Text(stringResource(R.string.ui_undo_last_merge)) }
                                }
                                if (current.undoBlockedByFailure) {
                                    OutlinedButton(onClick = viewModel::discardFailedUndo) {
                                        Text(stringResource(R.string.contacts_discard_failed_undo))
                                    }
                                    Text(
                                        stringResource(R.string.contacts_discard_failed_undo_explanation),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                current.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                    items(
                        current.candidates,
                        key = { "${it.first.contactId}:${it.second.contactId}" },
                    ) { candidate ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(candidate.first.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(candidate.second.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.contacts_similarity_summary, candidate.reason, candidate.score * 100.0),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { pendingMerge = candidate }) { Text(stringResource(R.string.ui_review_merge)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
