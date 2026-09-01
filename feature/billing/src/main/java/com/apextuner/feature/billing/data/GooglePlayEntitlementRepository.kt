package com.apextuner.feature.billing.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.apextuner.core.billing.EncryptedEntitlementCache
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.BillingCatalogState
import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.EntitlementTier
import com.apextuner.core.model.EntitlementVerification
import com.apextuner.core.model.PurchaseLaunchResult
import com.apextuner.core.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class GooglePlayEntitlementRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: EncryptedEntitlementCache,
    private val timeProvider: TimeProvider,
    private val acknowledgementRetryScheduler: PurchaseAcknowledgementRetryScheduler,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EntitlementRepository {

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectionMutex = Mutex()
    private val refreshMutex = Mutex()
    private val catalogMutex = Mutex()
    private val purchaseLaunchGate = PurchaseLaunchGate()
    private val offeringLock = Any()
    private var playOfferings: Map<String, PlayOffering> = emptyMap()

    private val _entitlement = MutableStateFlow(
        cache.loadOfflineGrace(timeProvider.nowEpochMillis()) ?: EntitlementState(),
    )
    override val entitlement: StateFlow<EntitlementState> = _entitlement.asStateFlow()

    private val _catalog = MutableStateFlow(BillingCatalogState())
    override val catalog: StateFlow<BillingCatalogState> = _catalog.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        scope.launch {
            when (purchaseUpdateAction(result.responseCode)) {
                PurchaseUpdateAction.ReconcilePurchases -> processPurchaseUpdate(purchases.orEmpty())
                PurchaseUpdateAction.IgnoreCancellation -> Unit
                PurchaseUpdateAction.RefreshAlreadyOwned -> refresh("item_already_owned")
                PurchaseUpdateAction.ReportFailure -> {
                    _catalog.value = _catalog.value.copy(
                        message = billingResponseMessage(result.responseCode, result.debugMessage),
                    )
                }
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override suspend fun refresh(reason: String) {
        refreshMutex.withLock {
            val connected = ensureConnected()
            if (!connected) {
                applyOfflineFallback(
                    "Google Play Billing is unavailable. Premium access is using a bounded offline grace only when a recent entitlement exists.",
                )
                return
            }
            try {
                val inApp = queryPurchases()
                val now = timeProvider.nowEpochMillis()
                val state = evaluatePurchases(inApp, now)
                _entitlement.value = state
                withContext(ioDispatcher) {
                    if (state.isPremium) cache.saveVerified(state) else cache.clear()
                }
                try {
                    acknowledgeCompleted(inApp)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: OwnershipChangedException) {
                    val currentInApp = queryPurchases()
                    val current = evaluatePurchases(
                        currentInApp,
                        timeProvider.nowEpochMillis(),
                    )
                    _entitlement.value = current.copy(
                        message = current.message
                            ?: "Google Play reported an ownership change. Active purchases were re-verified.",
                    )
                    withContext(ioDispatcher) {
                        if (current.isPremium) cache.saveVerified(current) else cache.clear()
                    }
                } catch (_: Throwable) {
                    _catalog.value = _catalog.value.copy(
                        message = "Purchase is active, but Google Play acknowledgement could not be confirmed yet. ApexTuner will retry automatically.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                applyOfflineFallback(error.message ?: "Google Play purchase verification failed.")
            }
        }
    }

    override suspend fun refreshCatalog() {
        catalogMutex.withLock {
            _catalog.value = _catalog.value.copy(loading = true, message = null, productErrors = emptyMap())
            if (!ensureConnected()) {
                clearOfferings()
                val unavailable = "Google Play Billing is unavailable on this device or account. Check Google Play and retry."
                _catalog.value = BillingCatalogState(
                    loading = false,
                    playAvailable = false,
                    productErrors = mapOf(
                        BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID to unavailable,
                    ),
                    message = unavailable,
                )
                return@withLock
            }

            try {
                val lifetimeSpec = BillingCatalog.lifetimeProduct
                val lifetimeOutcome = queryCatalogProducts(listOf(lifetimeSpec))
                val lifetimeOffers = mapCatalogOutcome(lifetimeOutcome)
                val mergedPlayOfferings = lifetimeOffers
                    .filter { BillingCatalog.recognizes(it.public.productId) }
                    .distinctBy { it.public.key }

                val mergedCatalog = mergeCatalogSegments(
                    listOf(lifetimeOutcome.toSegment(lifetimeSpec, lifetimeOffers)),
                )

                synchronized(offeringLock) {
                    playOfferings = mergedPlayOfferings.associateBy { it.public.key }
                }
                _catalog.value = BillingCatalogState(
                    offerings = mergedCatalog.offerings,
                    loading = false,
                    playAvailable = mergedCatalog.playAvailable,
                    unfetchedProductIds = mergedCatalog.unfetchedProducts.map { it.productId }.toSet(),
                    productErrors = mergedCatalog.productErrors,
                    message = mergedCatalog.message,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                clearOfferings()
                val failure = "Google Play product details could not be loaded. Retry to request current offers and prices."
                _catalog.value = BillingCatalogState(
                    loading = false,
                    playAvailable = true,
                    productErrors = mapOf(
                        BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID to failure,
                    ),
                    message = failure,
                )
            }
        }
    }

    override suspend fun launchPurchase(activity: Activity, offeringKey: String): PurchaseLaunchResult {
        if (!purchaseLaunchGate.tryEnter()) {
            return PurchaseLaunchResult.Failed(
                "A Google Play purchase is already starting. Complete or cancel it before trying another offer.",
            )
        }
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                return PurchaseLaunchResult.Failed(
                    "The purchase screen is no longer active. Reopen Premium and try again.",
                )
            }
            if (!ensureConnected()) {
                return PurchaseLaunchResult.Failed("Google Play Billing is unavailable. Check Google Play and retry.")
            }

            val selectedIdentity = synchronized(offeringLock) {
                playOfferings[offeringKey]?.identity
            } ?: return PurchaseLaunchResult.Failed(
                "This Google Play offer was refreshed. Retry the Premium catalog and select the offer again.",
            )
            val selectedSpec = BillingCatalog.lifetimeProduct
            if (selectedIdentity.productId != selectedSpec.productId) {
                return PurchaseLaunchResult.Failed(
                    "This purchase offer no longer matches the expected Google Play product. Retry the catalog.",
                )
            }

            val freshOutcome = queryCatalogProducts(listOf(selectedSpec))
            if (!freshOutcome.succeeded) {
                return PurchaseLaunchResult.Failed(
                    billingResponseMessage(freshOutcome.responseCode, freshOutcome.debugMessage),
                )
            }
            val freshOfferings = mapCatalogOutcome(freshOutcome)
            val selectedRef = selectFreshCheckoutOffer(
                selectedIdentity = selectedIdentity,
                candidates = freshOfferings.map {
                    CheckoutOfferRef(identity = it.identity, offerToken = it.offerToken)
                },
            ) ?: return PurchaseLaunchResult.Failed(
                "This Google Play offer is no longer eligible or available. Retry to load the current purchase option.",
            )
            val currentOfferToken = selectedRef.offerToken?.takeIf { it.isNotBlank() }
                ?: return PurchaseLaunchResult.Failed(
                    "Google Play did not return a current purchase token for this offer. Retry before purchasing.",
                )
            val offering = freshOfferings.firstOrNull {
                it.identity == selectedRef.identity && it.offerToken == currentOfferToken
            } ?: return PurchaseLaunchResult.Failed(
                "This Google Play offer changed before checkout. Retry to load the current purchase option.",
            )

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(offering.productDetails)
                .setOfferToken(currentOfferToken)
                .build()
            val result = withContext(Dispatchers.Main.immediate) {
                billingClient.launchBillingFlow(
                    activity,
                    BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(productParams))
                        .build(),
                )
            }
            return when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> PurchaseLaunchResult.Launched
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    refresh("launch_item_already_owned")
                    PurchaseLaunchResult.Failed(
                        "Google Play reports this item is already owned. Your purchases were refreshed.",
                    )
                }
                else -> PurchaseLaunchResult.Failed(
                    billingResponseMessage(result.responseCode, result.debugMessage),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PurchaseLaunchResult.Failed(
                "The Google Play purchase flow could not be started. Retry with a current Play Store connection.",
            )
        } finally {
            purchaseLaunchGate.exit()
        }
    }

    private suspend fun queryCatalogProducts(
        specs: List<BillingProductRequestSpec>,
    ): CatalogQueryOutcome {
        require(specs.isNotEmpty()) { "At least one Google Play product is required." }
        val productTypes = specs.map { it.productType }.distinct()
        require(productTypes.size == 1) {
            "All products in a Google Play ProductDetails request must have the same product type."
        }
        val productType = productTypes.single()
        val requestedIds = specs.map { it.productId }

        return try {
            val products = specs.map { spec ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(spec.productId)
                    .setProductType(spec.productType)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build()

            withTimeoutOrNull(PLAY_CALLBACK_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    billingClient.queryProductDetailsAsync(
                        params,
                        ProductDetailsResponseListener { billingResult, queryResult ->
                            if (!continuation.isActive) return@ProductDetailsResponseListener
                            continuation.resume(
                                CatalogQueryOutcome(
                                    productType = productType,
                                    requestedProductIds = requestedIds,
                                    responseCode = billingResult.responseCode,
                                    debugMessage = sanitizePlayDebugMessage(billingResult.debugMessage),
                                    productDetails = queryResult.productDetailsList,
                                    unfetchedProducts = queryResult.unfetchedProductList.map {
                                        UnfetchedProductInfo(
                                            productId = it.productId,
                                            productType = it.productType,
                                            statusCode = it.statusCode,
                                        )
                                    },
                                ),
                            )
                        },
                    )
                }
            } ?: catalogFailureOutcome(
                productType = productType,
                requestedIds = requestedIds,
                responseCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                debugMessage = "Google Play product query timed out.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            catalogFailureOutcome(
                productType = productType,
                requestedIds = requestedIds,
                responseCode = BillingClient.BillingResponseCode.ERROR,
                debugMessage = "Google Play product query could not be completed.",
            )
        }
    }

    private fun catalogFailureOutcome(
        productType: String,
        requestedIds: List<String>,
        responseCode: Int,
        debugMessage: String,
    ): CatalogQueryOutcome = CatalogQueryOutcome(
        productType = productType,
        requestedProductIds = requestedIds,
        responseCode = responseCode,
        debugMessage = sanitizePlayDebugMessage(debugMessage),
        productDetails = emptyList(),
        unfetchedProducts = emptyList(),
    )

    private fun mapCatalogOutcome(
        outcome: CatalogQueryOutcome,
    ): List<PlayOffering> {
        if (!outcome.succeeded) return emptyList()
        return outcome.productDetails
            .asSequence()
            .filter { it.productType == BillingClient.ProductType.INAPP }
            .flatMap { it.toPlayOfferings().asSequence() }
            .filter { BillingCatalog.recognizes(it.public.productId) }
            .distinctBy { it.public.key }
            .toList()
    }

    private fun CatalogQueryOutcome.toSegment(
        spec: BillingProductRequestSpec,
        offerings: List<PlayOffering>,
    ): CatalogSegment = CatalogSegment(
        productId = spec.productId,
        productType = spec.productType,
        responseCode = responseCode,
        debugMessage = debugMessage,
        offerings = offerings.map { it.public }.filter { it.productId == spec.productId },
        unfetchedProducts = unfetchedProducts.filter { it.productId == spec.productId },
    )

    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) return true
        return connectionMutex.withLock {
            if (billingClient.isReady) return@withLock true
            withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    billingClient.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(billingResult: BillingResult) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    billingResult.responseCode == BillingClient.BillingResponseCode.OK,
                                )
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            // Auto reconnection remains enabled; caller timeout bounds broken OEM callbacks.
                        }
                    })
                }
            } ?: false
        }
    }

    private suspend fun queryPurchases(): List<Purchase> {
        val result = withTimeoutOrNull(PLAY_CALLBACK_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Result<List<Purchase>>> { continuation ->
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                    if (!continuation.isActive) return@queryPurchasesAsync
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(Result.success(purchases))
                    } else {
                        continuation.resume(
                            Result.failure(
                                IllegalStateException(
                                    billingResponseMessage(
                                        billingResult.responseCode,
                                        billingResult.debugMessage,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        } ?: Result.failure(
            IllegalStateException("Google Play purchase restoration timed out."),
        )
        return result.getOrThrow()
    }

    private fun evaluatePurchases(
        inApp: List<Purchase>,
        now: Long,
    ): EntitlementState = BillingEntitlementEvaluator.evaluate(
        evidence = inApp.map { it.toEvidence() },
        nowEpochMillis = now,
    )

    private fun Purchase.toEvidence(): PurchaseEvidence = PurchaseEvidence(
        products = products.toSet(),
        packageMatches = packageName == context.packageName,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = purchaseState == Purchase.PurchaseState.PENDING,
    )

    private fun isRecognizedPurchase(purchase: Purchase): Boolean =
        purchase.packageName == context.packageName && purchase.products.any(BillingCatalog::recognizes)

    private suspend fun acknowledgeCompleted(inApp: List<Purchase>) {
        val pending = inApp.asSequence()
            .filter { purchase ->
                shouldAcknowledgePurchase(
                    recognized = isRecognizedPurchase(purchase),
                    purchaseState = purchase.purchaseState,
                    acknowledged = purchase.isAcknowledged,
                )
            }
            .distinctBy { it.purchaseToken }
            .toList()
        if (pending.isEmpty()) {
            acknowledgementRetryScheduler.cancel()
            return
        }

        // Persist a retry intent before the first client-side acknowledgement attempt. If the
        // process dies or Play/network connectivity disappears mid-flight, WorkManager retains
        // the reconciliation job instead of relying on the user reopening ApexTuner.
        acknowledgementRetryScheduler.schedule()
        pending.forEach { purchase -> acknowledge(purchase.purchaseToken) }
        acknowledgementRetryScheduler.cancel()
    }

    internal suspend fun retryPendingAcknowledgementsInBackground(): Boolean {
        if (!ensureConnected()) return false
        return try {
            val inApp = queryPurchases()
            val pending = inApp.asSequence()
                .filter { purchase ->
                    shouldAcknowledgePurchase(
                        recognized = isRecognizedPurchase(purchase),
                        purchaseState = purchase.purchaseState,
                        acknowledged = purchase.isAcknowledged,
                    )
                }
                .distinctBy { it.purchaseToken }
                .toList()
            pending.forEach { purchase -> acknowledge(purchase.purchaseToken) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OwnershipChangedException) {
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun acknowledge(token: String) {
        var lastFailure: BillingResult? = null
        for (attempt in 0 until ACK_MAX_ATTEMPTS) {
            val result = acknowledgeOnce(token)
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> return
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> throw OwnershipChangedException()
                else -> {
                    lastFailure = result
                    val canRetry = attempt < ACK_MAX_ATTEMPTS - 1 &&
                        result.isTransientAcknowledgementFailure()
                    if (!canRetry) break
                    delay(ACK_RETRY_DELAYS_MILLIS[attempt])
                }
            }
        }
        throw IllegalStateException(
            "Purchase acknowledgement failed: ${
                billingResponseMessage(
                    checkNotNull(lastFailure).responseCode,
                    lastFailure.debugMessage,
                )
            }",
        )
    }

    private suspend fun acknowledgeOnce(token: String): BillingResult =
        withTimeoutOrNull(PLAY_CALLBACK_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(token)
                    .build()
                billingClient.acknowledgePurchase(params) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            .setDebugMessage("Google Play acknowledgement timed out.")
            .build()

    private fun BillingResult.isTransientAcknowledgementFailure(): Boolean = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.ERROR -> true
        else -> false
    }

    private suspend fun processPurchaseUpdate(purchases: List<Purchase>) {
        val wasPremium = _entitlement.value.isPremium
        refresh("purchase_update")
        _catalog.value = _catalog.value.copy(
            message = when {
                purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING } ->
                    "Purchase pending. ApexTuner will unlock only after Google Play confirms payment."
                !wasPremium && _entitlement.value.isPremium ->
                    "Purchase complete. ApexTuner Premium is now active."
                else -> _catalog.value.message
            },
        )
    }

    private fun clearOfferings() {
        synchronized(offeringLock) {
            playOfferings = emptyMap()
        }
    }

    private suspend fun applyOfflineFallback(reason: String) {
        val cached = withContext(ioDispatcher) {
            cache.loadOfflineGrace(timeProvider.nowEpochMillis())
        }
        _entitlement.value = cached ?: EntitlementState(
            tier = EntitlementTier.Free,
            verification = EntitlementVerification.PlayUnavailable,
            message = reason,
        )
    }

    private class OwnershipChangedException :
        IllegalStateException("Google Play reported ITEM_NOT_OWNED during acknowledgement.")

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
        const val PLAY_CALLBACK_TIMEOUT_MILLIS = 10_000L
        const val ACK_MAX_ATTEMPTS = 3
        val ACK_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L)
    }
}
