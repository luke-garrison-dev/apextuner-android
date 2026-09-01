# ApexTuner Google Play Billing Correction — Single Lifetime Purchase

## Correct monetization model

ApexTuner now exposes exactly one purchasable Google Play product:

- Product ID: `apextuner_premium_lifetime`
- Google Play type: `INAPP`
- Semantics: non-consumable, one-time lifetime Premium purchase

There is no subscription product, subscription entitlement tier, subscription catalog query, subscription purchase flow, subscription-management UI, subscription restore query, prepaid-plan handling, or subscription-specific offline grace logic in the current runtime implementation.

## Root cause corrected

The previous package explicitly modeled a second product (`apextuner_pro`) as `SUBS`. Its validator also required both `INAPP` and `SUBS`, so the subscription was being intentionally preserved by the codebase rather than merely appearing because of Play Console configuration.

A related feature-gating defect was also corrected: advanced privileged tools previously required the removed Pro-subscription entitlement. They now use the same verified lifetime Premium entitlement as the rest of the premium feature set.

## Safety and purchase behavior retained

- Price and localized currency remain sourced only from Google Play `ProductDetails`.
- Checkout re-queries the selected lifetime product immediately before launch and uses the current eligible offer token.
- Pending transactions remain locked until Google Play reports `PURCHASED`.
- Completed recognized lifetime purchases are acknowledged but never consumed.
- Restore/re-query checks only `BillingClient.ProductType.INAPP` ownership.
- Existing verified lifetime ownership can use a bounded encrypted offline grace when Play is temporarily unavailable.
- Concurrent checkout launches remain gated.
- Billing callbacks remain bounded by timeouts.

## Anti-regression protection

`tools/validate_project.py` now fails if the billing runtime/UI contains a subscription product, `ProductType.SUBS`, subscription-specific entitlement state, prepaid subscription pending-purchase support, or a Pro-subscription-only advanced-tools gate.

## Version

- versionCode: 25
- versionName: 1.1.3

## Local validation

`python3 tools/validate_project.py` passes after the correction.

Gradle execution requires downloading the configured Gradle distribution. In the current isolated environment, `services.gradle.org` cannot be resolved, so Gradle unit/compile tasks cannot be truthfully claimed as executed here. The project is intended for final build verification in Android Studio / CI with network access.
