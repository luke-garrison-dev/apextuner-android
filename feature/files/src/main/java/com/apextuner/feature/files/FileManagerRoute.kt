package com.apextuner.feature.files

import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileManagerRoute(
    onBack: () -> Unit,
    viewModel: FileManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::grantTree)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_files)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!viewModel.navigateBack()) onBack() },
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.ui_back)) }
                },
                actions = {
                    IconButton(onClick = viewModel::cancelOperation) {
                        Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.ui_cancel_file_operation))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            FileManagerUiState.NoAccess -> NoAccess(
                modifier = Modifier.padding(padding),
                onGrant = { treePicker.launch(null) },
            )
            is FileManagerUiState.Loading -> {
                Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                    Text(stringResource(R.string.file_manager_loading_location, current.location?.displayName ?: stringResource(R.string.file_manager_granted_folders)))
                }
            }
            is FileManagerUiState.Error -> {
                Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { treePicker.launch(null) }) { Text(stringResource(R.string.ui_choose_folder)) }
                }
            }
            is FileManagerUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.location.displayName, style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.ui_all_operations_use_android_s_storage_access_framework_a),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = { treePicker.launch(null) }) { Text(stringResource(R.string.ui_choose_another_folder)) }
                                current.busyMessage?.let { Text(it) }
                                current.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                                current.transferSource?.let { source ->
                                    val transferAction = if (current.transferMove) {
                                        stringResource(R.string.file_transfer_move)
                                    } else {
                                        stringResource(R.string.file_transfer_copy)
                                    }
                                    Text(
                                        stringResource(R.string.file_transfer_staged, transferAction, source.displayName),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = viewModel::pasteHere) { Text(stringResource(R.string.ui_paste_here)) }
                                        OutlinedButton(onClick = viewModel::cancelTransfer) { Text(stringResource(R.string.ui_cancel_transfer)) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(255) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ui_name_archive_name)) },
                            singleLine = true,
                        )
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { viewModel.createFolder(name) }) { Text(stringResource(R.string.ui_new_folder)) }
                            OutlinedButton(onClick = { viewModel.renameSelected(name) }) { Text(stringResource(R.string.ui_rename)) }
                            OutlinedButton(onClick = { viewModel.zipSelected(name.ifBlank { "archive.zip" }) }) { Text(stringResource(R.string.ui_zip)) }
                        }
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = viewModel::stageCopy) { Text(stringResource(R.string.ui_copy)) }
                            OutlinedButton(onClick = viewModel::stageMove) { Text(stringResource(R.string.ui_move)) }
                            OutlinedButton(onClick = viewModel::extractSelected) { Text(stringResource(R.string.ui_extract_zip)) }
                        }
                    }
                    items(current.entries, key = { it.uri }) { node ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (node.isDirectory) viewModel.open(node) else viewModel.select(node)
                            },
                        ) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    if (node.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                )
                                Column {
                                    Text(node.displayName)
                                    Text(
                                        if (node.isDirectory) "Folder" else node.sizeBytes?.let(::formatBytes) ?: node.mimeType,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (current.selected?.uri == node.uri) {
                                        Text(stringResource(R.string.ui_selected), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoAccess(modifier: Modifier, onGrant: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_file_manager), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.ui_choose_a_folder_with_android_s_system_picker_apextuner))
        Button(onClick = onGrant) { Text(stringResource(R.string.ui_choose_folder)) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
