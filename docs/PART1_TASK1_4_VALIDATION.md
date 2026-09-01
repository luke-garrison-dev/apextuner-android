# PART 1 — TASK 1.4 VALIDATION

## Scope

Task 1.4 adds opt-in, local-only notification history and management through a dedicated
`feature:notifications` module. Notification collection is disabled by default and requires:

1. ApexTuner's explicit in-app opt-in after the prominent disclosure.
2. Verified Premium entitlement through `EntitlementRepository`.
3. Android Notification access granted by the user.
4. A platform/profile where `NotificationListenerService` is available.
5. A connected listener whose entitlement refresh and initial database maintenance completed.

Stored Room rows contain only a generated row ID, source package name, title, text, and post
timestamp. Muted-package preferences and the retention window are kept in Preferences DataStore.
Notification content is not exported by ApexTuner backup and is not sent to a network service.

## Android API and compatibility review

- minSdk remains 26; compileSdk/targetSdk remain 36.
- The listener declaration uses `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` as the
  system binding permission on the service. It does not add a `<uses-permission>`.
- The service is `android:enabled="false"` in the manifest and becomes enabled only after the
  ApexTuner opt-in flow.
- `NotificationManager.isNotificationListenerAccessGranted(ComponentName)` is used on API 27+;
  API 26 falls back to `NotificationManagerCompat.getEnabledListenerPackages`.
- `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` is used only on API 30+.
- The general `ACTION_NOTIFICATION_LISTENER_SETTINGS` screen and then general Settings are safe
  fallbacks when a more-specific activity cannot be resolved.
- Static `NotificationListenerService.requestUnbind(ComponentName)` is guarded to API 34+.
  The instance `requestUnbind()` path is available from API 24 and therefore covers minSdk 26.
- Low-RAM Android 10-or-earlier devices and work-profile execution are reported as unavailable
  rather than pretending collection works.
- Android 15+ sensitive-notification/OTP redaction is accepted as returned by the platform.
  `RECEIVE_SENSITIVE_NOTIFICATIONS` is intentionally not requested.

Official Android references reviewed on 2026-08-29:
- https://developer.android.com/reference/android/service/notification/NotificationListenerService
- https://developer.android.com/reference/android/provider/Settings
- https://developer.android.com/about/versions/15/behavior-changes-all
- https://developer.android.com/reference/android/Manifest.permission
- https://developer.android.com/work/managed-profiles

## Privacy and collection-state invariants

Collection is allowed only when all gates are true:

- ApexTuner setting enabled.
- Premium access present.
- Android notification access present.
- Platform/profile availability present.
- Listener is connected and the collection-ready latch is true.

The listener's collection-ready latch remains false until preferences are loaded, entitlement
refresh completes successfully, Premium access is confirmed, and retention/hard-limit maintenance
completes. Disabling the in-app setting or losing Premium access clears readiness before unbinding
or disabling the component. Existing history remains reviewable and deletable after entitlement
loss.

The in-app disclosure explicitly explains the breadth of Android Notification access, the exact
fields stored, local-only handling, retention/clear controls, per-app ApexTuner-only muting, and
platform redaction behavior before the Android settings surface is opened.

## Database migration validation

`ApexTunerDatabase` moves from schema version 1 to 2 with `Migration1To2`.

The migration creates:

- `notification_history`
- index on `postedAtEpochMillis`
- index on `packageName`

There is deliberately no uniqueness constraint on `(packageName, postedAtEpochMillis)`. Two
legitimate notifications from one app can share a millisecond timestamp and must not be silently
collapsed.

SQLite migration simulation results:

- Table shape: 5 columns with non-null package/title/text/timestamp and auto-generated ID.
- Indexes: exactly the two intended non-unique indexes.
- Same package + same timestamp + different content: both rows retained.
- 30,000-row flood followed by hard-limit trim: newest 25,000 retained.
- Retention deletion uses `< cutoff`; a row exactly on the cutoff remains.

## Data minimization and bounds

- Title is bounded to 256 UTF-16 code units before String materialization.
- Body is bounded to 2,048 UTF-16 code units before String materialization.
- Package names are bounded to 255 characters and sanitized before persistence.
- Empty title + empty body is rejected.
- Muted-package preferences are sanitized, exclude ApexTuner itself, and are capped at 2,000.
- Review UI observes at most 500 newest rows.
- Target history bound is 25,000 rows.
- During high-volume listener capture, hard-limit maintenance runs every 128 successful inserts;
  therefore the modeled transient maximum before an in-process trim is 25,128 rows. The periodic
  cleanup worker independently re-enforces the 25,000-row target.
- Retention windows are restricted to 1/3/7/14/30 days, default 7 days.
- Changing retention prunes immediately, while unique periodic WorkManager cleanup enforces it
  over time.

## Management behavior

- "Clear all" deletes all local notification-history rows after an explicit confirmation.
- Per-app clear deletes only that app's local rows.
- Per-app mute changes only ApexTuner's local capture preference.
- No notification channel is created, changed, muted, or deleted by this feature.
- Disabling collection disables the listener component but does not trap or hide existing data.
- Notification history is intentionally excluded from manual backup/export.

## Tests and simulations

### Repository validator

Final source before packaging:

```text
ApexTuner validation: PASS (1365 checks, 31 XML files, 12 main manifests)
```

### Pure/JVM test sources

`NotificationHistoryPolicyTest.kt` covers:

- supported retention windows and default fallback;
- cutoff calculation and epoch flooring;
- package sanitizer invalid/unsafe cases;
- bounded title/body sanitization;
- proof that a large `CharSequence` is sliced before `toString()` materialization;
- muted-package sanitization and cap;
- exhaustive Boolean collection-gate behavior.

`NotificationHistoryRepositoryTest.kt` covers:

- sanitization/bounding before DAO insertion;
- invalid-package and contentless rejection;
- DAO insertion failure propagation through the Boolean result;
- retention cutoff and hard-limit forwarding.

### Deterministic simulations executed outside Gradle

- 32-state exhaustive opt-in/Premium/access/availability/readiness truth-table:
  exactly one state permits collection.
- 200,000-event randomized state-transition simulation:
  zero modeled unauthorized-capture states.
- 100,000-case randomized retention/cutoff simulation:
  zero range/normalization invariant violations.
- 100,000-case package-name sanitizer fuzzing:
  zero modeled valid/invalid classification invariant violations.
- 100,000-notification flood model:
  periodic hard-limit enforcement remained bounded to a maximum modeled transient row count of
  25,128.
- SQLite migration/database simulation:
  migration shape, indexes, same-millisecond coexistence, retention boundary, ordering, and
  30,000→25,000 trimming all passed.

### Static privacy/security checks

Passed:

- notification service manifest-disabled by default;
- no new `<uses-permission>` compared with Task 1.3;
- no `RECEIVE_SENSITIVE_NOTIFICATIONS`;
- no `MANAGE_EXTERNAL_STORAGE`;
- no `QUERY_ALL_PACKAGES`;
- no notification-content logging in `feature:notifications`;
- no HTTP client/socket/network path in `feature:notifications`;
- no notification-channel mutation API in `feature:notifications`;
- no notification-history payload in ApexTuner backup;
- no arbitrary shell/process execution;
- API-30 and API-34 listener calls are version guarded;
- all Kotlin/KTS source passed lexical delimiter/string/comment balancing;
- changed text files contain no merge markers, tabs, or trailing whitespace.

## Gradle/JUnit execution status

Attempted on the final source:

```text
bash ./gradlew :feature:notifications:testDebugUnitTest --no-daemon
```

The wrapper could not bootstrap the pinned Gradle distribution because this isolated execution
environment cannot resolve `services.gradle.org`:

```text
curl: (6) Could not resolve host: services.gradle.org
```

Kotlin/JUnit compilation therefore did not begin. No claim is made that Gradle/JUnit or Android
runtime tests passed in this environment.

## Required Android Studio/device release gates

Before release, run the project CI/device matrix (API 26, 28, 30, 33, 35, 36) and verify:

1. Clean install starts with the listener component disabled and no notification rows.
2. Canceling the disclosure makes no component/state change.
3. Enabling without Android access collects nothing.
4. Granting access after the disclosure starts collection only after listener connection.
5. Revoking Android access stops collection.
6. Turning the ApexTuner toggle off stops collection while stored rows remain manageable.
7. Premium entitlement loss stops new collection while stored rows remain manageable.
8. Low-RAM Android Q-or-earlier and work-profile cases report unavailable where reproducible.
9. Android 15+ OTP/sensitive-content redaction is displayed exactly as Android exposes it.
10. 1/3/7/14/30-day retention windows prune correctly.
11. Cleanup worker survives process death/reboot according to WorkManager behavior.
12. Clear-all and per-app clear affect only ApexTuner's local database.
13. Per-app mute suppresses future ApexTuner history without touching Android notification channels.
14. Large notification text is bounded without ANR/memory spikes.
15. Rapid notification bursts maintain ordering and bounded storage.
16. Database migration from a real v1 app database preserves existing scan/history tables.
17. Backup export contains no notification-history rows/text/title/muted-package preferences.
18. App uninstall/reinstall and app-data-clear behaviors are consistent with Android's access model.

## Acceptance criteria

- [x] Disabled by default, no data collected until user explicitly grants notification access.
- [x] Local Room storage only, retention window enforced by a WorkManager cleanup job.
- [x] Explicit in-app disclosure screen before requesting access, matching the existing prominent-disclosure pattern.
- [x] Premium-gated through `PremiumFeature.NotificationHistory` and `EntitlementRepository`.
