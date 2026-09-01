package com.apextuner.feature.tools.systeminfo

data class SystemInfoSection(
    val title: String,
    val rows: List<SystemInfoRow>,
)

data class SystemInfoRow(
    val label: String,
    val value: String,
)

data class SystemInfoSnapshot(
    val sections: List<SystemInfoSection>,
    val diagnostics: List<String> = emptyList(),
)
