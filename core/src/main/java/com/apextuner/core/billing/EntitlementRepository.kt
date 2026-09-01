package com.apextuner.core.billing

import android.app.Activity
import com.apextuner.core.model.BillingCatalogState
import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.PremiumFeature
import com.apextuner.core.model.PurchaseLaunchResult
import kotlinx.coroutines.flow.StateFlow

interface EntitlementRepository {
    val entitlement: StateFlow<EntitlementState>
    val catalog: StateFlow<BillingCatalogState>

    suspend fun refresh(reason: String = "manual")
    suspend fun refreshCatalog()
    suspend fun launchPurchase(activity: Activity, offeringKey: String): PurchaseLaunchResult

    fun hasAccess(feature: PremiumFeature): Boolean = entitlement.value.isPremium
}
