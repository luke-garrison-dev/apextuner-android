package com.apextuner.feature.network.diagnostics

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticPolicyTest {
    @Test
    fun ipv4Hosts_capsBroaderSubnetToLocalSlash24AndSkipsOwnAddress() {
        val address = InetAddress.getByName("192.168.42.99") as java.net.Inet4Address
        val hosts = NetworkDiagnosticPolicy.ipv4Hosts(address, prefixLength = 16)
        assertEquals(253, hosts.size)
        assertFalse(hosts.any { it.hostAddress == "192.168.42.99" })
        assertTrue(hosts.all { it.hostAddress!!.startsWith("192.168.42.") })
        assertEquals("192.168.42.0/24", NetworkDiagnosticPolicy.networkLabel(address, 16))
    }

    @Test
    fun ipv4Hosts_respectsNarrowSubnet() {
        val address = InetAddress.getByName("10.0.0.2") as java.net.Inet4Address
        val hosts = NetworkDiagnosticPolicy.ipv4Hosts(address, prefixLength = 30)
        assertEquals(listOf("10.0.0.1"), hosts.map { it.hostAddress })
        assertEquals("10.0.0.0/30", NetworkDiagnosticPolicy.networkLabel(address, 30))
    }


    @Test
    fun arpParser_acceptsOnlyCompleteIpv4Entries() {
        val table = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.2      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
            192.168.1.3      0x1         0x0         00:00:00:00:00:00     *        wlan0
            malformed
            10.0.0.4         0x1         0x2         11:22:33:44:55:66     *        eth0
        """.trimIndent()

        assertEquals(listOf("192.168.1.2", "10.0.0.4"), ArpTableParser.parse(table))
    }

    @Test
    fun validatorsRejectUnsafeHostAndPort() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkDiagnosticPolicy.validateHost("host/name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkDiagnosticPolicy.validatePort(0)
        }
        assertEquals("example.com", NetworkDiagnosticPolicy.validateHost(" example.com "))
        assertEquals(443, NetworkDiagnosticPolicy.validatePort(443))
    }
}
