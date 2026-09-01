# ApexTuner 1.1.3 — Final holistic UX/reliability audit

Build identity: `versionName 1.1.3`, `versionCode 32`.

This pass deliberately froze feature scope and audited how the existing premium systems interact from a first-time-user perspective. Confirmed issues fixed in this candidate:

- Game Booster uses a bounded temporary system-profile lease so Smart Automation and the scheduled Night Battery profile cannot fight a live gaming session.
- Every Gaming-profile start failure releases that lease immediately; cancellation and launch failure remain rollback-safe.
- Reboot/app replacement terminates persisted game-session ownership and attempts reversible restoration before normal automation reconciliation.
- Night/Smart profile restoration respects newer manual profiles instead of overwriting them.
- Dashboard recommendations are actionable and route directly to Battery, Memory, Optimize, or Network Diagnostics.
- App Manager refreshes after returning from Android settings/uninstall flows; Usage-Access-dependent filters/sorts are disabled until meaningful.
- Network, Security, and Game Booster special-access state refreshes immediately after returning from Android Settings.
- The foreground telemetry preference controls Dashboard/overlay sampling, while Battery/Memory/CPU diagnostics retain their conservative audited cadences to avoid new foreground polling cost.

This source candidate still requires a real Android SDK/Gradle build and emulator or physical-device instrumentation pass before store submission. Host-side gates are intentionally not represented as a substitute for ART/framework testing.
