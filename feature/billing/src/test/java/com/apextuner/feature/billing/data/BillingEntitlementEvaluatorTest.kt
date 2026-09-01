package com.apextuner.feature.billing.data

import com.apextuner.core.model.EntitlementTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingEntitlementEvaluatorTest {
    private val now = 1_800_000_000_000L

    @Test
    fun pendingOneTimePurchaseStaysLocked() {
        val state = BillingEntitlementEvaluator.evaluate(
            listOf(
                PurchaseEvidence(
                    products = setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID),
                    packageMatches = true,
                    purchased = false,
                    pending = true,
                ),
            ),
            now,
        )

        assertEquals(EntitlementTier.Free, state.tier)
        assertTrue(state.hasPendingPurchase)
    }

    @Test
    fun recognizedPurchasedLifetimeUnlocksPremium() {
        val state = BillingEntitlementEvaluator.evaluate(
            listOf(
                PurchaseEvidence(
                    products = setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID),
                    packageMatches = true,
                    purchased = true,
                    pending = false,
                ),
            ),
            now,
        )

        assertEquals(EntitlementTier.PremiumLifetime, state.tier)
        assertFalse(state.hasPendingPurchase)
        assertTrue(state.isPremium)
    }

    @Test
    fun canceledOrDeclinedWithoutPurchasedStateNeverUnlocks() {
        val state = BillingEntitlementEvaluator.evaluate(
            listOf(
                PurchaseEvidence(
                    products = setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID),
                    packageMatches = true,
                    purchased = false,
                    pending = false,
                ),
            ),
            now,
        )

        assertEquals(EntitlementTier.Free, state.tier)
        assertFalse(state.hasPendingPurchase)
    }

    @Test
    fun wrongPackageAndUnknownProductsNeverUnlock() {
        val state = BillingEntitlementEvaluator.evaluate(
            listOf(
                PurchaseEvidence(
                    products = setOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID),
                    packageMatches = false,
                    purchased = true,
                    pending = false,
                ),
                PurchaseEvidence(
                    products = setOf("unknown.product"),
                    packageMatches = true,
                    purchased = true,
                    pending = false,
                ),
            ),
            now,
        )

        assertEquals(EntitlementTier.Free, state.tier)
        assertFalse(state.isPremium)
    }
}
