# ApexTuner Android 1.1.4 / versionCode 38 — holistic reliability audit

## Scope

This candidate was audited from the supplied `1.1.4 / versionCode 37` Android Studio source package. The review covered project/build metadata, app/module wiring, navigation and lifecycle invariants, foreground/background work, reversible system tuning, Game Session Booster, diagnostics, cleaner/file safety, networking, battery/memory telemetry, notification/settings integration, Play Billing entitlement policy, accessibility/layout guardrails, and source-package hygiene.

No speculative dependency/SDK migration was performed. The audited toolchain remains AGP 9.3.2, Kotlin 2.3.21, Gradle 9.5.0, Java bytecode target 17, compile/target SDK 36, min SDK 26, and Compose BOM 2026.06.00.

## Confirmed issues fixed

1. **Scheduled maintenance could swallow WorkManager cancellation.**
   - `MaintenanceWorker` wrapped the suspend `DeviceRepository.snapshot()` call in `runCatching`, which also catches `CancellationException`.
   - A cancelled/replaced worker could therefore be converted into `Result.retry()` instead of stopping cooperatively.
   - Fixed with explicit `CancellationException` propagation and retry only for genuine sampling failures.

2. **Game Session Booster could ignore cancellation during start/monitor telemetry sampling.**
   - The optional start snapshot and periodic active-session snapshot used `runCatching` around a suspend repository call.
   - Cancellation could be swallowed before subsequent state/system-profile work.
   - Fixed with a cancellation-cooperative best-effort snapshot helper used only in non-cleanup paths. The post-restore analytics path intentionally remains best-effort so completed reversible-setting cleanup is not stranded.

3. **Diagnostic capture could swallow cancellation while reading local history.**
   - Three suspend DAO history reads were wrapped in `runCatching`.
   - Fixed with a typed best-effort helper that returns defaults for ordinary read failures but always rethrows coroutine cancellation.

4. **One constructor-injected coroutine dispatcher still relied on Kotlin's changing annotation default target.**
   - `IntelligenceViewModel` used `@IoDispatcher` directly on a constructor property parameter.
   - Fixed to `@param:IoDispatcher`, matching the rest of the project and avoiding Kotlin 2.3/future target ambiguity without changing runtime behavior.

5. **Current release identity documentation was stale.**
   - The supplied versionCode 37 package still declared `versionCode 36` in `docs/RELEASE_IDENTITY_1.1.4.md`.
   - Fixed and synchronized to the new `versionCode 38` candidate.
   - Added deterministic gates so README, Gradle metadata, and the current release-identity document cannot silently diverge again.

## Preventive release hardening

- Build number increased from **37 to 38** while retaining `versionName 1.1.4` and application ID `com.apextuner.app`.
- Added cancellation regression invariants for scheduled maintenance, Game Session Booster, and diagnostic history reads.
- Added an explicit invariant that the screen recorder registers exactly one encoded video track before `MediaMuxer.start()`; the supplied source already satisfies this invariant and was not changed for a non-existent duplicate-track issue.
- Expanded source-package hygiene gates to reject `.git`, `.idea`, `.gradle`, `build`, `__pycache__`, `.pyc/.pyo`, `.class`, `.apk`, and `.aab` artifacts.
- Historical audit documents are retained as historical records; only the current release identity is updated.

## Validation performed

- `tools/validate_project.py`: **PASS — 2,414 checks, 42 XML files, 14 main manifests**.
- `tools/release_gate.py`: **PASS — 461 release checks + project validator**.
- One-time Premium entitlement host harness: **PASS**, including lifetime, pending, wrong-package, unknown-product, and **5,000 randomized evidence sets**.
- Static production-source scan: no TODO/FIXME/NotImplemented production markers; no GlobalScope/runBlocking/Thread.sleep regressions; project release gate continues to enforce source/module/resource/Room/branding/navigation/lifecycle/accessibility invariants.
- Package hygiene checked after validation with Python bytecode generation disabled.

## Environment boundary

A real Gradle/Android compilation could not be executed in this sandbox: no Android SDK is installed and the Gradle wrapper cannot download Gradle because `services.gradle.org` is not resolvable from the environment. This report therefore does **not** claim a successful `assembleRelease`, lint, or instrumentation run here. The included CI remains configured to run debug/release unit tests, debug/release lint, APK/AAB assembly, and instrumentation across API 26/28/30/33/35/36 in an Android-capable environment.

## Final status

All issues listed under **Confirmed issues fixed** are fixed in this candidate. No confirmed issue from this audit is intentionally left unresolved. No unrelated feature redesign or dependency migration was introduced.
