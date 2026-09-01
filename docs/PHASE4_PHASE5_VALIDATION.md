# ApexTuner v1.1 — Phase 4 + Phase 5 Validation

Release candidate: **1.1.0** (`versionCode 22`)  
Baseline for this integration pass: verified Phase 2 + Phase 3 package.

## Scope

Phase 4:
- 4.1 rollback-safe system/Dalvik cache capability handling.
- 4.2 explicit per-app freeze/restore and force-stop through typed Shizuku/root gateways.
- 4.3 reversible CPU governor/frequency tuning through `SafeSystemTuningController` and `SystemProfilePlanner`.

Phase 5:
- Firewall and Cleaner Quick Settings tiles.
- Storage and battery-health home-screen widgets.
- Scheduled versioned SAF backups with retention.
- UI localization/resource pass.

## Safety decisions

### Cache maintenance

ApexTuner does **not** execute ART/Dalvik or system cache clearing because Android does not expose a transactional rollback primitive that satisfies the project's reversible-mutation standard. `CacheMaintenanceCapability.RollbackSafeUnavailable` is surfaced explicitly. No fake success state is produced and no destructive cache command is issued.

### App freeze / force-stop

Actions are user-triggered only and require Pro entitlement plus an active Shizuku/root backend. Candidate packages are limited to launchable third-party applications. ApexTuner, `android`, `com.android.*`, system/updated-system apps, and UIDs below the application UID boundary are rejected. Freeze snapshots the prior `PackageManager` enabled state and restore writes that exact supported state. The automation package contains no freeze/force-stop calls.

### Extended CPU tuning

The existing `SafeSystemTuningController` owns the transaction and persisted rollback journal. CPU targets are generated only from a verified live cpufreq snapshot, governors must be present in the reported governor set, policy IDs are bounded, and min/max frequencies stay inside verified hardware limits. Android thermal policy remains platform-managed; no thermal node or thermal service mutation is allow-listed.

Root execution is additionally constrained by an explicit command-shape allowlist before the existing `BoundedProcessRunner` bridge. Only fixed `id`, `dumpsys deviceidle`, `/proc/version`, approved cpufreq leaves, package state/force-stop verbs, and animation-scale settings are accepted. Arbitrary paths, package-manager verbs, secure settings, shell metacharacter package names, and thermal paths are rejected.

### Scheduled backups

Scheduled backups reuse `BackupRestoreManager`, DataStore preferences, the existing automation scheduler, WorkManager, and persisted SAF tree grants. Only names matching `ApexTuner-backup-v1-YYYYMMDD-HHMMSS.json` participate in retention deletion. Unrelated documents are ignored. Writes are cancellation-aware and a failed newly-created document is removed best-effort.

## Validation performed

- `tools/validate_project.py`: PASS, **1871 checks**, 42 XML files, 14 main manifests.
- All XML files parsed successfully after resource/localization changes.
- Phase 4/5 permission delta against the Phase 2+3 baseline: **none**.
- CPU target property simulation: 100,000 randomized valid policies × four profiles, **0 range/governor invariant violations**.
- Backup retention property simulation: 50,000 randomized directories, **0 unrelated-file deletion violations**.
- Storage widget fraction simulation: 100,000 randomized total/free byte pairs, **0 non-finite/out-of-range results**.
- Protected-package model simulation: 100,000 randomized representative cases, **0 policy invariant violations**.
- Battery widget trend simulation: 100,000 randomized histories, **0 invalid ready-state/division invariants**.
- Root command allowlist adversarial fixtures: all intended command shapes accepted; destructive/arbitrary command, package injection, secure-setting, out-of-range cpufreq and thermal-path fixtures rejected.
- Java `Formatter` smoke validation of newly introduced localized format strings: PASS.
- Visible Compose, Quick Settings, widget and notification literal audit: no direct hardcoded `Text("...")`, tile label/content-description, widget text, notification title/text/action, or notification-channel literals remain in those surfaces.
- Quick-scan tile launch flow checked for one-shot request token propagation into Cleaner.
- Widget and tile manifest component declarations checked.
- No `killBackgroundProcesses` implementation exists.
- No Phase-4 app freeze/force-stop action is reachable from scheduled automation.

## Gradle/JUnit limitation

The following real Gradle test invocation was attempted on the final source:

```text
bash ./gradlew :core:testDebugUnitTest :feature:tools:testDebugUnitTest :feature:settings:testDebugUnitTest --no-daemon
```

The wrapper could not download the pinned Gradle 9.5.0 distribution because the execution environment could not resolve `services.gradle.org`. Kotlin/JUnit compilation therefore did not begin. This document does **not** claim JVM compilation, emulator, hardware/OEM cpufreq, Shizuku, root, Quick Settings, widget-host, WorkManager persistence, process-death, or device tests passed.

## Required release-environment checks

Before production release, run the repository CI matrix on API 26, 28, 30, 33, 35 and 36 and perform device checks for:
- Shizuku and root authorization/failure/cancellation.
- Freeze → restore with each supported original enabled state.
- Force-stop remains explicit and single-app only.
- CPU partial-write rollback and process-death journal recovery on supported rooted devices.
- Thermal controls remain untouched.
- Tile add/start/stop behavior on supported Android releases.
- Widget locked/Premium/history states and launcher refresh.
- Scheduled backup grant revocation, cancellation, retention, and WorkManager restart behavior.
- Localization layout/formatting under at least one non-English resource set once translations are supplied.
