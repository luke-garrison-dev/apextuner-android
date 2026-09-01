# ApexTuner 1.0.20 — final validation summary

**Version:** `1.0.20` / `versionCode 20`

This release contains a narrowly scoped Battery Profile correctness repair on top of the validated 1.0.19 Android Studio, adaptive-landscape, and Photo Picker baseline.

## Scope

- Exact system-profile rollback timeout preservation.
- Live Battery profile/settings reconciliation.
- Accurate Battery profile result/status wording and explicit stock-Android limitations.
- Additional pure planner tests and structural regression guards.

No unrelated feature implementation was intentionally changed.

## Checks executed against the final source tree

- `tools/validate_project.py`: **PASS — 1,234 checks, 29 XML files, 11 main manifests**.
- Existing Part 7 executable domain harness: **PASS — 50,022 checks** covering Billing entitlement logic, monitor rate/retry behavior, and DST-safe automation timing.
- Battery/System Profile property harness compiled directly from the production `AppPreferences.kt` and `SystemProfilePlanner.kt`: **PASS — 1,000,002 checks**.
- Modified-production-file Kotlin parser scan: **no parser/syntax diagnostics**. Android/Compose references are unresolved in this no-SDK parser-only invocation as expected.
- Differential guard against pristine 1.0.19: Gradle/KSP/AGP configuration, adaptive layout implementation, Dashboard landscape implementation, Tools landscape implementation, and Photo Picker implementation are byte-identical. `app/build.gradle.kts` differs only by `versionCode`/`versionName`.

## Battery regression properties covered

- Balanced preserves the exact positive baseline, including values above one hour.
- Battery never lengthens a positive timeout and caps the applied timeout at 30 seconds.
- A non-positive unusual snapshot is retained exactly for rollback while applied profile targets remain safe.
- A live Battery target matches itself on legacy Android.
- Android 13+ matching intentionally ignores the deprecated global haptic setting.
- An external timeout change is detected as profile drift instead of being displayed as an active Battery profile.

## Environment boundary

The local container does not provide a complete Android SDK/device runtime. Therefore real `WRITE_SETTINGS` mutation behavior, Android framework unit/instrumentation execution, OEM-specific screen-timeout representations, and physical-device UI behavior remain Android Studio/device release gates. No such runtime result is claimed here.
