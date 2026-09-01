# ApexTuner 1.1.3 — Android Studio build repair 2

Confirmed compiler failures fixed from the Reliability Audit 2 package:

1. `ApexIntelligenceEngine.kt`: Kotlin cannot smart-cast nullable public properties from `GameSessionRecordEntity` across module boundaries. The battery levels are now copied into local immutable values before null checks/arithmetic.
2. `NetworkDiagnosticsRoute.kt`: added the missing `androidx.compose.ui.text.font.FontWeight` import used by the throughput result.

The release gate now permanently checks both conditions.
