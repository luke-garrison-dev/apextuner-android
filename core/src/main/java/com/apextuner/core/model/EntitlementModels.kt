package com.apextuner.core.model

enum class EntitlementTier {
    Free,
    PremiumLifetime,
}

enum class EntitlementVerification {
    NotChecked,
    VerifiedByPlayClient,
    PlayUnavailable,
    CachedOfflineGrace,
}

data class EntitlementState(
    val tier: EntitlementTier = EntitlementTier.Free,
    val verification: EntitlementVerification = EntitlementVerification.NotChecked,
    val hasPendingPurchase: Boolean = false,
    val lastCheckedAtEpochMillis: Long? = null,
    val message: String? = null,
) {
    val isPremium: Boolean get() = tier == EntitlementTier.PremiumLifetime
}

enum class PremiumFeature(val displayName: String) {
    DuplicateCleaner("Exact duplicate cleaner"),
    NearDuplicatePhotos("Near-duplicate photo review"),
    BlurryPhotoReview("Blurry / low-quality photo review"),
    SocialMediaCleaner("Social media cleaner"),
    LargeFileCleaner("Large-file cleanup"),
    NotificationHistory("Notification history"),
    ScheduledAutomation("Scheduled automation"),
    GameBooster("Game Booster"),
    LocalFirewall("Local per-app firewall"),
    RealTimeMonitor("Real-time floating monitor"),
    HomeWidgets("Home-screen widgets"),
    AdvancedAccess("Shizuku/root advanced tools"),
    SystemCacheMaintenance("Rollback-safe system cache maintenance"),
    AppFreeze("Per-app freeze and force-stop"),
    ExtendedTuning("Extended CPU tuning"),
}

data class BillingOffering(
    val key: String,
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val pricingSummary: String? = null,
    val offerId: String? = null,
)

data class BillingCatalogState(
    val offerings: List<BillingOffering> = emptyList(),
    val loading: Boolean = false,
    val playAvailable: Boolean = true,
    val unfetchedProductIds: Set<String> = emptySet(),
    val productErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
)

sealed interface PurchaseLaunchResult {
    data object Launched : PurchaseLaunchResult
    data class Failed(val reason: String) : PurchaseLaunchResult
}
