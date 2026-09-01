# ApexTuner 1.1.3 claims audit

Audit date: 2026-08-30

## Outcome

The reported items were checked against the complete project rather than accepted as a single batch. Six defects were confirmed and fixed. Five claims were not defects in the supplied source and were intentionally left unchanged to avoid regressions.

## Fixed defects

1. `ScreenRecordingService.startRecording()` now keeps foreground-service promotion inside its fail-closed startup guard. A rejected foreground promotion no longer escapes the service startup path.
2. Screen recording now requires usable notification visibility before MediaProjection consent. Android 13+ requests `POST_NOTIFICATIONS`; globally disabled app notifications are also detected.
3. The in-app recording Stop & save request is guarded. A failed service delivery retains a recoverable recording state and shows retry guidance.
4. Contact merge no longer silently truncates raw-contact aggregation pairs at 64. It plans every pair, writes bounded provider batches, and explicitly rejects unsafe merges above 512 rules.
5. Contact duplicate blocking and scoring now include two-to-four-character one-letter prefix variations such as `Al` / `Ali`, without treating unrelated short names as duplicates.
6. `MainActivity.onNewIntent()` refreshes entitlement before handling a newly delivered deep link, preventing stale paywall gating in a reused activity.

## Claims not reproduced

- The Compose BOM/compiler claim was incorrect. The Kotlin Compose compiler plugin is versioned with Kotlin; the BOM controls Compose library versions. A broad upgrade to the newest BOM was not introduced because it would also move the app onto a newer Compose/API baseline and is unrelated to Gradle plugin resolution.
- SAF documents are permanently deleted because Android's Storage Access Framework does not expose a universal trash operation. The existing confirmation copy explicitly distinguishes MediaStore trash from permanent SAF deletion.
- `MemoryRepository` and `BatteryRepository` failures are converted by their ViewModels into retry and explicit error states. The claimed blank-screen process crash path was not present.
- `BillingViewModel` performs two different initial operations: product-catalog refresh and entitlement verification. Removing either would create stale paywall state.
- The notification-listener component is disabled by default. It is enabled only after the in-app disclosure so Android can display its access screen; collection remains gated by the user's OS grant, app setting, readiness, and entitlement.

## Validation

- Project validator: PASS — 2,294 checks, 42 XML files, 14 production manifests.
- Release gate: PASS — 356 release checks plus the project validator.
- Gradle 9.5.0 / AGP 9.3.2 project configuration (`gradlew help`): PASS.
- The supplied project uses standard `google()`, `mavenCentral()`, and `gradlePluginPortal()` repositories and contains no local validation-proxy configuration or generated Gradle cache.

The isolated validation runner available for this audit contained a Java 17 runtime but no Java compiler. Consequently, its compile task stopped before source compilation with `JAVA_COMPILER` unavailable; that is a host-tooling limitation, not a source or dependency-resolution error. Android Studio's bundled JDK includes the required compiler.
