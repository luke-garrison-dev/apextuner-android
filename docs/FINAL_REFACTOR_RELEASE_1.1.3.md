# ApexTuner 1.1.3 — Final Refactor Release Candidate

Build identity: `versionName 1.1.3`, `versionCode 31`.

## Final consolidation changes

- Smart Automation ownership is now entitlement-safe: if Premium becomes inactive while ApexTuner owns a reversible Battery profile, foreground, lifecycle-recovery and stale-worker paths force an ownership-safe restoration instead of leaving the profile stranded.
- Smart Automation event history is bounded by both 90-day age retention and a hard 1,000-row newest-event cap.
- Fixed a Kotlin visibility build blocker where the public Hilt `SmartAutomationWorker` injected an `internal` executor type. Worker dependencies now have compatible visibility for generated Hilt/WorkManager code.
- Network Quality no longer presents unavailable DNS timing as `0 ms`; it renders an unavailable value and clarifies that stored history shows the preferred-route median.
- Added `tools/release_gate.py`, a deterministic pre-Gradle release gate that runs the project validator and checks module dependency boundaries, SDK/JDK/toolchain pins, XML parsing, Room migration configuration, production source hygiene and supplied-branding integrity.

## Final validation executed in this environment

- `python tools/validate_project.py`: PASS.
- `python tools/release_gate.py`: PASS.
- Actual Smart Automation + lifecycle-recovery production Kotlin compiled together against Android/API-shape stubs: PASS.
- Actual Smart Automation Worker compiled with the actual executor/recovery production code against Android/API-shape stubs: PASS.
- Automation history pruning SQL executed against SQLite with 1,205 rows and verified to retain exactly the newest 1,000: PASS.
- All production XML parsed successfully through the release gate.
- Supplied ApexTuner canonical in-app logo SHA-256 verified by the release gate.

## Android SDK/emulator boundary

This execution environment contains no Android SDK, adb, emulator, sdkmanager or avdmanager. The pinned Gradle 9.5.0 distribution is not cached, and outbound DNS to `services.gradle.org`/Google Android repositories is unavailable. A genuine Android Gradle assemble/install/instrumentation/emulator run therefore cannot be performed here and is not represented as completed.

For Android Studio/CI, run:

- Windows: `python tools/release_gate.py` then `gradlew.bat clean testDebugUnitTest assembleDebug`
- macOS/Linux: `python3 tools/release_gate.py` then `./gradlew clean testDebugUnitTest assembleDebug`

The project pins AGP 9.3.2, Gradle 9.5.0, compile/target SDK 36, min SDK 26, and Java bytecode 17.
