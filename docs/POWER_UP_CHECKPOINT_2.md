# ApexTuner Power-Up Checkpoint 2

Version: 1.1.3 (`versionCode 26`)

## Batch scope

1. Smart Automation reliability and configuration
   - Removed repeated Google Play entitlement refreshes from the 15-minute background rule worker. Foreground/resume remains the authoritative Play refresh path, while background work uses the existing bounded encrypted entitlement mirror.
   - Smart rules are opt-in by default. The mutating low-battery profile rule begins in Dry run mode.
   - Added configurable safe threshold presets and bounded cooldown presets.
   - Notification actions now report a skipped result when Android notification permission/policy prevents delivery instead of recording a false success.
   - Added exponential retry backoff to new periodic automation/health/charging work.
   - Fixed a Kotlin compile blocker in SmartAutomationEvaluator and the SmartAutomationWorker caused by expression-body functions containing explicit returns.

2. Device Health Timeline intelligence
   - Added average CPU and battery-temperature statistics.
   - Added signed free-storage endpoint trend.
   - Added Severe-or-worse thermal event transition counting in addition to raw elevated samples.
   - Added high-CPU + warm-battery coincidence analysis.
   - Added metered-network observation percentage and safe monotonic device-wide traffic-counter delta.
   - All insights reuse already persisted samples; no additional sampling worker or database migration was introduced.

3. Regression and build-safety work
   - Added unit tests for timeline aggregation and smart-automation policy safety.
   - Updated Tools descriptions so the new capabilities are discoverable.
   - Kept the supplied ApexTuner launcher/in-app branding from Checkpoint 1 unchanged.
   - Project structural validator and targeted Kotlin production-logic harnesses pass in the available environment.

## Environment limitation

This environment does not provide the Android SDK/emulator or a cached Gradle 9.5 distribution, and outbound DNS is unavailable. Therefore a real Android Gradle build, APK install, and emulator/instrumentation pass cannot be claimed here. The source package is prepared for those checks in Android Studio/CI.
