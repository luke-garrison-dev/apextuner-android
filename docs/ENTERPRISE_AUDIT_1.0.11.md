# ApexTuner 1.0.11 — enterprise Android audit

**Audit date:** 2026-08-28  
**Application ID:** `com.apextuner.app`  
**Audited input:** ApexTuner 1.0.10 Android Studio source package  
**Delivered version:** `1.0.11` / `versionCode 11`

## Executive result

A holistic source audit was performed across the application shell, shared core, dashboard, cleaner, battery/profile tuning, memory, app manager, network/VPN, tools, privileged gateways, screen recording, settings, automations, widget, Google Play Billing, build configuration, manifests, tests and packaging.

Two correctness/reliability defects were confirmed and corrected:

1. **Battery profile cross-app interference.** Version 1.0.10 could call Android's global master-sync API while applying a Battery profile. Android documents master sync as a global setting that affects all providers/accounts. This meant an ApexTuner optimization could suspend synchronization used by unrelated apps. Version 1.0.11 removes global sync from all new profile planning and keeps only a narrowly scoped one-release migration path that restores the baseline recorded by 1.0.10.
2. **Scheduled Premium work could use stale entitlement state.** Periodic/night workers gated on the in-memory entitlement snapshot without first reconciling it with Google Play. After process recreation or cache expiry, a valid user could be incorrectly treated as Free, while a stale offline state could be less deterministic than desired. The affected workers now perform a bounded Play refresh before Premium gating. Morning rollback is intentionally entitlement-independent so an expired entitlement can never strand a system setting applied overnight.

Three performance/cleanliness improvements were also made without broad behavioral changes:

- removed a duplicate cold-start Billing refresh (the Activity's initial/resume reconciliation remains the single app-entry path);
- moved the night-automation disabled/already-applied short circuit before Play Billing work;
- removed an unused entitlement dependency from the morning restore worker.

The project remains intentionally conservative: it does not claim to change Android kernel governors, defeat thermal management, silently kill other apps, or delete user data without interactive review/confirmation.

## Changes implemented in 1.0.11

### 1. System profile isolation and 1.0.10 migration

Affected files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/apextuner/app/AppViewModel.kt`
- `core/src/main/java/com/apextuner/core/model/AppPreferences.kt`
- `core/src/main/java/com/apextuner/core/datastore/PreferencesRepository.kt`
- `core/src/main/java/com/apextuner/core/tuning/SystemProfilePlanner.kt`
- `core/src/main/java/com/apextuner/core/tuning/SafeSystemTuningController.kt`
- `core/src/test/java/com/apextuner/core/tuning/SystemProfilePlannerTest.kt`
- Battery/Game UI explanatory strings

Corrections:

- `SystemProfileTargets` now contains only ApexTuner-owned, reversible system-setting targets: screen timeout and supported haptic state.
- New Battery/Performance/Gaming profile applications never read or write Android global master sync.
- `READ_SYNC_SETTINGS` was removed.
- `WRITE_SYNC_SETTINGS` is retained **only as a migration permission** so 1.0.11 can undo a global-sync mutation left by 1.0.10. It should be removed in a later release after the supported migration window.
- Old DataStore state is decoded through `legacyOriginalMasterSyncEnabled`; newly saved profiles remove the legacy key.
- `reconcileLegacyState()` runs through the same serialized controller path as profile mutation/recovery, restores the old baseline when present, then clears the migration marker.
- Interrupted transaction recovery remains journaled and idempotent.
- UI wording no longer suggests that Battery/Game profiles pause global account synchronization.

Safety property: a new 1.0.11 profile cannot intentionally change synchronization behavior of mail, contacts, cloud providers or other apps.

Official reference: https://developer.android.com/reference/android/content/ContentResolver

### 2. WorkManager / Premium entitlement consistency

Affected file:

- `feature/settings/src/main/java/com/apextuner/feature/settings/automation/AutomationWorkers.kt`

Corrections:

- `MaintenanceWorker` refreshes Google Play entitlement before performing Premium-only scheduled maintenance checks.
- `NightBatteryProfileWorker` first exits when the feature is disabled/already applied, then refreshes entitlement and gates on the reconciled state.
- cancellation is propagated instead of being translated into retry/success;
- unexpected transient refresh failure returns `Result.retry()` rather than authorizing work from an uncertain state;
- `MorningProfileRestoreWorker` has no Billing dependency and never Premium-gates rollback.

This split is deliberate: authorization-sensitive work must use current entitlement state, while safety cleanup must always remain possible.

Official references:

- https://developer.android.com/google/play/billing/integrate
- https://developer.android.com/jetpack/androidx/releases/work

### 3. Billing cold-start efficiency

The previous application flow could trigger an entitlement refresh from `AppViewModel` initialization and another immediately from `MainActivity.onResume()`. The startup refresh was removed; resume-time reconciliation remains and continues to cover initial foreground entry and later returns from Google Play purchase UI/out-of-app changes.

This reduces duplicate Play Billing work and startup contention without weakening entitlement freshness.

## Feature-by-feature audit

### Application shell / lifecycle

- Hilt application graph and modular feature boundaries are coherent.
- top-level app entry is lifecycle-aware;
- purchase reconciliation occurs at foreground/resume rather than through a permanent polling loop;
- stale game-session/system-setting recovery is performed before normal use;
- no forced portrait/landscape orientation is declared.

Result: **pass**, with the cold-start Billing deduplication implemented in this release.

### Dashboard and device telemetry

- telemetry work is dispatched away from latency-sensitive UI paths;
- storage telemetry has a 10-second cache and serialized cache misses to avoid duplicate system I/O;
- telemetry parser guards reject malformed/invalid samples;
- polling ViewModels use `SharingStarted.WhileSubscribed` so periodic work stops when there are no collectors.

Result: **pass**.

### Storage Cleaner

- no `MANAGE_EXTERNAL_STORAGE` / all-files permission;
- MediaStore, SAF and Photo Picker paths are used;
- Android 11+ destructive MediaStore operations rely on platform user approval where required;
- duplicate hashing is streamed rather than loading entire files into memory;
- scan/discovery counts are bounded to protect memory and UI responsiveness;
- deletion remains interactive and reviewable; scheduled maintenance is advisory and does not silently delete user files.

Google Play policy note: because the app declares `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`, the Play Console photo/video permission declaration must continue to demonstrate why broad library analysis is core functionality and why a picker alone is insufficient. If Play rejects that core-use justification, the correct remediation is to reduce the feature to picker-selected content and remove broad media permissions—not to bypass the policy.

Official references:

- https://support.google.com/googleplay/android-developer/answer/16558241
- https://developer.android.com/training/data-storage/shared/photopicker

Result: **pass with publishing declaration requirement**.

### Battery / system profiles

- mutations are explicit and reversible;
- the `WRITE_SETTINGS` gate is checked before system-setting mutation;
- transaction journaling precedes mutation and recovery is retried on later controller entry if interrupted;
- API 33+ haptic restrictions are respected by not forcing unsupported behavior;
- Battery timeout only tightens the baseline, Performance/Gaming only extend it, and Balanced restores the saved baseline;
- global master sync interference is removed in 1.0.11.

Result: **pass after 1.0.11 correction**.

### Memory

- no fake RAM-booster behavior such as `killBackgroundProcesses()`;
- the feature reports/visualizes memory state rather than disrupting other applications;
- collection is subscription-aware and does not create an unconditional polling loop.

Result: **pass**.

### App Manager

- does not request `QUERY_ALL_PACKAGES`;
- uses Android-supported discovery/launcher surfaces rather than attempting unrestricted package inventory;
- actions remain user-visible and platform-mediated.

Result: **pass**.

### Network / local firewall VPN

- VPN service is bound through `BIND_VPN_SERVICE`;
- the implementation owns one TUN descriptor at a time and guards against stale-close races;
- the VPN is a local filtering/sink mechanism for explicitly selected applications, not a packet-forwarding or payload-inspection tunnel;
- IPv4/IPv6 handling is explicit;
- foreground-service declaration and special-use subtype are present;
- no covert remote forwarding path was found.

Result: **pass**.

### Floating monitor

- overlay access is user-controlled;
- the service is not exported;
- foreground service use is declared as `specialUse` with a manifest subtype explanation;
- sampling is bounded and drag coordinates are clamped to the visible display;
- `FLAG_LAYOUT_NO_LIMITS` is not used.

Result: **pass**. Play Console review must accurately describe the special-use FGS reason.

### Screen recording / MediaProjection

- service is non-exported and typed `mediaProjection`;
- user MediaProjection consent is required;
- foreground-service permission/type are declared;
- capture callback is registered before virtual display creation;
- resource ownership/cleanup is explicit.

Potential enhancement (not auto-applied): API 34+ captured-window resizing/rotation could be handled more dynamically to reduce possible letterboxing during selected-app capture. Safe codec/surface reconfiguration should be validated on physical devices before shipping because a blind reconfiguration can introduce recorder corruption or lifecycle races.

Official references:

- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/develop/background-work/services/fgs/service-types

Result: **pass; device-tested adaptive capture is a future enhancement**.

### Root / Shizuku / privileged tools

- privileged execution is opt-in;
- normal Android operation does not assume root;
- privileged commands are selected from internal allowlisted operations rather than arbitrary user strings;
- command quoting and bounded process execution reduce shell injection/hang risk;
- Shizuku integration remains an explicit advanced path.

Result: **pass**.

### Game / performance tools

- the game-session controller journals reversible state and provides stale-session recovery;
- unsupported kernel/governor/thermal promises are described as privileged/unavailable rather than silently faked;
- no global sync mutation remains in Game/Performance copy or tuning plan.

Result: **pass after 1.0.11 wording/state-isolation correction**.

### Settings, automation and widget

- WorkManager is used for deferrable scheduled work;
- current stable WorkManager 2.11.2 is declared;
- worker failure paths are retry/safe-success oriented rather than destructive;
- morning rollback is never blocked by purchase status;
- widget action targets are at least 48dp and resize minima protect layout legibility.

Result: **pass after 1.0.11 entitlement correction**.

### Google Play Billing

The existing 1.0.10 Billing implementation remains intact apart from the lifecycle/worker integration improvements above:

- Play Billing `9.1.0`;
- centralized exact product IDs and product types;
- prices shown from Google Play `ProductDetails` (`formattedPrice`), not hardcoded currency values;
- current eligible offer token used for subscription checkout;
- checkout refreshes product details rather than launching from stale time-cached details;
- pending/suspended states do not grant Premium;
- acknowledgement applies only to completed unacknowledged purchases and uses bounded transient retries;
- resumed app state re-queries purchases;
- recurring subscription disclosure is present.

Residual security boundary: entitlement verification is client-only because this source package contains no trusted backend/API contract or server credentials. Google explicitly recommends secure-backend purchase verification and server-side acknowledgement/RTDN for stronger tamper, refund/revocation and connectivity resilience. That cannot be safely fabricated inside an Android source-only audit. If a backend is added, migrate purchase-token verification to the Google Play Developer API and use RTDN as the authoritative asynchronous state feed.

Official references:

- https://developer.android.com/google/play/billing/integrate
- https://developer.android.com/google/play/billing/security
- https://developer.android.com/google/play/billing/backend

Result: **client implementation passes; backend hardening remains an architectural option**.

## Foreground services and Android 14+ compliance

The project targets API 36. All foreground services discovered in the source declare a type:

- `mediaProjection` for screen capture;
- `specialUse` for the floating monitor and local firewall VPN use cases, with subtype properties;
- services are non-exported except where the Android system contract requires a provider/service surface.

Android 14+ requires a declared foreground-service type, and `specialUse` additionally requires a service-level subtype property describing the use case. Google Play may review these declarations, so the Play Console disclosure must match the actual implementation.

Official references:

- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/develop/background-work/services/fgs/service-types

## Dedicated performance audit

### Confirmed efficient patterns

- expensive telemetry/storage work is on I/O dispatchers;
- storage telemetry is cached and cache misses serialized;
- UI telemetry polling stops when there are no subscribers;
- cleaner hashing streams fixed-size buffers and discovery is bounded;
- periodic work uses WorkManager instead of resident background loops;
- the VPN descriptor lifecycle prevents stale descriptor ownership leaks;
- the screen-recording stack has explicit cleanup;
- Billing work is serialized/reconciled rather than continuously polled;
- 1.0.11 removes the duplicated cold-start Billing refresh;
- 1.0.11 avoids starting Billing for disabled/already-applied night automation;
- R8/resource shrinking remain enabled for release.

### Performance enhancement intentionally not fabricated

No app-specific, measured Baseline Profile/Macrobenchmark module is shipped. Google recommends generating a Baseline Profile from real critical user journeys for each release and benchmarking the result, preferably on a physical device. A hand-authored profile without measurement would make the package look optimized without proving an improvement, so it was not added in this environment.

Recommended release benchmark journeys:

1. cold start to usable Dashboard (TTID + TTFD);
2. Dashboard telemetry settle;
3. Cleaner open + representative media scan;
4. Tools grid scroll and open/close transitions;
5. Network/VPN configuration screen;
6. Premium screen product load;
7. settings navigation on compact and expanded widths.

Record `StartupTimingMetric`, `FrameTimingMetric`, CPU time and memory where appropriate; compare no-compilation vs Baseline Profile mode on a physical mid-range Android device.

Official references:

- https://developer.android.com/topic/performance/benchmarking/benchmarking-overview
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile

## UI / accessibility / all-screen audit

The 1.0.9 adaptive UI baseline remains intact and was regression-checked in this pass:

- layout decisions derive from currently available window width rather than a fixed device category;
- compact navigation switches to a rail at the 600dp boundary;
- wide content is capped to a readable maximum width;
- metric rows have a narrow-width fallback below 340dp;
- chip/control groups use wrapping layouts instead of clipping;
- Tools uses an adaptive grid;
- home-widget actions maintain 48dp targets;
- Compose typography does not define app typography below 12sp;
- no orientation lock is declared.

This is consistent with Google's core adaptive-layout principle that window size is dynamic (split screen, resize, rotation and fold/unfold). A future refactor can centralize the existing width logic on `currentWindowAdaptiveInfo()` and add explicit Large/Extra-large decisions, but this is an architectural cleanup rather than a current usability defect.

Official references:

- https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes
- https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes

## Build / target compatibility

Current configuration:

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- Android Gradle Plugin `8.11.1`
- Gradle `8.13`
- JDK toolchain `17`
- release minification/resource shrinking enabled

Google's AGP 8.11 compatibility table lists API 36 as supported with Gradle 8.13, Build Tools 35.0.0 and JDK 17. Google Play requires new apps and app updates to target Android 16/API 36 or higher starting 2026-08-31, so this source package is already configured for that requirement.

Official references:

- https://developer.android.com/build/releases/agp-8-11-0-release-notes
- https://developer.android.com/google/play/requirements/target-sdk

## Permissions and security review

Observed permission surface is purpose-limited to network state, Usage Access, Modify System Settings, migration-only sync write, scoped/legacy storage compatibility, media categories, notification policy, notifications, overlay and foreground-service requirements.

Not present:

- `MANAGE_EXTERNAL_STORAGE`
- `QUERY_ALL_PACKAGES`
- `WRITE_SECURE_SETTINGS`
- `KILL_BACKGROUND_PROCESSES`

Other hardening observed:

- `android:allowBackup="false"`;
- `android:usesCleartextTraffic="false"`;
- app-owned PendingIntents are immutable;
- service export state is restricted;
- no arbitrary remote shell/control channel was found.

## Validation strategy and limitations

The source tree was validated with three complementary approaches:

1. project-wide structural/security/regression validator;
2. direct Kotlin/JVM compilation and high-volume execution of production-domain logic that does not require Android framework classes;
3. property-style randomized testing of the actual system-profile planner.

The sandbox used for this audit does not contain an Android SDK/adb and its shell network cannot fetch the Gradle distribution. Therefore this document does **not** claim a local Android Gradle build, Android Lint run, emulator/instrumentation run, Play internal-track purchase or real-device benchmark.

The included CI workflow remains the Android execution gate and covers validation, unit tests, lint, debug/release assembly, release bundle and instrumentation across API 26/28/30/33/35/36. Account-specific Billing validation must be run through a Google Play internal test track/license tester.

See `docs/FINAL_VALIDATION.md` for exact checks executed against the final 1.0.11 tree.

## Remaining release notes / follow-up items

These are not hidden blockers; they are explicit external/architectural follow-ups:

1. **Remove migration-only `WRITE_SYNC_SETTINGS` in a future release** after the 1.0.10 migration window is no longer supported.
2. **Play Console media declaration:** keep broad photo/video access only while full-library analysis is genuinely core functionality.
3. **Play Console FGS declaration:** ensure `specialUse` explanations match the monitor/VPN behaviors in the source.
4. **Billing backend hardening:** optional but strongly recommended for a high-value Premium product; requires real backend ownership and Google Play Developer API setup.
5. **Baseline Profile/Macrobenchmark:** generate and benchmark from measured critical user journeys on physical hardware.
6. **MediaProjection resize polish:** validate API 34+ dynamic captured-content resizing on real devices before changing codec/surface ownership.

## Versioning

The final audited source is intentionally bumped from:

- `versionCode 10`, `versionName 1.0.10`

to:

- **`versionCode 11`, `versionName 1.0.11`**
