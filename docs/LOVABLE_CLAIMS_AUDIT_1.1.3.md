# ApexTuner 1.1.3 — Lovable claims audit

Audited against the post-holistic 1.1.3 Android Studio source package.

## Findings and disposition

- **AppManagerRoute missing `HorizontalDivider` import — CONFIRMED.** Added the Material3 import and a release-gate regression check.
- **Network refresh cancellation can leave `refreshing` stale — CONFIRMED IN SUBSTANCE.** The original "forever" wording was stronger than the normal successful path, because the queued firewall mutation usually cleared the state later. The state machine was nevertheless unsafe. Refresh cancellation now clears the flag immediately, and all Network mutations (firewall selection/profile and data-cap changes) cancel an in-flight refresh before writing newer state.
- **Security snapshot work on the UI thread — CONFIRMED.** `SecurityRepository.snapshot()` is now suspend/IO-dispatched. `SecurityViewModel` uses a coalesced coroutine refresh and keeps existing data visible during resume refreshes.
- **Battery current direction based on OEM current sign — CONFIRMED.** Current/power magnitude still uses the sensor value, but direction now uses the authoritative `BatterySnapshot.charging` state. Display formatting uses the device locale consistently.
- **Quick Scan replay on Activity recreation — CONFIRMED.** Launch/deep-link extras are consumed once and removed from the retained Intent, preventing rotation/configuration changes from replaying the request.
- **Fast double-tap can stack Tools destinations — CONFIRMED.** Literal Tools navigation uses `launchSingleTop = true`, including Settings → Notification History child navigation.
- **Premium Advanced Tools paywall dead-end when discoverability is hidden — CONFIRMED.** `showAdvancedTools` controls tile discoverability only; verified premium entitlement controls route access.
- **Game session timeout worker should age-check before stopping — CONFIRMED AS RELIABILITY HARDENING.** The worker now calls `recoverStaleSession()` instead of unconditionally stopping the current session.
- **Cleaner access-state binder/permission probes on main dispatcher — CONFIRMED.** `accessState()` is now suspend and executes on the injected IO dispatcher.
- **Data-usage caps lack in-app usage-vs-cap feedback — CONFIRMED.** The Network snapshot now exposes current-month cap usage and each configured cap displays current usage, threshold and percentage/overage.

## Verification added

The deterministic release gate now guards these fixes, and pure Kotlin / ViewModel harnesses cover battery-direction semantics, data-cap math, Network refresh cancellation, and Security refresh coalescing/non-blocking behavior.
