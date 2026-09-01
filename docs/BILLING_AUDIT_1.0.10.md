> **Superseded monetization note (1.1.3):** this historical audit described an earlier two-product design. ApexTuner now intentionally supports only the non-consumable lifetime product `apextuner_premium_lifetime` (`INAPP`). Subscription code, UI, entitlement tiers, and restore/query paths were removed in 1.1.3.

# ApexTuner 1.0.10 — Google Play Billing audit

**Audit date:** 2026-08-28  
**Application ID:** `com.apextuner.app`  
**Version:** `1.0.10` / `versionCode 10`

## Scope

This release is intentionally limited to Google Play Billing reliability, entitlement correctness, price/offer retrieval, purchase lifecycle handling and the Premium purchase UI. It does not introduce a Play Developer API / RTDN backend; the requested architecture remains client-only.

Expected Play Console products are centralized in `BillingCatalog`:

- One-time non-consumable: `apextuner_premium_lifetime` (`INAPP`)
- Subscription: `apextuner_pro` (`SUBS`)

The application does not hardcode sale prices or currencies. User-facing prices, pricing phases and eligible offers are populated from Google Play `ProductDetails` and their localized `formattedPrice` values.

## Reliability changes

- Google Play Billing Library remains on audited version **9.1.0**.
- Product IDs and expected product types are validated together. A valid ID returned under the wrong Play product type cannot unlock Premium or enter the visible catalog.
- Checkout no longer trusts cached `ProductDetails` or offer tokens. The selected stable Play Console identity is retained, then `queryProductDetailsAsync()` is run again immediately before `launchBillingFlow()` so eligibility, localized price and offer token are current.
- Subscription support is checked before launching a subscription purchase.
- `launchBillingFlow()` is dispatched on the Android main thread.
- Multiple eligible one-time purchase options/offers are supported through `oneTimePurchaseOfferDetailsList`; subscription base plans/offers are mapped from `subscriptionOfferDetails`.
- `unfetchedProductList` is retained in catalog state so unavailable/misconfigured products are not silently treated as valid offers.
- Pending purchases never unlock Premium and are never acknowledged until Play reports `PURCHASED`.
- Suspended subscriptions do not unlock Premium.
- Active purchases are re-queried on app resume and from the purchase callback, covering completed pending purchases and ownership changes that occur while the app is not active.
- Completed, recognized and unacknowledged purchases are acknowledged. Transient acknowledgement failures use a bounded three-attempt retry (500 ms then 1.5 s); configuration/ownership failures are not blindly retried.
- `ITEM_NOT_OWNED` during acknowledgement forces an immediate ownership re-query rather than trusting stale state.
- Purchase callback handling converges through the same full re-query/evaluation path instead of granting directly from callback payloads.
- Purchase completion and pending state are explicitly communicated to the user.
- Subscription cards disclose Google Play renewal behavior and display all returned pricing phases; prices remain localized by Play.
- Billing cache persistence remains on the I/O dispatcher.

## Google integration references

Implementation was cross-checked against current official Google documentation:

- Play Billing integration: https://developer.android.com/google/play/billing/integrate
- Play Billing release notes: https://developer.android.com/google/play/billing/release-notes
- Test purchases: https://developer.android.com/google/play/billing/test
- One-time product offers/options: https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers
- Subscription policy: https://support.google.com/googleplay/android-developer/answer/9900533

## Tests executed against the 1.0.10 source tree

- Project/source/security/UI/Billing validator: **PASS — 1,169 checks, 39 XML files, 11 main manifests**.
- Pure JVM regression suite recompiled from current sources: **PASS — 55 methods across 18 test classes**.
- Domain/property harness recompiled from current sources: **PASS — 50,015 checks**.
- Billing mapper harness compiled against Billing-9.1-compatible API stubs: **PASS — 10 checks**.
- Full `GooglePlayEntitlementRepository.kt` compile harness against Android/Billing-9.1-compatible API stubs: **PASS**.
- Billing repository behavior harness: **PASS — 12 checks**, covering localized catalog prices, wrong-type rejection, lifetime unlock, pending/suspended lockout, transient acknowledgement retry and non-transient acknowledgement behavior.

The execution sandbox has no Android SDK and cannot resolve `services.gradle.org`, so it cannot truthfully run an Android Gradle build, Play Store process, emulator, instrumentation or a real Play purchase. The repository's CI remains the SDK-capable build/lint gate.

## Play Console / real-device acceptance tests

Because the Play Console account and Play Store transaction service are external to this source package, release QA should use Google Play license testers / an internal test track and confirm the two product IDs above are active and available to the test account. Exercise successful/canceled purchase, slow pending approval, slow pending decline, already-owned restore, subscription renewal/cancel/suspension and acknowledgement behavior. The app is designed to obtain the actual localized prices and eligible offer tokens from that Play configuration at runtime.
