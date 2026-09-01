package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.apextuner.core.model.BillingOffering

internal data class UnfetchedProductInfo(
    val productId: String,
    val productType: String,
    val statusCode: Int,
)

internal data class CatalogQueryOutcome(
    val productType: String,
    val requestedProductIds: List<String>,
    val responseCode: Int,
    val debugMessage: String,
    val productDetails: List<ProductDetails>,
    val unfetchedProducts: List<UnfetchedProductInfo>,
) {
    val succeeded: Boolean get() = responseCode == BillingClient.BillingResponseCode.OK
}

internal data class CatalogSegment(
    val productId: String,
    val productType: String,
    val responseCode: Int,
    val debugMessage: String,
    val offerings: List<BillingOffering>,
    val unfetchedProducts: List<UnfetchedProductInfo>,
)

internal data class CatalogMergeResult(
    val offerings: List<BillingOffering>,
    val unfetchedProducts: List<UnfetchedProductInfo>,
    val productErrors: Map<String, String>,
    val playAvailable: Boolean,
    val message: String?,
)

internal fun mergeCatalogSegments(segments: List<CatalogSegment>): CatalogMergeResult {
    val offerings = segments
        .flatMap { it.offerings }
        .distinctBy { it.key }
    val unfetched = segments
        .flatMap { it.unfetchedProducts }
        .distinctBy { Triple(it.productId, it.productType, it.statusCode) }
    val productErrors = buildMap {
        segments.forEach { segment ->
            if (offerings.none { it.productId == segment.productId }) {
                put(segment.productId, catalogProductError(segment))
            }
        }
    }
    val playAvailable = segments.any {
        it.responseCode != BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
    }
    val message = when {
        offerings.isNotEmpty() && productErrors.isNotEmpty() ->
            "Some Google Play products are unavailable. Available offers and localized prices below come directly from Google Play."
        offerings.isEmpty() && productErrors.isNotEmpty() ->
            "Google Play did not return an eligible purchase offer. Use Retry after checking Play Store availability, account eligibility, and network connectivity."
        else -> null
    }
    return CatalogMergeResult(
        offerings = offerings,
        unfetchedProducts = unfetched,
        productErrors = productErrors,
        playAvailable = playAvailable,
        message = message,
    )
}

internal fun billingResponseMessage(responseCode: Int, debugMessage: String): String = when (responseCode) {
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
        "Google Play Billing is unavailable for this account or device."
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
        "Google Play Billing disconnected. Try again."
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
        "Google Play Billing is temporarily unavailable. Try again."
    BillingClient.BillingResponseCode.NETWORK_ERROR ->
        "A network error prevented Google Play Billing from completing the request. Check your connection and retry."
    BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
        "Google Play Billing configuration is incomplete for this build."
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
        "This product is not currently available to this account."
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
        "Google Play reports this item is already owned."
    BillingClient.BillingResponseCode.USER_CANCELED ->
        "Purchase canceled."
    BillingClient.BillingResponseCode.ERROR ->
        "Google Play Billing returned an unexpected error."
    else -> sanitizePlayDebugMessage(debugMessage).ifBlank {
        "Google Play Billing could not complete the request."
    }
}

internal fun sanitizePlayDebugMessage(message: String): String =
    message.replace(Regex("\\s+"), " ").trim().take(MAX_DEBUG_MESSAGE_LENGTH)

private fun catalogProductError(segment: CatalogSegment): String {
    if (segment.responseCode != BillingClient.BillingResponseCode.OK) {
        return billingResponseMessage(segment.responseCode, segment.debugMessage)
    }
    val unfetched = segment.unfetchedProducts.firstOrNull {
        it.productId == segment.productId && it.productType == segment.productType
    }
    return if (unfetched != null) {
        "Google Play could not fetch this product (status ${unfetched.statusCode}). Retry to request current eligibility and pricing."
    } else {
        "Google Play returned no eligible offer for this product. Retry to request current eligibility and pricing."
    }
}

private const val MAX_DEBUG_MESSAGE_LENGTH = 300
