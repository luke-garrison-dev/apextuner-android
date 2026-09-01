# ApexTuner 1.0.15 — enterprise Android audit

**Audit date:** 2026-08-28  
**Application ID:** `com.apextuner.app`  
**Delivered version:** `1.0.15` / `versionCode 15`  
**Android baseline:** minSdk 26, targetSdk 36, compileSdk 36  

## Executive result

The complete multi-module source tree was reviewed as one system: app shell, telemetry, cleaner, battery, memory, app manager, network/firewall, advanced tools, game-session tuning, screen recording, settings/automation, floating monitor, backup/restore, and Google Play Billing. The release keeps the existing safety architecture and includes one concrete reliability correction found in this audit.

### Confirmed issue corrected in 1.0.15

**Floating monitor telemetry could become permanently stale after one transient sampling exception.** `DeviceRepository.observeSnapshots()` is intentionally an exception-transparent flow. The monitor previously used a terminal `catch`, displayed “temporarily unavailable,” and then left the foreground service active after the flow completed. A transient OEM/sysfs/provider failure could therefore strand an apparently active monitor without further samples.

The monitor now uses cancellation-safe `retryWhen`, clears the network-rate baseline before resuming, and applies exponential retry delays of 1, 2, 4, 8, 16, then at most 30 seconds. This avoids a hot failure loop while allowing the user-started monitor to recover automatically. Unit/domain regression guards cover the bounded retry policy.

## 1. Architecture and cross-feature safety

- Hilt-scoped repositories keep Android I/O and capability access outside Compose rendering.
- Compose screens collect state with lifecycle-aware APIs; no raw `collectAsState()` usage was found in UI routes.
- Potentially conflicting system tuning is serialized and journaled by `SafeSystemTuningController`; rollback/recovery is explicit.
- Game Session Booster restores only settings it still owns. If the user or another ApexTuner feature selected a newer profile, the newer choice wins.
- Scheduled morning restoration is intentionally entitlement-independent so an expired subscription cannot strand a setting changed while Premium was active.
- VPN, screen recording, overlay monitor, cleaner removal, DND changes, WRITE_SETTINGS changes, Shizuku and root access are all opt-in/capability-gated rather than silently activated.

## 2. System tuning

The stock-Android profile path is deliberately narrow. It does not claim CPU/GPU overclocking, thermal bypass, process priority control, or account-sync “optimization.” New profiles only plan reversible screen-timeout and supported haptic targets. Mutations are mutex-serialized, baseline state is persisted before writes, cancellation/failure triggers rollback, and interrupted state is recoverable on a later launch.

The migration-only `WRITE_SYNC_SETTINGS` permission is retained solely to repair possible state from the historical 1.0.10 behavior. Current profiles do not read or change global master sync. Removing the migration write capability prematurely could prevent restoration for direct upgraders.

A 100,000-baseline property harness executed against the production `SystemProfilePlanner` passed 900,000 invariants: Battery never lengthens timeout and disables its haptic target; Performance/Gaming never shorten the baseline and preserve haptics; Balanced restores the exact baseline.

## 3. Cleaner and destructive-operation safety

The cleaner does not request `MANAGE_EXTERNAL_STORAGE`. It supports scoped MediaStore access, Android Photo Picker, SAF folders/documents, and optional broad media permissions for the explicit bulk-library analysis use case.

Safety properties confirmed in source:

- scan/discovery is bounded and cancellation-aware;
- duplicate work is content-hash based rather than filename-only;
- reclaim estimates deduplicate physical identities and use saturating arithmetic;
- user selection is required before removal;
- Android 11+ MediaStore trash/delete operations use system confirmation requests;
- direct document-provider deletions are bounded into batches;
- a confirmed destructive batch is completed non-cancellably to avoid deliberately stopping mid-commit;
- process recreation after a MediaStore confirmation does not reconstruct/guess lost direct-document selection;
- no automatic deletion is performed by scheduled “maintenance.”

Google Play requires a strong core use case and declaration for retained `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; the UI already provides privacy-preserving picker/SAF alternatives and an in-app rationale. The Play Console declaration remains an account-side release gate.

## 4. Memory, battery and performance claims

ApexTuner avoids unsafe or misleading “booster” behavior. No `killBackgroundProcesses`, hidden task killing, thermal-control bypass, or unrestricted system-setting mutation is implemented.

Telemetry/sysfs work is dispatched off the main thread. Expensive storage telemetry is cached and serialized to avoid duplicate `StorageManager`/`StatFs` stampedes. CPU sampling rejects samples that are too close together and resets stale baselines. GPU/sysfs reads are best-effort. Historical usage/network operations are on-demand and I/O-dispatched.

Compose routes use lifecycle-aware flow collection. Dynamic long lists generally have stable keys; informational fixed lists are small. The shell constrains content width on wide displays and switches to navigation rail at the compact/wide breakpoint. No app-specific Baseline Profile was fabricated without measurement data; Macrobenchmark/physical-device profiling remains the correct final optimization gate.

## 5. Network and local VPN firewall

The firewall is implemented with `VpnService`, protected by `BIND_VPN_SERVICE`, with Android VPN consent in the UI. It is explicitly not an always-on VPN. Selected applications are routed into a local TUN sink; packets are discarded on-device and are not forwarded to an external server. ApexTuner itself is excluded from the sink set. Package names are sanitized and stale packages are pruned.

The service promotes itself to foreground before asynchronous work, uses `specialUse` with the required manifest subtype explanation, stops on Premium entitlement loss, and returns `START_NOT_STICKY`.

Google Play permits `VpnService` only for eligible core functionality/use cases such as device security/network tools and requires prominent disclosure/consent plus accurate Play-listing disclosure. These listing/declaration requirements must be completed in Play Console.

## 6. Foreground services and overlay monitor

Both special-use foreground services declare `FOREGROUND_SERVICE_SPECIAL_USE`, service-level `foregroundServiceType="specialUse"`, and a human-readable `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value. Android 14+ starts pass the corresponding foreground service type constant.

The floating monitor:

- is started explicitly by the user;
- requires Premium and overlay permission;
- remains within the visible display during drag and after configuration changes;
- samples at 2 seconds and reuses core telemetry caching;
- stops on entitlement loss;
- now retries transient telemetry failures with a bounded backoff instead of becoming stale.

## 7. Screen recording

MediaProjection is user-consented for every capture. The service starts as a media-projection foreground service before obtaining/using the projection, registers `MediaProjection.Callback`, creates one virtual display per authorized projection, records video only, and stores a bounded H.264 MP4 through MediaStore. Encoder size/frame-rate/bitrate are capability-checked. Failed/incomplete output is deleted rather than exposed as a valid recording.

The design is consistent with the Android 14+ rule that a MediaProjection consent token/session is single-use for a capture session.

## 8. Advanced/Shizuku/root tools

Advanced access is hidden behind explicit settings/Premium gating and never assumed. Shizuku permission and binder availability are checked before binding. The provider uses the Shizuku integration contract and application-scoped authority.

Root/Shizuku commands are a fixed allowlist: identity, device-idle state, animation scales, and kernel version. Process lifetime/output are bounded. Root shell arguments are generated internally and single-quoted after rejecting quote/newline/NUL characters; arbitrary user command text is not accepted.

Animation writes validate values, capture a baseline, verify current values before mutation, and attempt rollback on a partial write failure. A persisted baseline remains available until successful restoration.

## 9. Game Session Booster and feature interaction

Game Session Booster uses only reversible profile changes, optional Android DND integration, and launcher-mediated app start. It persists a recovery record before launching the target app. If launch fails or the coroutine is cancelled, changed state is rolled back. A safety restore is scheduled for stale sessions.

Restoration is ownership-aware: it does not overwrite a newer profile/user DND choice made after the session began. Per-component restoration progress is persisted so a retry does not reapply a component already restored.

## 10. App Manager, usage access and backups

The App Manager intentionally uses `LauncherApps` rather than `QUERY_ALL_PACKAGES`. Usage Access is optional and requested only for cross-app historical usage/storage/network insight. Large APK backup is streamed into ZIP output with bounded total source size and SHA-256 metadata; app-private data is not included.

ApexTuner preference backup/restore validates its schema/source and size, excludes entitlement/Keystore/privileged state, and never imports an active system-profile transaction baseline.

## 11. Google Play Billing

The project uses Google Play Billing 9.1.0. One `BillingClient` instance is maintained, automatic service reconnection is enabled, purchases are re-queried on resume/refresh, pending and suspended states do not unlock Premium, visible prices/offers come from current `ProductDetails`, and only `PURCHASED` unacknowledged recognized purchases are acknowledged with bounded retry handling.

A 50,000-case randomized entitlement harness plus deterministic billing/automation/monitor checks passes on the production-domain sources.

**Residual security boundary:** the project is client-centric and has no secure server. Google recommends server-side purchase-token verification, authoritative entitlement storage/lifecycle processing, RTDN, and server-side acknowledgement. A local “fake backend” would not improve security, so none was introduced. If commercial entitlement security must be enterprise-authoritative, a real backend is the remaining architecture requirement.

## 12. Android 16 / adaptive UI

The app targets API 36 and calls `enableEdgeToEdge()`. No forced activity orientation is declared. The top-level shell adapts from bottom navigation to navigation rail, constrains oversized content, uses responsive padding, adaptive grids and wrapping controls, and the floating overlay is reclamped after display/configuration changes.

Android 16 ignores orientation/aspect-ratio/resizability restrictions on large screens for target-36 apps, so tablet/foldable/desktop-window testing remains mandatory even when a phone layout appears correct.

## 13. Security/privacy posture

Confirmed source invariants include:

- automatic app backup disabled;
- cleartext traffic disabled;
- no `MANAGE_EXTERNAL_STORAGE`;
- no `QUERY_ALL_PACKAGES`;
- no `WRITE_SECURE_SETTINGS`;
- no `KILL_BACKGROUND_PROCESSES`;
- no arbitrary user shell command execution;
- Keystore-backed encrypted local secret storage for security-sensitive client state;
- bounded imported backup size and validated schema;
- explicit Android/system permission flows for sensitive capabilities.

## 14. Code hygiene

The source validator reports no production implementation markers, legacy branding, package/path mismatches, malformed XML, or forbidden permissions. Generated Python `__pycache__`/`.pyc` material found in the input archive was removed and `.gitignore` now excludes it to prevent recurrence.

The existing module boundaries are coherent and no broad refactor was performed solely for stylistic reasons; unnecessary architecture churn would add regression risk without user benefit.

## 15. Validation actually executed for 1.0.15

Executed in this environment:

1. `python3 tools/validate_project.py` — PASS after the 1.0.15 changes.
2. Production-domain Kotlin harness — PASS, 50,022 checks.
3. Production `SystemProfilePlanner` randomized property harness — PASS, 900,000 checks.
4. XML parsing and merged-source manifest invariants are included in the project validator.
5. Package hygiene checks and final ZIP integrity test are required at packaging time.

Unavailable here and therefore **not claimed as completed**:

- Android Gradle/AGP compilation, because this container has no Android SDK and cannot resolve the Gradle distribution host;
- Android Lint in a real SDK environment;
- emulator/instrumentation and physical-device interaction;
- actual VPN, MediaProjection, overlay, Usage Access, WRITE_SETTINGS, DND, Shizuku/root and OEM-specific provider behavior;
- real Google Play internal-track billing lifecycle;
- Play pre-launch report;
- Macrobenchmark/battery/thermal/jank measurements.

The included GitHub Actions workflow remains the mandatory Android build gate: JDK 17 + Gradle 8.13 + API 36 SDK, source validation, unit tests, lint, debug/release APK compilation, release AAB generation, and instrumentation across API 26, 28, 30, 33, 35 and 36.

## 16. Official documentation cross-checks

Current official references reviewed during this audit:

- Android 16 target behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16
- Adaptive orientation/resizability guidance: https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability
- Foreground service types / `specialUse`: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 14 MediaProjection behavior changes: https://developer.android.com/about/versions/14/behavior-changes-14
- Google Play sensitive permissions / VPN / photo-video policies: https://support.google.com/googleplay/android-developer/answer/16558241
- Google Play Billing integration: https://developer.android.com/google/play/billing/integrate
- Google Play Billing security: https://developer.android.com/google/play/billing/security
- Google Play Billing backend integration: https://developer.android.com/google/play/billing/backend

## Final status

**Source audit: PASS after the 1.0.15 monitor-resilience correction and package-hygiene cleanup.**  
**External Android SDK/device/Google Play release gates: still mandatory and explicitly documented; no unavailable test is represented as completed.**
