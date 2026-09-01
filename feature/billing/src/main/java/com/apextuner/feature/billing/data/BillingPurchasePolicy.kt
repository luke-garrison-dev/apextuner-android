package com.apextuner.feature.billing.data

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PurchaseUpdateAction {
    ReconcilePurchases,
    IgnoreCancellation,
    RefreshAlreadyOwned,
    ReportFailure,
}

internal fun purchaseUpdateAction(responseCode: Int): PurchaseUpdateAction = when (responseCode) {
    BillingClient.BillingResponseCode.OK -> PurchaseUpdateAction.ReconcilePurchases
    BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseUpdateAction.IgnoreCancellation
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdateAction.RefreshAlreadyOwned
    else -> PurchaseUpdateAction.ReportFailure
}

internal fun shouldAcknowledgePurchase(
    recognized: Boolean,
    purchaseState: Int,
    acknowledged: Boolean,
): Boolean = recognized &&
    purchaseState == Purchase.PurchaseState.PURCHASED &&
    !acknowledged

internal class PurchaseLaunchGate {
    private val active = AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun exit() {
        active.set(false)
    }
}
