package com.apextuner.feature.network

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.feature.network.firewall.FirewallPreferences
import com.apextuner.feature.network.firewall.FirewallRuntimeRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface NetworkRepository {
    suspend fun loadSnapshot(): NetworkSnapshot
    suspend fun setFirewallPackageSelected(packageName: String, selected: Boolean): Set<String>
    suspend fun setFirewallProfile(profile: FirewallProfile): Set<String>
    suspend fun setDataUsageCap(packageName: String, bytes: Long?): Map<String, Long>
    suspend fun loadMonthlyUsageForPackages(packages: Set<String>): Map<String, Long>?
}

class AndroidNetworkRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityManager: CapabilityManager,
    private val firewallPreferences: FirewallPreferences,
    private val dataUsageCapPreferences: DataUsageCapPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : NetworkRepository {
    override suspend fun loadSnapshot(): NetworkSnapshot = withContext(ioDispatcher) {
        val usageGranted = capabilityManager.status(Capability.UsageAccess).state == CapabilityState.Granted
        if (usageGranted && dataUsageCapPreferences.usageAccessWarningSent.first()) {
            dataUsageCapPreferences.setUsageAccessWarningSent(false)
        }
        val selected = firewallPreferences.blockedPackages.first()
        val profile = firewallPreferences.activeProfile.first()
        val firewallApps = loadFirewallApps(selected)
        val historicalResult = if (usageGranted) runCatching { loadHistoricalUsage() } else Result.success(null)
        val runtime = FirewallRuntimeRegistry.runtime.value
        val caps = dataUsageCapPreferences.caps.first()
        val capUsage = if (usageGranted && caps.isNotEmpty()) {
            try {
                loadMonthlyUsageForPackages(caps.keys).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyMap()
            }
        } else emptyMap()
        NetworkSnapshot(
            activeNetwork = loadActiveNetwork(),
            usageAccessGranted = usageGranted,
            usage = historicalResult.getOrNull(),
            usageDiagnostic = when {
                !usageGranted -> "Usage Access is optional and is required only for historical cross-app data usage."
                historicalResult.isFailure -> "Android/OEM network history is currently unavailable."
                else -> null
            },
            apexTunerDataSaverRestricted = readApexTunerDataSaverStatus(),
            firewallApps = firewallApps,
            firewallStatus = FirewallStatus(runtimeState = runtime.state, selectedPackages = selected, profile = profile, lastError = runtime.error),
            monthlyDataCaps = caps,
            monthlyDataCapUsage = capUsage,
        )
    }

    override suspend fun setFirewallPackageSelected(packageName: String, selected: Boolean): Set<String> = withContext(ioDispatcher) {
        require(isSafePackageName(packageName)) { "Invalid package name." }
        require(packageName != context.packageName) { "ApexTuner cannot route itself into its sink firewall." }
        val current = firewallPreferences.blockedPackages.first().toMutableSet()
        if (selected) current += packageName else current -= packageName
        val sanitized = sanitizeFirewallPackages(current, context.packageName)
        firewallPreferences.setBlockedPackages(sanitized)
        sanitized
    }


    override suspend fun setFirewallProfile(profile: FirewallProfile): Set<String> = withContext(ioDispatcher) {
        require(FirewallRuntimeRegistry.runtime.value.state !in setOf(FirewallRuntimeState.Active, FirewallRuntimeState.Starting)) {
            "Stop the firewall before switching profiles."
        }
        firewallPreferences.setActiveProfile(profile)
        firewallPreferences.blockedPackages.first()
    }

    override suspend fun setDataUsageCap(packageName: String, bytes: Long?): Map<String, Long> = withContext(ioDispatcher) {
        require(isSafePackageName(packageName)) { "Invalid package name." }
        if (bytes != null) {
            val uid = runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
                ?: error("Android no longer exposes this application.")
            val visiblePackagesForUid = packageLabelsByUid()[uid].orEmpty().map { it.first }.distinct()
            require(visiblePackagesForUid.size <= 1) {
                "Android reports this app through a shared Linux UID, so ApexTuner cannot claim a reliable per-app threshold."
            }
        }
        dataUsageCapPreferences.setCap(packageName, bytes)
        dataUsageCapPreferences.caps.first()
    }

    @Suppress("DEPRECATION")
    override suspend fun loadMonthlyUsageForPackages(packages: Set<String>): Map<String, Long>? = withContext(ioDispatcher) {
        val usageGranted = capabilityManager.status(Capability.UsageAccess).state == CapabilityState.Granted
        if (!usageGranted) return@withContext null
        val safePackages = packages.filter(::isSafePackageName).toSet()
        if (safePackages.isEmpty()) return@withContext emptyMap()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val start = YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val manager = context.getSystemService(NetworkStatsManager::class.java)
        val wifi = querySummaryByUid(manager, ConnectivityManager.TYPE_WIFI, start, now)
        val mobile = querySummaryByUid(manager, ConnectivityManager.TYPE_MOBILE, start, now)
        val pm = context.packageManager
        val visibleByUid = packageLabelsByUid()
        buildMap {
            safePackages.forEach { packageName ->
                val uid = runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrNull()
                    ?: return@forEach
                if (visibleByUid[uid].orEmpty().map { it.first }.distinct().size > 1) return@forEach
                val wifiRow = wifi[uid]
                val mobileRow = mobile[uid]
                put(
                    packageName,
                    saturatingAdd(
                        saturatingAdd(wifiRow?.receivedBytes ?: 0L, wifiRow?.sentBytes ?: 0L),
                        saturatingAdd(mobileRow?.receivedBytes ?: 0L, mobileRow?.sentBytes ?: 0L),
                    ),
                )
            }
        }
    }

    private fun loadActiveNetwork(): ActiveNetworkInsight {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val linkProperties = network?.let(manager::getLinkProperties)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi‑Fi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
        }
        return ActiveNetworkInsight(
            connected = network != null && capabilities != null,
            transports = transports,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            vpnTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            interfaceName = linkProperties?.interfaceName,
            mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProperties?.mtu?.takeIf { it > 0 } else null,
            dnsServers = linkProperties?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.distinct().take(MAX_DNS_SERVERS),
            privateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) linkProperties?.isPrivateDnsActive else null,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) linkProperties?.privateDnsServerName else null,
        )
    }

    private fun readApexTunerDataSaverStatus(): Boolean? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return when (manager.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> true
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> false
            else -> null
        }
    }

    private fun loadFirewallApps(selected: Set<String>): List<FirewallApp> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = android.os.Process.myUserHandle()
        return runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            .asSequence()
            .groupBy { it.applicationInfo.packageName }
            .mapNotNull { (packageName, activities) ->
                if (packageName == context.packageName || !isSafePackageName(packageName)) return@mapNotNull null
                val applicationInfo = activities.firstOrNull()?.applicationInfo ?: return@mapNotNull null
                val label = runCatching { context.packageManager.getApplicationLabel(applicationInfo).toString() }.getOrDefault(packageName)
                FirewallApp(packageName, label, packageName in selected)
            }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun loadHistoricalUsage(): HistoricalNetworkUsage {
        val manager = context.getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(USAGE_LOOKBACK_DAYS.toLong())
        val wifi = querySummaryByUid(manager, ConnectivityManager.TYPE_WIFI, start, end)
        val mobile = querySummaryByUid(manager, ConnectivityManager.TYPE_MOBILE, start, end)
        val labels = packageLabelsByUid()
        return HistoricalNetworkUsage(
            periodDays = USAGE_LOOKBACK_DAYS,
            wifiReceivedBytes = wifi.values.fold(0L) { total, row -> saturatingAdd(total, row.receivedBytes) },
            wifiSentBytes = wifi.values.fold(0L) { total, row -> saturatingAdd(total, row.sentBytes) },
            mobileReceivedBytes = mobile.values.fold(0L) { total, row -> saturatingAdd(total, row.receivedBytes) },
            mobileSentBytes = mobile.values.fold(0L) { total, row -> saturatingAdd(total, row.sentBytes) },
            topApps = mergeNetworkUsageRows(wifi, mobile, labels, TOP_APP_COUNT),
        )
    }

    @Suppress("DEPRECATION")
    private fun querySummaryByUid(
        manager: NetworkStatsManager,
        networkType: Int,
        start: Long,
        end: Long,
    ): Map<Int, MutableUidUsage> {
        val result = LinkedHashMap<Int, MutableUidUsage>()
        val bucket = NetworkStats.Bucket()
        val stats = manager.querySummary(networkType, null, start, end)
        stats.use { networkStats ->
            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                accumulateUidUsage(result, bucket.uid, bucket.rxBytes, bucket.txBytes)
            }
        }
        return result
    }

    private fun packageLabelsByUid(): Map<Int, List<Pair<String, String>>> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = android.os.Process.myUserHandle()
        return runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            .asSequence()
            .map { activity ->
                val info = activity.applicationInfo
                val label = runCatching { context.packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                info.uid to (info.packageName to label)
            }
            .groupBy({ it.first }, { it.second })
    }

    companion object {
        const val USAGE_LOOKBACK_DAYS = 30
        const val TOP_APP_COUNT = 12
        const val MAX_DNS_SERVERS = 8
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds abstract fun bindNetworkRepository(impl: AndroidNetworkRepository): NetworkRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FirewallPreferencesModule {
    @Binds abstract fun bindFirewallPreferences(impl: com.apextuner.feature.network.firewall.DataStoreFirewallPreferences): FirewallPreferences
}
