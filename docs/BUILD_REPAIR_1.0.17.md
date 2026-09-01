# ApexTuner Android — Build Repair 1.0.17

**Version:** `1.0.17` / `versionCode 17`

This release repairs the follow-up Android Studio failures reported after the 1.0.16 toolchain migration and keeps the earlier KAPT→KSP/AGP 9 modernization intact.

## Confirmed build blockers repaired

1. **Deprecated AGP dependency-constraint option**
   - Removed `android.dependency.excludeLibraryComponentsFromConstraints=true`.
   - Replaced it with the supported AGP 9 option `android.dependency.useConstraints=false`, matching the optimized/default AGP 9 behavior.

2. **Deprecated Kotlin JVM-default flag**
   - Replaced every `-Xjvm-default=all` occurrence with `-jvm-default=no-compatibility`.
   - This preserves the previous `all` semantics while using Kotlin's current stable flag/value naming.

3. **Invalid qualifier target in `CoreModule.kt`**
   - `provideDatabase()` now uses `@ApplicationContext` directly on its ordinary function parameter; `@param:` is constructor-parameter-specific and was invalid here.
   - `provideIoDispatcher()` now qualifies the provided binding with `@IoDispatcher`; it no longer applies `@param:` to the provider function.

4. **Proactive field-injection repair**
   - `GameSessionReceiver.io` used `@param:IoDispatcher` on a field-injected property and would fail once compilation reached that module.
   - It now uses `@field:IoDispatcher`, which targets the backing field used by Hilt/Dagger field injection.

## Regression prevention

`tools/validate_project.py` now explicitly fails if any of the following reappear:

- `android.dependency.excludeLibraryComponentsFromConstraints`;
- `-Xjvm-default` in module build scripts;
- constructor-only `@param:` targets on the `CoreModule` provider sites;
- `@param:IoDispatcher` on the `GameSessionReceiver` field injection;
- a version mismatch between the application build and README.

## Validation actually executed

- Enterprise source/project validator: **PASS — 1,202 checks**.
- Production-domain executable Kotlin harness: **PASS — 50,022 checks**.
- `SystemProfilePlanner` randomized property harness: **PASS — 900,000 checks**.
- Exact residual scan for the newly reported deprecated/invalid signatures: **PASS — zero residual hits**.
- XML parsing: **PASS — 29 XML files**.
- Main-manifest validation: **PASS — 11 manifests**.

## Android build boundary in this environment

A complete AGP build still cannot be executed inside this container because the Android SDK is unavailable and the Gradle wrapper cannot resolve `services.gradle.org` from the sandbox. This is reported as an external release gate rather than represented as a successful local Android build.

On a normal Android Studio installation, open the **1.0.17** project directory, sync Gradle, then run the release build. Do not build an older extracted `1.0.15`/`1.0.16` folder when validating this repair.
