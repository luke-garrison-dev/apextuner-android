package com.apextuner.feature.billing.data

import com.apextuner.core.model.EntitlementTier
import kotlin.random.Random

private fun checkThat(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val now = 1_800_000_000_000L

    val lifetime = BillingEntitlementEvaluator.evaluate(
        listOf(PurchaseEvidence(setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID), true, purchased = true, pending = false)), now,
    )
    checkThat(lifetime.tier == EntitlementTier.PremiumLifetime, "lifetime must unlock")

    val pending = BillingEntitlementEvaluator.evaluate(
        listOf(PurchaseEvidence(setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID), true, purchased = false, pending = true)), now,
    )
    checkThat(pending.tier == EntitlementTier.Free && pending.hasPendingPurchase, "pending must stay locked")

    val wrongPackage = BillingEntitlementEvaluator.evaluate(
        listOf(PurchaseEvidence(setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID), false, purchased = true, pending = false)), now,
    )
    checkThat(wrongPackage.tier == EntitlementTier.Free, "wrong package must never unlock")

    val unknown = BillingEntitlementEvaluator.evaluate(
        listOf(PurchaseEvidence(setOf("unknown"), true, purchased = true, pending = false)), now,
    )
    checkThat(unknown.tier == EntitlementTier.Free, "unknown product must never unlock")

    val random = Random(7)
    repeat(5_000) {
        val evidence = List(random.nextInt(0, 5)) {
            PurchaseEvidence(
                products = setOf(if (random.nextBoolean()) BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID else "unknown"),
                packageMatches = random.nextBoolean(),
                purchased = random.nextBoolean(),
                pending = random.nextBoolean(),
            )
        }
        val state = BillingEntitlementEvaluator.evaluate(evidence, now)
        val shouldLifetime = evidence.any { e ->
            e.packageMatches && BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID in e.products && e.purchased
        }
        val expected = if (shouldLifetime) EntitlementTier.PremiumLifetime else EntitlementTier.Free
        checkThat(state.tier == expected, "randomized entitlement mismatch")
    }

    println("PASS: one-time lifetime entitlement harness")
}
