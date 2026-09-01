# ApexTuner Part 4 validation record

Date: 2026-08-28

## Scope

Part 4 adds the production Storage Cleaner and renames the cumulative product/project consistently to **ApexTuner** (`com.apextuner.*`). This document distinguishes checks actually executed in the available environment from release gates that still require the Android toolchain and devices.

## Official Android / Google Play contracts cross-checked

### Android 16 / API 36

Official Android 16 setup documentation confirms `compileSdk = 36` for Android 16 APIs and `targetSdk = 36` when opting into Android 16 target behavior:
https://developer.android.com/about/versions/16/setup-sdk

ApexTuner remains `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`.

### MediaStore confirmation/removal

`MediaStore.createDeleteRequest()` and `createTrashRequest()` are API-30+ user-confirmed operations. The MediaStore API contract states that an approved requested operation has completely finished before the activity result is delivered. For apps targeting Android 16/Baklava and above, a request may contain at most 2,000 URIs:
https://developer.android.com/reference/android/provider/MediaStore

ApexTuner therefore:
- caps one MediaStore removal request at 2,000 items;
- treats `RESULT_OK` as the authority for the confirmed MediaStore operation;
- does not infer deletion/trash success by querying deleted/trashed URIs afterward;
- performs a reconciliation scan after removal;
- fails safe after process recreation by never reconstructing or continuing lost direct-SAF selections.

### Storage Access Framework restrictions

Android 11+ does not allow `ACTION_OPEN_DOCUMENT_TREE` access to storage roots or the Download root, and SAF cannot be used to select content under `Android/data` or `Android/obb`:
https://developer.android.com/about/versions/11/privacy/storage
https://developer.android.com/training/data-storage/shared/documents-files

ApexTuner does not promise or fake access to those locations.

### Photo Picker

Android documents Photo Picker as a privacy-preserving selected-media flow. Supported older devices with Google Play services can receive a backported picker when the documented disabled ModuleDependencies metadata service is present:
https://developer.android.com/training/data-storage/shared/photo-picker

ApexTuner includes that manifest metadata service and provides Photo Picker as a read-only alternative to broad visual-library access.

### StorageStatsManager

`queryStatsForUser()` requires user-granted Usage Access and may take several seconds, so Android explicitly says it should run on a worker thread:
https://developer.android.com/reference/android/app/usage/StorageStatsManager

ApexTuner calls StorageStats on its IO dispatcher and remains usable if Usage Access is absent.

### Google Play photo/video permission policy

Google Play requires apps targeting Android 13+ to use broad `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` only when picker-based minimum-scope access cannot provide the app's core functionality, and apps retaining the broad permissions must submit the corresponding Play Console declaration:
https://support.google.com/googleplay/android-developer/answer/16558241

ApexTuner keeps broad media access optional for its user-invoked bulk storage-management function and also exposes Photo Picker/SAF alternatives. Release publication still requires the Play Console declaration/review if these broad permissions remain in the production manifest.

## Executed implementation checks

### Storage-domain behavior

Pure Kotlin checks cover:
- media/document classification;
- conservative junk-directory matching;
- prefix + full exact-duplicate grouping;
- provider-boundary isolation;
- Android-proven physical alias collapse;
- cross-MediaStore/SAF exact duplicates only when distinct physical identities are proven;
- independent best-quality and newest keep candidates;
- read-only redundant copies excluded from reclaimable-byte totals;
- duplicate/junk overlap excluded from double-counting;
- physical aliases excluded from accessible-byte double-counting;
- saturating overflow-safe reclaim totals.

### Destructive-operation review

Source audit verifies:
- no removal occurs without selected user items;
- selected aliases are deduplicated by physical identity before removal;
- Android 11+ prefers MediaStore confirmation/Trash for removable MediaStore aliases;
- direct provider removal excludes MediaStore on API 30+;
- Photo Picker items are not deletable;
- direct provider commit is bounded to 500 items;
- MediaStore confirmation is bounded to 2,000 items;
- access changes/scans/removals are serialized at the ViewModel boundary;
- a lost in-memory selection after process recreation does not cause guessed SAF deletion;
- Room/audit-history failures are separated from the success/failure of the actual file operation.

### Permission / manifest review

- `MANAGE_EXTERNAL_STORAGE`: absent.
- `QUERY_ALL_PACKAGES`: absent.
- broad media permissions are explicit and user-invoked.
- legacy write permission is `maxSdkVersion=29` and is requested separately from scanning.
- cleartext traffic disabled.
- Android automatic backup disabled.
- Photo Picker backport metadata service disabled and not exported.

### Branding review

- root project: ApexTuner.
- app label: ApexTuner.
- application ID: `com.apextuner.app`.
- module namespaces and Kotlin packages: `com.apextuner.*`.
- theme/application class/database/DataStore/Keystore identifiers updated to ApexTuner naming.
- legacy development branding is rejected by the package validation script.

## Safety/efficiency constraints intentionally enforced

- no whole-file buffering for duplicate hashes;
- bounded hash buffer;
- bounded file count and directory traversal;
- cooperative scan/hash cancellation;
- no silent deletion;
- no fabricated Android privileges;
- no all-files permission;
- no background auto-clean introduced by Part 4;
- no network upload of scan data;
- no attempt to bypass scoped storage.

## Not verified in this execution environment

The available execution environment does not include the Android SDK, emulator images or a trusted generated Gradle wrapper JAR. The following must still be completed before a release can be called production-verified:

1. `./gradlew clean :app:assembleDebug`.
2. `./gradlew lint test`.
3. minified release build and R8/resource-shrink validation.
4. Compose/instrumented cleaner tests.
5. API 26/28/30/33/35/36 emulator/device matrix.
6. physical phone/tablet tests.
7. real MediaStore Trash/Delete confirmation on Android 11–16.
8. Android 10 scoped-storage behavior on a real/emulated API-29 device.
9. representative DocumentsProviders (AOSP DocumentsUI, Google Drive/provider if supported, removable SD storage).
10. process-death restoration while the system MediaStore confirmation UI is open.
11. 100k-item and multi-gigabyte media stress tests with memory/CPU/battery profiling.
12. Play pre-launch report and the production Photo/Video permission declaration/review if broad permissions are retained.

No statement in this record should be interpreted as claiming those Android/device gates have already run.

## Final local validation results

Executed after the ApexTuner rename and final process-recreation/Photo-Picker hardening:

- Cleaner pure-domain executable harness: **PASS**.
- Part 3 dashboard accumulation/recommendation regression harness after namespace rename: **PASS**.
- Part 3 telemetry parser regression harness after namespace rename: **PASS**.
- Gradle modules present: **11 / 11**.
- Project XML files parsed: **30 / 30**.
- Version-catalog TOML parse: **PASS**.
- Kotlin package declarations vs source paths: **PASS**.
- API baseline check: all modules `compileSdk = 36`; app `minSdk = 26`, `targetSdk = 36`: **PASS**.
- ApexTuner branding check: **PASS**; legacy development brand occurrences: **0**.
- `TODO` / `FIXME` / `NotImplementedError` implementation markers: **0**.
- `MANAGE_EXTERNAL_STORAGE` / `QUERY_ALL_PACKAGES` manifest occurrences: **0**.
- Part 4 safety invariants (streaming SHA-256, scan limits, MediaStore 2,000-item cap, 500 direct-item cap, alias mapping, process-recreation fail-safe): **PASS**.

The standalone executable harnesses use the system Kotlin compiler only for pure Kotlin source validation. They are not a substitute for the project's actual Android/Kotlin/AGP build.

## Toolchain cross-check

Android's AGP 8.11 release notes document API 36 as the maximum supported API, Gradle 8.13 as the required/default Gradle version, and JDK 17 as the required/default JDK. That matches this project's build baseline:
https://developer.android.com/build/releases/agp-8-11-0-release-notes
