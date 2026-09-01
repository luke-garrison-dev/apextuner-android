# ApexTuner Android — build fix 1.1.4 / versionCode 41

## Confirmed compiler issue fixed

`MainActivity.parseLaunchRequest()` accepted `Intent?` and used a safe call only for the destination extra. Kotlin therefore could not smart-cast the nullable parameter for the following Boolean and Long extra reads, causing `:app:compileReleaseKotlin` to fail.

The function now returns immediately for a null intent and binds the remaining path to a non-null `launchIntent`. Destination, Quick Scan, and request-token extras are all read from that same non-null instance. No non-null assertion is used, and the existing destination allow-list and one-shot consumption behavior remain unchanged.

## Release identity

- Version name: `1.1.4`
- Version code: `41`
- Application ID: `com.apextuner.app`

The project validator and release gate include regression checks for the non-null intent binding and all three typed extra reads.

## Screen-recording lifecycle hardening

A post-audit review confirmed a startup/teardown race in `ScreenRecordingService.RecordingSession`: projection revocation or an immediately failing encoder drain could previously reach cleanup while synchronous encoder startup was still publishing resources/state.

The build now serializes structural screen-recording lifecycle transitions through `RecordingLifecycleGate` (`New -> Starting -> Running -> Stopping -> Finished`), delivers `MediaProjection.Callback` on the Service main looper, assigns the drain-thread reference before starting it, owner-checks Service callbacks, makes stop/finalization idempotent, rejects a null `VirtualDisplay`, and deletes output when muxer/container publication cannot be finalized safely.

Regression coverage was added in `RecordingLifecycleGateTest`, and `tools/validate_project.py` now asserts the new lifecycle invariants instead of the obsolete dedicated-callback-thread implementation detail. See `docs/SCREEN_RECORDING_LIFECYCLE_FIX_1.1.4_VC41.md` for the detailed reasoning and validation boundary.
