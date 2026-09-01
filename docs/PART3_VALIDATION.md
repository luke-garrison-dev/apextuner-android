# Part 3 validation record

Date: 2026-08-28

## Scope

Part 3 adds the production Device Dashboard and hardens the shared telemetry layer used by it. This record distinguishes checks that were actually executed from checks that still require a normal Android build/test environment.

## Executed checks

### Pure Kotlin executable checks

Dashboard calculation/recommendation sources were compiled with `kotlinc` together with a deterministic harness. Verified behaviors:
- first traffic sample has no invented rate;
- RX/TX rates use elapsed monotonic time;
- reboot/counter regression does not create negative throughput;
- unsupported negative counters do not create rates;
- dashboard history remains bounded;
- severe thermal state receives critical priority;
- internal storage below 10% free receives critical priority;
- healthy snapshots produce a healthy informational state.

Telemetry parsers were separately compiled/executed with `kotlinc`. Verified behaviors:
- Linux aggregate `/proc/stat` CPU fields are parsed without double-counting guest time;
- malformed CPU input is rejected;
- counter regressions are rejected;
- overflow-prone input is rejected;
- common Qualcomm-style `gpubusy` and devfreq `load` forms parse safely;
- invalid GPU percentages and zero denominators are rejected.

### Static project checks

- All XML files parse as XML.
- `gradle/libs.versions.toml` parses as TOML.
- All declared Gradle modules exist.
- Dashboard `R.string.*` references resolve to declared dashboard string resources.
- No old `androidx.hilt.navigation.compose.hiltViewModel` import remains.
- No `hilt-navigation-compose` dependency remains for Dashboard ViewModel injection.
- No `TODO`, `FIXME` or `NotImplementedError` implementation marker remains.
- All module `compileSdk` values are API 36 and the app `targetSdk` is API 36.
- Gradle wrapper distribution configuration is 8.13, matching the AGP 8.11 API-36 baseline.

## Defensive behaviors reviewed

- Dashboard telemetry is read-only.
- No root command, ADB command, package kill, file deletion or settings mutation is performed by Part 3.
- `/proc`/sysfs values are best-effort and fail closed to unavailable.
- CPU parser rejects malformed, regressing and overflow-prone counters.
- GPU parser rejects values outside a defensible percentage range.
- Network throughput is derived from cumulative counters instead of treating the counters themselves as speed.
- Polling is lifecycle/subscription bound and has bounded history.
- Repeated transient read failures are counted consecutively; a successful read resets failure state.
- Coroutine cancellation is propagated, not converted to a dashboard failure.
- High RAM usage alone does not trigger force-stop or RAM-clearing behavior.
- Storage recommendations are advisory and explicitly state that user files are not deleted without confirmation.

## Cross-referenced platform decisions

- AndroidX lifecycle guidance: UI Flow collection must be lifecycle-aware; Dashboard uses `collectAsStateWithLifecycle` backed by a subscription-aware StateFlow.
- `TrafficStats.getTotalRxBytes/getTotalTxBytes` are cumulative since boot; Dashboard computes rates from deltas.
- `BATTERY_PROPERTY_CURRENT_NOW` is in microamperes and is signed; Dashboard preserves the sign when displaying current.
- AndroidX Hilt 1.3 moved Compose `hiltViewModel()` to `hilt-lifecycle-viewmodel-compose`; the Dashboard uses the new artifact/package.
- Google Play requires new mobile apps and app updates to target API 36 beginning 2026-08-31; the cumulative project now targets API 36.
- Android's API-36 setup guidance requires a sufficiently recent AGP; AGP 8.11 supports API 36 and requires Gradle 8.13/JDK 17.

## Not executed in this environment

These remain mandatory before release and are not represented as complete:
- Android Gradle sync/build with downloaded Maven dependencies.
- Android Lint.
- Compose instrumentation tests.
- Emulator installation and runtime testing on API 26, 28, 30, 33, 35 and 36.
- Rotation/foldable/tablet runtime tests.
- Doze/background lifecycle profiling.
- LeakCanary/Android Studio memory profiling.
- Battery Historian/energy profiling.
- OEM physical-device verification for vendor-specific `/proc`/sysfs visibility.
- Physical-device battery current sign/availability verification.

Part 8 must execute and record the complete release test matrix. A release should not be claimed until those checks pass.
