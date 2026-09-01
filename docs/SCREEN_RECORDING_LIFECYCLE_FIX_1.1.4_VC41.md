# ApexTuner Android — screen-recording lifecycle hardening (1.1.4 / versionCode 41)

## Confirmed defect

`ScreenRecordingService.RecordingSession` previously registered `MediaProjection.Callback` on a dedicated callback thread before synchronous encoder startup had completed. If Android revoked the projection while `start()` was still configuring the output, muxer, codec, input surface, or virtual display, `requestStop()` could observe `drainThread == null` and call `finish()` concurrently with startup. The old `AtomicBoolean` flags prevented duplicate completion but did not serialize startup and resource teardown.

A second startup boundary existed because the encoder drain thread was started inside `Thread.apply { start() }` before the resulting thread reference was assigned to `drainThread`. An immediately failing drain loop could therefore complete while the Service was still publishing the startup state.

## Definitive fix

The recording session now uses a dedicated `RecordingLifecycleGate` with explicit `New -> Starting -> Running -> Stopping -> Finished` phases. The gate's monitor is also the structural resource-ownership lock: creation/publication, stop acceptance, and final release cannot overlap.

Additional invariants:

- `MediaProjection.Callback` is delivered on the Service main looper. Projection revocation is therefore queued behind the synchronous Service startup path instead of running halfway through it.
- The drain `Thread` object is assigned to `drainThread` before `Thread.start()` is called.
- The drain thread reference is assigned, the session transitions to `Running`, and `Thread.start()` must succeed before the Service publishes `ScreenRecordingState.Recording`. An immediately failing drain can request `finish()`, but teardown remains serialized behind the still-held startup gate until state publication completes.
- `requestStop()` is idempotent. Once `Stopping` or `Finished` is reached, later user, projection, destruction, or callback stop requests cannot mutate the lifecycle again.
- Active-session completion is posted back to the Service main looper and is owner-checked (`session === owner`) before clearing state, stopping foreground mode, or stopping the Service. A stale completion cannot overwrite a different session.
- A null/failed `VirtualDisplay`, failed startup, and failed/incomplete encoder finalization remove the MediaStore output rather than entering or remaining in a false successful-recording state.
- A successful drain is no longer considered publishable if `MediaMuxer.stop()` itself fails; the output is converted to failure and deleted.
- The former dedicated `HandlerThread` and its extra lifetime/cleanup path were removed.

## Regression coverage

`RecordingLifecycleGateTest` verifies that a stop request from another thread cannot enter while the startup transaction owns the lifecycle monitor, and verifies duplicate stop/finish idempotence.

A standalone JVM concurrency harness using the production gate also passed in this audit environment. The project-level validator/release gate should still be run before the Android Studio release build, followed by the real Gradle unit-test/build command documented in the root README.

## Release identity

No release identity or billing behavior was changed:

- Version name: `1.1.4`
- Version code: `41`
- Application ID: `com.apextuner.app`
