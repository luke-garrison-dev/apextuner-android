# ApexTuner Android — build fix 1.1.4 / versionCode 41

## Confirmed compiler issue fixed

`MainActivity.parseLaunchRequest()` accepted `Intent?` and used a safe call only for the destination extra. Kotlin therefore could not smart-cast the nullable parameter for the following Boolean and Long extra reads, causing `:app:compileReleaseKotlin` to fail.

The function now returns immediately for a null intent and binds the remaining path to a non-null `launchIntent`. Destination, Quick Scan, and request-token extras are all read from that same non-null instance. No non-null assertion is used, and the existing destination allow-list and one-shot consumption behavior remain unchanged.

## Release identity

- Version name: `1.1.4`
- Version code: `41`
- Application ID: `com.apextuner.app`

The project validator and release gate include regression checks for the non-null intent binding and all three typed extra reads.
