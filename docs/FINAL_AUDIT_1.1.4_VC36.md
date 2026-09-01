# ApexTuner Android 1.1.4 / versionCode 36 — final audit

## Scope

Holistic source-package review of the uploaded Android Studio project, including app shell/navigation, billing entitlement flow, feature-module integration, manifests/resources, lifecycle/service safeguards, Room/repository wiring, release configuration, and package hygiene.

## Confirmed issue fixed in this pass

1. **Bottom-navigation label typography bypassed the app readability floor.**
   - `ApexTunerApp.kt` locally forced bottom navigation labels to `12sp / 15sp` even though the shared typography system uses a 13sp minimum for readable small labels.
   - Fixed to `13sp / 16sp` without changing navigation layout, destinations, behavior, or feature logic.
   - Added a structural validator invariant so a future change cannot silently reduce the bottom-navigation label below that floor.

## Build/tooling checks

- Project identity retained: `versionName 1.1.4`, `versionCode 36`.
- `compileSdk 36`, `targetSdk 36`, `minSdk 26` retained.
- Gradle distribution remains pinned to 9.5.0 with SHA-256 verification.
- Project validator: PASS — 2,396 checks.
- Release gate: PASS — 460 release checks plus the project validator.
- One-time lifetime billing domain harness: PASS, including 5,000 randomized entitlement cases. The host-only harness used a test-only BillingClient constant stub outside the project because the Android Billing SDK is not installed in this container.
- Package hygiene: no `.git`, `.idea`, `.gradle`, `build`, `__pycache__`, `.pyc`, APK, AAB, or class artifacts shipped.

## Environment limitation

This execution environment does not contain an Android SDK or a locally installed Gradle distribution, and its shell cannot resolve the configured Gradle download host. Therefore an actual Android Studio/Gradle `assembleRelease`, instrumentation launch, emulator run, or physical-device run cannot be truthfully claimed here. The package-level/static/domain release gates above all pass.
