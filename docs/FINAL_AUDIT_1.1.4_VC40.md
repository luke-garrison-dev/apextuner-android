# ApexTuner Android — final audit 1.1.4 / versionCode 40

## Candidate identity

- Application ID: `com.apextuner.app`
- Version name: `1.1.4`
- Version code: `40`
- compileSdk / targetSdk / minSdk: `36 / 36 / 26`
- Android Gradle Plugin: `9.3.2`
- Gradle: `9.5.0`
- Java bytecode target: `17`

## Confirmed issues fixed in this pass

1. **Firewall Quick Settings fallback opened the generic Dashboard — FIXED.**
   When premium entitlement, blocked-app configuration, VPN consent, or foreground-service startup required attention, the tile opened ApexTuner without context. It now opens Network tools directly, where the user can configure or resolve the firewall prerequisite.

2. **Real-time Monitor Quick Settings fallback opened the generic Dashboard — FIXED.**
   When premium entitlement, overlay permission, or foreground-service startup required attention, the tile gave no direct path to the relevant control. It now opens Settings directly, where the monitor card and permission action are available.

3. **The activity launch protocol only recognized Quick Scan and duplicated protocol strings — FIXED.**
   A shared core launch contract now owns the extra keys and three supported destinations. `MainActivity` rejects every route outside that explicit allow-list, restricts Quick Scan to Optimize, and the app shell consumes non-scan navigation commands after one use. This prevents arbitrary route injection and configuration-change replay while keeping all three tiles consistent.

## Confirmed unused/redundant code

No additional production declaration was removed in this pass. A project-wide symbol/reference scan found only Android manifest, Hilt, Room, widget, service, receiver, and test entry points among apparent single-reference declarations. Removing those would break runtime discovery or generated integration. Existing historical removals remain documented in the versionCode 39 audit.

## Regression guards added

- Unit coverage accepts every supported launch destination and rejects missing/untrusted routes.
- The structural gate enforces central destination sanitization, Quick Scan destination binding, one-shot consumption, correct tile targets, and a single shared destination-key declaration.
- Release identity gates now require `1.1.4` / `versionCode 40`.

## Verification

- All Kotlin/XML/manifests, feature integration points, Room migrations, permission/service declarations, billing lifetime-purchase flow, cancellation paths, responsive navigation, cleaner SAF/photo-picker flow, firewall selection safeguards, monitor lifecycle, and release configuration were statically audited.
- `python3 tools/validate_project.py` — **PASS: 2,430 checks, 42 XML files, 14 main manifests**.
- `python3 tools/release_gate.py` — **PASS: 466 release checks plus the full project validator**.
- Package hygiene excludes Gradle/IDE caches, build outputs, APK/AAB/class files, and Python bytecode.

## Build-environment boundary

The real Gradle task graph was initialized with the official Android 36 SDK, Build Tools 36.0.0, JDK 17, Gradle 9.5.0, and the pinned AGP/Kotlin/KSP/Hilt plugins. Full compilation could not finish in this execution sandbox because Java dependency access must be relayed through a restricted network shim; repeated relay sessions were interrupted before dependency resolution completed. This is not recorded as an app defect. Android Studio and CI retain the normal official `google()`, `mavenCentral()`, and `gradlePluginPortal()` repositories.
