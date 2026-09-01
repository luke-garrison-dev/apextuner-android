# ApexTuner Android 1.1.4 / versionCode 37 — holistic maintenance audit

## Confirmed issues fixed

1. **Quick Settings / widget relaunch could recreate MainActivity instead of reusing it.**
   - `MainActivity` already contains `onNewIntent()` handling for one-shot launch requests, but the manifest did not declare `singleTop`.
   - Quick Scan uses `FLAG_ACTIVITY_CLEAR_TOP`; with a standard launch mode Android can destroy/recreate the existing activity rather than deliver the request to `onNewIntent()`, losing in-memory navigation/UI state.
   - Fixed by declaring `android:launchMode="singleTop"` for `MainActivity`.

2. **Cold-start Quick Scan had a state-initialization race.**
   - `OptimizeRoute` previously started and consumed an auto-scan request immediately, even while `CleanerViewModel` was still `Loading`.
   - `CleanerViewModel.startScan()` reports progress/results through `updateReady()`. If a fast/empty scan completed before `refreshAccess()` produced `Ready`, those state writes could be dropped and the later access refresh could leave the screen without the completed scan result.
   - Fixed by waiting until `CleanerUiState.Ready` before starting and consuming the one-shot Quick Scan request.

3. **Current README build guidance was stale and contradicted the actual project toolchain.**
   - The project is AGP 9.3.2 / Gradle 9.5.0 / Build Tools 36.0.0, but the current Build section still instructed users to provision AGP 8.11-era Gradle 8.13 / Build Tools 35.0.0.
   - Fixed current release/build instructions while preserving historical audit documents unchanged.

4. **Build number needed incrementing for the updated package.**
   - `versionName` remains `1.1.4`.
   - `versionCode` increased from `36` to `37`.

## Regression protection added

- Project validation now rejects a missing `singleTop` MainActivity launch mode.
- Project validation now rejects a Quick Scan auto-start path that does not wait for cleaner readiness.
- Version identity gates now require `versionCode 37`.

## Validation boundary

The source/static release gates are executed in this environment. A real AGP build cannot be truthfully claimed here because the container has no Android SDK and cannot resolve the Gradle distribution host. The final package therefore still requires Android Studio/CI to run the normal clean unit/lint/debug/release/AAB build gates before store submission.
