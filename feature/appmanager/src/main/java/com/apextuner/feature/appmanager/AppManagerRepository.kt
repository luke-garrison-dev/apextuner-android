package com.apextuner.feature.appmanager

import android.Manifest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.os.storage.StorageManager
import androidx.core.content.pm.PermissionInfoCompat
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

interface AppManagerRepository {
    suspend fun loadApps(): AppManagerSnapshot
    suspend fun loadDetail(packageName: String): AppDetail
    suspend fun exportApkBackup(packageName: String, destination: Uri): ApkExportResult
}

class AndroidAppManagerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityManager: CapabilityManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppManagerRepository {
    private val packageManager: PackageManager get() = context.packageManager
    private val usageCacheMutex = Mutex()
    private var usageCache: Pair<Long, Map<String, Long>>? = null
    private val permissionInfoCache = HashMap<String, PermissionInfo?>()

    override suspend fun loadApps(): AppManagerSnapshot = withContext(ioDispatcher) {
        val usageGranted = hasUsageAccess()
        val usageByPackage = if (usageGranted) queryUsageByPackageCached() else emptyMap()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = android.os.Process.myUserHandle()
        val launchable = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())

        val apps = launchable
            .asSequence()
            .groupBy { it.applicationInfo.packageName }
            .mapNotNull { (packageName, activities) ->
                val info = packageInfo(packageName, PackageManager.GET_PERMISSIONS) ?: return@mapNotNull null
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                val firstActivity = activities.firstOrNull()
                toSummary(
                    info = info,
                    appInfo = appInfo,
                    mainActivityClassName = firstActivity?.componentName?.className,
                    lastUsed = usageByPackage[packageName],
                )
            }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()

        AppManagerSnapshot(
            apps = apps,
            usageAccessGranted = usageGranted,
            inventoryScopeNote = "Android 11+ limits installed-app visibility. ApexTuner intentionally lists launchable apps exposed through LauncherApps and does not request QUERY_ALL_PACKAGES.",
        )
    }

    override suspend fun loadDetail(packageName: String): AppDetail = withContext(ioDispatcher) {
        require(isValidAppPackageName(packageName)) { "Invalid package name." }
        val info = packageInfo(packageName, PackageManager.GET_PERMISSIONS)
            ?: throw IllegalArgumentException("The selected application is no longer available.")
        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("Application metadata is unavailable.")
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val mainActivity = runCatching {
            launcherApps.getActivityList(packageName, android.os.Process.myUserHandle()).firstOrNull()?.componentName?.className
        }.getOrNull()
        val usageGranted = hasUsageAccess()
        val summary = toSummary(info, appInfo, mainActivity, if (usageGranted) queryUsageByPackageCached()[packageName] else null)
        val permissions = dangerousPermissions(info, packageName)

        val storageResult = if (usageGranted) runCatching { queryStorage(info, appInfo) } else Result.success(null)
        val networkResult = if (usageGranted) runCatching { queryNetwork(appInfo.uid) } else Result.success(null)
        AppDetail(
            summary = summary,
            installerPackage = installerPackage(packageName),
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 1,
            requestedDangerousPermissions = permissions,
            storage = storageResult.getOrNull(),
            network = networkResult.getOrNull(),
            usageAccessGranted = usageGranted,
            storageDiagnostic = when {
                !usageGranted -> "Usage Access is required for another app's detailed storage statistics."
                storageResult.isFailure -> "Android/OEM storage statistics were unavailable for this app."
                else -> null
            },
            networkDiagnostic = when {
                !usageGranted -> "Usage Access is required for another app's historical network statistics."
                networkResult.isFailure -> "Android/OEM network history was unavailable for this app."
                else -> null
            },
        )
    }

    override suspend fun exportApkBackup(packageName: String, destination: Uri): ApkExportResult = withContext(ioDispatcher) {
        require(isValidAppPackageName(packageName)) { "Invalid package name." }
        val info = packageInfo(packageName, 0)
            ?: throw IllegalArgumentException("The selected application is no longer available.")
        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("Application metadata is unavailable.")

        val sources = buildList {
            val base = appInfo.publicSourceDir?.takeIf { it.isNotBlank() }
                ?: appInfo.sourceDir?.takeIf { it.isNotBlank() }
            if (base != null) add("base.apk" to File(base))
            val splitNames = info.splitNames.orEmpty()
            val splitPaths = appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs.orEmpty()
            splitPaths.forEachIndexed { index, path ->
                if (path.isNullOrBlank()) return@forEachIndexed
                val rawName = splitNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "${index + 1}"
                add("split_${sanitizeArchiveName(rawName)}.apk" to File(path))
            }
        }.distinctBy { (_, file) -> runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) }

        if (sources.isEmpty()) throw IllegalStateException("Android did not expose an APK source for this app.")
        sources.forEach { (_, file) ->
            if (!file.isFile || !file.canRead()) throw IllegalStateException("One or more APK files are not readable on this device.")
            if (file.length() <= 0L) throw IllegalStateException("Android reported an empty APK file.")
        }
        val totalBytes = sources.fold(0L) { total, (_, file) -> saturatingNetworkAdd(total, file.length()) }
        if (totalBytes == Long.MAX_VALUE || totalBytes > MAX_APK_EXPORT_BYTES) {
            throw IllegalStateException("This APK set is too large for ApexTuner's bounded local export.")
        }

        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destination, "w")
            ?: throw IllegalStateException("The selected destination could not be opened for writing.")
        val digests = mutableListOf<Triple<String, Long, String>>()
        try {
            ZipOutputStream(BufferedOutputStream(output, COPY_BUFFER_BYTES)).use { zip ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                for ((entryName, file) in sources) {
                    currentCoroutineContext().ensureActive()
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                    var copied = 0L
                    BufferedInputStream(FileInputStream(file), COPY_BUFFER_BYTES).use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count.toLong()
                            if (copied > file.length() + COPY_BUFFER_BYTES) throw IllegalStateException("APK source changed unexpectedly during export.")
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    if (copied != file.length()) throw IllegalStateException("APK source changed while it was being exported.")
                    digests += Triple(entryName, copied, digest.digest().toHex())
                }

                val metadata = buildString {
                    appendLine("format=ApexTuner APK backup v1")
                    appendLine("package=$packageName")
                    appendLine("label=${safeMetadataValue(runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(packageName))}")
                    appendLine("versionName=${safeMetadataValue(info.versionName ?: "")}")
                    appendLine("versionCode=${packageVersionCode(info)}")
                    appendLine("apkCount=${digests.size}")
                    digests.forEachIndexed { index, (name, bytes, sha256) ->
                        appendLine("apk.$index.name=$name")
                        appendLine("apk.$index.bytes=$bytes")
                        appendLine("apk.$index.sha256=$sha256")
                    }
                    appendLine("note=This ZIP is a local backup of the base and split APK files Android exposed. It does not include app-private data or guarantee reinstall compatibility on another device.")
                }.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("ApexTuner-backup-info.txt").apply { time = 0L })
                zip.write(metadata)
                zip.closeEntry()
            }
        } catch (error: Throwable) {
            runCatching { resolver.delete(destination, null, null) }
            throw error
        }
        ApkExportResult(packageName = packageName, apkCount = sources.size, uncompressedBytes = totalBytes)
    }

    private fun sanitizeArchiveName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')
        .take(80)
        .ifBlank { "split" }

    private fun safeMetadataValue(value: String): String = value.replace(Regex("[\\r\\n\\u0000]+"), " ").take(240)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private fun toSummary(
        info: PackageInfo,
        appInfo: ApplicationInfo,
        mainActivityClassName: String?,
        lastUsed: Long?,
    ): AppSummary {
        val dangerous = dangerousPermissions(info, appInfo.packageName)
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 || appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return AppSummary(
            packageName = appInfo.packageName,
            label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName),
            mainActivityClassName = mainActivityClassName,
            versionName = info.versionName,
            versionCode = packageVersionCode(info),
            isSystem = isSystem,
            enabled = appInfo.enabled,
            firstInstallTimeMillis = info.firstInstallTime.coerceAtLeast(0L),
            lastUpdateTimeMillis = info.lastUpdateTime.coerceAtLeast(0L),
            lastUsedTimeMillis = lastUsed?.takeIf { it > 0L },
            dangerousPermissionCount = dangerous.size,
            grantedDangerousPermissionCount = dangerous.count { it.granted },
            installerPackage = installerPackage(appInfo.packageName),
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 1,
            legacyTargetSdk = appInfo.targetSdkVersion <= (Build.VERSION.SDK_INT - LEGACY_TARGET_SDK_GAP),
        )
    }

    private fun dangerousPermissions(info: PackageInfo, packageName: String): List<AppPermissionInsight> {
        return info.requestedPermissions.orEmpty().asSequence()
            .mapNotNull { permissionName ->
                val permissionInfo = permissionInfo(permissionName) ?: return@mapNotNull null
                if (!permissionInfo.isDangerousPermission()) return@mapNotNull null
                val groupLabel = permissionInfo.group?.let { groupName ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        packageManager.getPermissionGroupInfo(groupName, 0).loadLabel(packageManager).toString()
                    }.getOrNull()
                }
                AppPermissionInsight(
                    permissionName = permissionName,
                    shortName = permissionName.substringAfterLast('.'),
                    groupLabel = groupLabel,
                    granted = packageManager.checkPermission(permissionName, packageName) == PackageManager.PERMISSION_GRANTED,
                )
            }
            .sortedWith(compareBy({ it.groupLabel ?: "" }, { it.shortName }))
            .toList()
    }

    private fun permissionInfo(name: String): PermissionInfo? = synchronized(permissionInfoCache) {
        if (permissionInfoCache.containsKey(name)) return@synchronized permissionInfoCache[name]
        val value = getPermissionInfoCompat(name)
        permissionInfoCache[name] = value
        value
    }

    private fun getPermissionInfoCompat(name: String): PermissionInfo? = runCatching {
        getPermissionInfoLegacy(name)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun getPermissionInfoLegacy(name: String): PermissionInfo = packageManager.getPermissionInfo(name, 0)

    private fun PermissionInfo.isDangerousPermission(): Boolean =
        PermissionInfoCompat.getProtection(this) == PermissionInfo.PROTECTION_DANGEROUS

    private fun installerPackage(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            installerPackageLegacy(packageName)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installerPackageLegacy(packageName: String): String? = packageManager.getInstallerPackageName(packageName)

    private suspend fun queryUsageByPackageCached(): Map<String, Long> = usageCacheMutex.withLock {
        val now = System.currentTimeMillis()
        usageCache?.takeIf { (createdAt, _) -> now - createdAt in 0..USAGE_CACHE_TTL_MILLIS }?.second?.let { return@withLock it }
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val start = now - TimeUnit.DAYS.toMillis(APP_USAGE_LOOKBACK_DAYS.toLong())
        val value = runCatching { manager.queryAndAggregateUsageStats(start, now) }
            .getOrDefault(emptyMap())
            .mapValues { (_, stats) -> stats.lastTimeUsed }
        usageCache = now to value
        value
    }

    private fun queryStorage(info: PackageInfo, appInfo: ApplicationInfo): AppStorageInsight? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = context.getSystemService(StorageStatsManager::class.java)
        val uuid = appInfo.storageUuid ?: StorageManager.UUID_DEFAULT
        val user = UserHandle.getUserHandleForUid(appInfo.uid)
        val stats = manager.queryStatsForPackage(uuid, info.packageName, user)
        return AppStorageInsight(
            appBytes = stats.appBytes.coerceAtLeast(0L),
            dataBytes = stats.dataBytes.coerceAtLeast(0L),
            cacheBytes = stats.cacheBytes.coerceAtLeast(0L),
        )
    }

    @Suppress("DEPRECATION")
    private fun queryNetwork(uid: Int): AppNetworkInsight {
        val manager = context.getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(NETWORK_LOOKBACK_DAYS.toLong())
        val wifi = aggregateUidNetwork(manager, ConnectivityManager.TYPE_WIFI, start, end, uid)
        val mobile = aggregateUidNetwork(manager, ConnectivityManager.TYPE_MOBILE, start, end, uid)
        return AppNetworkInsight(
            periodDays = NETWORK_LOOKBACK_DAYS,
            wifiReceivedBytes = wifi.first,
            wifiSentBytes = wifi.second,
            mobileReceivedBytes = mobile.first,
            mobileSentBytes = mobile.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun aggregateUidNetwork(
        manager: NetworkStatsManager,
        networkType: Int,
        start: Long,
        end: Long,
        uid: Int,
    ): Pair<Long, Long> {
        var received = 0L
        var sent = 0L
        val bucket = NetworkStats.Bucket()
        manager.queryDetailsForUid(networkType, null, start, end, uid).use { stats ->
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                received = saturatingNetworkAdd(received, bucket.rxBytes.coerceAtLeast(0L))
                sent = saturatingNetworkAdd(sent, bucket.txBytes.coerceAtLeast(0L))
            }
        }
        return received to sent
    }

    private fun hasUsageAccess(): Boolean = capabilityManager.status(Capability.UsageAccess).state == CapabilityState.Granted

    private fun packageInfo(packageName: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageInfoLegacy(packageName, flags)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfoLegacy(packageName: String, flags: Int): PackageInfo = packageManager.getPackageInfo(packageName, flags)

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    companion object {
        const val APP_USAGE_LOOKBACK_DAYS = 90
        const val NETWORK_LOOKBACK_DAYS = 30
        const val USAGE_CACHE_TTL_MILLIS = 60_000L
        const val COPY_BUFFER_BYTES = 128 * 1024
        const val MAX_APK_EXPORT_BYTES = 4L * 1024L * 1024L * 1024L
        const val LEGACY_TARGET_SDK_GAP = 3
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class AppManagerModule {
    @Binds abstract fun bindAppManagerRepository(impl: AndroidAppManagerRepository): AppManagerRepository
}
