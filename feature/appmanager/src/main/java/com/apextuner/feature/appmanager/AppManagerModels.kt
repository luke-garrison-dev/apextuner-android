package com.apextuner.feature.appmanager

enum class AppKindFilter { All, User, System }
enum class AppInsightFilter { All, ReviewRecommended, Unused30Days, PermissionHeavy, RecentlyInstalled, RecentlyUpdated, LegacyTarget, InstallerUnknown }
enum class AppSort { Name, ReviewPriority, LastUsed, RecentlyUpdated, PermissionExposure, TargetSdk }

data class AppSummary(
    val packageName: String,
    val label: String,
    val mainActivityClassName: String?,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val enabled: Boolean,
    val firstInstallTimeMillis: Long,
    val lastUpdateTimeMillis: Long,
    val lastUsedTimeMillis: Long?,
    val dangerousPermissionCount: Int,
    val grantedDangerousPermissionCount: Int,
    val installerPackage: String?,
    val targetSdk: Int,
    val minSdk: Int,
    val legacyTargetSdk: Boolean,
)

data class AppPermissionInsight(
    val permissionName: String,
    val shortName: String,
    val groupLabel: String?,
    val granted: Boolean,
)

data class AppStorageInsight(
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long
        get() = saturatingSum(appBytes, dataBytes)
}

data class AppNetworkInsight(
    val periodDays: Int,
    val wifiReceivedBytes: Long,
    val wifiSentBytes: Long,
    val mobileReceivedBytes: Long,
    val mobileSentBytes: Long,
) {
    val totalReceivedBytes: Long get() = saturatingSum(wifiReceivedBytes, mobileReceivedBytes)
    val totalSentBytes: Long get() = saturatingSum(wifiSentBytes, mobileSentBytes)
}

data class AppDetail(
    val summary: AppSummary,
    val installerPackage: String?,
    val targetSdk: Int,
    val minSdk: Int,
    val requestedDangerousPermissions: List<AppPermissionInsight>,
    val storage: AppStorageInsight?,
    val network: AppNetworkInsight?,
    val usageAccessGranted: Boolean,
    val storageDiagnostic: String?,
    val networkDiagnostic: String?,
)

data class AppManagerSnapshot(
    val apps: List<AppSummary>,
    val usageAccessGranted: Boolean,
    val inventoryScopeNote: String,
)

data class AppInventoryInsights(
    val totalApps: Int,
    val userApps: Int,
    val systemApps: Int,
    val unused30Days: Int?,
    val permissionHeavy: Int,
    val legacyTarget: Int,
    val recentlyInstalled: Int,
    val recentlyUpdated: Int,
    val unknownInstaller: Int,
    val reviewRecommended: Int,
)

data class AppReviewAssessment(
    val signals: List<String>,
) {
    val priority: Int get() = signals.size
    val recommended: Boolean get() = signals.isNotEmpty()
}

fun appReviewAssessment(
    app: AppSummary,
    usageAccessGranted: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): AppReviewAssessment {
    val signals = buildList {
        if (usageAccessGranted && app.lastUsedTimeMillis?.let { nowMillis - it >= APP_INSIGHT_AGE_MILLIS } == true) {
            add("Unused for at least 30 days")
        }
        if (app.grantedDangerousPermissionCount >= PERMISSION_HEAVY_THRESHOLD) {
            add("${app.grantedDangerousPermissionCount} dangerous permissions granted")
        }
        if (app.legacyTargetSdk) add("Targets an older Android SDK")
        if (!app.isSystem && app.installerPackage.isNullOrBlank()) add("Installer source is not identifiable through Android")
    }
    return AppReviewAssessment(signals)
}

fun summarizeAppInventory(
    apps: List<AppSummary>,
    usageAccessGranted: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): AppInventoryInsights {
    var userApps = 0
    var systemApps = 0
    var unused30Days = 0
    var permissionHeavy = 0
    var legacyTarget = 0
    var recentlyInstalled = 0
    var recentlyUpdated = 0
    var unknownInstaller = 0
    var reviewRecommended = 0
    apps.forEach { app ->
        if (app.isSystem) systemApps++ else userApps++
        if (usageAccessGranted && app.lastUsedTimeMillis?.let { nowMillis - it >= APP_INSIGHT_AGE_MILLIS } == true) unused30Days++
        if (app.grantedDangerousPermissionCount >= PERMISSION_HEAVY_THRESHOLD) permissionHeavy++
        if (app.legacyTargetSdk) legacyTarget++
        if (nowMillis - app.firstInstallTimeMillis in 0..APP_INSIGHT_AGE_MILLIS) recentlyInstalled++
        if (nowMillis - app.lastUpdateTimeMillis in 0..APP_INSIGHT_AGE_MILLIS) recentlyUpdated++
        if (!app.isSystem && app.installerPackage.isNullOrBlank()) unknownInstaller++
        if (appReviewAssessment(app, usageAccessGranted, nowMillis).recommended) reviewRecommended++
    }
    return AppInventoryInsights(
        totalApps = apps.size,
        userApps = userApps,
        systemApps = systemApps,
        unused30Days = if (usageAccessGranted) unused30Days else null,
        permissionHeavy = permissionHeavy,
        legacyTarget = legacyTarget,
        recentlyInstalled = recentlyInstalled,
        recentlyUpdated = recentlyUpdated,
        unknownInstaller = unknownInstaller,
        reviewRecommended = reviewRecommended,
    )
}

data class ApkExportResult(
    val packageName: String,
    val apkCount: Int,
    val uncompressedBytes: Long,
)

sealed interface ApkExportUiState {
    data object Idle : ApkExportUiState
    data object Working : ApkExportUiState
    data class Message(val text: String, val isError: Boolean = false) : ApkExportUiState
}

sealed interface AppManagerUiState {
    data object Loading : AppManagerUiState
    data class Error(val message: String) : AppManagerUiState
    data class Ready(
        val snapshot: AppManagerSnapshot,
        val query: String = "",
        val kindFilter: AppKindFilter = AppKindFilter.All,
        val insightFilter: AppInsightFilter = AppInsightFilter.All,
        val sort: AppSort = AppSort.Name,
        val selectedPackage: String? = null,
        val selectedDetail: AppDetail? = null,
        val detailLoading: Boolean = false,
        val message: String? = null,
    ) : AppManagerUiState
}

fun filterAndSortApps(
    apps: List<AppSummary>,
    query: String,
    filter: AppKindFilter,
    insightFilter: AppInsightFilter,
    sort: AppSort,
    nowMillis: Long = System.currentTimeMillis(),
    usageAccessGranted: Boolean = true,
): List<AppSummary> {
    val needle = query.trim().lowercase()
    val filtered = apps.asSequence()
        .filter { app ->
            when (filter) {
                AppKindFilter.All -> true
                AppKindFilter.User -> !app.isSystem
                AppKindFilter.System -> app.isSystem
            }
        }
        .filter { app ->
            when (insightFilter) {
                AppInsightFilter.All -> true
                AppInsightFilter.ReviewRecommended -> appReviewAssessment(app, usageAccessGranted = usageAccessGranted, nowMillis).recommended
                AppInsightFilter.Unused30Days -> usageAccessGranted &&
                    app.lastUsedTimeMillis?.let { nowMillis - it >= APP_INSIGHT_AGE_MILLIS } == true
                AppInsightFilter.PermissionHeavy -> app.grantedDangerousPermissionCount >= PERMISSION_HEAVY_THRESHOLD
                AppInsightFilter.RecentlyInstalled -> nowMillis - app.firstInstallTimeMillis in 0..APP_INSIGHT_AGE_MILLIS
                AppInsightFilter.RecentlyUpdated -> nowMillis - app.lastUpdateTimeMillis in 0..APP_INSIGHT_AGE_MILLIS
                AppInsightFilter.LegacyTarget -> app.legacyTargetSdk
                AppInsightFilter.InstallerUnknown -> !app.isSystem && app.installerPackage.isNullOrBlank()
            }
        }
        .filter { app -> needle.isBlank() || app.label.lowercase().contains(needle) || app.packageName.lowercase().contains(needle) }

    val comparator = when (sort) {
        AppSort.Name -> compareBy<AppSummary>({ it.label.lowercase() }, { it.packageName })
        AppSort.ReviewPriority -> compareByDescending<AppSummary> { appReviewAssessment(it, usageAccessGranted = usageAccessGranted, nowMillis).priority }.thenBy { it.label.lowercase() }
        AppSort.LastUsed -> compareByDescending<AppSummary> { it.lastUsedTimeMillis ?: Long.MIN_VALUE }
            .thenBy { it.label.lowercase() }
        AppSort.RecentlyUpdated -> compareByDescending<AppSummary> { it.lastUpdateTimeMillis }
            .thenBy { it.label.lowercase() }
        AppSort.PermissionExposure -> compareByDescending<AppSummary> { it.grantedDangerousPermissionCount }
            .thenBy { it.label.lowercase() }
        AppSort.TargetSdk -> compareBy<AppSummary> { it.targetSdk }
            .thenBy { it.label.lowercase() }
    }
    return filtered.sortedWith(comparator).toList()
}

private fun saturatingSum(a: Long, b: Long): Long = when {
    a < 0L || b < 0L -> 0L
    Long.MAX_VALUE - a < b -> Long.MAX_VALUE
    else -> a + b
}

fun isValidAppPackageName(value: String): Boolean {
    if (value.length !in 3..255) return false
    val parts = value.split('.')
    if (parts.size < 2) return false
    return parts.all { part ->
        part.isNotBlank() && (part.first().isLetter() || part.first() == '_') &&
            part.all { it.isLetterOrDigit() || it == '_' }
    }
}

fun saturatingNetworkAdd(a: Long, b: Long): Long = when {
    a < 0L || b < 0L -> 0L
    Long.MAX_VALUE - a < b -> Long.MAX_VALUE
    else -> a + b
}

const val APP_INSIGHT_AGE_DAYS = 30L
const val APP_INSIGHT_AGE_MILLIS = APP_INSIGHT_AGE_DAYS * 24L * 60L * 60L * 1000L
const val PERMISSION_HEAVY_THRESHOLD = 5
