# ApexTuner Android — Compiler Warning Cleanup 1.0.18

**Version:** `1.0.18` / `versionCode 18`  
**Date:** 2026-08-29

## Scope

This update removes the remaining Kotlin compiler warnings reported from a successful Android Studio release build without changing application behavior.

## Corrections

- Removed redundant `@param:` use-site targets from Dagger `@Assisted` worker parameters. `@Assisted` targets parameters directly.
- Removed redundant `@param:` from plain `@ApplicationContext` constructor parameters in `AutomationScheduler` and `AndroidKeystoreSecureKeyValueStore`.
- Preserved explicit `@param:` targets on constructor **properties** whose qualifier placement must remain parameter-scoped under Kotlin annotation default-target evolution.
- Replaced the unnecessary API 30+ `context.display?.refreshRate` safe call with `context.display.refreshRate`.
- Reworked Widevine session cleanup to snapshot nullable DRM/session references into locals and close them after explicit null checks, eliminating the unnecessary `!!` assertion while keeping cleanup exception-safe.
- Added permanent source-validation guards for all four warning classes.

## Behavior and safety

No feature logic, permissions, scheduling cadence, Hilt worker contract, DRM querying behavior, or UI behavior was altered. The changes are compiler-hygiene and null-safety cleanup only.

## Verification boundary

The project validator and standalone production-domain/property harnesses are executed in this environment. A full Android Gradle build remains an external gate because this sandbox does not provide the Android SDK/toolchain required by the project.

## Validation actually executed

- Enterprise project validator: **PASS — 1,208 checks**, including 29 XML files and 11 main manifests.
- Production-domain Kotlin harness: **PASS — 50,022 checks**.
- `SystemProfilePlanner` randomized property harness: **PASS — 900,000 checks**.
- Non-property `@param:` target scan: **PASS — zero occurrences**.
- Exact residual scan for the newly reported source-warning patterns: **PASS — zero production-source hits**.

The user-reported Android Studio build already completes; this update specifically removes the remaining warning causes from the source tree.
