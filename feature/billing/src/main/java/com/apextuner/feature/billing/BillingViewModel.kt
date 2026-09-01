package com.apextuner.feature.billing

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.model.BillingCatalogState
import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.PurchaseLaunchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class BillingViewModel @Inject constructor(
    private val repository: EntitlementRepository,
) : ViewModel() {
    val entitlement: StateFlow<EntitlementState> = repository.entitlement
    val catalog: StateFlow<BillingCatalogState> = repository.catalog

    private val _checkoutOfferingKey = MutableStateFlow<String?>(null)
    val checkoutOfferingKey: StateFlow<String?> = _checkoutOfferingKey.asStateFlow()

    private val _purchaseErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val purchaseErrors: StateFlow<Map<String, String>> = _purchaseErrors.asStateFlow()

    init {
        viewModelScope.launch { repository.refreshCatalog() }
        viewModelScope.launch { repository.refresh("billing_screen_open") }
    }

    fun restorePurchases() {
        viewModelScope.launch { repository.refresh("restore") }
        viewModelScope.launch { repository.refreshCatalog() }
    }

    fun refreshCatalog() {
        _purchaseErrors.value = emptyMap()
        viewModelScope.launch { repository.refreshCatalog() }
    }

    fun reportPurchaseError(offeringKey: String, message: String) {
        _purchaseErrors.update { it + (offeringKey to message) }
    }

    fun purchase(activity: Activity, offeringKey: String) {
        if (_checkoutOfferingKey.value != null) return
        _checkoutOfferingKey.value = offeringKey
        _purchaseErrors.update { it - offeringKey }
        viewModelScope.launch {
            val result = try {
                repository.launchPurchase(activity, offeringKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                PurchaseLaunchResult.Failed(
                    "The Google Play purchase flow could not be started. Retry with a current Play Store connection.",
                )
            } finally {
                if (_checkoutOfferingKey.value == offeringKey) {
                    _checkoutOfferingKey.value = null
                }
            }
            if (result is PurchaseLaunchResult.Failed) {
                reportPurchaseError(offeringKey, result.reason)
            }
        }
    }
}
