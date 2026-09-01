# ApexTuner — final cumulative Android Studio project

ApexTuner is a Kotlin/Jetpack Compose Android device-health, diagnostics, optimization, privacy, and power-management application designed around safe, capability-aware Android APIs. This package is the final cumulative Android Studio source project and includes all implemented phases.

## Product identity

- Product name: **ApexTuner**
- Release candidate source version: **1.1.7** (`versionCode 44`)
- Before every Android Studio/CI release build, run `python tools/release_gate.py` from the project root, then run the real Gradle build (`gradlew.bat clean testDebugUnitTest assembleDebug` on Windows or `./gradlew clean testDebugUnitTest assembleDebug` on macOS/Linux).
- Android application ID: `com.apextuner.app`
- Kotlin/Android namespaces: `com.apextuner.*`
- Room database: `apextuner.db`
- DataStore: `apextuner_preferences`
- Keystore alias prefix: `apextuner.*`

The project has not yet been released under a stable production package identity, so the former development identifier was replaced cleanly instead of carrying legacy branding forward. Existing local debug installs using the former application ID are separate Android applications and should be uninstalled before device testing ApexTuner.

## Platform baseline

- Kotlin / Compose compiler plugin: 2.3.21
- Jetpack Compose + Material 3
- Minimum SDK: API 26 (Android 8.0)
- Compile SDK / Target SDK: API 36 (Android 16)
- Android Gradle Plugin: 9.3.2 (built-in Kotlin)
- Gradle: 9.5.0
- SDK Build Tools baseline: 36.0.0
- JDK: 17
- Architecture: Clean Architecture + MVVM + Repository
- Dependency injection: Dagger/Hilt 2.60.1 via KSP 2.3.9
- Async/state: Kotlin Coroutines + Flow
- Persistence: Room + DataStore Preferences

The original project brief requested API 35. The cumulative project targets API 36 so the release baseline is Android 16 ready. The API-26 minimum remains unchanged.

## Modules

- `app`
- `core`
- `feature:dashboard`
- `feature:cleaner`
- `feature:battery`
- `feature:memory`
- `feature:appmanager`
- `feature:network`
- `feature:notifications`
- `feature:files`
- `feature:contacts`
- `feature:tools`
- `feature:settings`
- `feature:billing`

## Part 1 — application foundation

- Multi-module Android Studio project.
- Hilt application setup.
- Bottom navigation and feature boundaries.
- Dark-first Material 3 theme with dynamic color support.
- R8/resource shrinking release configuration.
- Edge-to-edge activity and portrait/landscape-compatible layout foundation.

## Part 2 — core platform layer

- Core models for device, memory, storage, battery, network, thermal, scans, optimization history and preferences.
- Explicit capability model instead of simulated privileges.
- Room persistence for scan/action history.
- DataStore preferences.
- Android Keystore AES-256/GCM local encrypted storage.
- Hilt repositories/providers and coroutine dispatcher qualifiers.
- Automatic Android backup disabled and cleartext network traffic disabled.
- Root is never silently invoked or treated as authorized merely because `su` is detected.

## Part 3 — Device Dashboard

- RAM, storage, battery, network, thermal, uptime and CPU-core telemetry.
- Best-effort CPU/GPU telemetry with explicit unavailable states.
- Network throughput derived from monotonic deltas of cumulative counters.
- Lifecycle-aware foreground sampling and bounded chart history.
- Responsive phone/tablet UI and actionable recommendation cards.
- Cancellation-safe telemetry pipeline and bounded consecutive-failure retry behavior.

See `docs/PART3_VALIDATION.md` for its detailed validation boundary.

## Part 4 — Storage Cleaner

### Supported data sources

ApexTuner scans only sources Android legitimately exposes to it:

1. **MediaStore** for shared images, videos and audio when the corresponding user permission is granted.
2. **Storage Access Framework (SAF) folders** explicitly selected by the user.
3. **SAF documents** explicitly selected by the user.
4. **Android Photo Picker selections** as a privacy-preserving, read-only alternative to broad visual-media access.
5. **StorageStatsManager cache insight** only when the user grants Usage Access.

The UI never implies access to restricted Android locations that the platform does not grant.

### Scanning and performance controls

- Streaming cursors; no whole-library materialization before classification.
- Hard ceiling of 100,000 discovered items per scan.
- Hard ceiling of 20,000 visited SAF directories per scan.
- Relative/path text capped to 1,024 characters.
- Cooperative coroutine cancellation while scanning and hashing.
- Progress updates are batched rather than emitted for every file.
- `StorageStatsManager` work runs on the IO dispatcher.
- Exact duplicate hashing streams file content and never loads large files wholly into RAM.

### Large files and storage analyzer

- Large-file threshold: 10 MiB.
- Category breakdown for image, video, audio, document, archive, APK, temporary, log, empty folder and other content.
- Accessible-byte totals collapse Android-proven aliases so one physical item is not counted twice simply because it is visible through both MediaStore and SAF.
- Potential-reclaim calculations are saturating/overflow-safe and do not double-count an item that appears in more than one review category.

### Exact duplicate detection

- Files are first grouped by size and a safe source/physical-identity scope.
- A 256 KiB streaming SHA-256 prefix digest narrows candidates.
- Candidate copies are then verified with a full streaming SHA-256 digest using a bounded buffer.
- Android-proven aliases of one physical item are collapsed and are never presented as two duplicate copies.
- Unrelated/unmapped document-provider authorities remain isolated rather than being guessed equivalent.
- Smart selection offers **Keep best quality** and **Keep newest** independently.
- Reclaimable duplicate bytes count only redundant copies that ApexTuner can actually remove with the current grant.

### Review-oriented junk detection

The cleaner is intentionally conservative. Strong temporary/log signatures can be bulk selected; weaker heuristics remain manual review items. Empty folders, APK/installers and cache-like folder matches are never silently selected simply because of their location/name.

### Deletion safety

- No user file is removed without an explicit selection and review dialog.
- Android 11+ MediaStore removals use Android's system confirmation UI through `MediaStore.createTrashRequest()` or `createDeleteRequest()`.
- **Move to system Trash** is the safer default when applicable; permanent deletion is a distinct action.
- API-36 MediaStore requests are capped at 2,000 URIs, matching the platform contract.
- Direct SAF/provider deletions are capped at 500 selected items per operation to reduce provider timeout/partial-operation risk.
- One physical item exposed through multiple Android URI aliases is processed only once. When both a MediaStore and writable SAF alias exist on Android 11+, MediaStore is preferred so the system confirmation/Trash path is retained.
- Direct document-provider deletion runs only after the user has confirmed the reviewed operation and is bounded/non-cancellable during the small commit phase; the UI prevents overlapping scans/access changes/removals.
- Android 8–9 use legacy shared-media write permission only when the user explicitly chooses deletion. Scanning asks for read access only.
- Android 10 MediaStore results are intentionally read-only in this implementation rather than pretending that legacy write permission bypasses scoped-storage ownership rules. Explicit SAF grants remain actionable.
- Photo Picker results are read-only.
- If ApexTuner is recreated while Android's MediaStore confirmation UI is open, a successful system result is treated as authoritative, **but lost SAF selections are never guessed or resumed**. ApexTuner performs a fresh reconciliation scan instead.

### Permission strategy

ApexTuner requests storage/media access incrementally and only from user-invoked cleaner actions.

- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`: optional broad visual-library scan on Android 13+.
- `READ_MEDIA_VISUAL_USER_SELECTED`: supports Android's selected-media model on Android 14+.
- `READ_MEDIA_AUDIO`: optional shared-audio scan.
- `READ_EXTERNAL_STORAGE` (maxSdk 32): legacy shared-media read access.
- `WRITE_EXTERNAL_STORAGE` (maxSdk 29): requested separately only for user-initiated legacy deletion on Android 8–9; it is not required for scanning.
- `PACKAGE_USAGE_STATS`: declared for optional StorageStats/cache insight; the user must grant Usage Access in Settings.

`MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES` are **not declared**.

Broad visual-library permissions require a legitimate core use case and the corresponding Google Play Console declaration. ApexTuner also exposes Photo Picker and SAF alternatives so users can choose narrower access.

### Photo Picker compatibility

The manifest contains Android's documented disabled `com.google.android.gms.metadata.ModuleDependencies` metadata service so supported API 26–29/older devices with Google Play services can receive the Photo Picker backport. No custom gallery picker is used for this fallback.

## Part 5 — Battery, Memory/RAM and CPU/Performance

### Battery Intelligence

- Dedicated battery reader avoids re-running unrelated dashboard telemetry.
- Level, health, temperature, voltage, signed instantaneous/average current, charge/energy counters, charging source, technology and API-34+ cycle count.
- Android Battery Saver, thermal status, API-31+ discharge prediction and calling-app standby bucket.
- Optional 24-hour UsageStats activity ranking is cached for 60 seconds and is explicitly presented as foreground-activity evidence, not measured battery drain.
- Conservative charging/thermal/low-battery guidance; device signals are never presented as medical/repair diagnoses.

### Memory Intelligence

- `ActivityManager.MemoryInfo`, swap and PSI memory pressure.
- ApexTuner PSS/private-dirty, Java heap, native heap, trim level and process importance.
- Bounded Android-reported process list without pretending Android exposes reliable per-app RAM for unrelated UIDs.
- No fake RAM booster and no `killBackgroundProcesses()` implementation. Android 14 explicitly warns that unnecessary cached-process killing can worsen performance/battery use.

### CPU & Performance Intelligence

- CPU utilization, current core frequencies, min/max frequency and governor where readable.
- GPU utilization where OEM/kernel interfaces expose a supported signal.
- Thermal state, I/O schedulers and TCP congestion-control algorithm.
- Root detection remains potential-capability information only. Privileged governor/LMK/VM/secure-setting changes remain locked for the later explicit Shizuku/root layer.

### Safe reversible profiles

- Balanced, Battery, Performance and Gaming share one serialized tuning controller.
- Only legitimate stock-Android setting changes are used.
- Original values are persisted before mutation and restored on failure/cancellation.
- A persisted transaction-pending journal now detects process death during a profile change and self-heals to the saved baseline on the next controller entry when Modify Settings access remains available.
- API 33+ no longer changes the deprecated global haptic setting.
- Profile polling/action UIs use lifecycle-aware `SharingStarted.WhileSubscribed`; telemetry stops when the corresponding screen is no longer collected.

See `docs/PART5_VALIDATION.md` for the extended JVM/fault-injection/regression test results and the remaining real-Android release gates.

## Part 6 — App Manager, Network/Firewall, Privacy & Security, Advanced Access

### App Manager

- Privacy-preserving installed-app inventory based on Android package visibility / `LauncherApps`; `QUERY_ALL_PACKAGES` is not requested.
- Search, user/system filtering, evidence-based insight filters, sorting, version/install/update/last-use metadata where Android exposes it.
- A metadata-only inventory summary surfaces permission-heavy apps, legacy target SDKs, 30-day install/update activity, stale usage (when Usage Access is granted), and unknown installers without triggering expensive per-app storage/network scans.
- Heavy per-app storage/network detail is loaded only when an app is opened, and repository work runs off the main thread.
- Uninstall, notification and app-detail actions are routed through Android-owned confirmation/settings surfaces.
- Dangerous-permission state is informational; ApexTuner does not claim stock Android can silently revoke or force-stop unrelated apps.

### Network diagnostics and local firewall

- Connectivity, transport, validation, metered/captive-portal state, MTU, DNS and Private DNS reporting.
- Network Quality Lab uses a fixed total probe budget across IPv4/IPv6, reports family-specific reliability/latency, and prefers the more reliable route before latency when selecting the headline endpoint.
- Historical network totals and per-visible-app/UID usage through `NetworkStatsManager` when Usage Access is granted; shared UID attribution is explicitly identified.
- Premium local firewall implemented with Android `VpnService`. Only user-selected packages are routed into ApexTuner’s local TUN sink and discarded on-device; unselected apps bypass the tunnel.
- The firewall never forwards payload traffic to an ApexTuner endpoint and does not inspect/store browsing payloads.
- Dedicated in-app prominent disclosure appears before Android VPN consent.
- Always-on VPN is explicitly disabled because the selected-app local sink is not designed for that operating mode.
- Firewall selection updates are FIFO-serialized and locked while the tunnel is starting/active.
- Stale/uninstalled package selections are pruned.
- Tunnel descriptor replacement uses guarded resource ownership so an obsolete reader cannot close a newly established tunnel.
- The service promotes itself to foreground synchronously before DataStore/I/O work, then updates the notification after configuration is loaded.

### Privacy & Security

- Screen-lock posture, installation-source posture and explicit root-potential reporting.
- User-triggered clipboard clear only; ApexTuner does not run a hidden clipboard-history monitor.
- Unsupported or non-authoritative security signals remain unavailable instead of contributing fabricated risk scores.

### Shizuku/root advanced layer

- Shizuku API `13.1.5`, maintained `UserService` flow, stable tag/version and non-daemon lifetime.
- Reserved AIDL destroy transaction is implemented so remote privileged processes can be cleaned up.
- No arbitrary terminal or user-supplied shell text. Privileged actions are typed and allow-listed.
- Root authorization occurs only after explicit user action.
- Shared `BoundedProcessRunner` limits output and execution time and destroys child processes on timeout/interruption/cancellation.
- Privileged animation-scale writes validate numeric ranges, snapshot the previous values and immediately roll back partial multi-setting failure; saved baselines remain available for explicit Restore.

See `docs/PART6_VALIDATION.md` for executable production-source/fault-injection results and remaining real-device release gates.

## Privacy and security posture

- Storage analysis is local-only.
- No scanned file contents, names, hashes, media metadata or device telemetry are uploaded by ApexTuner.
- The local VPN firewall is an on-device packet sink: selected-app traffic is discarded locally and is not forwarded to an ApexTuner server.
- Cleartext networking is disabled at the application level.
- Android automatic cloud backup is disabled.
- URI grants remain Android-scoped and user mediated.
- `MediaStore.getMediaUri()` is used only as an identity hint where Android can map an equivalent SAF document to MediaStore; it is never treated as additional permission.
- Unavailable privileges/metrics fail closed instead of being simulated.

## Dependency compatibility decisions for Part 5

ApexTuner uses targeted stable AndroidX updates rather than a blanket newest-version policy:

- Activity Compose `1.13.0`: includes the Photo/Video ActivityResultContract fix for devices carrying the latest URI-security fixes, and Activity 1.11+ is compiled against API 36.
- Navigation Compose `2.9.8`: includes the predictive-back `NavHost` race/NPE hardening while staying on the 2.9 line.
- DataStore Preferences `1.2.1`: current stable maintenance release; its release notes describe infrastructure fixes without API/behavior changes from 1.2.0.
- WorkManager `2.11.2`: current stable 2.11 maintenance release with background-network, SecurityException, and periodic-work rescheduling fixes. Its minSdk is 23 and compileSdk requirement is 33+, both compatible with ApexTuner (minSdk 26 / compileSdk 36).
- Lifecycle stays on `2.8.7` because that line is already validated across ApexTuner's API-26–36 behavior and changing lifecycle semantics in this maintenance pass would add regression risk without fixing a confirmed defect. The project itself now uses the audited API-36 / AGP-9.3.2 / Gradle-9.5.0 baseline.
- Room remains pinned for this Part because changing the persistence compiler/runtime without a real AGP/Room schema build would increase migration risk for no Part-5-specific benefit.

## Current official platform references

- Android Gradle Plugin compatibility/current 9.3 line: https://developer.android.com/build/releases/about-agp
- AGP 9.3 release notes: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Android 14 cached-process / `killBackgroundProcesses()` behavior: https://developer.android.com/about/versions/14/behavior-changes-all
- ActivityManager memory API restrictions: https://developer.android.com/reference/android/app/ActivityManager
- BatteryManager: https://developer.android.com/reference/android/os/BatteryManager
- UsageStatsManager / standby buckets: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- WRITE_SETTINGS: https://developer.android.com/reference/android/Manifest.permission
- ContentResolver master-sync settings: https://developer.android.com/reference/android/content/ContentResolver
- Settings.System haptic deprecation: https://developer.android.com/reference/android/provider/Settings.System
- MediaStore (`createTrashRequest`, `createDeleteRequest`, `getMediaUri`): https://developer.android.com/reference/android/provider/MediaStore
- Android Photo Picker and backport: https://developer.android.com/training/data-storage/shared/photo-picker
- Android 11 storage/SAF restrictions: https://developer.android.com/about/versions/11/privacy/storage
- Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- StorageStatsManager: https://developer.android.com/reference/android/app/usage/StorageStatsManager
- Google Play sensitive-media permission policy: https://support.google.com/googleplay/android-developer/answer/16558241
- Android VpnService contract: https://developer.android.com/reference/android/net/VpnService
- Android foreground-service launch requirements: https://developer.android.com/develop/background-work/services/fgs/launch
- Android `specialUse` foreground service type: https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE
- Android package visibility: https://developer.android.com/training/package-visibility/declaring
- Android NetworkStatsManager: https://developer.android.com/reference/android/app/usage/NetworkStatsManager
- Google Play VpnService policy/declaration: https://support.google.com/googleplay/android-developer/answer/12564964
- Shizuku API/UserService documentation: https://github.com/RikkaApps/Shizuku-API

## Build

Required local tooling:

- JDK 17
- Android SDK Platform 36
- SDK Build Tools 36.0.0
- network access on first command-line bootstrap so the verified Gradle 9.5.0 distribution can be downloaded

Open the repository root directly in Android Studio. This package ships the canonical Gradle 9.5.0 `gradle-wrapper.jar` (SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`) so Android Studio Sync uses the official Wrapper Main class. Command-line `gradlew` / `gradlew.bat` still verify Gradle 9.5.0 against the official distribution SHA-256 in `gradle/wrapper/gradle-wrapper.properties` before executing it.

macOS/Linux:

```bash
./gradlew clean :app:assembleDebug
./gradlew testDebugUnitTest lintDebug :app:assembleRelease :app:bundleRelease
```

Windows:

```bat
gradlew.bat clean :app:assembleDebug
gradlew.bat testDebugUnitTest lintDebug :app:assembleRelease :app:bundleRelease
```

For organizations that require the canonical generated Gradle Wrapper JAR, `gradle/wrapper/README.md` documents the trusted regeneration command.

## Release validation boundary

This environment does not provide an Android SDK or emulator, so this package does **not** claim a successful AGP Android compilation, emulator matrix, instrumented test run or physical-device test. The canonical Gradle 9.5.0 `gradle-wrapper.jar` is included so Android Studio can Sync the project.

Completed source-level/JVM regression checks and required remaining gates are summarized in `docs/FINAL_VALIDATION.md`. The 1.0.20 Battery Profile correctness repair is documented in `docs/BUILD_REPAIR_1.0.20.md`; the preceding holistic application audit remains in `docs/ENTERPRISE_AUDIT_1.0.15.md`.

Before shipping any release, run at minimum:

- clean debug and minified release builds;
- lint and all unit tests;
- Compose/instrumented cleaner flows;
- Android API 26, 28, 30, 33, 35 and 36 emulator/device coverage;
- at least one physical phone plus one representative tablet;
- large-library, low-storage, permission-denial, revoked-grant, process-death, provider-failure and cancellation tests;
- Google Play pre-launch report and policy declarations for any restricted permission retained in the release manifest.

## Final implemented phase

The cumulative project also includes:

- Google Play Billing 9.1.0 with one non-consumable lifetime Premium product (`apextuner_premium_lifetime`), pending-state protection, acknowledgement, restore/re-query, encrypted bounded offline grace, and shared lifetime-Premium feature gating. No subscription product or subscription flow is present.
- Settings backed by DataStore, theme/dynamic-color controls, advanced-tool visibility, monitor controls, automation controls, local privacy/data-deletion information, and Play purchase management entry points.
- Premium real-time floating monitor, Quick Settings tile, passive home-screen widget, and WorkManager-based scheduled maintenance/profile automation.
- System Information covering device/build/kernel/CPU/RAM/storage/display/sensors/cameras/battery/DRM and supported security posture.
- Premium Game Session Booster using reversible profiles, optional DND integration, safe game launch, persisted session restoration, and a failsafe worker.
- User-consented MediaProjection screen recording to MediaStore using H.264 surface encoding, no microphone permission, bounded geometry/bitrate, foreground-service ownership, serialized startup/stop/finalization lifecycle, idempotent cleanup, and deletion of failed or incompletely finalized output.
- SAF local backup/restore for ApexTuner preferences and informational visible-app inventory; security-sensitive billing/Keystore/privileged state is deliberately excluded.
- APK backup for exposed applications, including base + split APKs in a streamed ZIP with SHA-256 metadata.
- Conventional JUnit tests, Compose instrumentation smoke tests, and GitHub Actions CI for lint/unit/build plus emulator coverage across API 26, 28, 30, 33, 35, and 36.

The repository intentionally does not claim capabilities that stock Android or Google Play do not safely permit. Privileged operations remain explicit, capability-gated, reversible where possible, and never silently enabled.


## v1.1 expansion

The v1.1 release candidate extends the cleaner with perceptual near-duplicate review, blur review, and local media re-encoding; adds opt-in local notification history, local network diagnostics, a SAF file manager, contact duplicate review/merge, per-app data-usage alerts, and battery-health history; deepens the typed Pro Shizuku/root layer with user-directed app control and reversible CPU policy tuning; and adds Quick Settings tiles, storage/battery widgets, scheduled versioned SAF backups with retention, and a localization cleanup.

The system/Dalvik cache action remains explicitly unavailable because Android does not expose a rollback-safe primitive that satisfies ApexTuner's reversible-mutation policy. Android thermal protection is never disabled or bypassed.
