package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient

internal data class BillingProductRequestSpec(
    val productId: String,
    val productType: String,
)

object BillingCatalog {
    const val PREMIUM_LIFETIME_PRODUCT_ID = "apextuner_premium_lifetime"

    val recognizedProductIds = setOf(PREMIUM_LIFETIME_PRODUCT_ID)

    internal val lifetimeProduct = BillingProductRequestSpec(
        productId = PREMIUM_LIFETIME_PRODUCT_ID,
        productType = BillingClient.ProductType.INAPP,
    )

    internal fun recognizes(productId: String): Boolean = productId == PREMIUM_LIFETIME_PRODUCT_ID
}
