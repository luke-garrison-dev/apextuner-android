package com.apextuner.feature.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.database.NetworkQualityRunDao
import com.apextuner.core.database.NetworkQualityRunEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

interface NetworkDiagnosticsRepository {
    suspend fun ping(host: String, timeoutMillis: Int = NetworkDiagnosticPolicy.DEFAULT_PING_TIMEOUT_MILLIS): PingResult
    suspend fun resolveDns(host: String): DnsResult
    suspend fun testTcp(host: String, port: Int, timeoutMillis: Int = NetworkDiagnosticPolicy.DEFAULT_TCP_TIMEOUT_MILLIS): TcpPortResult
    suspend fun scanLocalSubnet(): SubnetScanResult
    suspend fun qualityTest(host: String, port: Int, attempts: Int = 8): NetworkQualityResult
    suspend fun throughputTest(): NetworkThroughputResult
    suspend fun qualityHistory(limit: Int = 10): List<NetworkQualityHistoryItem>
}

@Singleton
class AndroidNetworkDiagnosticsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val qualityRunDao: NetworkQualityRunDao,
) : NetworkDiagnosticsRepository {

    override suspend fun ping(host: String, timeoutMillis: Int): PingResult = withContext(ioDispatcher) {
        val safeHost = NetworkDiagnosticPolicy.validateHost(host)
        val timeout = timeoutMillis.coerceIn(100, 10_000)
        var reachable = false
        var diagnostic: String? = null
        val elapsed = measureTimeMillis {
            try {
                coroutineContext.ensureActive()
                val result = withTimeoutOrNull(DNS_TIMEOUT_MILLIS + timeout.toLong()) {
                    runInterruptible { InetAddress.getByName(safeHost).isReachable(timeout) }
                }
                reachable = result == true
                if (result == null) {
                    diagnostic = "Reachability check timed out."
                } else if (!reachable) {
                    diagnostic = "No ICMP/TCP-echo reachability response before timeout."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostic = error.message ?: "Reachability check failed."
            }
        }
        PingResult(safeHost, reachable, elapsed, diagnostic)
    }

    override suspend fun resolveDns(host: String): DnsResult = withContext(ioDispatcher) {
        val safeHost = NetworkDiagnosticPolicy.validateHost(host)
        var addresses = emptyList<String>()
        var diagnostic: String? = null
        val elapsed = measureTimeMillis {
            try {
                coroutineContext.ensureActive()
                val result = withTimeoutOrNull(DNS_TIMEOUT_MILLIS) {
                    runInterruptible {
                        InetAddress.getAllByName(safeHost)
                            .mapNotNull { it.hostAddress }
                            .distinct()
                            .take(MAX_DNS_RESULTS)
                    }
                }
                if (result == null) {
                    diagnostic = "DNS resolution timed out."
                } else {
                    addresses = result
                    if (addresses.isEmpty()) diagnostic = "Resolver returned no addresses."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostic = error.message ?: "DNS resolution failed."
            }
        }
        DnsResult(safeHost, addresses, elapsed, diagnostic)
    }

    override suspend fun testTcp(host: String, port: Int, timeoutMillis: Int): TcpPortResult = withContext(ioDispatcher) {
        val safeHost = NetworkDiagnosticPolicy.validateHost(host)
        val safePort = NetworkDiagnosticPolicy.validatePort(port)
        val timeout = timeoutMillis.coerceIn(100, 10_000)
        var reachable = false
        var diagnostic: String? = null
        val elapsed = measureTimeMillis {
            try {
                coroutineContext.ensureActive()
                val result = withTimeoutOrNull(DNS_TIMEOUT_MILLIS + timeout.toLong()) {
                    runInterruptible {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(safeHost, safePort), timeout)
                            socket.isConnected
                        }
                    }
                }
                reachable = result == true
                if (result == null) diagnostic = "TCP connection timed out."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostic = error.message ?: "TCP connection failed."
            }
        }
        TcpPortResult(safeHost, safePort, reachable, elapsed, diagnostic)
    }

    override suspend fun qualityTest(host: String, port: Int, attempts: Int): NetworkQualityResult = withContext(ioDispatcher) {
        val safeHost = NetworkDiagnosticPolicy.validateHost(host)
        val safePort = NetworkDiagnosticPolicy.validatePort(port)
        val safeAttempts = attempts.coerceIn(3, 20)
        var resolved = emptyList<InetAddress>()
        var dnsDiagnostic: String? = null
        val dnsElapsed = measureTimeMillis {
            try {
                val result = withTimeoutOrNull(DNS_TIMEOUT_MILLIS) {
                    runInterruptible { InetAddress.getAllByName(safeHost).distinctBy { it.hostAddress }.take(MAX_DNS_RESULTS) }
                }
                if (result == null) dnsDiagnostic = "DNS resolution timed out." else resolved = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dnsDiagnostic = error.message ?: "DNS resolution failed."
            }
        }

        val ipv4Endpoint = resolved.firstOrNull { it is Inet4Address }
        val ipv6Endpoint = resolved.firstOrNull { it is Inet6Address }
        val resolverPreferred = when (resolved.firstOrNull()) {
            is Inet4Address -> NetworkQualityProbePlanner.Family.IPv4
            is Inet6Address -> NetworkQualityProbePlanner.Family.IPv6
            else -> null
        }
        val plan = NetworkQualityProbePlanner.plan(
            totalAttempts = safeAttempts,
            resolverPreferred = resolverPreferred,
            hasIpv4 = ipv4Endpoint != null,
            hasIpv6 = ipv6Endpoint != null,
        )
        if (plan.isEmpty()) {
            return@withContext NetworkQualityResult(
                host = safeHost,
                port = safePort,
                endpointAddress = null,
                attempts = safeAttempts,
                successes = 0,
                minLatencyMillis = null,
                medianLatencyMillis = null,
                averageLatencyMillis = null,
                p95LatencyMillis = null,
                jitterMillis = null,
                dnsLatencyMillis = dnsElapsed,
                ipv4Count = resolved.count { it is Inet4Address },
                ipv6Count = resolved.count { it is Inet6Address },
                ipv4Quality = null,
                ipv6Quality = null,
                preferredAddressFamily = null,
                networkMetered = currentNetworkMetered(),
                diagnostic = dnsDiagnostic ?: "No IPv4 or IPv6 address was resolved.",
            )
        }

        val allSamples = mutableListOf<Long>()
        val ipv4Samples = mutableListOf<Long>()
        val ipv6Samples = mutableListOf<Long>()
        var ipv4Attempts = 0
        var ipv6Attempts = 0

        plan.forEachIndexed { index, family ->
            coroutineContext.ensureActive()
            val endpoint = when (family) {
                NetworkQualityProbePlanner.Family.IPv4 -> ipv4Endpoint.also { ipv4Attempts++ }
                NetworkQualityProbePlanner.Family.IPv6 -> ipv6Endpoint.also { ipv6Attempts++ }
            } ?: return@forEachIndexed
            qualityHandshakeMillis(endpoint, safePort)?.let { latency ->
                allSamples += latency
                when (family) {
                    NetworkQualityProbePlanner.Family.IPv4 -> ipv4Samples += latency
                    NetworkQualityProbePlanner.Family.IPv6 -> ipv6Samples += latency
                }
            }
            if (index < plan.lastIndex) delay(QUALITY_SAMPLE_DELAY_MILLIS)
        }

        val summary = NetworkQualityStatistics.summarize(allSamples)
        val ipv4Summary = NetworkQualityStatistics.summarize(ipv4Samples)
        val ipv6Summary = NetworkQualityStatistics.summarize(ipv6Samples)
        val preferredFamily = NetworkQualityProbePlanner.preferred(
            ipv4Attempts = ipv4Attempts,
            ipv4Successes = ipv4Samples.size,
            ipv4MedianMillis = ipv4Summary.median,
            ipv6Attempts = ipv6Attempts,
            ipv6Successes = ipv6Samples.size,
            ipv6MedianMillis = ipv6Summary.median,
            resolverPreferred = resolverPreferred,
        )
        val preferredEndpoint = when (preferredFamily) {
            NetworkQualityProbePlanner.Family.IPv4 -> ipv4Endpoint
            NetworkQualityProbePlanner.Family.IPv6 -> ipv6Endpoint
            null -> resolved.firstOrNull()
        }
        val headlineSummary = when (preferredFamily) {
            NetworkQualityProbePlanner.Family.IPv4 -> ipv4Summary
            NetworkQualityProbePlanner.Family.IPv6 -> ipv6Summary
            null -> summary
        }
        val ipv4Quality = ipv4Endpoint?.hostAddress?.let { address ->
            NetworkFamilyQuality(
                endpointAddress = address,
                attempts = ipv4Attempts,
                successes = ipv4Samples.size,
                medianLatencyMillis = ipv4Summary.median,
                p95LatencyMillis = ipv4Summary.p95,
                jitterMillis = ipv4Summary.jitter,
            )
        }
        val ipv6Quality = ipv6Endpoint?.hostAddress?.let { address ->
            NetworkFamilyQuality(
                endpointAddress = address,
                attempts = ipv6Attempts,
                successes = ipv6Samples.size,
                medianLatencyMillis = ipv6Summary.median,
                p95LatencyMillis = ipv6Summary.p95,
                jitterMillis = ipv6Summary.jitter,
            )
        }
        val diagnostic = when {
            allSamples.isEmpty() -> "No TCP handshake completed. Port $safePort may be blocked or the network may be unavailable."
            ipv4Attempts > 0 && ipv4Samples.isEmpty() && ipv6Samples.isNotEmpty() -> "IPv4 handshakes failed while IPv6 succeeded; this may indicate an IPv4 route or NAT problem."
            ipv6Attempts > 0 && ipv6Samples.isEmpty() && ipv4Samples.isNotEmpty() -> "IPv6 handshakes failed while IPv4 succeeded; this may indicate an IPv6 route problem."
            allSamples.size < safeAttempts -> "${safeAttempts - allSamples.size} of $safeAttempts handshake attempts did not complete before timeout."
            else -> dnsDiagnostic
        }
        val result = NetworkQualityResult(
            host = safeHost,
            port = safePort,
            endpointAddress = preferredEndpoint?.hostAddress,
            attempts = safeAttempts,
            successes = allSamples.size,
            minLatencyMillis = headlineSummary.minimum,
            medianLatencyMillis = headlineSummary.median,
            averageLatencyMillis = headlineSummary.average,
            p95LatencyMillis = headlineSummary.p95,
            jitterMillis = headlineSummary.jitter,
            dnsLatencyMillis = dnsElapsed,
            ipv4Count = resolved.count { it is Inet4Address },
            ipv6Count = resolved.count { it is Inet6Address },
            ipv4Quality = ipv4Quality,
            ipv6Quality = ipv6Quality,
            preferredAddressFamily = preferredFamily?.name,
            networkMetered = currentNetworkMetered(),
            diagnostic = diagnostic,
        )
        qualityRunDao.insert(
            NetworkQualityRunEntity(
                capturedAtEpochMillis = System.currentTimeMillis(),
                host = safeHost,
                port = safePort,
                attempts = safeAttempts,
                successes = allSamples.size,
                minLatencyMillis = headlineSummary.minimum,
                medianLatencyMillis = headlineSummary.median,
                averageLatencyMillis = headlineSummary.average,
                p95LatencyMillis = headlineSummary.p95,
                jitterMillis = headlineSummary.jitter,
                dnsLatencyMillis = dnsElapsed,
                ipv4Count = result.ipv4Count,
                ipv6Count = result.ipv6Count,
                networkMetered = result.networkMetered,
            ),
        )
        val retentionNow = System.currentTimeMillis()
        qualityRunDao.deleteBefore(retentionNow - QUALITY_HISTORY_RETENTION_MILLIS)
        qualityRunDao.trimToNewest(QUALITY_HISTORY_MAX_ROWS)
        result
    }

    override suspend fun throughputTest(): NetworkThroughputResult = withContext(ioDispatcher) {
        val metered = currentNetworkMetered()
        var downloadBytes = 0L
        var uploadBytes = 0L
        var downloadMbps: Double? = null
        var uploadMbps: Double? = null
        val diagnostics = mutableListOf<String>()

        try {
            val started = System.nanoTime()
            downloadBytes = boundedDownload(NetworkThroughputPolicy.DOWNLOAD_BYTES)
            downloadMbps = NetworkThroughputPolicy.megabitsPerSecond(downloadBytes, System.nanoTime() - started)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics += "Download sample: ${error.message ?: "failed"}"
        }

        coroutineContext.ensureActive()
        try {
            val started = System.nanoTime()
            uploadBytes = boundedUpload(NetworkThroughputPolicy.UPLOAD_BYTES)
            uploadMbps = NetworkThroughputPolicy.megabitsPerSecond(uploadBytes, System.nanoTime() - started)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics += "Upload sample: ${error.message ?: "failed"}"
        }

        NetworkThroughputResult(
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            metered = metered,
            provider = "Cloudflare speed.cloudflare.com",
            assessment = NetworkThroughputPolicy.assessment(downloadMbps, uploadMbps),
            diagnostic = diagnostics.takeIf { it.isNotEmpty() }?.joinToString(" • "),
        )
    }

    private suspend fun boundedDownload(targetBytes: Long): Long = runInterruptible {
        val safeTarget = targetBytes.coerceIn(1L, NetworkThroughputPolicy.DOWNLOAD_BYTES)
        val connection = URL("https://speed.cloudflare.com/__down?bytes=$safeTarget").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = THROUGHPUT_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = THROUGHPUT_READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            connection.inputStream.use { input ->
                while (total < safeTarget) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), safeTarget - total).toInt())
                    if (read < 0) break
                    total += read
                }
            }
            total
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun boundedUpload(targetBytes: Long): Long = runInterruptible {
        val safeTarget = targetBytes.coerceIn(1L, NetworkThroughputPolicy.UPLOAD_BYTES)
        val connection = URL("https://speed.cloudflare.com/__up").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = THROUGHPUT_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = THROUGHPUT_READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(safeTarget)
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            connection.outputStream.use { output ->
                while (total < safeTarget) {
                    val count = minOf(buffer.size.toLong(), safeTarget - total).toInt()
                    output.write(buffer, 0, count)
                    total += count
                }
                output.flush()
            }
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                val discard = ByteArray(1024)
                while (input.read(discard) >= 0) Unit
            }
            total
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun qualityHistory(limit: Int): List<NetworkQualityHistoryItem> = withContext(ioDispatcher) {
        qualityRunDao.recent(limit.coerceIn(1, 50)).map {
            NetworkQualityHistoryItem(
                capturedAtEpochMillis = it.capturedAtEpochMillis,
                host = it.host,
                port = it.port,
                medianLatencyMillis = it.medianLatencyMillis,
                jitterMillis = it.jitterMillis,
                successes = it.successes,
                attempts = it.attempts,
            )
        }
    }

    override suspend fun scanLocalSubnet(): SubnetScanResult = withContext(ioDispatcher) {
        val link = currentIpv4Link()
            ?: return@withContext SubnetScanResult(
                networkLabel = "Unavailable",
                devices = emptyList(),
                hostsAttempted = 0,
                truncated = false,
                diagnostic = "No active IPv4 Wi‑Fi/Ethernet subnet is available.",
            )
        val candidates = NetworkDiagnosticPolicy.ipv4Hosts(
            address = link.address as Inet4Address,
            prefixLength = link.prefixLength,
        )
        val label = NetworkDiagnosticPolicy.networkLabel(link.address as Inet4Address, link.prefixLength)
        val candidateAddresses = candidates.mapNotNull { it.hostAddress }.toSet()
        val arpDevices = readArpDevices()
            .filter { it.address in candidateAddresses }
            .associateBy { it.address }
        val remaining = candidates.filterNot { candidate -> candidate.hostAddress?.let(arpDevices::containsKey) == true }
        val semaphore = Semaphore(NetworkDiagnosticPolicy.MAX_PARALLEL_DISCOVERY)
        val probedDevices = coroutineScope {
            remaining.mapIndexed { index, address ->
                async {
                    delay(index * NetworkDiagnosticPolicy.DISCOVERY_LAUNCH_DELAY_MILLIS)
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        probeHost(address)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val devices = (arpDevices.values + probedDevices).distinctBy { it.address }
        SubnetScanResult(
            networkLabel = label,
            devices = devices.sortedBy { device ->
                val bytes = InetAddress.getByName(device.address).address
                bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xffL) }
            },
            hostsAttempted = candidates.size,
            truncated = link.prefixLength < 24,
            diagnostic = if (link.prefixLength < 24) {
                "The connected subnet is broader than /24; ApexTuner intentionally scanned only the local /24 slice."
            } else null,
        )
    }

    private suspend fun qualityHandshakeMillis(endpoint: InetAddress, port: Int): Long? {
        coroutineContext.ensureActive()
        val started = System.nanoTime()
        val connected = try {
            withTimeoutOrNull(QUALITY_TIMEOUT_MILLIS.toLong() + QUALITY_TIMEOUT_GRACE_MILLIS) {
                runInterruptible {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(endpoint, port), QUALITY_TIMEOUT_MILLIS)
                        socket.isConnected
                    }
                }
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return if (connected) ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L) else null
    }

    private fun currentNetworkMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private fun currentIpv4Link(): LinkAddress? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        val localTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!localTransport || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return manager.getLinkProperties(network)
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    private suspend fun probeHost(address: Inet4Address): LocalDevice? {
        val started = System.nanoTime()
        val reachable = try {
            runInterruptible { address.isReachable(NetworkDiagnosticPolicy.DISCOVERY_TIMEOUT_MILLIS) } ||
                tcpProbe(address, DISCOVERY_PORT_HTTP) ||
                tcpProbe(address, DISCOVERY_PORT_HTTPS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!reachable) return null
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        return LocalDevice(address.hostAddress ?: return null, true, elapsed)
    }

    private suspend fun tcpProbe(address: InetAddress, port: Int): Boolean {
        coroutineContext.ensureActive()
        return try {
            runInterruptible {
                Socket().use { it.connect(InetSocketAddress(address, port), DISCOVERY_TCP_TIMEOUT_MILLIS) }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readArpDevices(): List<LocalDevice> {
        coroutineContext.ensureActive()
        return try {
            runInterruptible {
                val table = File(ARP_TABLE_PATH)
                if (!table.isFile || !table.canRead()) return@runInterruptible emptyList()
                ArpTableParser.parse(table.readText())
                    .map { LocalDevice(address = it, reachable = true, latencyMillis = null) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_DNS_RESULTS = 16
        const val DNS_TIMEOUT_MILLIS = 5_000L
        const val ARP_TABLE_PATH = "/proc/net/arp"
        const val DISCOVERY_TCP_TIMEOUT_MILLIS = 180
        const val DISCOVERY_PORT_HTTP = 80
        const val DISCOVERY_PORT_HTTPS = 443
        const val QUALITY_TIMEOUT_MILLIS = 1_500
        const val QUALITY_TIMEOUT_GRACE_MILLIS = 250L
        const val QUALITY_SAMPLE_DELAY_MILLIS = 120L
        const val QUALITY_HISTORY_RETENTION_MILLIS = 90L * 24L * 60L * 60L * 1_000L
        const val QUALITY_HISTORY_MAX_ROWS = 200
        const val THROUGHPUT_CONNECT_TIMEOUT_MILLIS = 7_000
        const val THROUGHPUT_READ_TIMEOUT_MILLIS = 20_000
    }
}
