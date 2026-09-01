# Phase 2 + Phase 3 Validation

Baseline: accepted Task 1.4 package (`ApexTuner-Android-1.0.21-task-1.4.zip`).

This validation note covers Tasks 2.1, 2.2, 2.3, 3.1, and 3.2 in one integration pass. The implementation preserves the project's minSdk 26 / targetSdk 36 floor, Clean Architecture/MVVM conventions, local-only privacy posture, existing Room/DataStore/WorkManager infrastructure, and feature-scoped permission policy.

## Task 2.1 — Local network diagnostics

- Added `feature/network/diagnostics/` with ping/reachability, DNS resolution, TCP-port reachability, and local-subnet discovery.
- Blocking resolver/socket/reachability work runs on the IO dispatcher and through interruptible coroutine calls. DNS and user-initiated probes have explicit bounds; subnet host probes have bounded reachability/TCP timeouts.
- Local discovery accepts active Wi-Fi/Ethernet only, rejects VPN/cellular, caps broad networks to the current `/24`, limits concurrency to 6, and staggers launches by 35 ms.
- `/proc/net/arp` is parsed only when readable; failure/SELinux denial falls back to the bounded sweep.
- No raw socket, packet crafting, privileged command, arbitrary shell, or background scan loop is introduced.
- Android 16 / targetSdk 36 continues to use the existing INTERNET-based local-network model. If/when ApexTuner targets Android 17/API 37, the local-network permission model must be re-audited rather than silently assuming this behavior remains valid.

Acceptance:
- [x] No permission beyond `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_WIFI_STATE`.
- [x] Operations are coroutine-cancellable and host operations are time-bounded.
- [x] Sweep is capped at `/24`, maximum 254 candidates, concurrency 6, with launch rate limiting.
- [x] Deterministic unit tests cover host/port validation, subnet policy, and ARP parsing.

## Task 2.2 — Minimal SAF file manager

- Added `feature:files`; its manifest declares no permission.
- The feature uses `ACTION_OPEN_DOCUMENT_TREE` and persisted URI grants. All document operations run on the injected IO dispatcher.
- Browse, rename, copy, move, create-folder, ZIP create, and ZIP extract are implemented through `DocumentsContract`.
- Directory copy/move into self or a descendant is rejected; providers that cannot verify the relationship fail closed for directory transfer.
- ZIP extraction rejects absolute paths, drive-prefixed paths, traversal segments, NUL characters, excessive depth/name lengths, more than 10,000 entries, and more than 4 GiB expanded data.
- Copy/ZIP/extract loops call coroutine cancellation checks and remove partial destinations on failure/cancellation where the provider permits deletion.

Acceptance:
- [x] Zero new dangerous permission.
- [x] File operations use the IO dispatcher and cooperative cancellation.
- [x] ZIP extraction has an explicit zip-slip guard before document creation.
- [x] `ZipPathGuardTest` covers traversal/absolute path fixtures.

## Task 2.3 — Contact duplicate/merge

- Added `feature:contacts`; only this feature manifest declares `READ_CONTACTS` and `WRITE_CONTACTS`.
- Runtime permission requests occur only inside `ContactMergeRoute`.
- No contact value is written to Room/DataStore, logged, backed up, or transmitted.
- Duplicate analysis normalizes names, phones, and email addresses, uses normalized Levenshtein scoring plus exact/suffix identity signals, caps contacts/results, and caps pair comparisons at 250,000.
- Merge is never automatic. The user reviews a pair and confirms before `ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER` rules are applied.
- Each successful merge stores only an in-memory aggregation-rule snapshot in the screen ViewModel. Undo restores the preceding rules in LIFO order. The stack is cleared when the screen ViewModel is destroyed.

Acceptance:
- [x] Permissions requested only from this feature's entry point.
- [x] Contact data stays on-device and is not logged.
- [x] Every merge requires explicit confirmation and has current-session undo.
- [x] Unit tests cover name/phone/email similarity fixtures and pair deduplication.

## Task 3.1 — Per-app data-usage caps and alerts

- Extended the existing NetworkRepository/NetworkViewModel path; no parallel usage reader is introduced.
- Thresholds and previous observations use feature DataStore preferences.
- A unique WorkManager periodic job runs every 6 hours (1-hour flex), only when caps exist.
- The worker reuses Usage Access-gated `NetworkStatsManager` data. Shared-UID packages are rejected/skipped because Android cannot reliably attribute their bytes to one package.
- Alerts are local notifications. On API 33+, if the already-existing notification permission is absent, the worker records state but does not post a notification; this task adds no notification permission.
- Crossing is edge-triggered per calendar month so a threshold does not repeatedly notify every work run.

Acceptance:
- [x] No new permission for Task 3.1; existing Usage Access is reused.
- [x] Threshold checks use bounded periodic WorkManager, not foreground polling.
- [x] Unit tests cover threshold crossing, new-period behavior, and malformed preference values.

## Task 3.2 — Battery health trend

- Added a Room daily snapshot keyed by local `epochDay`; `@Upsert` makes repeat runs for the same day idempotent.
- Database schema advances from version 2 to 3 through explicit `Migration2To3`; the existing 1→2 migration remains registered.
- Cycle count comes from the existing battery telemetry reader where Android/OEM exposes it.
- Full-charge capacity is explicitly labeled an estimate derived only when the existing charge-counter and battery-level signals are available and plausible. Missing/implausible signals remain unavailable rather than being fabricated.
- The unique WorkManager snapshot job runs every 24 hours (6-hour flex), retains bounded history, and the Battery screen shows an explicit insufficient-history state below 7 distinct days.

Acceptance:
- [x] No new permission.
- [x] Same-day snapshot writes are idempotent by primary key/upsert.
- [x] Fewer than 7 distinct days produces `InsufficientHistory`, never a fabricated trend.
- [x] Unit tests cover same-day keys, missing-signal estimates, insufficient history, and duplicate-day collapse.

## Automated/static validation performed in this environment

- Project validator on the frozen source: PASS (1549 checks, 33 XML files, 14 main manifests).
- Kotlin/KTS delimiter and whitespace scan over all changed Kotlin/KTS files: PASS.
- Permission diff against Task 1.4: exactly `INTERNET`, `ACCESS_WIFI_STATE`, `READ_CONTACTS`, `WRITE_CONTACTS` added; no permission removed.
- Forbidden-surface scan of changed source: no `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, arbitrary shell/process execution, logging of contact/file content, or application network client.
- 100,000 randomized subnet-policy cases: zero modeled invariant violations.
- 100,000 randomized ZIP-path cases plus malicious fixtures: zero traversal acceptance.
- 100,000 randomized contact-similarity symmetry/property cases: zero modeled invariant violations.
- 100,000 randomized data-cap codec cases: zero modeled invariant violations.
- 200,000 randomized battery-capacity-estimate cases: zero modeled invariant violations.
- In-memory SQLite schema simulation confirms the battery daily primary key replaces a same-day row instead of duplicating it.

## Gradle/Android release gate

The following command was attempted against the final source:

```text
./gradlew :feature:network:testDebugUnitTest :feature:files:testDebugUnitTest :feature:contacts:testDebugUnitTest :feature:battery:testDebugUnitTest --no-daemon
```

The wrapper could not bootstrap Gradle because this execution environment cannot resolve `services.gradle.org`; Kotlin/JUnit compilation therefore did not start. This is not counted as a test pass.

Before Play release, run the repository CI/device matrix (API 26, 28, 30, 33, 35, 36) and specifically exercise:
- Wi-Fi/Ethernet diagnostics on reachable/unreachable hosts, VPN/cellular refusal, cancellation, and a broad subnet.
- Multiple SAF providers (DocumentsUI/local storage and at least one cloud-backed provider), process cancellation, move rollback, malicious ZIPs, and low-space failures.
- Contacts permission deny/allow, aggregation confirmation, multiple sequential merges and LIFO undo, read-only/provider-managed contacts, and screen-close undo expiration.
- Usage Access absent/present, shared-UID apps, threshold crossing/month rollover, reboot/rescheduling, and API 33+ notification-denied behavior.
- Battery devices that expose both, one, or neither of cycle count/charge counter; timezone/day rollover; repeated worker runs; 6-day versus 7-day history.
- Room upgrade paths 1→2→3 and 2→3 on representative persisted databases.
