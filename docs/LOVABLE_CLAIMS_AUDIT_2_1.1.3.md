# ApexTuner 1.1.3 — Lovable follow-up findings audit

Audited against `ApexTuner-Android-1.1.3-LOVABLE-AUDIT-FIXED-ANDROID-STUDIO.zip`.

## Findings and resolution

1. **ZIP extraction can leave earlier extracted files after a later failure — CONFIRMED.**
   Extraction now tracks every document/directory created by the current operation and rolls them back in reverse order on failure or cancellation. Pre-existing directories are never added to the rollback set. If the Android document provider refuses a rollback deletion, ApexTuner reports that the destination requires review instead of pretending cleanup succeeded.

2. **Failed move rollback can falsely claim destination removal — CONFIRMED.**
   Destination rollback now checks the provider's boolean result and exceptions. Messages distinguish a confirmed rollback from a provider refusal. Cancellation after the copy but before source deletion also attempts destination rollback.

3. **A failed contacts undo can block older undos — CONFIRMED IN LIFO FAILURE CASES.**
   Contact rule restoration first tries bounded batches, then retries a failed batch rule-by-rule so one stale raw-contact ID cannot poison the entire batch. Failed top undo records remain retryable, and the UI can explicitly discard an irrecoverable failed undo record to reach older undo snapshots without silently mutating contacts.

4. **Purchase acknowledgement has no durable background retry — CONFIRMED.**
   A network-constrained unique WorkManager retry is now persisted before client-side acknowledgement starts. The worker queries current Play purchases again and retries recognized PURCHASED/unacknowledged items with WorkManager backoff. The normal immediate acknowledgement path remains intact.

5. **Revoked Usage Access can silently pause data alerts — CONFIRMED.**
   Active caps now show an in-app paused warning when Usage Access is missing. The periodic worker also posts a bounded one-time system warning when notification permission is available. The local NetworkStats/permission check no longer unnecessarily requires an active network connection.

6. **Battery-health history can silently remain empty without OEM telemetry — CONFIRMED.**
   The daily worker now persists a bounded observation even when cycle count and charge-counter-derived capacity are unavailable. Battery UI and widget distinguish unsupported telemetry from missing history. Intelligence capacity trends count only real capacity estimates, never placeholder observations.

## Safety notes

- No Room schema/version migration was required.
- No new external runtime library family was introduced; the billing module now declares the WorkManager/Hilt Worker libraries already pinned and used elsewhere in the app.
- Client-side WorkManager significantly improves acknowledgement durability, but a client-only app still cannot execute while force-stopped/offline indefinitely. Google recommends secure server-side acknowledgement for the strongest guarantee.
