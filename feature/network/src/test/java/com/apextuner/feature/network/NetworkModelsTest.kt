package com.apextuner.feature.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModelsTest {
    @Test
    fun firewallSelectionIsSanitizedDeduplicatedAndBounded() {
        val value = sanitizeFirewallPackages(
            listOf("com.example.one", "com.example.one", "bad", "com.apextuner.app", "_vendor.tool"),
            ownPackageName = "com.apextuner.app",
        )
        assertEquals(linkedSetOf("com.example.one", "_vendor.tool"), value)
        assertEquals(128, sanitizeFirewallPackages((1..600).map { "com.example.p$it" }, "com.self").size)
    }

    @Test
    fun uidAggregationLabelsSharedUidWithoutInventingPerPackageTraffic() {
        val wifi = linkedMapOf<Int, MutableUidUsage>()
        accumulateUidUsage(wifi, 10001, 100, 50)
        accumulateUidUsage(wifi, 10001, 20, 10)
        val rows = mergeNetworkUsageRows(
            wifi = wifi,
            mobile = emptyMap(),
            packageLabelsByUid = mapOf(10001 to listOf("com.a" to "Alpha", "com.b" to "Beta")),
        )
        val row = rows.single()
        assertTrue(row.isSharedUid)
        assertEquals(120L, row.receivedBytes)
        assertEquals(60L, row.sentBytes)
        assertEquals(listOf("com.a", "com.b"), row.packages)
    }

    @Test
    fun packageValidationAndCountersFailClosed() {
        assertTrue(isSafePackageName("com.example.app"))
        assertFalse(isSafePackageName("1bad.package"))
        assertFalse(isSafePackageName("bad/package"))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(0L, saturatingAdd(-1L, 1L))
    }
}
