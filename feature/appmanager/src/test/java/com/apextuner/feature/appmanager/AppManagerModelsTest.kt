package com.apextuner.feature.appmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagerModelsTest {
    @Test
    fun packageValidationRejectsInjectionLikeOrMalformedValues() {
        assertTrue(isValidAppPackageName("com.example.app"))
        assertTrue(isValidAppPackageName("_vendor.tool_2"))
        listOf("", "single", ".bad", "bad..name", "1bad.name", "bad-name.app", "bad/name.app", "bad\nname.app")
            .forEach { assertFalse("Expected invalid package: $it", isValidAppPackageName(it)) }
    }

    @Test
    fun networkTotalsSaturateInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, saturatingNetworkAdd(Long.MAX_VALUE, 1L))
        assertEquals(0L, saturatingNetworkAdd(-1L, 10L))
    }

    @Test
    fun filteringAndSortingRemainDeterministic() {
        fun app(name: String, packageName: String, system: Boolean, used: Long?, updated: Long) = AppSummary(
            packageName = packageName,
            label = name,
            mainActivityClassName = "Main",
            versionName = "1",
            versionCode = 1,
            isSystem = system,
            enabled = true,
            firstInstallTimeMillis = 1,
            lastUpdateTimeMillis = updated,
            lastUsedTimeMillis = used,
            dangerousPermissionCount = 0,
            grantedDangerousPermissionCount = 0,
            installerPackage = "com.android.vending",
            targetSdk = 36,
            minSdk = 26,
            legacyTargetSdk = false,
        )
        val apps = listOf(
            app("Zulu", "com.z.app", false, 20, 2),
            app("Alpha", "com.a.app", true, null, 9),
            app("Beta", "com.b.app", false, 50, 4),
        )
        assertEquals(listOf("Alpha", "Beta", "Zulu"), filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.All, AppSort.Name).map { it.label })
        assertEquals("Beta", filterAndSortApps(apps, "com.b", AppKindFilter.User, AppInsightFilter.All, AppSort.LastUsed).single().label)
        assertEquals("Alpha", filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.All, AppSort.RecentlyUpdated).first().label)
    }
    @Test
    fun insightFiltersAreEvidenceBased() {
        val now = 40L * 24L * 60L * 60L * 1000L
        fun app(used: Long?, permissions: Int, installer: String?, target: Int, installed: Long, updated: Long) = AppSummary(
            packageName = "com.example.${permissions}${target}", label = "Example", mainActivityClassName = "Main",
            versionName = "1", versionCode = 1, isSystem = false, enabled = true, firstInstallTimeMillis = installed,
            lastUpdateTimeMillis = updated, lastUsedTimeMillis = used, dangerousPermissionCount = permissions,
            grantedDangerousPermissionCount = permissions, installerPackage = installer, targetSdk = target, minSdk = 26,
            legacyTargetSdk = target <= 33,
        )
        val unused = app(now - APP_INSIGHT_AGE_MILLIS - 1, 1, "store", 36, 1, 1)
        val heavy = app(now, 6, null, 32, now - 1, now - 1)
        val apps = listOf(unused, heavy)
        assertEquals(listOf(unused), filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.Unused30Days, AppSort.Name, now))
        assertEquals(listOf(heavy), filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.PermissionHeavy, AppSort.Name, now))
        assertEquals(listOf(heavy), filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.InstallerUnknown, AppSort.Name, now))
        assertEquals(listOf(heavy), filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.LegacyTarget, AppSort.Name, now))
    }


    @Test
    fun reviewCenterAndUnusedFilterNeverInferUsageWhenAccessIsDenied() {
        val now = 100L * 24L * 60L * 60L * 1000L
        val app = AppSummary(
            packageName = "com.example.old", label = "Old", mainActivityClassName = "Main", versionName = "1", versionCode = 1,
            isSystem = false, enabled = true, firstInstallTimeMillis = 1L, lastUpdateTimeMillis = 1L,
            lastUsedTimeMillis = 1L, dangerousPermissionCount = 0, grantedDangerousPermissionCount = 0,
            installerPackage = "com.android.vending", targetSdk = 36, minSdk = 26, legacyTargetSdk = false,
        )

        assertTrue(appReviewAssessment(app, usageAccessGranted = false, nowMillis = now).signals.none { it.startsWith("Unused") })
        assertTrue(
            filterAndSortApps(
                listOf(app), "", AppKindFilter.All, AppInsightFilter.Unused30Days, AppSort.Name,
                nowMillis = now, usageAccessGranted = false,
            ).isEmpty(),
        )
    }

    @Test
    fun inventoryInsightsStayMetadataOnlyAndRespectUsageAccess() {
        val now = 100L * 24L * 60L * 60L * 1000L
        fun app(
            packageName: String,
            system: Boolean,
            used: Long?,
            permissions: Int,
            installer: String?,
            target: Int,
            installed: Long,
            updated: Long,
        ) = AppSummary(
            packageName = packageName, label = packageName, mainActivityClassName = "Main", versionName = "1", versionCode = 1,
            isSystem = system, enabled = true, firstInstallTimeMillis = installed, lastUpdateTimeMillis = updated,
            lastUsedTimeMillis = used, dangerousPermissionCount = permissions, grantedDangerousPermissionCount = permissions,
            installerPackage = installer, targetSdk = target, minSdk = 26, legacyTargetSdk = target <= 33,
        )
        val apps = listOf(
            app("com.example.old", false, now - APP_INSIGHT_AGE_MILLIS - 1, 6, null, 32, 1, 1),
            app("com.example.new", false, now, 1, "com.android.vending", 36, now - 1, now - 1),
            app("com.android.system", true, null, 0, null, 36, 1, now - 1),
        )

        val granted = summarizeAppInventory(apps, usageAccessGranted = true, nowMillis = now)
        assertEquals(3, granted.totalApps)
        assertEquals(2, granted.userApps)
        assertEquals(1, granted.systemApps)
        assertEquals(1, granted.unused30Days)
        assertEquals(1, granted.permissionHeavy)
        assertEquals(1, granted.legacyTarget)
        assertEquals(1, granted.unknownInstaller)
        assertEquals(1, granted.recentlyInstalled)
        assertEquals(2, granted.recentlyUpdated)

        val denied = summarizeAppInventory(apps, usageAccessGranted = false, nowMillis = now)
        assertEquals(null, denied.unused30Days)
        assertEquals(
            listOf("com.example.old"),
            filterAndSortApps(apps, "", AppKindFilter.All, AppInsightFilter.InstallerUnknown, AppSort.Name, now).map { it.packageName },
        )
    }
}
