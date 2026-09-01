# Billing model — ApexTuner 1.1.3

ApexTuner 1.1.3 intentionally uses a single Google Play Billing product: `apextuner_premium_lifetime` as `INAPP`.

The product is a one-time, non-consumable lifetime Premium unlock. All Premium functionality, including advanced privileged tools, is gated by this same verified lifetime entitlement.

The application does not query `SUBS`, does not expose subscription offers, does not contain a subscription entitlement tier, and does not open subscription-management screens.
