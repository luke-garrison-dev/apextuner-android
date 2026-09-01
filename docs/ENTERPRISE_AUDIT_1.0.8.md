# ApexTuner 1.0.8 — enterprise Android audit

## Scope

The audit covered the cumulative multi-module Android Studio project holistically: application/build configuration, telemetry, dashboard, Cleaner, battery, RAM/memory, App Manager, network diagnostics/VPN firewall, privileged Shizuku/root layer, settings/automation/monitor, Google Play Billing client integration, Game Session Booster, screen recording, storage/backup flows, concurrency/lifecycle behavior, permissions, failure recovery and performance/resource usage.

The project-owner instruction to **not implement a server-side Google Play Developer API / RTDN entitlement backend** is intentionally preserved.

## Safety model

ApexTuner is designed around capability-aware Android APIs instead of claims that stock Android cannot support. The final source validator ensures the application does not add `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `WRITE_SECURE_SETTINGS` or `KILL_BACKGROUND_PROCESSES`. Destructive storage operations remain user-reviewed/system-mediated where the platform provides those surfaces. Privileged operations remain explicit and gated.

## Main reliability corrections

### Game Session transactional recovery

Game-session startup now records which changes were actually made by ApexTuner. Failure/cancellation rollback executes in a non-cancellable section, partial restore progress is persisted, and the restore path avoids overwriting newer choices that no longer match the ApexTuner-applied state. This closes a process/coroutine-lifecycle gap that could otherwise leave performance or DND state applied after a failed session startup.

### VPN lifecycle and descriptor ownership

Firewall startup/runtime errors survive teardown so the UI retains the real diagnostic state. The TUN resource slot also rejects stale close requests: once a descriptor has been replaced, an obsolete reader cannot double-close or affect the newer descriptor. A focused JVM regression test now proves this contract.

### MediaProjection screen recorder

The missing `DisplayMetrics` import was corrected. MediaProjection consent/token validation now happens before callback-thread creation, preventing a rejected/expired token from stranding a thread. The Tools manifest explicitly owns the required foreground-service permissions.

### Telemetry parsing and cost

GPU `gpubusy` parsing now rejects zero/missing denominators. Stable storage telemetry is short-cache protected so dashboard/monitor consumers do not repeatedly invoke identical slow storage calls in a tight window.

### Billing hot-path efficiency

The existing client-side Billing architecture remains unchanged by owner direction. Encrypted entitlement-cache I/O used by refresh/error/grace paths is moved to the injected IO dispatcher to reduce UI-thread latency risk.

### Build reproducibility

Versioning is normalized to `1.0.8` / `versionCode 8`. API 36 / AGP 8.11.1 CI is pinned to Gradle 8.13, JDK 17 and SDK Build Tools 35.0.0, matching Google's AGP compatibility table. Cross-platform Gradle bootstrap launchers verify the official Gradle 8.13 SHA-256 before extraction.

## Feature interaction conclusion

The highest-risk cross-feature area was Game Session plus shared tuning/DND state; this is now ownership-aware and recovery-journaled. VPN resource replacement is serialized/guarded rather than relying on descriptor timing. Cleaner operations remain separate from telemetry/tuning and do not use unrestricted storage access. Background automation remains WorkManager-based instead of assuming exact scheduling. The screen recorder owns a foreground MediaProjection lifecycle rather than silently coupling to another module's manifest permission.

No justification was found for broadening permissions, adding process-killing, forcing hidden APIs, or making optimization behavior more aggressive. Those changes would reduce reliability and increase platform/policy risk.

## Performance conclusion

A performance optimizer must not become a persistent resource consumer. The final design therefore favors lifecycle-bound telemetry, bounded scans, streaming hashes, short stable-metric caching, dispatcher isolation for storage/crypto I/O, explicit foreground operation for continuous monitoring, and conservative scheduling. Device-significant measurements remain a hardware release gate because Macrobenchmark on an emulator is not representative of real-user performance.

## Executed regression evidence

See `docs/FINAL_VALIDATION.md` for exact executed counts and environment boundaries. Final local evidence includes 1,121 project invariants, 55 recompiled JVM unit-test methods across 18 classes, and the 50,015-check Part 7 domain harness, plus bootstrap integrity/delegation tests and parser/XML/TOML/YAML checks.

## Official documentation

- https://developer.android.com/build/releases/agp-8-11-0-release-notes
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/develop/background-work/background-tasks/persistent
- https://developer.android.com/topic/performance/benchmarking/benchmarking-overview
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args
