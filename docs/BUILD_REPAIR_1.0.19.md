# ApexTuner Android — Landscape UI + Photo Picker Crash Repair 1.0.19

**Version:** `1.0.19` / `versionCode 19`  
**Repair date:** 2026-08-29

## Scope

This cumulative repair starts from the clean 1.0.18 Android Studio source package and preserves the AGP 9.3.2 / Gradle 9.5 / Kotlin 2.3.21 / KSP 2.3.9 / Hilt 2.60.1 migration and all compiler-warning fixes from 1.0.16–1.0.18.

Two runtime issues were addressed:

1. phone landscape windows could become cramped/clipped because the shell selected a full tablet-style NavigationRail based on width alone;
2. Optimize → **Select photos/videos** could terminate the app when its fixed 250-item request exceeded the device Photo Picker's platform maximum.

## Confirmed Photo Picker crash cause

The previous implementation created both `PickMultipleVisualMedia(250)` and a `PickVisualMediaRequest(maxItems = 250)`. On devices with the framework Photo Picker, AndroidX validates the effective maximum against `MediaStore.getPickImagesMaxLimit()` while creating the launch intent. If the requested count exceeds the device limit, AndroidX throws `IllegalArgumentException` before the picker opens.

### Repair

- Removed the fixed `PHOTO_PICKER_MAX_ITEMS = 250` policy completely.
- Use `ActivityResultContracts.PickMultipleVisualMedia()` with its AndroidX/device-aware default.
- Use `PickVisualMediaRequest(ImageAndVideo)` without overriding `maxItems`.
- Retained AndroidX's normal system/fallback picker behavior.
- Added a defensive explicit Storage Access Framework fallback using `OpenMultipleDocuments` for malformed OEM picker implementations or missing handlers.
- If even the fallback cannot launch, ApexTuner reports a visible error instead of terminating.
- Existing persistent read access behavior for user-selected media remains unchanged.

## Landscape/adaptive UI repair

The old shell used only `maxWidth >= 600.dp` to choose a navigation rail. A landscape phone can easily be wider than 600dp while only ~320–450dp tall. The resulting rail contained the 64dp brand mark, two text lines, Premium chip, and five labeled destinations, which could exceed available height before feature content was considered.

### New navigation strategy

`ApexLayout.navigationPresentationFor(widthDp, heightDp)` now classifies the actual app window as:

- `BottomBar` — normal compact portrait phone windows;
- `CompactRail` — short wide/landscape phone and compact freeform windows;
- `ExpandedRail` — tablet/large windows with enough vertical room.

Compact rail behavior:

- no brand header;
- no rail text labels;
- icon content descriptions retained for accessibility;
- reduced outer/horizontal rail padding;
- selected destination remains visually indicated;
- top-level content gets compact landscape horizontal padding.

Additional phone-landscape safeguards:

- Dashboard metric grid remains single-column on compact landscape rather than forcing narrow two-column metric cards.
- Tools grid remains single-column on compact landscape rather than compressing long descriptions.
- Dashboard loading placeholders are now in a `LazyColumn`, so all content is reachable on short windows.
- `FeatureLanding` is vertically scrollable.
- Cleaner permission/removal dialogs, Settings restore/legal dialogs, VPN disclosure, and Advanced confirmation text are scroll-safe in compact-height and large-font windows.

## Regression tests added

`core/src/test/.../ApexLayoutTest.kt` covers representative portrait phone, landscape phone, tablet, and short freeform sizes.

`app/src/androidTest/.../ApexTunerSmokeTest.kt` now includes:

- `phoneLandscapeKeepsTopLevelNavigationReachable()`
- `defaultMultiPhotoPickerContractCreatesAnIntent()`

The latter directly exercises AndroidX intent creation so an invalid hard-coded Photo Picker maximum is caught on supported emulator/API configurations.

## Checks executed in this environment

- Enterprise structural validator: **PASS — 1,223 checks**, 29 XML files, 11 main manifests.
- Production-domain Kotlin harness: **PASS — 50,022 checks**.
- `SystemProfilePlanner` randomized property harness: **PASS — 900,000 checks**.
- Parser-level scan of all modified Kotlin surfaces: **0 syntax/parser markers**.
- Exact source scan: no `PHOTO_PICKER_MAX_ITEMS`, numeric `PickMultipleVisualMedia(...)`, or explicit `maxItems` remains in the cleaner launch path.

## Runtime boundary

This container still has no Android SDK/emulator and therefore cannot execute AGP assembly, Compose instrumentation, rotation on an emulator, or the system Photo Picker itself. The project includes the corresponding Android instrumentation regressions so these paths are exercised by the external Android/CI/device gate. The concrete Photo Picker exception path was verified against the current AndroidX implementation and Android Photo Picker contract.
