# ApexTuner Phase 1 Task 1.3 — Validation

## Scope

Task 1.3 adds an on-device **Compress** action to the existing Cleaner > Large files review flow.

The implementation:

- estimates image/video output size before any write;
- defaults to **Save as copy**;
- requires a distinct second in-app confirmation for **Replace original**;
- reuses the cleaner's existing MediaStore and persisted SAF grants;
- stages every encoded result in app-private cache before publishing or replacing;
- uses `BitmapFactory`/`Bitmap.compress` for JPEG/PNG/WebP images;
- uses `MediaExtractor` + `MediaCodec` + an encoder input `Surface` rendered through EGL/OpenGL ES + `MediaMuxer` for H.264/AAC MP4 video output;
- checks device decoder/encoder capability, track structure, DRM initialization/PSSH/CAS metadata, encrypted sample flags, HDR transfer characteristics, and AAC passthrough compatibility before enabling video compression;
- never commits an output that is not actually smaller than the source;
- creates and byte-verifies a rollback snapshot before a destructive replacement;
- retains that verified recovery snapshot if a catastrophic provider failure prevents verified rollback;
- keeps staging, transcoding, save-as-copy, and rollback-snapshot creation cancellable;
- makes only the final overwrite/verify/rollback transaction non-cancellable so cancellation cannot intentionally strand an original half-written;
- inherits the existing Premium gate for the Large-file cleaner; Free-tier users see the existing upgrade path rather than a runnable compression action.

No manifest permission, Gradle dependency, database schema, service, receiver, or network behavior was added.

## Platform/API review

The implementation was checked against Android platform behavior relevant to the minSdk 26 / targetSdk 36 floor:

- `MediaStore.createWriteRequest()` is used only on Android 11/API 30+ to obtain user-approved write access for MediaStore items.
- Android 10/API 29 uses `RecoverableSecurityException` and its user action when a MediaStore edit needs user approval.
- Android 8–9 replacement is exposed only when the cleaner's existing legacy write grant is already present.
- SAF output is restricted to persisted writable tree/document grants. Creating a sibling requires `FLAG_DIR_SUPPORTS_CREATE`; replacing an original requires `FLAG_SUPPORTS_WRITE`.
- framework `ExifInterface(InputStream)` is available below the project floor (API 24+); visible image orientation is applied to pixels before output, while the replace confirmation explicitly discloses that arbitrary EXIF fields are not guaranteed to survive re-encoding.
- video encoding uses the platform H.264 surface-encoder path and signals surface EOS with `MediaCodec.signalEndOfInputStream()`;
- extractor sync/partial-frame flags are explicitly translated to the corresponding `MediaCodec` buffer flags instead of reusing extractor bit values.

## Executed validation

### Repository validator — PASS

```text
ApexTuner validation: PASS (1288 checks, 29 XML files, 11 main manifests)
```

### Differential configuration guard — PASS

The current Task 1.3 tree was compared byte-for-byte with the Task 1.2 baseline.

- no `AndroidManifest.xml` changed;
- no `build.gradle(.kts)` changed;
- no version-catalog file changed;
- no new `MANAGE_EXTERNAL_STORAGE`;
- no new `QUERY_ALL_PACKAGES`;
- no shell/process execution surface was introduced.

### Pure estimator randomized simulation — PASS

250,000 deterministic randomized dimension/duration/size cases were exercised against a faithful model of the production estimator math.

Checked properties:

- target dimensions stay positive and even;
- target long edge remains bounded by the selected preset;
- target video bitrate remains in the production 350 kbps–12 Mbps safety range;
- duration-to-byte arithmetic stays positive and bounded by `Long.MAX_VALUE`;
- no negative/overflowed savings values are produced.

No invariant violation was observed.

### Deterministic estimator fixtures — PASS

The production test fixtures were independently recalculated:

- PNG 2560×1440 → Compact 1280×720: estimated 2,880,000 bytes from a 12,000,000-byte source;
- 4K/60-second video → Compact 1280×720: 1,520,640 bps target video bitrate and 12,364,800-byte estimated output;
- JPEG aspect-ratio/downscale math remained bounded and predicted a smaller output for the fixture.

### Presentation-timestamp simulation — PASS

10,000 deterministic randomized timestamp sequences, including duplicate and backward input timestamps, were passed through the production monotonic-normalization rule. Every modeled output sequence remained strictly increasing before muxer submission.

### Replacement safety structural assertions — PASS

Source-level ordering assertions verified all of the following in the current production file:

1. a complete staged output is created and fsynced before replacement can be dispatched;
2. the staged output must be non-empty and actually smaller than the original;
3. rollback-space availability is rechecked immediately before snapshot creation;
4. the rollback snapshot is cancellably copied and fsynced;
5. the snapshot size and SHA-256 bytes are verified against the still-unchanged original;
6. UI progress switches to a non-cancellable destructive phase before the overwrite boundary;
7. immediately before overwrite, the source is SHA-256 rechecked against the verified snapshot so an external change aborts without overwriting newer bytes;
8. the actual overwrite occurs only inside the non-cancellable commit block;
9. staged bytes are SHA-256 verified after the overwrite;
10. any commit/verification failure attempts rollback and verifies rollback bytes;
11. a failed rollback verification raises an explicit high-severity error rather than claiming success;
12. the verified rollback snapshot is retained instead of deleted when rollback cannot be verified.

### Fault-injection transaction simulation — PASS with documented physical-device boundary

A state-machine fault simulation covered failures/cancellation:

- before staging;
- during staging;
- after staging;
- during rollback snapshot;
- after rollback snapshot;
- immediately before overwrite;
- during overwrite;
- after overwrite but before verification;
- new-content verification failure;
- rollback-write failure;
- rollback-verification failure;
- successful commit.

All pre-destructive failures left the modeled original unchanged. Commit failures restored and verified the modeled original when rollback I/O succeeded. The two intentionally catastrophic rollback-I/O cases produced an explicit unverified/high-severity state rather than a false success.

This simulation validates transaction ordering, not Android/OEM storage-provider durability. Process kill, device power loss, provider-specific truncate semantics, and physical codec behavior remain real-device release gates.

### Cancellation/cleanup structural checks — PASS

- image encoding checks coroutine cancellation through a cancellation-aware output stream;
- video feed/drain, frame waits, and AAC passthrough loops call `ensureActive()`;
- video sample timestamps are normalized to a strictly increasing presentation sequence before surface submission;
- save-as-copy loops call `ensureActive()`;
- partial SAF documents are deleted on cancellation/failure;
- partial MediaStore rows are deleted on cancellation/failure;
- rollback snapshot creation is cancellable;
- destructive overwrite, verification, and rollback are intentionally non-cancellable once started.

### Kotlin/JUnit Gradle execution — ENVIRONMENT BLOCKED

Attempted:

```text
bash ./gradlew :feature:cleaner:testDebugUnitTest --no-daemon
```

The Gradle wrapper could not download Gradle 9.5.0 because this environment cannot resolve `services.gradle.org`:

```text
curl: (6) Could not resolve host: services.gradle.org
```

Therefore Android/Kotlin compilation and JUnit execution did **not** begin in this container. No compile/test success is claimed from that command.

## Unit tests added

`MediaReencodeEstimatorTest.kt` includes deterministic coverage for:

- JPEG target/aspect/savings estimation;
- PNG resize-only estimation;
- video bitrate × duration output estimation;
- non-MP4 replacement rejection;
- unsupported/animated-format rejection behavior;
- missing video duration;
- already-efficient media producing zero predicted savings;
- target-resolution bounds/even dimensions;
- rollback-snapshot cancellability versus destructive-phase non-cancellability;
- overflow resistance with `Long.MAX_VALUE`.

`VideoTranscoderFlagsTest.kt` additionally guards the extractor-to-codec flag mapping, including the API-26 partial-frame constants whose numeric values differ between `MediaExtractor` and `MediaCodec`, and verifies that the encrypted-sample bit is never forwarded as an ordinary codec input flag.

## Packaging integrity — PASS

Before final delivery, both archive layouts were CRC-tested and every archived file was SHA-256 compared with the working tree:

- full Android Studio project: 245 files verified;
- changed-files integration archive: 11 files verified;
- the extracted full archive reran `tools/validate_project.py` successfully with 1288 checks;
- applying the changed-files archive to a fresh Task 1.2 baseline recreated the final 245-file tree byte-for-byte.

The final archive generation uses the same deterministic file set and is re-verified after this validation note is included.

## Required Android Studio/device release gates

Before release, run at minimum:

```text
./gradlew :feature:cleaner:testDebugUnitTest
./gradlew :feature:cleaner:lintDebug
./gradlew :app:assembleDebug
```

Then exercise on API 26, 29, 30, 33, 35, and 36:

1. JPEG/PNG save-as-copy from MediaStore;
2. MP4 H.264/AAC save-as-copy;
3. unsupported audio/video codec capability state;
4. Android 10 recoverable MediaStore write approval;
5. Android 11+ `createWriteRequest()` approval and denial;
6. writable and read-only SAF providers;
7. cancellation during image encode, video encode, copy publish, and rollback snapshot;
8. deliberate low-space failure before staging and before rollback snapshot;
9. replacement success with byte verification;
10. injected/provider write failure to verify rollback behavior;
11. process termination/power-loss behavior on representative OEM devices.

No real-device result is claimed where this container cannot provide Android framework/media hardware/runtime behavior.
