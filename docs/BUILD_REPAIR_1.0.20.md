# ApexTuner Android — Battery Profile Correctness Repair 1.0.20

**Version:** `1.0.20` / `versionCode 20`

This repair is intentionally limited to the Battery/System Profile path. It preserves the Android Studio toolchain, KSP/AGP fixes, compact-landscape UI, Photo Picker crash repair, firewall, billing, cleaner, and all unrelated behavior from 1.0.19.

## Correctness changes

- Preserve the exact pre-profile `Settings.System.SCREEN_OFF_TIMEOUT` value in the rollback snapshot. The previous one-hour clamp could prevent `Restore Balanced` from restoring long/OEM "Never" representations exactly.
- Keep profile target generation safe: Battery still applies at most 30 seconds, while unusual non-positive baselines are never used as an applied timeout target. The exact snapshot is nevertheless retained for rollback.
- Add live profile reconciliation. The Battery screen now compares the persisted ApexTuner profile with the actual Android settings ApexTuner manages and reports when the user/OEM changed them externally.
- Stop displaying a blindly persisted `Active: Battery` label when the live screen timeout no longer matches the Battery target.
- Surface `privilegedChangesUnavailable` in the Battery action result instead of silently discarding it.
- Rewrite Battery profile copy to state exactly what happens on Android 13+ versus Android 12L and earlier, and what ApexTuner intentionally does not change.

## Safety boundaries retained

ApexTuner still does not silently enable Android Battery Saver, force Doze/device idle, alter CPU/GPU governors, disable radios, modify Adaptive Battery, or pause global account synchronization. The API-33+ haptic behavior remains system/user controlled.

## Regression coverage

`SystemProfilePlannerTest` now covers:

- exact restoration of a two-hour baseline;
- 30-second Battery targeting from a long baseline;
- safe targeting with an unusual non-positive snapshot without mutating that snapshot;
- detection of external timeout drift;
- correct ignoring of the legacy haptic setting on Android 13+.

`tools/validate_project.py` also contains structural guards preventing rollback clamping, stale Battery status labels, or silent loss of privileged-action disclosure from returning.
