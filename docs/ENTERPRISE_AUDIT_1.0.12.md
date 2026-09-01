# ApexTuner 1.0.12 — enterprise Android audit

**Audit date:** 2026-08-28  
**Application ID:** `com.apextuner.app`  
**Delivered version:** `1.0.12` / `versionCode 12`  
**Baseline:** minSdk 26, compileSdk/targetSdk 36, AGP 8.11.1, Gradle 8.13, JDK 17

## Executive result

The complete multi-module source tree was re-audited as one system rather than as isolated screens. Two concrete reliability defects were corrected in 1.0.12:

1. **Periodic automation could drift from the intended local clock time across daylight-saving transitions.** The previous scheduler aligned only the initial WorkManager run and then repeated at a fixed 24-hour or 7-day elapsed interval. Night Battery (22:00), Morning Restore (07:00), and scheduled maintenance (03:00) could therefore move by an hour after DST changes. 1.0.12 uses WorkManager's `setNextScheduleTimeOverride()` and updates the same periodic work ID after successful/skipped executions so the next run is calculated from the local calendar. Retry paths deliberately retain WorkManager backoff behavior.
2. **The floating monitor was clamped while being dragged but was not re-clamped after a rotation/display-size change.** A position that was valid in a wide window could become inaccessible in a narrower one. 1.0.12 recomputes its maximum width and clamps its coordinates after configuration changes.

No other source-level blocker was confirmed. Existing safety architecture around cleaner deletion, system-profile rollback, VPN ownership, MediaProjection, Billing state, local backup, package visibility, root/Shizuku, foreground services, and telemetry lifecycle remains intact.

## 1. Platform and build baseline

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26` across the applicable modules.
- Google Play's 2026 target-API transition is therefore already satisfied for updates requiring Android 16 / API 36.
- The launcher activity does not force an orientation.
- `MainActivity` uses edge-to-edge support and the Compose shell consumes scaffold/system padding rather than relying on legacy opt-outs.
- No legacy `onBackPressed()` or `KEYCODE_BACK` interception was found, avoiding Android 16 predictive-back incompatibility.
- Gradle 8.13 is pinned with an explicit SHA-256 and CI uses JDK 17/API 36.

Official references:
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/build/releases/agp-8-11-0-release-notes

## 2. Cross-feature system tuning safety

`SafeSystemTuningController` remains the central mutation authority and provides:

- a mutex around mutations;
- deterministic latest-request ordering using an atomic request sequence;
- a persisted mutation journal before Android settings are changed;
- rollback/recovery after failure, cancellation, or process interruption;
- exact baseline restoration for the Balanced profile;
- conservative Battery/Performance/Gaming timeout targets;
- Android-version-aware haptic handling;
- no new read or mutation of Android's global master-sync setting.

The `WRITE_SYNC_SETTINGS` permission remains **migration-only** so a user upgrading directly from 1.0.10 can have the global sync state restored if that older build changed it. New 1.0.12 profile planning has no master-sync target. Removing this migration permission immediately would create a worse failure mode for direct upgraders, so it is retained intentionally for the supported migration window.

## 3. Scheduled automation — corrected in 1.0.12

### Confirmed issue

The former `setInitialDelay()` + fixed periodic interval model did not preserve wall-clock semantics across DST. A job intended for 22:00 could run around 21:00/23:00 after an offset change.

### Fix

- Initial scheduling uses `PeriodicWorkRequest.Builder.setNextScheduleTimeOverride()`.
- Successful/skipped periodic executions call `WorkManager.updateWork()` with the **same work UUID** and a newly calculated local-time override.
- Daily jobs use the next local calendar day; weekly maintenance uses seven local calendar days.
- The existing `ExistingPeriodicWorkPolicy.UPDATE` strategy is preserved.
- Failed Billing/telemetry/profile attempts still return `Result.retry()` **without** applying a next-time override, preserving standard WorkManager backoff.
- Disabled scheduled maintenance now exits before opening Billing or collecting telemetry, reducing unnecessary work if a stale WorkSpec survives a state transition.
- Morning rollback remains entitlement-independent, so an expired subscription can never strand a system profile that ApexTuner itself applied.

Google specifically documents next-schedule overrides as suitable for recurring wall-clock work that should run without drift and notes that actual execution can still be delayed by Doze, constraints, and the OS scheduler.

Official references:
- https://developer.android.com/reference/androidx/work/PeriodicWorkRequest.Builder#setNextScheduleTimeOverride(long)
- https://developer.android.com/reference/androidx/work/WorkManager#updateWork(androidx.work.WorkRequest)

## 4. Dashboard and telemetry

The telemetry architecture remains appropriately conservative for an optimization product:

- snapshots run off the main thread;
- polling is lifecycle/subscription-aware;
- screen-level refresh periods are measured in seconds rather than frames;
- storage metadata is cached/serialized to avoid repeatedly running relatively expensive volume/statistics work;
- CPU deltas require valid monotonic samples and reject malformed/regressing counters;
- GPU/sysfs discovery is lazy and best-effort;
- network rates derive from monotonic counter deltas and fail closed on reset;
- parsing code rejects malformed/overflowing input;
- UI histories are bounded.

No background “always-on” telemetry loop was found outside user-started foreground functionality.

## 5. Performance audit

ApexTuner does not undermine its own purpose through aggressive monitoring or fake optimization:

- no `killBackgroundProcesses()` RAM-booster behavior;
- no `GlobalScope`, unbounded polling loop, or frame-level hardware sampling;
- feature ViewModels use `SharingStarted.WhileSubscribed` where continuous telemetry is exposed;
- expensive storage/file work is performed on IO dispatchers and is bounded/cancellable;
- duplicate hashing streams data instead of loading files whole into memory;
- cleaner enumeration has hard ceilings on discovered items/directories;
- VPN traffic is discarded locally without packet inspection/remote forwarding;
- foreground monitor sampling is a user-started 2-second cadence and stops with the service;
- scheduled maintenance is advisory and does not run destructive cleaner logic in the background;
- release minification and resource shrinking remain enabled.

An app-specific Baseline Profile was **not fabricated**. Google recommends deriving profiles from representative critical journeys and validating them with Macrobenchmark/physical hardware. That remains an appropriate release-performance enhancement once a device build environment is available.

Official reference:
- https://developer.android.com/topic/performance/appstartup/best-practices

## 6. Storage Cleaner

The cleaner remains review-first and scoped to Android-authorized sources:

- MediaStore, selected Photo Picker media, SAF folders/documents, and optional StorageStats insight;
- no `MANAGE_EXTERNAL_STORAGE`;
- no unrestricted filesystem crawler;
- Photo Picker items are treated as read-only;
- SAF deletion requires an actual writable persisted/provider grant;
- API 30+ MediaStore destructive operations use Android confirmation via `createTrashRequest()` / `createDeleteRequest()`;
- API-36 request batches are capped at 2,000 URIs;
- SAF direct deletion batches are bounded;
- MediaStore/SAF aliases are collapsed to avoid double counting/deletion;
- exact duplicate detection uses streaming prefix/full SHA-256 checks and provider/physical-identity isolation;
- operation state prevents overlapping scan/access/removal flows;
- process-recreation handling never guesses lost direct-delete selections.

Android 14 selected-photo access is implemented with `READ_MEDIA_VISUAL_USER_SELECTED`, and the UI provides Photo Picker/SAF alternatives. Broad image/video access remains a **Google Play declaration requirement** because the cleaner's full-library scan is a core feature; retaining the permission in source does not itself satisfy the Play Console declaration.

Official references:
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- https://developer.android.com/training/data-storage/shared/photo-picker
- https://developer.android.com/reference/android/provider/MediaStore
- https://support.google.com/googleplay/android-developer/answer/16558241

## 7. Battery, memory, and performance tools

- Battery recommendations use device signals and do not pretend to provide repair/medical diagnoses.
- Memory screens explicitly avoid the obsolete “free RAM is always better” model.
- System profiles are reversible and based on settings the app can legitimately change.
- Advanced tuning does not silently invoke root; privileged actions are explicit, typed, bounded, and user-authorized.
- Shizuku/root command execution is constrained by a shared bounded runner and allow-listed operations rather than arbitrary terminal text.

## 8. App Manager

- Installed-app inventory is intentionally based on Android package visibility / `LauncherApps` rather than `QUERY_ALL_PACKAGES`.
- Package names are validated before privileged/network use.
- Shared-UID network data is represented as shared rather than inventing per-package attribution.
- APK backup is streamed and bounded rather than loading package artifacts into RAM.

This limits visibility compared with a device-owner/root utility but is the correct stock-Android privacy posture.

## 9. Local VPN firewall

`ApexFirewallVpnService` retains the expected Android VPN contract:

- protected by `BIND_VPN_SERVICE`;
- correct `android.net.VpnService` intent action;
- user consent through `VpnService.prepare()`;
- synchronous foreground promotion before asynchronous work;
- selected apps are explicitly added to the VPN and traffic is discarded locally;
- unselected apps bypass the tunnel;
- no payload inspection, analytics upload, proxy forwarding, or hidden remote endpoint;
- tunnel descriptor replacement is guarded to avoid stale-close races;
- stale/uninstalled package selections are pruned;
- Always-on VPN is intentionally unsupported for this selective user-controlled firewall model.

Official references:
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://support.google.com/googleplay/android-developer/answer/12564964

## 10. Screen recording and foreground services

The recorder:

- obtains user MediaProjection consent before acquiring the projection;
- promotes the service with the `mediaProjection` foreground-service type;
- does not request microphone access;
- writes through MediaStore with pending-output semantics where available;
- bounds geometry/bitrate and uses even dimensions suitable for H.264;
- drains encoder output off the service main thread;
- tears down projection/codec/surface/display resources and deletes failed output.

The monitor/VPN use declared `specialUse` foreground-service metadata. Their services promote synchronously before asynchronous work, matching modern foreground-service requirements.

Official references:
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://developer.android.com/develop/background-work/services/fgs/service-types

## 11. Floating monitor — corrected in 1.0.12

The monitor already:

- requires Premium and overlay authorization;
- starts as a foreground service;
- uses a single sampling job;
- stops on entitlement loss/service destruction;
- clamps user drag coordinates;
- avoids `FLAG_LAYOUT_NO_LIMITS`.

1.0.12 additionally re-measures the TextView maximum width and clamps `x/y` after `onConfigurationChanged()`. This protects rotation, split-screen-like size changes, and other display-configuration transitions from leaving the overlay outside its usable bounds.

## 12. UI, accessibility, and adaptive layout

Static UI review and regression rules confirm:

- adaptive top-level NavigationBar/NavigationRail switch at wide widths;
- wide content has a maximum content width rather than stretching indefinitely;
- shared horizontal padding adapts to available width;
- metric rows stack on very narrow widths;
- chip groups use wrapping `FlowRow` where clipping would otherwise occur;
- tools use an adaptive grid;
- cleaner selection rows expose checkbox/radio semantics instead of duplicate accessibility actions;
- widget actions retain at least 48dp touch height and resize bounds;
- Compose typography baseline does not define sub-12sp app typography;
- the manifest does not force phone-only orientation;
- target-36 edge-to-edge behavior is accounted for.

A physical-device/font-scale/screen-reader pass remains required because static source inspection cannot prove rendered clipping or TalkBack traversal on every OEM/device.

Official references:
- https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts
- https://developer.android.com/about/versions/16/behavior-changes-16

## 13. Billing

The Google Play Billing 9.1.0 implementation was cross-checked for state and entitlement safety:

- product details/prices are retrieved from Google Play; no user-facing hardcoded currency price is used;
- recurring subscription disclosure is displayed;
- pending purchases are enabled;
- `PENDING` does not unlock Premium;
- suspended subscriptions remain locked;
- purchase queries occur on foreground/resume paths to reconcile out-of-app and cross-device transitions;
- only recognized package/product/type combinations can grant entitlement;
- unacknowledged completed purchases are acknowledged;
- acknowledgement is guarded to completed recognized purchases;
- local entitlement grace is encrypted and bounded rather than becoming indefinite local authority;
- the app provides Play subscription-management routing.

### Remaining enterprise hardening boundary

Google strongly recommends verifying purchase tokens on a secure backend before granting benefits and using Real-time Developer Notifications / Developer API state for authoritative lifecycle handling. ApexTuner is currently client-centric. Adding a pretend/local “server verification” layer would not improve security, so no unsafe placeholder was added in 1.0.12. A real backend should be the next Billing hardening step if server infrastructure is available.

Official reference:
- https://developer.android.com/google/play/billing/integrate

## 14. Backup, privacy, and local security

- automatic Android cloud backup is disabled;
- cleartext network traffic is disabled;
- secure local values use Android Keystore AES-256/GCM;
- secure-store data is explicitly excluded from extraction/backup rules as defense in depth;
- exported JSON backup is size-bounded and validates schema/package/preferences;
- entitlement, Keystore secrets, active privileged authorization, VPN runtime state, and active system-profile ownership are intentionally excluded from restore;
- backup restore never imports `nightBatteryProfileAppliedByAutomation` or a system-profile mutation journal.

## 15. Code hygiene

- No production `TODO`, `FIXME`, or `NotImplementedError` markers remain.
- No `GlobalScope` or fake RAM-kill API was found.
- No generated `build`, `.gradle`, `.idea`, `local.properties`, APK, or AAB output is shipped.
- The obsolete delay-only automation helper became unused after the WorkManager wall-clock correction and was removed rather than retained as dead production code.
- Existing historical audit documents are preserved as release history; current delivery status is in this file and `FINAL_VALIDATION.md`.

## 16. Validation actually executed for 1.0.12

See `docs/FINAL_VALIDATION.md` for exact commands and boundaries. Current-source results include:

- source/project invariant validator: **PASS — 1,185 checks**;
- recompiled JVM unit suite: **PASS — 57 test methods / 18 classes**;
- production-domain fuzz/regression harness: **PASS — 50,018 checks**;
- system-profile property harness: **PASS — 900,000 checks**;
- scheduler compile/behavior harness with deterministic WorkManager stubs: **PASS — 5 checks**.

The sandbox cannot resolve/download the Gradle distribution and has no Android SDK/emulator, so Android Gradle build, Lint, instrumentation, Play purchase testing, MediaProjection, overlay/VPN special-access flows, and physical-device performance measurements are explicitly left as external release gates rather than falsely reported as passed.

## 17. Release decision

**Source-level audit status: PASS after the 1.0.12 corrections, with external Android/Google infrastructure gates documented.**

The package is suitable to open in Android Studio. Before a production Play rollout, execute the included CI or equivalent Android SDK build matrix, internal-track Billing tests, Play pre-launch report, and the required sensitive-media/VPN/foreground-service declarations.
