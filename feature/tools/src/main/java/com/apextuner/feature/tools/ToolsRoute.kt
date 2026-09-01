package com.apextuner.feature.tools

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.apexAccentPalette

private data class ToolEntry(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit,
)

private data class ToolGroup(
    val title: String,
    val body: String,
    val tools: List<ToolEntry>,
)

@Composable
fun ToolsRoute(
    onBattery: () -> Unit,
    onMemory: () -> Unit,
    onPerformance: () -> Unit,
    onNetwork: () -> Unit,
    onNetworkDiagnostics: () -> Unit,
    onFiles: () -> Unit,
    onContacts: () -> Unit,
    onNotificationHistory: () -> Unit,
    onSecurity: () -> Unit,
    onGameBooster: () -> Unit,
    onSystemInfo: () -> Unit,
    onDiagnosticReport: () -> Unit,
    onAdvanced: () -> Unit,
    showAdvancedTools: Boolean = false,
) {
    val accents = apexAccentPalette()
    val deviceTools = listOf(
        ToolEntry(stringResource(R.string.tools_entry_battery), stringResource(R.string.tools_entry_battery_body), Icons.Outlined.BatteryChargingFull, accents.green, onBattery),
        ToolEntry(stringResource(R.string.tools_entry_memory), stringResource(R.string.tools_entry_memory_body), Icons.Outlined.Memory, accents.violet, onMemory),
        ToolEntry(stringResource(R.string.ui_cpu_performance), stringResource(R.string.tools_entry_performance_body), Icons.Outlined.Speed, accents.cyan, onPerformance),
        ToolEntry(stringResource(R.string.ui_system_information), stringResource(R.string.tools_entry_system_info_body), Icons.Outlined.PhoneAndroid, accents.blue, onSystemInfo),
    )
    val connectivityTools = listOf(
        ToolEntry(stringResource(R.string.tools_entry_network), stringResource(R.string.tools_entry_network_body), Icons.Outlined.Wifi, accents.blue, onNetwork),
        ToolEntry(stringResource(R.string.tools_entry_network_diagnostics), stringResource(R.string.tools_entry_network_diagnostics_body), Icons.Outlined.NetworkCheck, accents.cyan, onNetworkDiagnostics),
        ToolEntry(stringResource(R.string.ui_privacy_security), stringResource(R.string.tools_entry_security_body), Icons.Outlined.Security, accents.cyan, onSecurity),
    )
    val dataTools = listOf(
        ToolEntry(stringResource(R.string.tools_entry_files), stringResource(R.string.tools_entry_files_body), Icons.Outlined.Folder, accents.warning, onFiles),
        ToolEntry(stringResource(R.string.tools_entry_contacts), stringResource(R.string.tools_entry_contacts_body), Icons.Outlined.Contacts, accents.violet, onContacts),
        ToolEntry(stringResource(R.string.tools_entry_notifications), stringResource(R.string.tools_entry_notifications_body), Icons.Outlined.Notifications, accents.cyan, onNotificationHistory),
        ToolEntry(stringResource(R.string.diagnostic_report_title), stringResource(R.string.tools_entry_diagnostics_body), Icons.Outlined.Assessment, accents.green, onDiagnosticReport),
    )
    val sessionTools = buildList {
        add(ToolEntry(stringResource(R.string.ui_game_session_booster), stringResource(R.string.tools_entry_game_body), Icons.Outlined.SportsEsports, accents.violet, onGameBooster))
        if (showAdvancedTools) {
            add(ToolEntry(stringResource(R.string.ui_advanced_access), stringResource(R.string.tools_entry_advanced_body), Icons.Outlined.AdminPanelSettings, accents.warning, onAdvanced))
        }
    }
    val groups = listOf(
        ToolGroup(stringResource(R.string.tools_group_device), stringResource(R.string.tools_group_device_body), deviceTools),
        ToolGroup(stringResource(R.string.tools_group_connectivity), stringResource(R.string.tools_group_connectivity_body), connectivityTools),
        ToolGroup(stringResource(R.string.tools_group_data), stringResource(R.string.tools_group_data_body), dataTools),
        ToolGroup(stringResource(R.string.tools_group_sessions), stringResource(R.string.tools_group_sessions_body), sessionTools),
    )


    val compactLandscape = ApexLayout.isCompactLandscape()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyVerticalGrid(
            columns = if (compactLandscape) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 280.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ApexLayout.horizontalPadding(),
                vertical = 18.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                                    ),
                                ),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            Text(stringResource(R.string.ui_tools), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(R.string.ui_system_insight_and_capability_aware_controls_grouped_by),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            groups.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "group:${group.title}") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(group.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(group.tools, key = { "${group.title}:${it.title}" }) { tool ->
                    ToolCard(tool)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolEntry) {
    Card(
        onClick = tool.onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                listOf(tool.accent.copy(alpha = 0.18f), tool.accent.copy(alpha = 0.08f)),
                            ),
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = null,
                        tint = tool.accent,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ui_open), style = MaterialTheme.typography.labelMedium, color = tool.accent)
                    Icon(Icons.Outlined.ArrowOutward, contentDescription = null, tint = tool.accent, modifier = Modifier.size(18.dp))
                }
            }
            Text(tool.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(tool.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
