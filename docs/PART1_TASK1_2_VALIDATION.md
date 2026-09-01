# Phase 1 Task 1.2 Validation — Blurry / Low-Quality Photo Flagging

## Scope

Task 1.2 extends the cleaner's existing perceptual-photo analysis pass with a conservative Laplacian-variance sharpness score. It does not add permissions, background services, persistence, network activity, or automatic deletion behavior.

## Integration checks

- The sampled bitmap decoded for near-duplicate dHash analysis is also used for sharpness scoring.
- There is one actual sampled bitmap decode per image-analysis attempt; the preceding `inJustDecodeBounds` pass allocates no bitmap.
- `LaplacianVariance` operates on an in-memory luminance buffer derived from that sampled bitmap.
- Images whose sampled dimensions are too small for a 3×3 Laplacian neighborhood report sharpness as unavailable and are not fabricated as blurry candidates.
- The sampled bitmap is recycled in the same `finally` block after both dHash and sharpness metrics are computed.
- Blurry candidates are stored in `CleanerScanResult.blurryPhotos`, separate from exact duplicates, near-duplicates, large files, and suspected junk.
- The UI exposes a dedicated `Blurry photos` review category with per-item selection only.
- No bulk-selection method reads `blurryPhotos`; `selectAllSuspectedJunk()` remains limited to strong temporary/log candidates.
- Analysis retains the shared 100,000-image ceiling from `CleanerScanLimits.MAX_ITEMS`.

## Pure-logic tests

`LaplacianVarianceTest` covers:

- deterministic synthetic blurred-edge versus sharp-edge fixtures;
- uniform and linear-gradient low-frequency fixtures;
- undersized input;
- invalid buffer dimensions.

`PerceptualDuplicateFinderTest` additionally verifies below-threshold classification and confirms threshold-equal/sharp samples are not flagged.

## Validation commands

`python tools/validate_project.py`

Result in the development environment:

```text
ApexTuner validation: PASS (1264 checks, 29 XML files, 11 main manifests)
```

`./gradlew :feature:cleaner:testDebugUnitTest`

The Gradle test task could not begin because the isolated development environment could not resolve `services.gradle.org` to bootstrap the pinned Gradle distribution. No Kotlin compiler or JUnit failure was observed because compilation did not start.
