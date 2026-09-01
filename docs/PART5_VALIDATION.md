# ApexTuner Part 5 — Validation Report

Part 5 covers Battery Intelligence, Memory/RAM Intelligence, CPU/Performance Intelligence, and the reversible stock-Android system-profile layer. This report distinguishes tests actually executed in the current environment from Android SDK/device gates that still require a proper Android build environment.

## Scope implemented

### Battery Intelligence

- `BatteryManager`-backed level, health, temperature, voltage, instantaneous current, average current, charge counter, energy counter, charge source, battery presence, technology, and API-34+ cycle count.
- Signed current is preserved: positive means current entering the battery; negative means discharge.
- Android Battery Saver state and API-31+ discharge prediction.
- Android thermal status on API 29+.
- Optional 24-hour UsageStats activity signal, cached for 60 seconds so live battery refresh does not repeat the expensive aggregation call every cycle.
- Calling-app standby bucket on API 28+.
- Conservative recommendations which are explicitly Android/device signals, not battery-health diagnoses.

### Memory/RAM Intelligence

- `ActivityManager.MemoryInfo` system RAM state.
- ApexTuner-only process PSS/private-dirty metrics through `getProcessMemoryInfo()`.
- Java/native heap values.
- Android trim level / process importance.
- `/proc/meminfo` swap parsing with impossible/overflow-safe handling.
- `/proc/pressure/memory` PSI `some avg10` parsing when exposed by the kernel.
- Bounded process list for user-facing diagnostics without pretending third-party process memory is available.
- No force-stop/kill-background-app RAM booster.

### CPU / Performance Intelligence

- Current CPU utilization through the existing bounded `/proc/stat` sampling layer.
- Logical cores and current per-core frequencies where exposed.
- Best-effort min/max frequency and current governor reads.
- Best-effort GPU utilization.
- Current thermal state.
- Bounded I/O scheduler discovery and current TCP congestion-control token.
- Root binary is reported only as *potential availability*, never authorization.
- Privileged governor, LMK/VM, I/O tuning, secure settings and thermal-disable operations remain locked until a later explicit privileged session exists.

### Reversible stock-Android profiles

- Balanced, Battery, Performance and Gaming profiles share one serialized controller.
- Stock Android mutations are deliberately limited to settings that a normal application can legitimately change with the required user/system authorization.
- Original settings are journaled before mutation.
- A persisted `mutationPending` marker protects against process death mid-transaction.
- On the next controller entry, an interrupted profile is reconciled back to the saved baseline when Modify Settings access is still available.
- If access was revoked, ApexTuner does not attempt an unauthorized recovery and does not report the interrupted profile as successfully active.
- Cancellation and ordinary failures rollback the live settings and prior persisted profile state.
- Haptic-setting modification is skipped on API 33+ because Android deprecated the old global setting there.

## Official Android behavior cross-checked

The implementation was checked against current Android documentation for the following contracts:

- Android 14 process-management behavior: third-party apps cannot improve another app's memory/power/thermal behavior by killing its cached processes, and `killBackgroundProcesses()` is limited to the caller's own processes on Android 14+.
  - https://developer.android.com/about/versions/14/behavior-changes-all
- `ActivityManager.getProcessMemoryInfo()`: since Android Q, regular applications receive meaningful process-memory information only for processes under their own UID; Android also rate-limits the sampling frequency.
  - https://developer.android.com/reference/android/app/ActivityManager
- `BatteryManager` current/charge properties and API-34 `EXTRA_CYCLE_COUNT`.
  - https://developer.android.com/reference/android/os/BatteryManager
- `UsageStatsManager`: most cross-app usage queries require Usage Access, while `getAppStandbyBucket()` for the calling app does not.
  - https://developer.android.com/reference/android/app/usage/UsageStatsManager
- `WRITE_SETTINGS` requires explicit user authorization through `ACTION_MANAGE_WRITE_SETTINGS` on modern Android.
  - https://developer.android.com/reference/android/Manifest.permission
- Master sync reads/writes require the normal `READ_SYNC_SETTINGS` / `WRITE_SYNC_SETTINGS` permissions.
  - https://developer.android.com/reference/android/content/ContentResolver
- `Settings.System.HAPTIC_FEEDBACK_ENABLED` is deprecated from API 33; ApexTuner therefore leaves it system/user controlled on API 33+.
  - https://developer.android.com/reference/android/provider/Settings.System
- AGP 8.11 supports API 36 and uses Gradle 8.13 / JDK 17.
  - https://developer.android.com/build/releases/agp-8-11-0-release-notes

## Dependency compatibility audit

The release catalog was cross-checked against the current official AndroidX release notes. This Part applies only targeted updates whose documented toolchain requirements remain compatible with the API-36 / AGP-8.11 baseline:

- `androidx.activity:activity-compose` -> `1.13.0` for the modern Photo/Video ActivityResultContract URI-security fix and configuration-change edge-to-edge fixes.
- `androidx.navigation:navigation-compose` -> `2.9.8` for the predictive-back `NavHost` race/NPE fix.
- `androidx.datastore:datastore-preferences` -> `1.2.1`, a stable maintenance release.
- `androidx.work:work-runtime-ktx` -> `2.11.2`, including background network constraint, SecurityException and periodic-work rescheduling fixes.

Lifecycle/Compose are deliberately not moved to the current API-37-compiled line because Google documents an AGP 9.2 minimum for those Compose artifacts. Room is also left pinned until its compiler/runtime migration can be verified with an actual Android/Room schema build. This avoids dependency churn that cannot be validated in the present environment.

## Tests actually executed in this environment

### 1. Extended pure-domain / fuzz harness — PASS

The current Part 5 production sources for the profile planner, battery recommendations, memory parsers/recommendations and performance parsers were compiled with the locally installed Kotlin compiler and executed in one deterministic harness.

Result:

```text
ApexTuner Part 5 extended domain harness: PASS (80796 assertions)
```

Coverage includes:

- 20,000 randomized profile baselines checking monotonic/safe timeout behavior and exact Balanced restoration.
- Valid/invalid/negative/overflow swap values.
- PSI parsing, negative/non-finite rejection and wrong-line rejection.
- I/O scheduler parsing.
- 20,000 randomized kernel-token inputs verifying maximum length and accepted character set.
- Unknown vs severe thermal behavior.
- low-battery Battery Saver guidance.
- abnormal battery-health disclaimer behavior.
- healthy/high-pressure memory guidance and explicit rejection of artificial RAM clearing.

### 2. Actual ViewModel lifecycle harness — PASS

The **actual production `BatteryViewModel.kt`, `MemoryViewModel.kt`, and `PerformanceViewModel.kt` files** were compiled and executed on the JVM against minimal lifecycle/Hilt annotation stubs and deterministic fake repositories. This tests the real coroutine/state-flow logic rather than a rewritten approximation.

Result:

```text
ApexTuner Part 5 ViewModel lifecycle harness: PASS (12 checks)
```

Verified behavior:

- no repository polling before a state subscriber exists;
- polling starts on subscription;
- repeated Retry/Refresh cannot create overlapping telemetry reads;
- two transient Memory failures recover through the bounded retry path;
- profile actions refresh the screen and preserve their result message;
- Battery, Memory and Performance telemetry all stop after the `WhileSubscribed` stop timeout.

This harness directly caught the original unconditional `init` polling / duplicate-loop weakness; the production ViewModels were refactored before the final pass.

### 3. Actual profile-controller fault-injection harness — PASS

The **actual production `SafeSystemTuningController.kt`** and `SystemProfilePlanner.kt` were compiled against deterministic Android `Settings`, `ContentResolver`, `Context`, DataStore-repository and annotation stubs. Failures and coroutine cancellation were injected at specific transaction boundaries.

Result:

```text
ApexTuner Part 5 profile transaction harness: PASS (36 checks)
```

Verified behavior:

- Modify Settings denial performs no mutation and creates no journal.
- successful Battery profile writes the intended safe settings and commits a non-pending profile journal.
- Balanced restoration restores the exact saved timeout/haptic/sync baseline and clears the journal.
- rejection after the first Android setting change rolls the timeout back and removes the new journal.
- failed switching from an already active profile restores the previous live state and previous committed profile record.
- persisted interrupted transactions self-heal to the saved baseline when authorization remains available.
- interrupted transactions are retained and not falsely reported as active when authorization has been revoked.
- cancellation during the final commit runs the non-cancellable rollback path and restores both live settings and persisted state.
- concurrent profile requests are serialized; stale requests are explicitly superseded so scheduler ordering cannot overwrite a newer request.

### 4. Part 3 regression harnesses — PASS

Current Part 3 source files were recompiled after the Part 5 changes.

```text
dashboard-core-tests: PASS
telemetry-parser-tests: PASS
```

Validated network delta calculations, bounded history, thermal/storage recommendations, CPU counter parsing and GPU parser behavior.

### 5. Part 4 regression harness — PASS

Current Part 4 cleaner sources were recompiled and exercised after the Part 5 changes.

```text
ApexTuner Part 4 pure-domain harness: PASS
```

Validated exact duplicate grouping, source/provider isolation, best-quality/newest selection, read-only duplicate accounting, alias collapse and saturating reclaim arithmetic.

### 6. Project-wide structural validator — PASS

A reproducible no-SDK validator is included at `tools/validate_project.py`.

Current result:

```text
ApexTuner validation: PASS (668 checks, 32 XML files)
```

It verifies, among other things:

- all 11 modules exist and are included;
- API 36 / minSdk 26 consistency;
- application ID and ApexTuner branding;
- XML well-formedness and TOML parsing;
- module-local `R.string` references;
- package/path consistency;
- release minification/resource shrinking remains enabled;
- cleartext traffic and automatic Android backup remain disabled;
- forbidden high-risk permissions are absent;
- legacy storage permissions remain API-bounded;
- no implementation TODO/FIXME/NotImplemented markers;
- Part 5 ViewModels use `SharingStarted.WhileSubscribed` and contain no unconditional `init` polling;
- transaction-pending persistence/recovery is present;
- no `killBackgroundProcesses()` implementation exists.

### 7. Kotlin parser surface check — PASS within its stated limit

All main Kotlin files were passed to the locally available Kotlin compiler without Android/Compose dependencies. As expected, AndroidX/Android/Hilt symbols are unresolved because the Android classpath is absent. The diagnostics were then checked specifically for parser-level failures such as `expecting`, unexpected tokens, conflicting declarations and redeclarations.

Parser-level syntax diagnostics: **0**.

This is not a substitute for an AGP compilation, but it catches malformed Kotlin structure such as the duplicated `try` defect found during Part 4.

## Android build/device boundary not claimed

The current execution environment still has no installed Android SDK, Build Tools, emulator system images, or Gradle 8.13 distribution. An attempt to retrieve the official Gradle binary distribution from the container network failed, and accepting/downloading the Android SDK command-line package would additionally require accepting Google's SDK license agreement outside the user's local Android environment.

Therefore the following are **not** claimed as completed here:

- AGP `assembleDebug` / minified release build;
- Android Lint;
- generated resource / manifest merger compile verification;
- instrumented Compose tests;
- API 26/28/30/33/35/36 emulator installation and interaction;
- physical phone/tablet behavior;
- OEM battery/sysfs differences;
- Play pre-launch report.

These remain release-blocking gates and are intentionally not represented as passed.

## Required next release-quality tests

When opened on a machine with Android Studio/SDK installed, run at minimum:

```bash
./gradlew clean :app:assembleDebug :app:assembleRelease
./gradlew test lint
```

Then validate on API 26, 28, 30, 33, 35 and 36, including:

- Modify Settings denied/granted/revoked while a profile is active;
- force-stop/process death immediately after each individual profile mutation step;
- Battery APIs returning unsupported/sentinel values;
- devices with no readable cpufreq/governor/GPU/PSI files;
- Android Q+ process-memory restrictions;
- Usage Access denied/revoked;
- high font scale, rotation and tablet layouts;
- background/foreground navigation to confirm live telemetry ceases while screens are not observed;
- Battery Saver, low battery and severe thermal states;
- minified release startup and Hilt/Room/DataStore initialization.

ApexTuner deliberately treats these as product verification, not documentation-only tasks.
