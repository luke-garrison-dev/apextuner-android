package com.apextuner.feature.billing.data

import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.EntitlementTier
import com.apextuner.core.model.EntitlementVerification

internal data class PurchaseEvidence(
    val products: Set<String>,
    val packageMatches: Boolean,
    val purchased: Boolean,
    val pending: Boolean,
)

internal object BillingEntitlementEvaluator {
    fun evaluate(evidence: List<PurchaseEvidence>, nowEpochMillis: Long): EntitlementState {
        val recognized = evidence.filter { item ->
            item.packageMatches && item.products.any(BillingCatalog::recognizes)
        }
        val pending = recognized.any { it.pending }
        val lifetime = recognized.any { it.purchased }
        return EntitlementState(
            tier = if (lifetime) EntitlementTier.PremiumLifetime else EntitlementTier.Free,
            verification = EntitlementVerification.VerifiedByPlayClient,
            hasPendingPurchase = pending,
            lastCheckedAtEpochMillis = nowEpochMillis,
            message = if (pending && !lifetime) {
                "A Google Play purchase is pending. Premium access starts only after payment completes."
            } else {
                null
            },
        )
    }
}
