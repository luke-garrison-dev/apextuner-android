package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingPurchasePolicyTest {

    @Test
    fun doubleTapCannotEnterConcurrentPurchaseLaunches() {
        val gate = PurchaseLaunchGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.exit()
        assertTrue(gate.tryEnter())
        gate.exit()
    }

    @Test
    fun purchaseUpdateResponsesRemainSafe() {
        assertEquals(
            PurchaseUpdateAction.ReconcilePurchases,
            purchaseUpdateAction(BillingClient.BillingResponseCode.OK),
        )
        assertEquals(
            PurchaseUpdateAction.IgnoreCancellation,
            purchaseUpdateAction(BillingClient.BillingResponseCode.USER_CANCELED),
        )
        assertEquals(
            PurchaseUpdateAction.RefreshAlreadyOwned,
            purchaseUpdateAction(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED),
        )

        listOf(
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.ERROR,
        ).forEach { code ->
            assertEquals(PurchaseUpdateAction.ReportFailure, purchaseUpdateAction(code))
        }
    }

    @Test
    fun onlyRecognizedCompletedUnacknowledgedPurchasesAreAcknowledged() {
        assertTrue(
            shouldAcknowledgePurchase(
                recognized = true,
                purchaseState = Purchase.PurchaseState.PURCHASED,
                acknowledged = false,
            ),
        )
        assertFalse(
            shouldAcknowledgePurchase(
                recognized = true,
                purchaseState = Purchase.PurchaseState.PURCHASED,
                acknowledged = true,
            ),
        )
        assertFalse(
            shouldAcknowledgePurchase(
                recognized = true,
                purchaseState = Purchase.PurchaseState.PENDING,
                acknowledged = false,
            ),
        )
        assertFalse(
            shouldAcknowledgePurchase(
                recognized = false,
                purchaseState = Purchase.PurchaseState.PURCHASED,
                acknowledged = false,
            ),
        )
    }
}
