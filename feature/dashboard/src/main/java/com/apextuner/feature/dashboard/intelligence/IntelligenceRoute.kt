package com.apextuner.feature.dashboard.intelligence

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexCard
import com.apextuner.core.ui.ApexLayout
import com.apextuner.feature.dashboard.R
import java.text.DateFormat
import java.util.Date

@Composable
fun IntelligenceRoute(
    onBack: () -> Unit,
    onDestination: (IntelligenceDestination) -> Unit,
    viewModel: IntelligenceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IntelligenceScreen(state, onBack, viewModel::refresh, onDestination)
}

@Composable
private fun IntelligenceScreen(
    state: IntelligenceUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDestination: (IntelligenceDestination) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.intelligence_back))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.intelligence_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.intelligence_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = state !is IntelligenceUiState.Loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.intelligence_refresh))
                }
            }
        }
        when (state) {
            IntelligenceUiState.Loading -> item {
                ApexCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.intelligence_loading))
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            is IntelligenceUiState.Error -> item {
                ApexCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.intelligence_error_title), style = MaterialTheme.typography.titleMedium)
                        Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRefresh) { Text(stringResource(R.string.intelligence_try_again)) }
                    }
                }
            }
            is IntelligenceUiState.Ready -> {
                val snapshot = state.snapshot
                item { OverallCard(snapshot) }
                item {
                    Text(stringResource(R.string.intelligence_health_signals), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                snapshot.scores.forEach { score -> item(key = "score-${score.name}") { ScoreCard(score) } }
                item {
                    Text(stringResource(R.string.intelligence_what_changed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                snapshot.findings.forEachIndexed { index, finding ->
                    item(key = "finding-$index-${finding.title}") { FindingCard(finding, onDestination) }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.History, contentDescription = null)
                        Text(stringResource(R.string.intelligence_ledger_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                item {
                    Text(
                        stringResource(R.string.intelligence_ledger_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (snapshot.activities.isEmpty()) {
                    item {
                        ApexCard(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.intelligence_no_activity), modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else snapshot.activities.forEachIndexed { index, activity ->
                    item(key = "activity-$index-${activity.timestampEpochMillis}") { ActivityCard(activity) }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
    }
}

@Composable
private fun OverallCard(snapshot: IntelligenceSnapshot) {
    ApexCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.intelligence_overall_health), style = MaterialTheme.typography.titleMedium)
                    Text(snapshot.overallSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snapshot.overallScore?.let { Text(it.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            }
            Text(
                stringResource(R.string.intelligence_sample_explanation, snapshot.sampleCount, snapshot.observationWindowDays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScoreCard(score: IntelligenceScore) {
    ApexCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(score.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.intelligence_score_value, score.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = toneColor(score.tone))
            }
            LinearProgressIndicator(progress = { score.score / 100f }, modifier = Modifier.fillMaxWidth())
            Text(score.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(score.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingCard(finding: IntelligenceFinding, onDestination: (IntelligenceDestination) -> Unit) {
    ApexCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(
                    imageVector = if (finding.tone == IntelligenceTone.Healthy) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = toneColor(finding.tone),
                )
                Text(finding.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(finding.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            finding.destination?.let { destination ->
                TextButton(onClick = { onDestination(destination) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.intelligence_review)) }
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: ActivityImpact) {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    ApexCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(activity.outcome, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(formatter.format(Date(activity.timestampEpochMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (activity.detail.isNotBlank()) Text(activity.detail, style = MaterialTheme.typography.bodyMedium)
            activity.observation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            activity.reversibleUntilEpochMillis?.let { until ->
                if (until > System.currentTimeMillis()) Text(stringResource(R.string.intelligence_reversible_until, formatter.format(Date(until))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun toneColor(tone: IntelligenceTone) = when (tone) {
    IntelligenceTone.Healthy -> MaterialTheme.colorScheme.primary
    IntelligenceTone.Informational -> MaterialTheme.colorScheme.secondary
    IntelligenceTone.Attention -> MaterialTheme.colorScheme.error
}
