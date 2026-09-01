package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient
import com.apextuner.core.model.BillingOffering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingCatalogPolicyTest {
    @Test
    fun lifetimeRequestUsesExactOneTimeProduct() {
        val request = BillingCatalog.lifetimeProduct

        assertEquals("apextuner_premium_lifetime", request.productId)
        assertEquals(BillingClient.ProductType.INAPP, request.productType)
        assertEquals(setOf("apextuner_premium_lifetime"), BillingCatalog.recognizedProductIds)
    }

    @Test
    fun lifetimeSuccessKeepsLocalizedOfferAvailable() {
        val result = mergeCatalogSegments(
            listOf(
                segment(
                    BillingCatalog.lifetimeProduct,
                    offerings = listOf(offering("lifetime", "Play localized lifetime price")),
                ),
            ),
        )

        assertEquals(listOf(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID), result.offerings.map { it.productId })
        assertTrue(result.productErrors.isEmpty())
        assertTrue(result.playAvailable)
    }

    @Test
    fun missingLifetimePreservesUnfetchedStatus() {
        val unfetched = UnfetchedProductInfo(
            productId = BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID,
            productType = BillingClient.ProductType.INAPP,
            statusCode = 4,
        )
        val result = mergeCatalogSegments(
            listOf(
                segment(
                    BillingCatalog.lifetimeProduct,
                    unfetchedProducts = listOf(unfetched),
                ),
            ),
        )

        assertTrue(result.offerings.isEmpty())
        assertEquals(listOf(unfetched), result.unfetchedProducts)
        assertTrue(result.productErrors.containsKey(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID))
    }

    @Test
    fun successfulResultsMergeWithoutDuplicateOfferingKeys() {
        val duplicate = offering("same-key", "Localized by Google Play")
        val result = mergeCatalogSegments(
            listOf(
                segment(BillingCatalog.lifetimeProduct, offerings = listOf(duplicate, duplicate)),
            ),
        )

        assertEquals(1, result.offerings.size)
        assertEquals("same-key", result.offerings.single().key)
    }

    @Test
    fun formattedPriceIsPreservedUnchangedDuringCatalogMerge() {
        val playPrice = "Localized price returned by Google Play"
        val result = mergeCatalogSegments(
            listOf(
                segment(
                    BillingCatalog.lifetimeProduct,
                    offerings = listOf(offering("lifetime", playPrice)),
                ),
            ),
        )

        assertEquals(playPrice, result.offerings.single().formattedPrice)
    }

    @Test
    fun checkoutSelectionUsesCurrentTokenForLifetimeBuyOption() {
        val identity = OfferingIdentity(
            productId = BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID,
            purchaseOptionId = "lifetime-buy",
        )

        val selected = selectFreshCheckoutOffer(
            selectedIdentity = identity,
            candidates = listOf(
                CheckoutOfferRef(identity = identity, offerToken = "fresh-current-token"),
            ),
        )

        assertEquals("fresh-current-token", selected?.offerToken)
        assertEquals("lifetime-buy", selected?.identity?.purchaseOptionId)
    }

    @Test
    fun checkoutSpecRemainsOneTimeInApp() {
        val lifetime = BillingCatalog.lifetimeProduct

        assertEquals(BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID, lifetime.productId)
        assertEquals(BillingClient.ProductType.INAPP, lifetime.productType)
    }

    @Test
    fun staleOrUnavailableOfferCannotBeSelectedForCheckout() {
        val selectedIdentity = OfferingIdentity(
            productId = BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID,
            purchaseOptionId = "lifetime-buy",
        )
        val wrongIdentity = selectedIdentity.copy(purchaseOptionId = "old-option")

        assertNull(
            selectFreshCheckoutOffer(
                selectedIdentity,
                listOf(CheckoutOfferRef(wrongIdentity, "token")),
            ),
        )
        assertNull(
            selectFreshCheckoutOffer(
                selectedIdentity,
                listOf(CheckoutOfferRef(selectedIdentity, "")),
            ),
        )
    }

    @Test
    fun billingUnavailableMarksCatalogUnavailable() {
        val result = mergeCatalogSegments(
            listOf(
                segment(
                    BillingCatalog.lifetimeProduct,
                    responseCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                ),
            ),
        )

        assertFalse(result.playAvailable)
        assertTrue(result.offerings.isEmpty())
    }

    private fun segment(
        spec: BillingProductRequestSpec,
        responseCode: Int = BillingClient.BillingResponseCode.OK,
        offerings: List<BillingOffering> = emptyList(),
        unfetchedProducts: List<UnfetchedProductInfo> = emptyList(),
    ) = CatalogSegment(
        productId = spec.productId,
        productType = spec.productType,
        responseCode = responseCode,
        debugMessage = "",
        offerings = offerings,
        unfetchedProducts = unfetchedProducts,
    )

    private fun offering(
        key: String,
        formattedPrice: String,
        productId: String = BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID,
    ) = BillingOffering(
        key = key,
        productId = productId,
        title = "Google Play title",
        description = "Google Play description",
        formattedPrice = formattedPrice,
    )
}
