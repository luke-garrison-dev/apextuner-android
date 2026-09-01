package com.apextuner.feature.cleaner

import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.cleaner.data.MediaRemovalMode
import com.apextuner.feature.cleaner.model.BlurryPhotoCandidate
import com.apextuner.feature.cleaner.model.CategoryUsage
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerAccessState
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.CleanerScanProgress
import com.apextuner.feature.cleaner.model.CleanerScanResult
import com.apextuner.feature.cleaner.model.CleanerSmartReviewSummary
import com.apextuner.feature.cleaner.model.smartReviewSummary
import com.apextuner.feature.cleaner.model.CleanerUiState
import com.apextuner.feature.cleaner.model.DuplicateGroup
import com.apextuner.feature.cleaner.model.DuplicateKeepStrategy
import com.apextuner.feature.cleaner.model.NearDuplicateGroup
import com.apextuner.feature.cleaner.model.PhotoReviewGroup
import com.apextuner.feature.cleaner.model.PhotoReviewGroupType
import com.apextuner.feature.cleaner.model.MediaReencodeEstimate
import com.apextuner.feature.cleaner.model.MediaReencodeMode
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import com.apextuner.feature.cleaner.model.MediaReencodeReview
import com.apextuner.feature.cleaner.model.PersistedAccess
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest

private enum class PermissionRationale { VisualMedia, Audio, LegacyWrite }

@Composable
fun OptimizeRoute(
    premiumEnabled: Boolean = false,
    onUpgrade: () -> Unit = {},
    autoStartScanToken: Long? = null,
    onAutoStartConsumed: () -> Unit = {},
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionRationale by remember { mutableStateOf<PermissionRationale?>(null) }

    val cleanerReady = state is CleanerUiState.Ready
    LaunchedEffect(autoStartScanToken, cleanerReady) {
        // Quick Settings can cold-launch this destination before refreshAccess() has completed.
        // Starting while the UI is still Loading creates a race where a fast/empty scan can
        // finish before Ready exists, dropping its progress/result through updateReady(). Queue
        // the one-shot request until access state is established, then consume it exactly once.
        if (autoStartScanToken != null && cleanerReady) {
            viewModel.startScan()
            onAutoStartConsumed()
        }
    }

    val visualPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess("Photo and video access updated.") }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess("Audio access updated.") }
    val legacyWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshAccess("Legacy media deletion access updated.") }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.persistTree(uri)
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.persistDocuments(uris)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        viewModel.persistDocuments(uris)
    }
    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshAccess("Usage access updated.")
    }
    val removalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onMediaRemovalResult(result.resultCode == Activity.RESULT_OK)
    }
    val mediaWriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onMediaWriteResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is CleanerEffect.LaunchMediaRemoval -> removalLauncher.launch(
                    IntentSenderRequest.Builder(effect.pendingIntent.intentSender).build(),
                )
                is CleanerEffect.LaunchMediaWrite -> mediaWriteLauncher.launch(
                    IntentSenderRequest.Builder(effect.pendingIntent.intentSender).build(),
                )
            }
        }
    }

    permissionRationale?.let { rationale ->
        val title = when (rationale) {
            PermissionRationale.VisualMedia -> "Photo & video library access"
            PermissionRationale.Audio -> "Music & audio access"
            PermissionRationale.LegacyWrite -> "Legacy media deletion access"
        }
        val explanation = when (rationale) {
            PermissionRationale.VisualMedia ->
                "ApexTuner uses this optional permission only for bulk on-device storage analysis such as exact duplicates and large media. Nothing is uploaded. You can decline and use Select photos/videos or Add files instead; Android may also offer selected-media access rather than the full library."
            PermissionRationale.Audio ->
                "ApexTuner uses this optional permission only to include shared music and audio in on-device storage analysis. Nothing is uploaded. You can decline and grant individual files instead."
            PermissionRationale.LegacyWrite ->
                "Android 8–9 require this separate legacy permission only when you choose to delete shared media. Scanning needs read access only. ApexTuner does not request this permission on Android 10 or newer."
        }
        AlertDialog(
            onDismissRequest = { permissionRationale = null },
            title = { Text(title) },
            text = { Text(explanation, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                Button(
                    onClick = {
                        val request = permissionRationale
                        permissionRationale = null
                        when (request) {
                            PermissionRationale.VisualMedia -> visualPermissionsLauncher.launch(requiredVisualPermissions())
                            PermissionRationale.Audio -> audioPermissionLauncher.launch(requiredAudioPermissions())
                            PermissionRationale.LegacyWrite -> legacyWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            null -> Unit
                        }
                    },
                ) { Text(stringResource(R.string.ui_continue)) }
            },
            dismissButton = { TextButton(onClick = { permissionRationale = null }) { Text(stringResource(R.string.ui_not_now)) } },
        )
    }

    CleanerScreen(
        state = state,
        onRequestVisualAccess = { permissionRationale = PermissionRationale.VisualMedia },
        onRequestAudioAccess = { permissionRationale = PermissionRationale.Audio },
        onRequestLegacyWrite = { permissionRationale = PermissionRationale.LegacyWrite },
        onAddFolder = { treeLauncher.launch(null) },
        onAddFiles = { filesLauncher.launch(arrayOf("*/*")) },
        onPickPhotos = {
            try {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(
                        mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                )
            } catch (_: RuntimeException) {
                // AndroidX normally falls back to ACTION_OPEN_DOCUMENT automatically. Keep a
                // second, explicit SAF path for malformed OEM picker implementations or missing
                // handlers so this user action never terminates ApexTuner.
                try {
                    filesLauncher.launch(arrayOf("image/*", "video/*"))
                } catch (_: RuntimeException) {
                    viewModel.reportAccessError(
                        "Android could not open a photo/video picker on this device. Try Add files instead.",
                    )
                }
            }
        },
        onOpenUsageAccess = {
            val usageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            try {
                usageAccessLauncher.launch(usageIntent)
            } catch (_: RuntimeException) {
                try {
                    usageAccessLauncher.launch(Intent(Settings.ACTION_SETTINGS))
                } catch (_: RuntimeException) {
                    viewModel.reportAccessError("Android could not open Usage Access settings on this device.")
                }
            }
        },
        onReleaseAccess = viewModel::releasePersistedAccess,
        onScan = viewModel::startScan,
        onCancelScan = viewModel::cancelScan,
        premiumEnabled = premiumEnabled,
        onUpgrade = onUpgrade,
        onFindDuplicates = if (premiumEnabled) viewModel::findDuplicates else onUpgrade,
        onToggleSelection = viewModel::toggleSelection,
        onSelectDuplicateRedundant = viewModel::selectDuplicateRedundant,
        onSelectAllJunk = viewModel::selectAllSuspectedJunk,
        onClearSelection = viewModel::clearSelection,
        onRemove = viewModel::beginRemoval,
        onReviewReencode = if (premiumEnabled) viewModel::reviewReencode else { _ -> onUpgrade() },
        onUpdateReencodePreset = viewModel::updateReencodePreset,
        onDismissReencodeReview = viewModel::dismissReencodeReview,
        onReencode = viewModel::beginReencode,
        onCancelReencode = viewModel::cancelReencode,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun CleanerScreen(
    state: CleanerUiState,
    premiumEnabled: Boolean,
    onUpgrade: () -> Unit,
    onRequestVisualAccess: () -> Unit,
    onRequestAudioAccess: () -> Unit,
    onRequestLegacyWrite: () -> Unit,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onReleaseAccess: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onFindDuplicates: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectDuplicateRedundant: (String, DuplicateKeepStrategy) -> Unit,
    onSelectAllJunk: () -> Unit,
    onClearSelection: () -> Unit,
    onRemove: (MediaRemovalMode) -> Unit,
    onReviewReencode: (String) -> Unit,
    onUpdateReencodePreset: (MediaReencodePreset) -> Unit,
    onDismissReencodeReview: () -> Unit,
    onReencode: (MediaReencodeMode) -> Unit,
    onCancelReencode: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state) {
            CleanerUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is CleanerUiState.Ready -> {
                CleanerContent(
                    state = state,
                    onRequestVisualAccess = onRequestVisualAccess,
                    onRequestAudioAccess = onRequestAudioAccess,
                    onRequestLegacyWrite = onRequestLegacyWrite,
                    onAddFolder = onAddFolder,
                    onAddFiles = onAddFiles,
                    onPickPhotos = onPickPhotos,
                    onOpenUsageAccess = onOpenUsageAccess,
                    onReleaseAccess = onReleaseAccess,
                    onScan = onScan,
                    onCancelScan = onCancelScan,
                    premiumEnabled = premiumEnabled,
                    onUpgrade = onUpgrade,
                    onFindDuplicates = onFindDuplicates,
                    onToggleSelection = onToggleSelection,
                    onSelectDuplicateRedundant = onSelectDuplicateRedundant,
                    onSelectAllJunk = onSelectAllJunk,
                    onClearSelection = onClearSelection,
                    onRemove = onRemove,
                    onReviewReencode = onReviewReencode,
                    onUpdateReencodePreset = onUpdateReencodePreset,
                    onDismissReencodeReview = onDismissReencodeReview,
                    onReencode = onReencode,
                    onCancelReencode = onCancelReencode,
                    onDismissMessage = onDismissMessage,
                )
                if (state.isRemoving) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.ui_applying_removal)) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text(stringResource(R.string.ui_android_is_applying_the_confirmed_operation_only_to_the))
                            }
                        },
                        confirmButton = {},
                    )
                }
                state.reencodeProgress?.let { progress ->
                    ReencodeProgressDialog(
                        progress = progress,
                        onCancel = onCancelReencode,
                    )
                }
            }
        }
    }
}

private enum class CleanerView(val label: String) {
    Overview("Overview"),
    Duplicates("Exact duplicates"),
    SimilarPhotos("Similar photos"),
    BlurryPhotos("Blurry photos"),
    Large("Large"),
    Review("Review"),
    All("All"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CleanerContent(
    state: CleanerUiState.Ready,
    premiumEnabled: Boolean,
    onUpgrade: () -> Unit,
    onRequestVisualAccess: () -> Unit,
    onRequestAudioAccess: () -> Unit,
    onRequestLegacyWrite: () -> Unit,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onReleaseAccess: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onFindDuplicates: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectDuplicateRedundant: (String, DuplicateKeepStrategy) -> Unit,
    onSelectAllJunk: () -> Unit,
    onClearSelection: () -> Unit,
    onRemove: (MediaRemovalMode) -> Unit,
    onReviewReencode: (String) -> Unit,
    onUpdateReencodePreset: (MediaReencodePreset) -> Unit,
    onDismissReencodeReview: () -> Unit,
    onReencode: (MediaReencodeMode) -> Unit,
    onCancelReencode: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var view by remember { mutableStateOf(CleanerView.Overview) }
    var showRemovalDialog by remember { mutableStateOf(false) }
    val selectedItems = state.scan?.items.orEmpty().filter { it.key in state.selectedKeys }

    if (showRemovalDialog && selectedItems.isNotEmpty()) {
        RemovalReviewDialog(
            selectedItems = selectedItems,
            onDismiss = { showRemovalDialog = false },
            onConfirm = { mode ->
                showRemovalDialog = false
                onRemove(mode)
            },
        )
    }

    var showReplaceConfirmation by remember { mutableStateOf(false) }
    val reencodeReview = state.reencodeReview
    val reencodeItem = reencodeReview?.let { review ->
        state.scan?.largeFiles?.firstOrNull { it.key == review.itemKey }
    }
    if (reencodeReview != null && reencodeItem != null && !showReplaceConfirmation) {
        MediaReencodeReviewDialog(
            item = reencodeItem,
            review = reencodeReview,
            onPresetChange = onUpdateReencodePreset,
            onDismiss = onDismissReencodeReview,
            onConfirm = { mode ->
                if (mode == MediaReencodeMode.ReplaceOriginal) {
                    showReplaceConfirmation = true
                } else {
                    onReencode(mode)
                }
            },
        )
    }
    if (showReplaceConfirmation && reencodeReview != null && reencodeItem != null) {
        ReplaceOriginalConfirmationDialog(
            item = reencodeItem,
            estimate = reencodeReview.estimate,
            onDismiss = { showReplaceConfirmation = false },
            onConfirm = {
                showReplaceConfirmation = false
                onReencode(MediaReencodeMode.ReplaceOriginal)
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
                    ) {
                        Icon(
                            Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(9.dp).size(24.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.ui_optimize), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ui_a_storage_cleaner_that_only_acts_on_content_android_act),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.errorMessage != null || state.infoMessage != null) {
            item {
                MessageCard(
                    text = state.errorMessage ?: state.infoMessage.orEmpty(),
                    isError = state.errorMessage != null,
                    onDismiss = onDismissMessage,
                )
            }
        }

        item {
            StorageAccessCard(
                access = state.access,
                persisted = state.persistedAccess,
                enabled = !state.isBusy,
                onRequestVisualAccess = onRequestVisualAccess,
                onRequestAudioAccess = onRequestAudioAccess,
                onRequestLegacyWrite = onRequestLegacyWrite,
                onAddFolder = onAddFolder,
                onAddFiles = onAddFiles,
                onPickPhotos = onPickPhotos,
                onOpenUsageAccess = onOpenUsageAccess,
                onReleaseAccess = onReleaseAccess,
            )
        }

        item {
            ScanCard(
                access = state.access,
                progress = state.scanProgress,
                scan = state.scan,
                enabled = !state.isBusy,
                onScan = onScan,
                onCancel = onCancelScan,
            )
        }

        state.scan?.let { scan ->
            item { SummaryCard(scan) }
            item {
                SmartReviewSummaryCard(
                    summary = scan.smartReviewSummary(),
                    onExactDuplicates = { view = CleanerView.Duplicates },
                    onPhotoReview = { view = CleanerView.SimilarPhotos },
                    onLargeFiles = { view = CleanerView.Large },
                    onReviewCandidates = { view = CleanerView.Review },
                )
            }
            if (scan.truncated || state.access.limitedVisualAccess) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                            Column {
                                if (state.access.limitedVisualAccess) {
                                    Text(stringResource(R.string.ui_selected_photos_access_is_active), fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.ui_only_the_photos_and_videos_android_currently_exposes_to),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (scan.truncated) {
                                    Text(stringResource(R.string.ui_safety_scan_limit_reached), fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.ui_the_scan_stopped_at_the_bounded_item_limit_to_protect_m),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CleanerView.entries.forEach { candidate ->
                        FilterChip(
                            selected = view == candidate,
                            onClick = { view = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }

            when (view) {
                CleanerView.Overview -> {
                    item { CategoryBreakdown(scan.categoryUsage, scan.totalAccessibleBytes) }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.ContentCopy,
                            title = stringResource(R.string.ui_exact_duplicates),
                            value = if (!scan.duplicateAnalysisCompleted) "Not analyzed" else "${scan.duplicateGroups.size} groups",
                            description = "Two-stage streaming SHA-256 verifies byte-for-byte equality before any copy is suggested for removal.",
                            action = if (state.isScanning) null else if (premiumEnabled) "Find duplicates" else "Unlock Premium",
                            onAction = if (premiumEnabled) onFindDuplicates else onUpgrade,
                        )
                    }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.PhotoLibrary,
                            title = stringResource(R.string.ui_near_duplicate_photos),
                            value = if (!scan.nearDuplicateAnalysisCompleted) "Not analyzed" else "${scan.nearDuplicateGroups.size} groups",
                            description = "Conservative 64-bit perceptual dHash groups visually similar accessible photos. Results stay separate from exact duplicates and are manual-review only.",
                            action = if (state.isScanning) null else if (premiumEnabled) "Find similar photos" else "Unlock Premium",
                            onAction = if (premiumEnabled) onFindDuplicates else onUpgrade,
                        )
                    }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.PhotoLibrary,
                            title = stringResource(R.string.ui_blurry_low_quality_photos),
                            value = if (!scan.blurryPhotoAnalysisCompleted) "Not analyzed" else "${scan.blurryPhotos.size} candidates",
                            description = "A conservative Laplacian-variance sharpness heuristic flags potentially blurry photos for individual review only. It shares the same bounded decode used for perceptual hashing.",
                            action = if (state.isScanning) null else if (premiumEnabled) "Analyze photo quality" else "Unlock Premium",
                            onAction = if (premiumEnabled) onFindDuplicates else onUpgrade,
                        )
                    }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.Storage,
                            title = stringResource(R.string.ui_large_files),
                            value = "${scan.largeFiles.size} files",
                            description = "Files at least 10 MB, sorted by size. Nothing is auto-selected.",
                            action = if (premiumEnabled) null else "Unlock Premium",
                            onAction = onUpgrade,
                        )
                    }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.WarningAmber,
                            title = stringResource(R.string.ui_review_candidates),
                            value = "${scan.suspectedJunk.size} items",
                            description = "Conservative temporary/log/installer and cache-location heuristics plus empty folders. Only strong temp/log signatures can be bulk-selected; all other candidates remain manual-review items.",
                            action = if (scan.suspectedJunk.any { it.canDelete && (it.category == CleanerCategory.Temporary || it.category == CleanerCategory.Log) }) "Select temp/log candidates" else null,
                            onAction = onSelectAllJunk,
                        )
                    }
                    item {
                        InsightCard(
                            icon = Icons.Outlined.Cached,
                            title = stringResource(R.string.ui_app_cache_estimate),
                            value = scan.cacheInsight.bytes?.let(ByteSizeFormatter::format) ?: "Usage access required",
                            description = if (scan.cacheInsight.available) {
                                "System-reported aggregate app cache. Android does not let a normal app silently clear other apps' private caches."
                            } else {
                                "Enable Usage Access to read aggregate cache statistics. This does not grant file access or deletion rights."
                            },
                            action = if (!scan.cacheInsight.available) "Open Usage Access" else null,
                            onAction = onOpenUsageAccess,
                        )
                    }
                }
                CleanerView.Duplicates -> {
                    if (!premiumEnabled) {
                        item { PremiumCleanerCard("Exact duplicate cleaner", "Streaming SHA-256 duplicate analysis and smart keep-newest/keep-best selection are Premium features.", onUpgrade) }
                    } else if (scan.duplicateGroups.isEmpty()) {
                        item {
                            if (scan.duplicateAnalysisCompleted) {
                                EmptyResultCard(
                                    title = stringResource(R.string.ui_no_exact_duplicates_found),
                                    body = "The accessible sources were analyzed and no byte-for-byte duplicate groups were verified.",
                                    action = "Analyze again",
                                    onAction = onFindDuplicates,
                                )
                            } else {
                                EmptyResultCard(
                                    title = stringResource(R.string.ui_no_duplicate_result_yet),
                                    body = "Run exact duplicate analysis. It hashes only same-size candidates and verifies them with a full streaming SHA-256 pass.",
                                    action = "Find duplicates",
                                    onAction = onFindDuplicates,
                                )
                            }
                        }
                    } else {
                        items(scan.duplicateGroups, key = { it.id }) { group ->
                            DuplicateGroupCard(
                                group = group,
                                selectedKeys = state.selectedKeys,
                                onToggleSelection = onToggleSelection,
                                onSelectRedundant = { strategy -> onSelectDuplicateRedundant(group.id, strategy) },
                            )
                        }
                    }
                }
                CleanerView.SimilarPhotos -> {
                    if (!premiumEnabled) {
                        item {
                            PremiumCleanerCard(
                                "Near-duplicate photo review",
                                "On-device perceptual hashing for visually similar photos is included with the Premium cleaner. It never auto-selects photos for removal.",
                                onUpgrade,
                            )
                        }
                    } else {
                        if (scan.photoReviewGroups.isNotEmpty()) {
                            item { PhotoReviewIntelligenceCard(scan.photoReviewGroups) }
                        }
                        if (scan.nearDuplicateGroups.isEmpty()) {
                            item {
                                if (scan.nearDuplicateAnalysisCompleted) {
                                    EmptyResultCard(
                                        title = stringResource(R.string.ui_no_near_duplicate_photos_found),
                                        body = if (scan.nearDuplicateAnalysisTruncated) {
                                            "No similar-photo groups were found within the first 100,000 hash attempts. Narrow storage access and analyze again for a smaller, complete pass."
                                        } else {
                                            "The accessible photos were perceptually analyzed and no conservative near-duplicate groups were found."
                                        },
                                        action = "Analyze again",
                                        onAction = onFindDuplicates,
                                    )
                                } else {
                                    EmptyResultCard(
                                        title = stringResource(R.string.ui_no_similar_photo_result_yet),
                                        body = "Run duplicate analysis to compute a small 64-bit dHash from one bounded, downsampled decode per accessible image.",
                                        action = "Find similar photos",
                                        onAction = onFindDuplicates,
                                    )
                                }
                            }
                        } else {
                            if (scan.nearDuplicateAnalysisTruncated) {
                                item {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.padding(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Icon(Icons.Outlined.Info, contentDescription = null)
                                            Text(stringResource(R.string.ui_near_duplicate_hashing_reached_the_100_000_image_safety),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            }
                            items(scan.nearDuplicateGroups, key = { it.id }) { group ->
                                NearDuplicateGroupCard(
                                    group = group,
                                    selectedKeys = state.selectedKeys,
                                    onToggleSelection = onToggleSelection,
                                )
                            }
                        }
                    }
                }
                CleanerView.BlurryPhotos -> {
                    if (!premiumEnabled) {
                        item {
                            PremiumCleanerCard(
                                "Blurry photo review",
                                "On-device sharpness scoring is included with the Premium cleaner. Flagged photos are review hints only and are never bulk-selected.",
                                onUpgrade,
                            )
                        }
                    } else if (scan.blurryPhotos.isEmpty()) {
                        item {
                            if (scan.blurryPhotoAnalysisCompleted) {
                                EmptyResultCard(
                                    title = stringResource(R.string.ui_no_blurry_photos_flagged),
                                    body = if (scan.blurryPhotoAnalysisTruncated) {
                                        "No low-sharpness candidates were found within the first 100,000 image-analysis attempts. Narrow storage access and analyze again for a smaller, complete pass."
                                    } else {
                                        "The accessible photos were analyzed and none fell below the conservative sharpness threshold."
                                    },
                                    action = "Analyze again",
                                    onAction = onFindDuplicates,
                                )
                            } else {
                                EmptyResultCard(
                                    title = stringResource(R.string.ui_no_photo_quality_result_yet),
                                    body = "Run photo analysis to compute Laplacian-variance sharpness from the same bounded, downsampled bitmap used for perceptual hashing.",
                                    action = "Analyze photo quality",
                                    onAction = onFindDuplicates,
                                )
                            }
                        }
                    } else {
                        if (scan.blurryPhotoAnalysisTruncated) {
                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(Icons.Outlined.Info, contentDescription = null)
                                        Text(stringResource(R.string.ui_photo_quality_analysis_reached_the_100_000_image_safety),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.ui_blur_detection_is_a_weak_heuristic_review_every_candida),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(scan.blurryPhotos, key = { it.item.key }) { candidate ->
                            BlurryPhotoCandidateCard(
                                candidate = candidate,
                                selected = candidate.item.key in state.selectedKeys,
                                onToggleSelection = onToggleSelection,
                            )
                        }
                    }
                }
                CleanerView.Large -> {
                    if (scan.largeFiles.isEmpty()) {
                        item { EmptyResultCard("No large files", "No accessible file at or above 10 MB was found.") }
                    } else {
                        items(scan.largeFiles, key = { it.key }) { item ->
                            CleanerItemRow(
                                item = item,
                                selected = item.key in state.selectedKeys,
                                onToggleSelection = onToggleSelection,
                                actionLabel = if (item.category == CleanerCategory.Image || item.category == CleanerCategory.Video) {
                                    if (premiumEnabled) "Compress" else "Unlock Premium"
                                } else {
                                    null
                                },
                                onAction = if (item.category == CleanerCategory.Image || item.category == CleanerCategory.Video) {
                                    { onReviewReencode(item.key) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                CleanerView.Review -> {
                    if (scan.suspectedJunk.isEmpty()) item { EmptyResultCard("Nothing flagged for review", "No accessible temporary, log, or conservative installer candidates were detected.") }
                    else items(scan.suspectedJunk, key = { it.key }) { item -> CleanerItemRow(item, item.key in state.selectedKeys, onToggleSelection) }
                }
                CleanerView.All -> items(scan.items, key = { it.key }) { item -> CleanerItemRow(item, item.key in state.selectedKeys, onToggleSelection) }
            }

            if (state.selectedKeys.isNotEmpty()) {
                item {
                    SelectionCard(
                        selectedItems = selectedItems,
                        onClear = onClearSelection,
                        onReviewRemoval = { showRemovalDialog = true },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageAccessCard(
    access: CleanerAccessState,
    persisted: List<PersistedAccess>,
    enabled: Boolean,
    onRequestVisualAccess: () -> Unit,
    onRequestAudioAccess: () -> Unit,
    onRequestLegacyWrite: () -> Unit,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onReleaseAccess: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ui_storage_access), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.ui_permissions_are_incremental_you_can_scan_media_grant_se),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccessRow(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.ui_photos_videos),
                status = when {
                    access.limitedVisualAccess -> "Selected items only"
                    access.canReadImages && access.canReadVideos -> "Available"
                    access.canReadImages || access.canReadVideos -> "Partially available"
                    else -> "Not granted"
                },
                action = if (access.canReadImages && access.canReadVideos && !access.limitedVisualAccess) null else "Grant",
                enabled = enabled,
                onAction = onRequestVisualAccess,
            )
            AccessRow(
                icon = Icons.Outlined.AudioFile,
                title = stringResource(R.string.ui_music_audio),
                status = if (access.canReadAudio) "Available" else "Not granted",
                action = if (access.canReadAudio) null else "Grant",
                enabled = enabled,
                onAction = onRequestAudioAccess,
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && (access.canReadImages || access.canReadVideos || access.canReadAudio)) {
                AccessRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.ui_legacy_media_deletion),
                    status = if (access.legacyMediaWriteGranted) "Enabled" else "Optional — Android 8–9 only",
                    action = if (access.legacyMediaWriteGranted) null else "Enable deletion",
                    enabled = enabled,
                    onAction = onRequestLegacyWrite,
                )
            }
            AccessRow(
                icon = Icons.Outlined.Cached,
                title = stringResource(R.string.ui_cache_statistics),
                status = if (access.usageAccessGranted) "Usage Access enabled" else "Optional",
                action = if (access.usageAccessGranted) null else "Settings",
                enabled = enabled,
                onAction = onOpenUsageAccess,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickPhotos, enabled = enabled) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ui_select_photos_videos))
                }
                OutlinedButton(onClick = onAddFolder, enabled = enabled) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ui_add_folder))
                }
                OutlinedButton(onClick = onAddFiles, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ui_add_files))
                }
            }
            Text(stringResource(R.string.ui_the_photo_picker_provides_a_privacy_preserving_read_onl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (persisted.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.ui_persisted_user_grants), style = MaterialTheme.typography.labelLarge)
                persisted.forEach { grant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (grant.isTree) Icons.Outlined.FolderOpen else Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(grant.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (grant.canWrite) "Read + delete where provider supports it" else "Read-only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onReleaseAccess(grant.uri) }, enabled = enabled) { Text(stringResource(R.string.ui_remove)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRow(
    icon: ImageVector,
    title: String,
    status: String,
    action: String?,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun ScanCard(
    access: CleanerAccessState,
    progress: CleanerScanProgress?,
    scan: CleanerScanResult?,
    enabled: Boolean,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(R.string.ui_storage_scan), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (scan == null) "Index only accessible sources; no file changes occur during scanning." else "Last result: ${scan.items.size} accessible items",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (progress == null) {
                Button(
                    onClick = onScan,
                    enabled = enabled && access.hasAnySource,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (scan == null) "Scan storage" else "Re-scan storage") }
            } else {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_cancel_scan)) }
            }
            if (!access.hasAnySource) {
                Text(stringResource(R.string.ui_grant_a_media_category_folder_or_file_before_scanning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (progress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val indexedSuffix = if (progress.bytesScanned > 0L) {
                    stringResource(R.string.cleaner_scan_indexed_suffix, ByteSizeFormatter.format(progress.bytesScanned))
                } else {
                    ""
                }
                Text(
                    stringResource(R.string.cleaner_scan_progress, progress.phase.name, progress.itemsScanned, indexedSuffix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(scan: CleanerScanResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_accessible_storage_summary), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            MetricLine("Visible entries indexed", scan.items.size.toString())
            MetricLine("Unique accessible bytes", ByteSizeFormatter.format(scan.totalAccessibleBytes))
            MetricLine("Potential review", ByteSizeFormatter.format(scan.potentialReclaimBytes))
            if (scan.skippedItems > 0) MetricLine("Sources/items skipped safely", scan.skippedItems.toString())
            Text(stringResource(R.string.ui_potential_review_counts_only_currently_removable_candid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartReviewSummaryCard(
    summary: CleanerSmartReviewSummary,
    onExactDuplicates: () -> Unit,
    onPhotoReview: () -> Unit,
    onLargeFiles: () -> Unit,
    onReviewCandidates: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.cleaner_smart_review_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.cleaner_smart_review_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MetricLine(
                stringResource(R.string.cleaner_smart_review_exact),
                summary.exactDuplicateBytes?.let { stringResource(R.string.cleaner_smart_review_value, summary.exactDuplicateGroups, ByteSizeFormatter.format(it)) }
                    ?: stringResource(R.string.cleaner_smart_review_not_analyzed),
            )
            MetricLine(
                stringResource(R.string.cleaner_smart_review_photos),
                summary.photoReviewCandidateBytes?.let { stringResource(R.string.cleaner_smart_review_value, summary.photoReviewGroups, ByteSizeFormatter.format(it)) }
                    ?: stringResource(R.string.cleaner_smart_review_not_analyzed),
            )
            MetricLine(stringResource(R.string.cleaner_smart_review_large), stringResource(R.string.cleaner_smart_review_value, summary.largeFiles, ByteSizeFormatter.format(summary.largeFileBytes)))
            MetricLine(stringResource(R.string.cleaner_smart_review_candidates), stringResource(R.string.cleaner_smart_review_value, summary.suspectedCandidates, ByteSizeFormatter.format(summary.suspectedCandidateBytes)))
            if (summary.compressibleMediaCandidates > 0) {
                Text(
                    stringResource(R.string.cleaner_smart_review_compressible, summary.compressibleMediaCandidates, ByteSizeFormatter.format(summary.compressibleMediaSourceBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onExactDuplicates) { Text(stringResource(R.string.cleaner_review_exact)) }
                TextButton(onClick = onPhotoReview) { Text(stringResource(R.string.cleaner_review_photos)) }
                TextButton(onClick = onLargeFiles) { Text(stringResource(R.string.cleaner_review_large)) }
                TextButton(onClick = onReviewCandidates) { Text(stringResource(R.string.cleaner_review_other)) }
            }
        }
    }
}

@Composable
private fun CategoryBreakdown(categories: List<CategoryUsage>, totalBytes: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_storage_analyzer), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (categories.isEmpty()) Text(stringResource(R.string.ui_no_categorized_accessible_files))
            else StorageTreemap(categories = categories, totalBytes = totalBytes)
            categories.forEach { usage ->
                val fraction = if (totalBytes > 0) (usage.bytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) else 0.0
                ApexMetricRow(
                    label = usage.category.name,
                    value = "${ByteSizeFormatter.format(usage.bytes)} • ${usage.itemCount}",
                )
                LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StorageTreemap(
    categories: List<CategoryUsage>,
    totalBytes: Long,
) {
    if (totalBytes <= 0L) return

    // Keep the visual summary purely graphical. The previous vertical weighted layout could
    // allocate only a few pixels to a smaller category while still composing a full text row
    // inside that cell; the text then overflowed into adjacent cells on phones and at larger
    // font scales. The detailed, adaptive ApexMetricRow list immediately below is the readable
    // source of labels/values, while this bounded horizontal strip is safe at every width.
    val visible = categories.asSequence()
        .filter { it.bytes > 0L }
        .take(6)
        .toList()
    if (visible.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visible.forEachIndexed { index, usage ->
            val weight = (usage.bytes.toDouble() / totalBytes.toDouble())
                .coerceAtLeast(0.001)
                .toFloat()
            val container = when (index % 4) {
                0 -> MaterialTheme.colorScheme.primaryContainer
                1 -> MaterialTheme.colorScheme.secondaryContainer
                2 -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                modifier = Modifier
                    .weight(weight)
                    .height(32.dp),
                color = container,
                shape = RoundedCornerShape(6.dp),
            ) {}
        }
    }
}

@Composable
private fun InsightCard(
    icon: ImageVector,
    title: String,
    value: String,
    description: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun PremiumCleanerCard(title: String, body: String, onUpgrade: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cleaner_premium_title, title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(body)
            Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_unlock_premium)) }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
    onSelectRedundant: (DuplicateKeepStrategy) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.cleaner_exact_copy_count, group.items.size), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.cleaner_reclaimable_copies, ByteSizeFormatter.format(group.reclaimableBytes)), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = { onSelectRedundant(DuplicateKeepStrategy.BestQuality) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (group.items.any { it.pixelCount != null }) "Keep best quality • select other removable copies" else "Keep preferred copy • select other removable copies")
            }
            OutlinedButton(
                onClick = { onSelectRedundant(DuplicateKeepStrategy.Newest) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ui_keep_newest_select_other_removable_copies))
            }
            group.items.forEach { item ->
                val badge = when {
                    item.key == group.bestQualityKey && item.key == group.newestKey -> "Best • newest"
                    item.key == group.bestQualityKey -> "Best quality"
                    item.key == group.newestKey -> "Newest"
                    else -> null
                }
                CleanerItemRow(item, item.key in selectedKeys, onToggleSelection, badge = badge)
            }
        }
    }
}

@Composable
private fun PhotoReviewIntelligenceCard(groups: List<PhotoReviewGroup>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cleaner_photo_review_intelligence), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.cleaner_photo_review_intelligence_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            groups.take(8).forEach { group ->
                val keep = group.items.firstOrNull { it.key == group.recommendedKeepKey }?.displayName ?: group.items.firstOrNull()?.displayName.orEmpty()
                Text(
                    stringResource(
                        R.string.cleaner_photo_review_group,
                        group.type.displayName(),
                        group.items.size,
                        keep,
                        ByteSizeFormatter.format(group.reclaimableReviewBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (groups.size > 8) Text(stringResource(R.string.cleaner_photo_review_more, groups.size - 8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun PhotoReviewGroupType.displayName(): String = when (this) {
    PhotoReviewGroupType.Similar -> "Similar"
    PhotoReviewGroupType.ScreenshotBatch -> "Screenshots"
    PhotoReviewGroupType.Burst -> "Burst"
    PhotoReviewGroupType.RepeatedDownload -> "Repeated download"
}

@Composable
private fun NearDuplicateGroupCard(
    group: NearDuplicateGroup,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.cleaner_similar_photo_count, group.items.size), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.cleaner_max_dhash_distance, group.maxHammingDistance),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(stringResource(R.string.ui_perceptual_similarity_is_only_a_review_hint_apextuner_n),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.items.forEach { match ->
                val badge = if (match.item.key == group.anchorKey) {
                    "Reference"
                } else {
                    "dHash ${match.hammingDistanceFromAnchor}/64"
                }
                CleanerItemRow(
                    item = match.item,
                    selected = match.item.key in selectedKeys,
                    onToggleSelection = onToggleSelection,
                    badge = badge,
                )
            }
        }
    }
}

@Composable
private fun BlurryPhotoCandidateCard(
    candidate: BlurryPhotoCandidate,
    selected: Boolean,
    onToggleSelection: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(candidate.item.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.cleaner_laplacian_variance, formatSharpnessScore(candidate.laplacianVariance)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(stringResource(R.string.ui_low_sharpness_can_also_be_intentional_portraits_motion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CleanerItemRow(
                item = candidate.item,
                selected = selected,
                onToggleSelection = onToggleSelection,
                badge = "Manual review",
            )
        }
    }
}

private fun formatSharpnessScore(value: Double): String =
    if (value >= 10.0) {
        java.lang.String.format(java.util.Locale.ROOT, "%.0f", value)
    } else {
        java.lang.String.format(java.util.Locale.ROOT, "%.1f", value)
    }

@Composable
private fun CleanerItemRow(
    item: CleanableItem,
    selected: Boolean,
    onToggleSelection: (String) -> Unit,
    badge: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val date = remember(item.modifiedAtEpochMillis) {
        item.modifiedAtEpochMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    }
    val detail = buildString {
        append(ByteSizeFormatter.format(item.sizeBytes))
        item.width?.let { width -> item.height?.let { height -> append(" • ${width}×$height") } }
        item.durationMillis?.takeIf { it > 0L }?.let { append(" • ${formatDuration(it)}") }
        date?.let { append(" • $it") }
    }
    val source = when (item.origin) {
        CleanerOrigin.MediaStore -> item.relativeLocation ?: "Media library"
        CleanerOrigin.SafTree -> "Granted folder"
        CleanerOrigin.SafDocument -> "Granted file"
        CleanerOrigin.SelectedMedia -> "Photo Picker selection"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = item.canDelete,
                role = Role.Checkbox,
                onValueChange = { onToggleSelection(item.key) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = item.canDelete)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val readOnlySuffix = if (!item.canDelete) stringResource(R.string.cleaner_read_only_suffix) else ""
            Text(
                stringResource(R.string.cleaner_item_source_summary, item.category.name, source, readOnlySuffix),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.category != CleanerCategory.EmptyFolder) {
                val context = LocalContext.current
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = { openItem(context, item) }) {
                        Text(stringResource(R.string.ui_open))
                    }
                    TextButton(onClick = { shareItem(context, item) }) {
                        Text(stringResource(R.string.ui_share))
                    }
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionCard(
    selectedItems: List<CleanableItem>,
    onClear: () -> Unit,
    onReviewRemoval: () -> Unit,
) {
    val physicalItems = selectedItems.distinctBy { it.identityKey }
    val bytes = physicalItems.fold(0L) { total, item ->
        val value = item.sizeBytes.coerceAtLeast(0L)
        if (total > Long.MAX_VALUE - value) Long.MAX_VALUE else total + value
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_selection), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (physicalItems.size == selectedItems.size) {
                    "${physicalItems.size} items • ${ByteSizeFormatter.format(bytes)}"
                } else {
                    "${physicalItems.size} unique items (${selectedItems.size} visible entries) • ${ByteSizeFormatter.format(bytes)}"
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onClear) { Text(stringResource(R.string.ui_clear_selection)) }
                Button(onClick = onReviewRemoval) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ui_review_removal))
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaReencodeReviewDialog(
    item: CleanableItem,
    review: MediaReencodeReview,
    onPresetChange: (MediaReencodePreset) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (MediaReencodeMode) -> Unit,
) {
    val estimate = review.estimate
    var mode by remember(review.itemKey) { mutableStateOf(MediaReencodeMode.SaveAsCopy) }

    LaunchedEffect(estimate?.copyAvailable, estimate?.replaceAvailable) {
        if (estimate != null) {
            mode = when {
                mode == MediaReencodeMode.SaveAsCopy && estimate.copyAvailable -> mode
                mode == MediaReencodeMode.ReplaceOriginal && estimate.replaceAvailable -> mode
                estimate.copyAvailable -> MediaReencodeMode.SaveAsCopy
                estimate.replaceAvailable -> MediaReencodeMode.ReplaceOriginal
                else -> mode
            }
        }
    }

    val selectedModeAvailable = when (mode) {
        MediaReencodeMode.SaveAsCopy -> estimate?.copyAvailable == true
        MediaReencodeMode.ReplaceOriginal -> estimate?.replaceAvailable == true
    }
    val canCommit = estimate?.supported == true &&
        estimate.estimatedSavingsBytes > 0L &&
        selectedModeAvailable &&
        !review.isEstimating

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_compress_media)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    item.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.cleaner_original_size, ByteSizeFormatter.format(item.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(stringResource(R.string.ui_quality_target), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaReencodePreset.entries.forEach { preset ->
                        val presetLabel = when (preset) {
                            MediaReencodePreset.Balanced -> stringResource(R.string.reencode_preset_balanced)
                            MediaReencodePreset.Compact -> stringResource(R.string.reencode_preset_compact)
                        }
                        FilterChip(
                            selected = review.preset == preset,
                            onClick = { onPresetChange(preset) },
                            enabled = !review.isEstimating,
                            label = { Text(presetLabel) },
                        )
                    }
                }

                if (review.isEstimating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.ui_calculating_an_on_device_size_estimate))
                    }
                } else if (estimate != null && estimate.supported) {
                    MetricLine("Estimated output", "≈ ${ByteSizeFormatter.format(estimate.estimatedOutputBytes)}")
                    MetricLine(
                        "Estimated reduction",
                        "≈ ${ByteSizeFormatter.format(estimate.estimatedSavingsBytes)} (${estimate.estimatedSavingsPercent}%)",
                    )
                    MetricLine("Target resolution", "${estimate.targetWidth}×${estimate.targetHeight}")
                    Text(stringResource(R.string.ui_the_estimate_is_advisory_because_codec_efficiency_varie),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.ui_save_as_copy_creates_an_additional_file_and_does_not_re),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val estimateError = review.errorMessage ?: estimate?.unavailableReason
                if (!estimateError.isNullOrBlank()) {
                    Text(
                        estimateError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()
                Text(stringResource(R.string.ui_output), style = MaterialTheme.typography.labelLarge)
                ReencodeModeRow(
                    selected = mode == MediaReencodeMode.SaveAsCopy,
                    enabled = estimate?.copyAvailable == true,
                    title = stringResource(R.string.ui_save_as_copy),
                    body = estimate?.copyUnavailableReason
                        ?: "Default. Writes a new compressed file and leaves the original unchanged.",
                    onClick = { mode = MediaReencodeMode.SaveAsCopy },
                )
                ReencodeModeRow(
                    selected = mode == MediaReencodeMode.ReplaceOriginal,
                    enabled = estimate?.replaceAvailable == true,
                    title = stringResource(R.string.ui_replace_original_af2015),
                    body = estimate?.replaceUnavailableReason
                        ?: "Requires a second explicit confirmation. ApexTuner stages and verifies the compressed result before replacing anything.",
                    onClick = { mode = MediaReencodeMode.ReplaceOriginal },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(mode) },
                enabled = canCommit,
            ) {
                Text(
                    if (mode == MediaReencodeMode.ReplaceOriginal) {
                        "Review replacement"
                    } else {
                        "Save compressed copy"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun ReplaceOriginalConfirmationDialog(
    item: CleanableItem,
    estimate: MediaReencodeEstimate?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_replace_original)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                estimate?.let {
                    Text(
                        stringResource(R.string.cleaner_estimated_reduction, ByteSizeFormatter.format(it.estimatedSavingsBytes), it.estimatedSavingsPercent),
                    )
                }
                Text(stringResource(R.string.ui_this_is_distinct_from_save_as_copy_apextuner_will_first),
                )
                Text(stringResource(R.string.ui_if_staging_verification_or_rollback_preparation_fails_t),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.category == CleanerCategory.Image) {
                    Text(stringResource(R.string.ui_image_pixels_keep_their_visible_orientation_but_re_enco),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = estimate?.let { it.replaceAvailable && it.estimatedSavingsBytes > 0L } == true,
            ) {
                Text(stringResource(R.string.ui_replace_original_af2015))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_keep_original)) } },
    )
}

@Composable
private fun ReencodeModeRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReencodeProgressDialog(
    progress: MediaReencodeProgress,
    onCancel: () -> Unit,
) {
    val title = when (progress.phase) {
        MediaReencodeProgress.Phase.Preparing -> "Preparing compression"
        MediaReencodeProgress.Phase.Transcoding -> "Compressing media"
        MediaReencodeProgress.Phase.SavingCopy -> "Saving compressed copy"
        MediaReencodeProgress.Phase.SnapshottingOriginal -> "Preparing rollback snapshot"
        MediaReencodeProgress.Phase.ReplacingOriginal -> "Replacing original"
        MediaReencodeProgress.Phase.Verifying -> "Verifying replacement"
    }
    val body = when (progress.phase) {
        MediaReencodeProgress.Phase.Preparing ->
            "ApexTuner is validating the source and preparing a temporary staged output."
        MediaReencodeProgress.Phase.Transcoding ->
            "The re-encode runs entirely on this device. You can cancel while transcoding."
        MediaReencodeProgress.Phase.SavingCopy ->
            "The staged result is being copied to the selected Android storage destination."
        MediaReencodeProgress.Phase.SnapshottingOriginal ->
            "ApexTuner is copying and verifying the original into temporary app storage. You can still cancel because the source has not been changed."
        MediaReencodeProgress.Phase.ReplacingOriginal ->
            "The destructive commit has started after explicit confirmation and verified rollback preparation. Cancellation is disabled until rollback-safe finalization finishes."
        MediaReencodeProgress.Phase.Verifying ->
            "ApexTuner is verifying the committed bytes before reporting success."
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                progress.fraction?.let {
                    LinearProgressIndicator(
                        progress = { it.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(body)
            }
        },
        confirmButton = {
            if (progress.cancellable) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_cancel)) }
            }
        },
    )
}

@Composable
private fun RemovalReviewDialog(
    selectedItems: List<CleanableItem>,
    onDismiss: () -> Unit,
    onConfirm: (MediaRemovalMode) -> Unit,
) {
    val supportsTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && selectedItems.any { it.origin == CleanerOrigin.MediaStore }
    val hasSaf = selectedItems.any { it.origin != CleanerOrigin.MediaStore }
    var mode by remember { mutableStateOf(if (supportsTrash) MediaRemovalMode.Trash else MediaRemovalMode.Permanent) }
    val totalBytes = selectedItems.fold(0L) { total, item -> if (total > Long.MAX_VALUE - item.sizeBytes.coerceAtLeast(0L)) Long.MAX_VALUE else total + item.sizeBytes.coerceAtLeast(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_review_removal)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.cleaner_selected_summary, selectedItems.size, ByteSizeFormatter.format(totalBytes)))
                selectedItems.take(6).forEach { item ->
                    Text(
                        stringResource(R.string.cleaner_selected_item_bullet, item.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selectedItems.size > 6) {
                    Text(
                        stringResource(R.string.cleaner_more_selected_items, selectedItems.size - 6),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (supportsTrash) {
                    RemovalModeRow(
                        selected = mode == MediaRemovalMode.Trash,
                        title = stringResource(R.string.ui_move_media_to_system_trash),
                        body = "Safer default. Trashed media remains recoverable and may continue using storage until permanently removed.",
                        onClick = { mode = MediaRemovalMode.Trash },
                    )
                    RemovalModeRow(
                        selected = mode == MediaRemovalMode.Permanent,
                        title = stringResource(R.string.ui_delete_media_permanently),
                        body = "Immediately removes approved MediaStore items. This cannot be undone by ApexTuner.",
                        onClick = { mode = MediaRemovalMode.Permanent },
                    )
                } else {
                    Text(stringResource(R.string.ui_this_android_source_combination_supports_only_permanent))
                }
                if (hasSaf) {
                    Text(stringResource(R.string.ui_important_files_from_user_granted_document_providers_do),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.ui_apextuner_never_deletes_an_unselected_file), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(mode) }) { Text(stringResource(R.string.ui_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun RemovalModeRow(
    selected: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyResultCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (isError) Icons.Outlined.WarningAmber else Icons.Outlined.Info, contentDescription = null)
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.ui_dismiss)) }
        }
    }
}

private fun openItem(context: Context, item: CleanableItem) {
    val uri = Uri.parse(item.uri)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType ?: "*/*")
        clipData = ClipData.newRawUri("ApexTuner item", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }
}

private fun shareItem(context: Context, item: CleanableItem) {
    val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return
    val share = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("ApexTuner item", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(share, "Share ${item.displayName}"))
    }
}


private fun requiredVisualPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun requiredAudioPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
