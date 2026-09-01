package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.apextuner.core.model.BillingOffering

internal data class OfferingIdentity(
    val productId: String,
    val purchaseOptionId: String? = null,
    val offerId: String? = null,
)

internal data class PlayOffering(
    val public: BillingOffering,
    val productDetails: ProductDetails,
    val offerToken: String?,
    val identity: OfferingIdentity,
)

internal data class CheckoutOfferRef(
    val identity: OfferingIdentity,
    val offerToken: String?,
)

internal fun selectFreshCheckoutOffer(
    selectedIdentity: OfferingIdentity,
    candidates: List<CheckoutOfferRef>,
): CheckoutOfferRef? = candidates.firstOrNull {
    it.identity == selectedIdentity && !it.offerToken.isNullOrBlank()
}

internal fun ProductDetails.toPlayOfferings(): List<PlayOffering> {
    if (productType != BillingClient.ProductType.INAPP) return emptyList()

    val multiple = oneTimePurchaseOfferDetailsList.orEmpty()
    val offers = if (multiple.isNotEmpty()) multiple else listOfNotNull(oneTimePurchaseOfferDetails)
    return offers.mapIndexed { index, offer ->
        val identity = OfferingIdentity(
            productId = productId,
            purchaseOptionId = offer.purchaseOptionId,
            offerId = offer.offerId,
        )
        PlayOffering(
            public = BillingOffering(
                key = stableOfferingKey(identity, fallback = index.toString()),
                productId = productId,
                title = name,
                description = description,
                formattedPrice = offer.formattedPrice,
                pricingSummary = offer.discountDisplayInfo?.let { "Eligible one-time Google Play offer" },
                offerId = offer.offerId,
            ),
            productDetails = this,
            offerToken = offer.offerToken,
            identity = identity,
        )
    }
}

private fun stableOfferingKey(identity: OfferingIdentity, fallback: String = "default"): String {
    val parts = listOfNotNull(
        identity.productId,
        identity.purchaseOptionId,
        identity.offerId,
    )
    val raw = (parts + fallback.takeIf { parts.size <= 1 }).joinToString(":")
    return raw.replace(Regex("[^A-Za-z0-9._:-]"), "_")
}
