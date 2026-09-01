package com.apextuner.feature.dashboard.intelligence

enum class IntelligenceTone { Healthy, Informational, Attention }

data class IntelligenceScore(
    val name: String,
    val score: Int,
    val summary: String,
    val explanation: String,
    val tone: IntelligenceTone,
)

data class IntelligenceFinding(
    val title: String,
    val detail: String,
    val tone: IntelligenceTone,
    val destination: IntelligenceDestination? = null,
)

enum class IntelligenceDestination { Battery, Network, Memory, Optimize, Automation, GameBooster }

data class ActivityImpact(
    val title: String,
    val timestampEpochMillis: Long,
    val outcome: String,
    val detail: String,
    val observation: String?,
    val reversibleUntilEpochMillis: Long? = null,
)

data class IntelligenceSnapshot(
    val generatedAtEpochMillis: Long,
    val overallScore: Int?,
    val overallSummary: String,
    val scores: List<IntelligenceScore>,
    val findings: List<IntelligenceFinding>,
    val activities: List<ActivityImpact>,
    val sampleCount: Int,
    val observationWindowDays: Int,
)

sealed interface IntelligenceUiState {
    data object Loading : IntelligenceUiState
    data class Error(val message: String) : IntelligenceUiState
    data class Ready(val snapshot: IntelligenceSnapshot) : IntelligenceUiState
}
