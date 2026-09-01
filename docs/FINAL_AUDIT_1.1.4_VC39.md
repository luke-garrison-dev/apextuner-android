# ApexTuner Android — final audit 1.1.4 / versionCode 39

## Candidate identity

- Application ID: `com.apextuner.app`
- Version name: `1.1.4`
- Version code: `39`
- compileSdk / targetSdk / minSdk: `36 / 36 / 26`
- Android Gradle Plugin: `9.3.2`
- Gradle wrapper: `9.5.0`
- Java bytecode target: `17`

## Confirmed issues fixed in this pass

1. **Lifecycle recovery could swallow coroutine cancellation — FIXED.**
   `AppLifecycleRecoveryWorker` used Kotlin `runCatching` around suspending recovery calls. Because `runCatching` catches `CancellationException`, a replaced/cancelled WorkManager job could continue best-effort recovery work. The worker now uses a cancellation-aware helper that rethrows cancellation and suppresses only non-cancellation failures.

2. **Compact bottom navigation could still show a cramped selected label — FIXED.**
   Compact mode previously set `alwaysShowLabel = false`, which still lets Material display the selected label. With five bottom destinations this could clip at narrow widths or large font scales. Compact mode is now truly icon-only while preserving icon content descriptions for accessibility; labels remain visible where the adaptive width/font threshold says they fit.

3. **File Manager action buttons could overflow — FIXED.**
   The two three-button action groups were rigid horizontal `Row`s. They now use `FlowRow` with horizontal and vertical spacing, so actions wrap naturally on narrow phones, split-screen windows, landscape constraints, and larger font scales.

4. **File Manager navigation could race file mutations/concurrent folder loads — FIXED.**
   Folder navigation used untracked coroutines and could run while copy/move/ZIP/rename work was active. Rapid taps or navigation during a mutation could allow stale completions to replace newer screen state. Folder loads are now tracked/serialized, conflicting navigation/selection is blocked while a mutation is active, in-flight navigation is cancellable when going back/changing grant, and the navigation job is cancelled on ViewModel teardown.

## Confirmed unused/redundant code removed

- `DeleteSelection` model — declaration had no references anywhere in production/test source.
- `PrivilegedOperationRisk` enum — declaration had no references.
- `core/ui/FeatureLanding.kt` — legacy composable had no call sites.
- `BillingPeriodFormatter.kt` — unused subscription-era billing period formatter; the current catalog is one-time lifetime purchase only.

No code was removed merely because it looked old or uncommon; removals were limited to declarations/files with no symbol references in the project.

## Regression guards added

The built-in structural/release gates now lock the new invariants so future edits fail validation if they reintroduce:

- cancellation-swallowing lifecycle recovery;
- compact selected bottom-nav labels;
- rigid File Manager action rows;
- concurrent File Manager navigation/mutation state races;
- the confirmed dead legacy declarations/files.

## Verification completed in this environment

- `python3 tools/validate_project.py` — **PASS: 2409 checks, 42 XML files, 14 main manifests**.
- `python3 tools/release_gate.py` — **PASS: 465 release checks + project validator**.
- One-time lifetime entitlement host harness — **PASS**, including 5,000 randomized purchase-evidence cases.
- XML parser smoke test — **PASS: all 42 XML files parsed**.
- Python gate syntax compilation — **PASS**.
- Package/source hygiene scan — no `build/`, `.gradle/`, `.idea/`, `__pycache__/`, `.pyc`, `.class`, APK or AAB artifacts retained in source.

## Environment boundary

A full Android Gradle compile/lint/instrumentation run cannot be executed inside this sandbox because it has no Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` are unset) and DNS access to `services.gradle.org` is unavailable, so the wrapper distribution cannot be downloaded. The project retains its CI workflow for debug/release JVM tests, lint, debug/release assembly, release bundle, and connected instrumentation. This limitation is an execution-environment limitation, not a reported app defect.
