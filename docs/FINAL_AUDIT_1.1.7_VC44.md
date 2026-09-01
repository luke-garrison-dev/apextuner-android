# ApexTuner Android — first-run copy and Files UX repair 1.1.7 / versionCode 44

## Confirmed issues

| Issue | Status |
| --- | --- |
| Settings, Game Booster, Advanced Tools, CPU, Battery, and Firewall showed raw Kotlin enum names (`Idle`, `DeniedOrUnavailable`, `NotChecked`, `Stopped`, `HomeWifi`) | Fixed — readable resource labels |
| Files toolbar always showed a Cancel action even with no transfer in progress | Fixed — Cancel appears only while a file operation is busy |
| Files first-run flash said “Loading granted folders…” with no progress indicator | Fixed — centered spinner and “Opening Files…” |
| File Manager operation/status copy was hardcoded in the ViewModel | Fixed — string/plural resources via application context |
| Settings notification-history status, cadence chips, backup folder actions, backup dialog titles, and restore theme preview used raw/hardcoded English | Fixed |
| Advanced Tools confirm dialogs, loading copy, backend chips, and root status used raw names | Fixed |
| CPU/Performance metric labels and safe-profile chips used hardcoded English / enum names | Fixed |
| Firewall state/selected-app metrics and profile names were hardcoded | Fixed |
| App Manager filters, sort chips, System/User badges, and inspector metric labels were hardcoded | Fixed |
| Cleaner media-compression progress dialog used hardcoded phase titles | Fixed |
| Duplicate-analysis completion copy was assembled by string concatenation | Fixed — quantity resources |
| Screen-recording stop-failure copy was hardcoded | Fixed |
| Validator treated qualified `feature.notifications.R.string.ui_back` as an app-module string | Fixed — regex ignores dotted `R.string` qualifiers |
| Validator treated a developer `.git` directory as a shipping defect | Fixed — only nested `.git` directories fail hygiene |
| Build number needed incrementing after the repair | Fixed — `1.1.7` / `versionCode 44` |

## Unchanged, still working

Lifetime Premium billing (`apextuner_premium_lifetime` INAPP), Quick Scan one-shot launch gate, screen-recording lifecycle gate, Game Session rollback, SAF/file guards, and capability-aware privileged tools were reviewed and left intact. AGP 9.3.2, Kotlin 2.3.21, KSP 2.3.9, Hilt 2.60.1, Gradle 9.5.0, and JDK 17 were not changed.

## Release identity

- Version name: `1.1.7`
- Version code: `44`
- Application ID: `com.apextuner.app`
