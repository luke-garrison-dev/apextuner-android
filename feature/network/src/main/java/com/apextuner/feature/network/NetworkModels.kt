package com.apextuner.feature.network

import kotlin.math.max

data class ActiveNetworkInsight(
    val connected: Boolean,
    val transports: List<String>,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val vpnTransport: Boolean,
    val interfaceName: String?,
    val mtu: Int?,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean?,
    val privateDnsServerName: String?,
)

data class AppNetworkUsageRow(
    val uid: Int,
    val label: String,
    val packages: List<String>,
    val receivedBytes: Long,
    val sentBytes: Long,
) {
    val totalBytes: Long get() = saturatingAdd(receivedBytes, sentBytes)
    val isSharedUid: Boolean get() = packages.size > 1
}

data class HistoricalNetworkUsage(
    val periodDays: Int,
    val wifiReceivedBytes: Long,
    val wifiSentBytes: Long,
    val mobileReceivedBytes: Long,
    val mobileSentBytes: Long,
    val topApps: List<AppNetworkUsageRow>,
) {
    val totalBytes: Long get() = saturatingAdd(
        saturatingAdd(wifiReceivedBytes, wifiSentBytes),
        saturatingAdd(mobileReceivedBytes, mobileSentBytes),
    )
}

data class FirewallApp(
    val packageName: String,
    val label: String,
    val selected: Boolean,
)

enum class FirewallRuntimeState { Stopped, Starting, Active, Error }
enum class FirewallProfile { HomeWifi, MobileData, PublicWifi }

data class FirewallStatus(
    val runtimeState: FirewallRuntimeState,
    val selectedPackages: Set<String>,
    val profile: FirewallProfile = FirewallProfile.HomeWifi,
    val lastError: String? = null,
)

data class NetworkSnapshot(
    val activeNetwork: ActiveNetworkInsight,
    val usageAccessGranted: Boolean,
    val usage: HistoricalNetworkUsage?,
    val usageDiagnostic: String?,
    val apexTunerDataSaverRestricted: Boolean?,
    val firewallApps: List<FirewallApp>,
    val firewallStatus: FirewallStatus,
    val monthlyDataCaps: Map<String, Long> = emptyMap(),
    val monthlyDataCapUsage: Map<String, Long> = emptyMap(),
)

sealed interface NetworkUiState {
    data object Loading : NetworkUiState
    data class Error(val message: String) : NetworkUiState
    data class Ready(
        val snapshot: NetworkSnapshot,
        val refreshing: Boolean = false,
        val message: String? = null,
    ) : NetworkUiState
}

data class MutableUidUsage(
    val uid: Int,
    var receivedBytes: Long = 0L,
    var sentBytes: Long = 0L,
)

fun accumulateUidUsage(
    target: MutableMap<Int, MutableUidUsage>,
    uid: Int,
    receivedBytes: Long,
    sentBytes: Long,
) {
    if (uid < 0) return
    val row = target.getOrPut(uid) { MutableUidUsage(uid) }
    row.receivedBytes = saturatingAdd(row.receivedBytes, max(0L, receivedBytes))
    row.sentBytes = saturatingAdd(row.sentBytes, max(0L, sentBytes))
}

fun mergeNetworkUsageRows(
    wifi: Map<Int, MutableUidUsage>,
    mobile: Map<Int, MutableUidUsage>,
    packageLabelsByUid: Map<Int, List<Pair<String, String>>>,
    maxRows: Int = 12,
): List<AppNetworkUsageRow> {
    if (maxRows <= 0) return emptyList()
    val allUids = LinkedHashSet<Int>(wifi.size + mobile.size).apply {
        addAll(wifi.keys)
        addAll(mobile.keys)
    }
    return allUids.asSequence()
        .map { uid ->
            val wifiUsage = wifi[uid]
            val mobileUsage = mobile[uid]
            val identities = packageLabelsByUid[uid].orEmpty().distinctBy { it.first }.sortedBy { it.second.lowercase() }
            val packages = identities.map { it.first }
            val label = when {
                identities.isEmpty() -> "UID $uid"
                identities.size == 1 -> identities.first().second
                else -> "${identities.first().second} + ${identities.size - 1} shared UID"
            }
            AppNetworkUsageRow(
                uid = uid,
                label = label,
                packages = packages,
                receivedBytes = saturatingAdd(wifiUsage?.receivedBytes ?: 0L, mobileUsage?.receivedBytes ?: 0L),
                sentBytes = saturatingAdd(wifiUsage?.sentBytes ?: 0L, mobileUsage?.sentBytes ?: 0L),
            )
        }
        .filter { it.totalBytes > 0L }
        .sortedWith(compareByDescending<AppNetworkUsageRow> { it.totalBytes }.thenBy { it.label.lowercase() })
        .take(maxRows.coerceIn(1, 100))
        .toList()
}

fun sanitizeFirewallPackages(
    requested: Collection<String>,
    ownPackageName: String,
    maximum: Int = 128,
): Set<String> = requested.asSequence()
    .map(String::trim)
    .filter { it != ownPackageName }
    .filter(::isSafePackageName)
    .distinct()
    .take(maximum.coerceIn(1, 512))
    .toCollection(LinkedHashSet())

fun isSafePackageName(value: String): Boolean {
    if (value.length !in 3..255) return false
    val parts = value.split('.')
    if (parts.size < 2) return false
    return parts.all { part ->
        part.isNotEmpty() && (part.first().isLetter() || part.first() == '_') &&
            part.all { ch -> ch.isLetterOrDigit() || ch == '_' }
    }
}

fun dataCapUsagePercent(usageBytes: Long?, capBytes: Long?): Double? {
    if (usageBytes == null || capBytes == null || capBytes <= 0L) return null
    val safeUsage = usageBytes.coerceAtLeast(0L)
    return (safeUsage.toDouble() / capBytes.toDouble() * 100.0).takeIf(Double::isFinite)
}

fun saturatingAdd(a: Long, b: Long): Long {
    if (a < 0L || b < 0L) return 0L
    return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
