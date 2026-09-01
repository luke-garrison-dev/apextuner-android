# ApexTuner Part 6 validation

This document records what was actually validated in the cumulative Part 6 source tree and what still requires a real Android build/device environment. It deliberately distinguishes executable JVM/fault-injection evidence from Android SDK/emulator/device evidence.

## Scope

Part 6 adds:

- privacy-preserving App Manager;
- Network diagnostics and historical Usage Access-based traffic analysis;
- local per-app `VpnService` firewall;
- Privacy & Security tools;
- explicit Shizuku/root advanced access;
- typed allow-listed privileged operations with bounded process execution.

## Official platform/policy checks

The implementation was re-checked against current Android/Google Play/Shizuku documentation on 2026-08-28:

- Android `VpnService`: service must match `android.net.VpnService` and be protected by `android.permission.BIND_VPN_SERVICE`; API 26+ VPN apps must promote themselves to foreground after launch.
  https://developer.android.com/reference/android/net/VpnService
- Android 14+ foreground services require an appropriate type; `specialUse` requires `FOREGROUND_SERVICE_SPECIAL_USE` and a service-level `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description.
  https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE
  https://developer.android.com/develop/background-work/services/fgs/launch
- Android package visibility should be minimized; ApexTuner intentionally does not declare `QUERY_ALL_PACKAGES`.
  https://developer.android.com/training/package-visibility/declaring
- Google Play permits firewall/device-security use cases for `VpnService`, but requires Play Console declaration and accurate prominent in-app disclosure/consent.
  https://support.google.com/googleplay/android-developer/answer/12564964
- Shizuku 13.1.5 remains the maintained API line; UserService is the supported privileged execution architecture, with stable tag/version guidance and reserved destroy transaction behavior documented upstream.
  https://github.com/RikkaApps/Shizuku-API

## Executable validation

### 1. Part 6 production-domain/fault harness — PASS

Freshly compiled from the current production `AppManagerModels`, `NetworkModels`, `CloseableResourceSlot`, `PrivilegedModels`, and `BoundedProcessRunner` sources.

```text
ApexTuner Part 6 domain harness: PASS (70,087 assertions)
```

Coverage includes:

- app-package validation/filter/sort behavior;
- saturating network arithmetic and negative-counter rejection;
- shared-UID network attribution;
- firewall package sanitation/deduplication/caps;
- animation-scale parsing/range validation;
- bounded privileged output;
- TUN resource replacement and stale-resource close protection;
- bounded process success/output cap/timeout;
- real child-process interruption with verification that the child is terminated;
- 50,000 randomized package inputs;
- 20,000 randomized saturating-network arithmetic cases.

### 2. Actual Part 6 ViewModel harness — PASS

The actual production `AppManagerViewModel`, `NetworkViewModel` and `AdvancedToolsViewModel` were freshly compiled against deterministic JVM-only Lifecycle/Hilt/Shizuku/repository compatibility stubs.

```text
ApexTuner Part 6 ViewModel harness: PASS (17 checks)
```

Validated:

- app detail latest-request-wins behavior and invalid package rejection;
- overlapping network refresh prevention;
- runtime firewall status synchronization;
- active/starting firewall selection lock;
- FIFO firewall selection ordering and final persisted state;
- Advanced Access refresh preserving an active operation's busy state;
- privileged operation serialization;
- backend stability;
- animation-baseline save/restore state;
- Shizuku permission-listener refresh and root-status refresh.

### 3. Part 5 profile transaction regression — PASS

The actual production `SafeSystemTuningController` and planner were freshly compiled against deterministic Android Settings/DataStore stubs.

```text
ApexTuner Part 5 profile transaction harness: PASS (36 checks)
```

This includes permission denial, partial-write rollback, interrupted-process reconciliation, cancellation rollback and deterministic superseding of stale concurrent profile requests.

### 4. Part 5 high-volume domain regression — PASS

```text
ApexTuner Part 5 extended domain harness: PASS (80,796 assertions)
```

### 5. Actual Part 5 ViewModel lifecycle regression — PASS

```text
ApexTuner Part 5 ViewModel lifecycle harness: PASS (12 checks)
```

### 6. Part 3 regressions — PASS

```text
dashboard-core-tests: PASS
telemetry-parser-tests: PASS
```

### 7. Part 4 Cleaner regression — PASS

```text
ApexTuner Part 4 pure-domain harness: PASS
```

### 8. Project-wide structural/security validator — PASS

```text
ApexTuner validation: PASS (853 checks, 34 XML files, 11 main manifests)
```

The validator checks all module manifests as a merged permission/security surface and enforces, among other things:

- all 11 modules and API 36/minSdk 26 consistency;
- ApexTuner namespace/branding consistency;
- XML/TOML/resource-reference integrity;
- release minification/resource shrinking;
- cleartext traffic disabled and automatic Android backup disabled;
- no `QUERY_ALL_PACKAGES`;
- no `MANAGE_EXTERNAL_STORAGE`;
- no `WRITE_SECURE_SETTINGS`;
- no `KILL_BACKGROUND_PROCESSES`;
- protected/exported VPN service contract;
- `specialUse` foreground-service permission/type/property;
- Always-on VPN opt-out;
- Shizuku provider protection, stable UserService contract and destroy transaction;
- no arbitrary unbounded privileged shell execution;
- shared bounded process runner;
- guarded TUN descriptor handoff;
- synchronous foreground promotion before DataStore/I/O;
- no implicit firewall start on null intents;
- stale firewall-selection pruning and FIFO selection queue;
- firewall-list editing lock while active;
- Android VPN consent and in-app disclosure path;
- singleton firewall preference lifetime;
- LauncherApps/package-visibility App Manager approach;
- no implementation TODO/FIXME/NotImplemented markers.

### 9. Kotlin parser surface check — PASS within its stated boundary

All production Kotlin sources were passed to the locally installed Kotlin compiler without an Android/Compose/Hilt classpath. Android symbols are therefore expected to be unresolved; diagnostics were filtered specifically for parser-structure failures.

```text
parser_level_error_count=0
```

This is useful for detecting malformed Kotlin structure but is not an AGP compile.

## Play Console declarations required before release

If the Part 6 firewall is shipped:

1. Complete the Google Play `VpnService` declaration and choose the accurate permitted use case (device security / firewall).
2. Document VpnService/firewall use in the store listing.
3. Provide the required short review video showing the VPN/firewall flow.
4. Show the prominent in-app firewall disclosure and both consent/decline flows in that video.
5. Declare whether the VPN service collects/shares data accurately. The current implementation is designed as an on-device sink and does not forward inspected payload traffic to an ApexTuner endpoint.
6. Review the `specialUse` foreground-service declaration/subtype in Play Console for the final release artifact.

Existing Part 4 broad media declarations remain separately required if the broad photo/video permissions are retained.

## Real Android build/device boundary not claimed

The execution environment still has no Android SDK, Build Tools, emulator system images, ADB or trusted generated Gradle wrapper JAR. Therefore this validation does **not** claim:

- AGP `assembleDebug` / minified `assembleRelease`;
- Android resource linking/manifest merger;
- Android Lint;
- instrumentation/Compose UI tests;
- API 26/28/30/33/35/36 emulator installation;
- physical-device VPN/TUN behavior;
- Shizuku service binding against real Shizuku/Sui;
- root authorization against real `su` implementations;
- OEM-specific VPN, UsageStats, battery or sysfs behavior;
- Play pre-launch report.

These remain release-blocking tests and must be executed before public distribution.

## Required real-device Part 6 scenarios

At minimum, validate:

- VPN consent accepted, declined, revoked and replaced by another VPN;
- rapid start/stop/restart and process death while TUN reader is blocked;
- selected-app traffic blocked while unselected-app traffic remains available;
- Wi-Fi/mobile handover while firewall is active;
- zero selected apps and 100+ selected apps;
- app uninstall while selected and subsequent stale-selection pruning;
- foreground-service notification behavior and Android 14–16 restrictions;
- Usage Access denied/granted/revoked during network analysis;
- shared-UID network rows;
- Shizuku unavailable/old/permission denied/binder death/service recreation;
- root absent/denied/granted/timeout/cancelled;
- privileged animation write failure after each individual setting and exact rollback;
- minified R8 build so Shizuku stable tag/version remains valid;
- rotation, tablets, large font scale and TalkBack across all Part 6 screens.
