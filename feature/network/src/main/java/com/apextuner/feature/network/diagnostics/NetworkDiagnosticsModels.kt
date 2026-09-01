package com.apextuner.feature.network.diagnostics

import java.net.Inet4Address
import java.net.InetAddress

data class PingResult(
    val host: String,
    val reachable: Boolean,
    val elapsedMillis: Long,
    val diagnostic: String? = null,
)

data class DnsResult(
    val host: String,
    val addresses: List<String>,
    val elapsedMillis: Long,
    val diagnostic: String? = null,
)

data class TcpPortResult(
    val host: String,
    val port: Int,
    val reachable: Boolean,
    val elapsedMillis: Long,
    val diagnostic: String? = null,
)

data class LocalDevice(
    val address: String,
    val reachable: Boolean,
    val latencyMillis: Long?,
)

data class SubnetScanResult(
    val networkLabel: String,
    val devices: List<LocalDevice>,
    val hostsAttempted: Int,
    val truncated: Boolean,
    val diagnostic: String? = null,
)


data class NetworkFamilyQuality(
    val endpointAddress: String,
    val attempts: Int,
    val successes: Int,
    val medianLatencyMillis: Long?,
    val p95LatencyMillis: Long?,
    val jitterMillis: Double?,
) {
    val failurePercent: Int get() = if (attempts <= 0) 0 else ((attempts - successes) * 100 / attempts).coerceIn(0, 100)
}

data class NetworkQualityResult(
    val host: String,
    val port: Int,
    val endpointAddress: String?,
    val attempts: Int,
    val successes: Int,
    val minLatencyMillis: Long?,
    val medianLatencyMillis: Long?,
    val averageLatencyMillis: Double?,
    val p95LatencyMillis: Long?,
    val jitterMillis: Double?,
    val dnsLatencyMillis: Long?,
    val ipv4Count: Int,
    val ipv6Count: Int,
    val ipv4Quality: NetworkFamilyQuality?,
    val ipv6Quality: NetworkFamilyQuality?,
    val preferredAddressFamily: String?,
    val networkMetered: Boolean,
    val diagnostic: String? = null,
) {
    val failurePercent: Int get() = if (attempts <= 0) 0 else ((attempts - successes) * 100 / attempts).coerceIn(0, 100)
}


data class NetworkThroughputResult(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val metered: Boolean,
    val provider: String,
    val assessment: String,
    val diagnostic: String? = null,
)

object NetworkThroughputPolicy {
    const val DOWNLOAD_BYTES = 4L * 1024L * 1024L
    const val UPLOAD_BYTES = 1L * 1024L * 1024L
    const val MAX_TRANSFER_BYTES = DOWNLOAD_BYTES + UPLOAD_BYTES

    fun megabitsPerSecond(bytes: Long, elapsedNanos: Long): Double? {
        if (bytes <= 0L || elapsedNanos <= 0L) return null
        val seconds = elapsedNanos / 1_000_000_000.0
        return (bytes * 8.0 / 1_000_000.0) / seconds
    }

    fun assessment(downloadMbps: Double?, uploadMbps: Double?): String = when {
        downloadMbps == null && uploadMbps == null -> "Throughput could not be measured on the active path."
        downloadMbps != null && downloadMbps < 5.0 -> "Download throughput is limited on this sample; latency, radio conditions, congestion, VPNs, or the remote path may contribute."
        uploadMbps != null && uploadMbps < 2.0 -> "Upload throughput is limited on this sample; radio conditions, congestion, VPNs, or the remote path may contribute."
        downloadMbps != null && downloadMbps >= 100.0 -> "This bounded sample shows high download throughput. Run the quality test too if responsiveness matters."
        else -> "This bounded sample completed without a strong throughput anomaly. Results can vary with radio conditions and remote-path load."
    }
}

data class NetworkQualityHistoryItem(
    val capturedAtEpochMillis: Long,
    val host: String,
    val port: Int,
    val medianLatencyMillis: Long?,
    val jitterMillis: Double?,
    val successes: Int,
    val attempts: Int,
)

object NetworkQualityStatistics {
    data class Summary(
        val minimum: Long?,
        val median: Long?,
        val average: Double?,
        val p95: Long?,
        val jitter: Double?,
    )

    fun summarize(samples: List<Long>): Summary {
        if (samples.isEmpty()) return Summary(null, null, null, null, null)
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else {
            val a = sorted[sorted.size / 2 - 1]
            val b = sorted[sorted.size / 2]
            a + (b - a) / 2L
        }
        val p95Index = kotlin.math.ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1
        val jitter = samples.zipWithNext().map { (a, b) -> kotlin.math.abs(b - a).toDouble() }.takeIf { it.isNotEmpty() }?.average()
        return Summary(sorted.first(), median, sorted.average(), sorted[p95Index], jitter)
    }
}

/**
 * Keeps the quality-test work budget fixed while ensuring that dual-stack hosts exercise both
 * address families. The resolver's first family receives the odd extra attempt so Android's
 * preferred route is still represented slightly more heavily.
 */
object NetworkQualityProbePlanner {
    enum class Family { IPv4, IPv6 }

    fun plan(totalAttempts: Int, resolverPreferred: Family?, hasIpv4: Boolean, hasIpv6: Boolean): List<Family> {
        val total = totalAttempts.coerceAtLeast(0)
        if (total == 0 || (!hasIpv4 && !hasIpv6)) return emptyList()
        if (hasIpv4 && !hasIpv6) return List(total) { Family.IPv4 }
        if (!hasIpv4 && hasIpv6) return List(total) { Family.IPv6 }
        val first = resolverPreferred ?: Family.IPv4
        val second = if (first == Family.IPv4) Family.IPv6 else Family.IPv4
        return List(total) { index -> if (index % 2 == 0) first else second }
    }

    fun preferred(
        ipv4Attempts: Int,
        ipv4Successes: Int,
        ipv4MedianMillis: Long?,
        ipv6Attempts: Int,
        ipv6Successes: Int,
        ipv6MedianMillis: Long?,
        resolverPreferred: Family?,
    ): Family? {
        if (ipv4Successes <= 0 && ipv6Successes <= 0) return null
        if (ipv4Successes > 0 && ipv6Successes <= 0) return Family.IPv4
        if (ipv6Successes > 0 && ipv4Successes <= 0) return Family.IPv6

        // Prefer the more reliable family before comparing latency. Cross-multiplication keeps
        // the success-rate comparison exact without floating-point rounding.
        if (ipv4Attempts > 0 && ipv6Attempts > 0) {
            val ipv4Reliability = ipv4Successes.toLong() * ipv6Attempts.toLong()
            val ipv6Reliability = ipv6Successes.toLong() * ipv4Attempts.toLong()
            if (ipv4Reliability > ipv6Reliability) return Family.IPv4
            if (ipv6Reliability > ipv4Reliability) return Family.IPv6
        }
        return when {
            ipv4MedianMillis != null && ipv6MedianMillis != null && ipv4MedianMillis < ipv6MedianMillis -> Family.IPv4
            ipv4MedianMillis != null && ipv6MedianMillis != null && ipv6MedianMillis < ipv4MedianMillis -> Family.IPv6
            else -> resolverPreferred ?: Family.IPv4
        }
    }
}

sealed interface DiagnosticRunState<out T> {
    data object Idle : DiagnosticRunState<Nothing>
    data class Running(val message: String) : DiagnosticRunState<Nothing>
    data class Ready<T>(val value: T) : DiagnosticRunState<T>
    data class Error(val message: String) : DiagnosticRunState<Nothing>
}

data class NetworkDiagnosticsUiState(
    val ping: DiagnosticRunState<PingResult> = DiagnosticRunState.Idle,
    val dns: DiagnosticRunState<DnsResult> = DiagnosticRunState.Idle,
    val tcp: DiagnosticRunState<TcpPortResult> = DiagnosticRunState.Idle,
    val subnet: DiagnosticRunState<SubnetScanResult> = DiagnosticRunState.Idle,
    val quality: DiagnosticRunState<NetworkQualityResult> = DiagnosticRunState.Idle,
    val throughput: DiagnosticRunState<NetworkThroughputResult> = DiagnosticRunState.Idle,
    val qualityHistory: List<NetworkQualityHistoryItem> = emptyList(),
)


object ArpTableParser {
    fun parse(content: String): List<String> =
        content.lineSequence()
            .drop(1)
            .mapNotNull(::parseLine)
            .distinct()
            .toList()

    private fun parseLine(line: String): String? {
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < 6) return null
        val flags = fields[2].removePrefix("0x").toIntOrNull(16) ?: return null
        if (flags and COMPLETE_ENTRY_FLAG == 0) return null
        val address = runCatching { InetAddress.getByName(fields[0]) }.getOrNull()
        return (address as? Inet4Address)?.hostAddress
    }

    private const val COMPLETE_ENTRY_FLAG = 0x2
    private val WHITESPACE = Regex("\\s+")
}

object NetworkDiagnosticPolicy {
    const val DEFAULT_PING_TIMEOUT_MILLIS = 1_000
    const val DEFAULT_TCP_TIMEOUT_MILLIS = 1_000
    const val DISCOVERY_TIMEOUT_MILLIS = 250
    const val MAX_SUBNET_HOSTS = 254
    const val MAX_PARALLEL_DISCOVERY = 6
    const val DISCOVERY_LAUNCH_DELAY_MILLIS = 35L

    fun validateHost(value: String): String {
        val host = value.trim()
        require(host.length in 1..253) { "Host must contain 1–253 characters." }
        require(host.none { it.isWhitespace() || it == '/' || it == '\\' || it == '\u0000' }) {
            "Host contains unsupported characters."
        }
        return host
    }

    fun validatePort(port: Int): Int {
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        return port
    }

    fun ipv4Hosts(address: Inet4Address, prefixLength: Int, maximum: Int = MAX_SUBNET_HOSTS): List<Inet4Address> {
        require(prefixLength in 0..32)
        val limit = maximum.coerceIn(1, MAX_SUBNET_HOSTS)
        val raw = address.address
        val value = ((raw[0].toInt() and 0xff) shl 24) or
            ((raw[1].toInt() and 0xff) shl 16) or
            ((raw[2].toInt() and 0xff) shl 8) or
            (raw[3].toInt() and 0xff)
        val effectivePrefix = prefixLength.coerceAtLeast(24)
        val hostBits = 32 - effectivePrefix
        val mask = if (effectivePrefix == 0) 0 else (-1 shl hostBits)
        val network = value and mask
        val count = if (hostBits == 0) 1 else (1 shl hostBits)
        val first = if (hostBits >= 2) network + 1 else network
        val lastExclusive = if (hostBits >= 2) network + count - 1 else network + count
        val own = value
        val result = ArrayList<Inet4Address>(minOf(limit, (lastExclusive - first).coerceAtLeast(0)))
        var current = first
        while (current < lastExclusive && result.size < limit) {
            if (current != own) result += intToIpv4(current)
            current++
        }
        return result
    }

    fun networkLabel(address: Inet4Address, prefixLength: Int): String {
        val effectivePrefix = prefixLength.coerceAtLeast(24)
        val raw = address.address
        val value = ((raw[0].toInt() and 0xff) shl 24) or
            ((raw[1].toInt() and 0xff) shl 16) or
            ((raw[2].toInt() and 0xff) shl 8) or
            (raw[3].toInt() and 0xff)
        val hostBits = 32 - effectivePrefix
        val mask = if (effectivePrefix == 0) 0 else (-1 shl hostBits)
        return "${intToIpv4(value and mask).hostAddress}/$effectivePrefix"
    }

    private fun intToIpv4(value: Int): Inet4Address {
        val bytes = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        return InetAddress.getByAddress(bytes) as Inet4Address
    }
}
