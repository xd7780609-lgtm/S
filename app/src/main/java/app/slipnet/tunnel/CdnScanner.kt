package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smart CDN IP Scanner with two-phase scanning strategy.
 *
 * Phase 1 (Range Discovery): Quick-ping one random IP from each range
 *   to find responsive ranges. Uses short timeout + high concurrency.
 *
 * Phase 2 (Deep Scan): For responsive ranges, test multiple IPs to find
 *   the absolute best latency.
 *
 * This approach scans 4000+ ranges in ~15-25 seconds instead of minutes.
 */
object CdnScanner {

    private const val TAG = "CdnScanner"

    // ==================== State Flows ====================

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _bestIp = MutableStateFlow<ScanResult?>(null)
    val bestIp: StateFlow<ScanResult?> = _bestIp.asStateFlow()

    private val isScanning = AtomicBoolean(false)
    private var scanJob: Job? = null

    // ==================== Data Classes ====================

    enum class RangeSource(val displayName: String) {
        MAIN("Cloudflare Main"),
        EXTENDED("Extended (Partner/Edge)"),
        BOTH("All Ranges (Recommended)");
    }

    enum class ScanSpeed(val displayName: String, val description: String) {
        QUICK("Quick Scan", "~10 sec • Good results"),
        BALANCED("Balanced", "~20 sec • Better results"),
        DEEP("Deep Scan", "~40 sec • Best results");
    }

    enum class ScanPhase(val displayName: String) {
        IDLE("Ready"),
        TESTING_CURRENT("Testing current IP..."),
        RANGE_DISCOVERY("Phase 1: Discovering ranges..."),
        DEEP_SCAN("Phase 2: Finding best IPs..."),
        DONE("Complete");
    }

    data class ScannerSettings(
        val maxLatency: Int = 300,
        val maxResults: Int = 10,
        val scanSpeed: ScanSpeed = ScanSpeed.BALANCED,
        val rangeSource: RangeSource = RangeSource.BOTH,
        val autoConnect: Boolean = true,
        val saveHistory: Boolean = true,
        val customRanges: List<String> = emptyList()
    ) {
        val concurrency: Int get() = when (scanSpeed) {
            ScanSpeed.QUICK -> 150
            ScanSpeed.BALANCED -> 100
            ScanSpeed.DEEP -> 80
        }

        val phase1Timeout: Int get() = when (scanSpeed) {
            ScanSpeed.QUICK -> 400
            ScanSpeed.BALANCED -> 600
            ScanSpeed.DEEP -> 800
        }

        val phase2Timeout: Int get() = when (scanSpeed) {
            ScanSpeed.QUICK -> 800
            ScanSpeed.BALANCED -> 1000
            ScanSpeed.DEEP -> 1500
        }

        val ipsPerRange: Int get() = when (scanSpeed) {
            ScanSpeed.QUICK -> 3
            ScanSpeed.BALANCED -> 5
            ScanSpeed.DEEP -> 8
        }

        val topRangesToDeepScan: Int get() = when (scanSpeed) {
            ScanSpeed.QUICK -> 15
            ScanSpeed.BALANCED -> 30
            ScanSpeed.DEEP -> 50
        }
    }

    data class ScanResult(
        val ip: String,
        val latency: Int,
        val range: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<ScanResult> {
        override fun compareTo(other: ScanResult): Int = latency.compareTo(other.latency)
    }

    data class RangeResult(
        val range: String,
        val sampleIp: String,
        val latency: Int
    ) : Comparable<RangeResult> {
        override fun compareTo(other: RangeResult): Int = latency.compareTo(other.latency)
    }

    data class ScanState(
        val isScanning: Boolean = false,
        val phase: ScanPhase = ScanPhase.IDLE,
        val totalRanges: Int = 0,
        val scannedRanges: Int = 0,
        val totalIps: Int = 0,
        val scannedIps: Int = 0,
        val foundCount: Int = 0,
        val responsiveRanges: Int = 0,
        val currentIp: String = "",
        val progress: Float = 0f,
        val statusMessage: String = "Ready to scan",
        val elapsedMs: Long = 0
    )

    // ==================== Main Scan Methods ====================

fun startScan(
    scope: CoroutineScope,
    settings: ScannerSettings = ScannerSettings()
) {
    if (isScanning.getAndSet(true)) {
        Log.w(TAG, "Scan already in progress")
        return
    }

    _scanResults.value = emptyList()
    _bestIp.value = null

    scanJob = scope.launch(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val allRanges = settings.customRanges.ifEmpty { DEFAULT_CF_RANGES }
                .filter { it.isNotBlank() && !it.startsWith('#') }

            val mainRanges = allRanges.filter { it.contains('-') }   // cf_ip_range_v4 style
            val cidrRanges = allRanges.filter { it.contains('/') }   // cf_cidrs_v4 style

            Log.i(TAG, "Scan start: main=${mainRanges.size}, cidr=${cidrRanges.size}, source=${settings.rangeSource}")

            val finalResults = when (settings.rangeSource) {
                RangeSource.MAIN -> {
                    // تک‌مرحله‌ای (بدون deep scan)
                    singlePhaseMainScan(mainRanges, settings, startTime)
                }

                RangeSource.EXTENDED -> {
                    // دو فازی
                    twoPhaseCidrScan(cidrRanges, settings, startTime)
                }

                RangeSource.BOTH -> {
                    // اول CIDR (بهتر برای ایران)، اگر کم بود با MAIN پرش می‌کنیم
                    val fromCidr = twoPhaseCidrScan(cidrRanges, settings, startTime)
                    if (!isActive) return@launch

                    if (fromCidr.size >= settings.maxResults) {
                        fromCidr
                    } else {
                        val need = settings.maxResults - fromCidr.size
                        val fromMain = singlePhaseMainScan(mainRanges, settings.copy(maxResults = need), startTime)
                        (fromCidr + fromMain)
                            .distinctBy { it.ip }
                            .sorted()
                            .take(settings.maxResults)
                    }
                }
            }

            val best = finalResults.firstOrNull()
            _scanResults.value = finalResults
            _bestIp.value = best

            _scanState.value = ScanState(
                isScanning = false,
                phase = ScanPhase.DONE,
                totalRanges = allRanges.size,
                scannedRanges = allRanges.size,
                responsiveRanges = 0,
                foundCount = finalResults.size,
                statusMessage = if (best != null) {
                    "Found ${finalResults.size} IPs • Best: ${best.latency}ms"
                } else {
                    "No IP found within ${settings.maxLatency}ms"
                },
                elapsedMs = System.currentTimeMillis() - startTime
            )

        } catch (e: CancellationException) {
            _scanState.value = _scanState.value.copy(
                isScanning = false,
                phase = ScanPhase.IDLE,
                statusMessage = "Scan cancelled"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scan error: ${e.message}", e)
            _scanState.value = _scanState.value.copy(
                isScanning = false,
                phase = ScanPhase.IDLE,
                statusMessage = "Error: ${e.message}"
            )
        } finally {
            isScanning.set(false)
        }
    }
}

    /**
     * Phase 1: Quick-ping one IP from each range.
     */
    private suspend fun phase1RangeDiscovery(
        ranges: List<String>,
        settings: ScannerSettings,
        startTime: Long
    ): List<RangeResult> = coroutineScope {
        val results = ConcurrentLinkedQueue<RangeResult>()
        val scannedCounter = AtomicInteger(0)
        val semaphore = Semaphore(settings.concurrency)

        val jobs = ranges.map { range ->
            launch {
                if (!isActive) return@launch
                semaphore.acquire()
                try {
                    if (!isActive) return@launch

                    val ip = generateOneIp(range) ?: return@launch
                    val latency = tcpPing(ip, 443, settings.phase1Timeout)
                    val scanned = scannedCounter.incrementAndGet()

                    if (latency in 1..settings.maxLatency) {
                        results.add(RangeResult(range, ip, latency))
                    }

                    if (scanned % 50 == 0) {
                        _scanState.value = _scanState.value.copy(
                            scannedRanges = scanned,
                            responsiveRanges = results.size,
                            currentIp = ip,
                            progress = scanned.toFloat() / ranges.size * 0.5f,
                            statusMessage = "Scanning ranges: $scanned/${ranges.size} • Found: ${results.size}",
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                    }
                } finally {
                    semaphore.release()
                }
            }
        }

        jobs.forEach { it.join() }
        results.toList()
    }

    /**
     * Phase 2: Deep-scan the best ranges.
     */
    private suspend fun phase2DeepScan(
        topRanges: List<RangeResult>,
        settings: ScannerSettings,
        startTime: Long
    ): List<ScanResult> = coroutineScope {
        val results = ConcurrentLinkedQueue<ScanResult>()
        val scannedCounter = AtomicInteger(0)
        val totalIps = topRanges.size * settings.ipsPerRange
        val semaphore = Semaphore(settings.concurrency)

        val jobs = topRanges.flatMap { rangeResult ->
            val ips = generateMultipleIps(rangeResult.range, settings.ipsPerRange)
            ips.map { ip ->
                launch {
                    if (!isActive) return@launch
                    semaphore.acquire()
                    try {
                        if (!isActive) return@launch

                        val latency = tcpPing(ip, 443, settings.phase2Timeout)
                        val scanned = scannedCounter.incrementAndGet()

                        if (latency in 1..settings.maxLatency) {
                            val result = ScanResult(ip, latency, rangeResult.range)
                            results.add(result)

                            // Update best IP in real-time
                            val sorted = results.toList().sorted()
                            _scanResults.value = sorted.take(settings.maxResults)
                            _bestIp.value = sorted.firstOrNull()
                        }

                        if (scanned % 20 == 0) {
                            val best = results.toList().minByOrNull { it.latency }
                            _scanState.value = _scanState.value.copy(
                                scannedIps = scanned,
                                totalIps = totalIps,
                                foundCount = results.size,
                                currentIp = ip,
                                progress = 0.5f + (scanned.toFloat() / totalIps * 0.5f),
                                statusMessage = "Deep scan: $scanned/$totalIps • Best: ${best?.latency ?: "—"}ms",
                                elapsedMs = System.currentTimeMillis() - startTime
                            )
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        jobs.forEach { it.join() }
        results.toList()
    }

    fun stopScan() {
        if (!isScanning.getAndSet(false)) return
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(
            isScanning = false,
            phase = ScanPhase.IDLE,
            statusMessage = "Scan stopped"
        )
    }
    
    private suspend fun singlePhaseMainScan(
    mainRanges: List<String>,
    settings: ScannerSettings,
    startTime: Long
): List<ScanResult> = coroutineScope {

    if (mainRanges.isEmpty()) return@coroutineScope emptyList()

    _scanState.value = ScanState(
        isScanning = true,
        phase = ScanPhase.DEEP_SCAN, // از همین برای نمایش استفاده می‌کنیم
        totalRanges = mainRanges.size,
        statusMessage = "Main ranges scan..."
    )

    // تولید candidate مثل D-Tools: یک IP از هر /24
    val candidates = mutableListOf<String>()
    for (range in mainRanges) {
        val parts = range.split('-')
        if (parts.size != 2) continue
        val fromIp = parts[0].trim().split('.').map { it.toInt() }
        val toIp = parts[1].trim().split('.').map { it.toInt() }
        if (fromIp.size != 4 || toIp.size != 4) continue

        for (a in fromIp[0]..toIp[0]) {
            for (b in fromIp[1]..toIp[1]) {
                for (c in fromIp[2]..toIp[2]) {
                    val d = (1..254).random()
                    candidates.add("$a.$b.$c.$d")
                }
            }
        }
    }
    candidates.shuffle()

    val results = ConcurrentLinkedQueue<ScanResult>()
    val scanned = AtomicInteger(0)
    val semaphore = Semaphore(settings.concurrency)

    val jobs = candidates.map { ip ->
        launch {
            if (!isActive) return@launch
            if (results.size >= settings.maxResults) return@launch

            semaphore.acquire()
            try {
                if (!isActive) return@launch
                if (results.size >= settings.maxResults) return@launch

                val latency = tcpPing(ip, 443, settings.phase2Timeout)
                val s = scanned.incrementAndGet()

                if (latency in 1..settings.maxLatency) {
                    results.add(ScanResult(ip = ip, latency = latency, range = "MAIN"))
                    val sorted = results.toList().sorted().take(settings.maxResults)
                    _scanResults.value = sorted
                    _bestIp.value = sorted.firstOrNull()
                }

                if (s % 50 == 0) {
                    _scanState.value = _scanState.value.copy(
                        scannedIps = s,
                        totalIps = candidates.size,
                        foundCount = results.size,
                        currentIp = ip,
                        progress = (s.toFloat() / candidates.size).coerceIn(0f, 1f),
                        statusMessage = "Main scan: $s/${candidates.size} • Found: ${results.size}",
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                }
            } finally {
                semaphore.release()
            }
        }
    }

    // وقتی به تعداد رسیدیم لازم نیست همه join شوند؛ اما برای سادگی:
    jobs.forEach { it.join() }

    results.toList().sorted().take(settings.maxResults)
}

private suspend fun twoPhaseCidrScan(
    cidrRanges: List<String>,
    settings: ScannerSettings,
    startTime: Long
): List<ScanResult> {
    if (cidrRanges.isEmpty()) return emptyList()

    // Phase 1
    _scanState.value = ScanState(
        isScanning = true,
        phase = ScanPhase.RANGE_DISCOVERY,
        totalRanges = cidrRanges.size,
        statusMessage = "Phase 1: Discovering ranges..."
    )

    val responsive = phase1RangeDiscovery(cidrRanges, settings, startTime)
    if (responsive.isEmpty()) return emptyList()

    // Phase 2
    val top = responsive.sorted().take(settings.topRangesToDeepScan)
    _scanState.value = _scanState.value.copy(
        phase = ScanPhase.DEEP_SCAN,
        responsiveRanges = responsive.size,
        totalIps = top.size * settings.ipsPerRange,
        statusMessage = "Phase 2: Deep scanning ${top.size} ranges..."
    )

    return phase2DeepScan(top, settings, startTime)
        .sorted()
        .take(settings.maxResults)
}

    // ==================== Quick Test Methods ====================

    suspend fun testIp(ip: String, port: Int = 443, timeout: Int = 2000): Int =
        withContext(Dispatchers.IO) { tcpPing(ip, port, timeout) }

    suspend fun testCurrentIp(ip: String, port: Int = 443, maxLatency: Int = 300): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Testing current IP: $ip:$port")
            val latency = tcpPing(ip, port, 2000)
            val good = latency in 1..maxLatency
            Log.i(TAG, if (good) "Current IP OK: ${latency}ms" else "Current IP bad: ${if (latency == -1) "timeout" else "${latency}ms"}")
            good
        }

    suspend fun autoFindBestIp(
        currentIp: String,
        port: Int = 443,
        settings: ScannerSettings = ScannerSettings(),
        scope: CoroutineScope
    ): String? {
        if (currentIp.isNotBlank()) {
            _scanState.value = ScanState(
                isScanning = true,
                phase = ScanPhase.TESTING_CURRENT,
                statusMessage = "Testing current IP: $currentIp"
            )
            if (testCurrentIp(currentIp, port, settings.maxLatency)) return currentIp
        }

        startScan(scope, settings)

        var waitTime = 0
        val maxWait = 90000
        while (isScanning.get() && waitTime < maxWait) {
            val best = _bestIp.value
            if (best != null && settings.autoConnect) {
                stopScan()
                return best.ip
            }
            delay(500)
            waitTime += 500
        }

        return _bestIp.value?.ip
    }

    // ==================== Ping ====================

    private fun tcpPing(ip: String, port: Int, timeout: Int): Int {
        return try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, port), timeout)
            val latency = (System.currentTimeMillis() - start).toInt()
            socket.close()
            latency
        } catch (e: Exception) {
            -1
        }
    }

    // ==================== IP Generation ====================

    private fun generateOneIp(range: String): String? {
        return try {
            if (range.contains('/')) {
                generateFromCidr(range, 1).firstOrNull()
            } else if (range.contains('-')) {
                generateFromRange(range, 1).firstOrNull()
            } else null
        } catch (e: Exception) { null }
    }

    private fun generateMultipleIps(range: String, count: Int): List<String> {
        return try {
            if (range.contains('/')) {
                generateFromCidr(range, count)
            } else if (range.contains('-')) {
                generateFromRange(range, count)
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun generateFromCidr(cidr: String, count: Int): List<String> {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return emptyList()

        val baseIpParts = parts[0].split('.').map { it.toInt() }
        val prefixLen = parts[1].toIntOrNull() ?: return emptyList()
        if (baseIpParts.size != 4 || prefixLen !in 0..32) return emptyList()

        val baseIp = (baseIpParts[0] shl 24) or (baseIpParts[1] shl 16) or
                (baseIpParts[2] shl 8) or baseIpParts[3]
        val hostBits = 32 - prefixLen
        val numHosts = 1 shl hostBits

        val ips = mutableListOf<String>()
        val attempts = count * 3
        repeat(minOf(attempts, numHosts)) {
            val offset = (1 until numHosts - 1).random()
            val ip = baseIp or offset
            val lastOctet = ip and 0xFF
            if (lastOctet != 0 && lastOctet != 255) {
                val ipStr = ipToString(ip)
                if (ipStr !in ips) ips.add(ipStr)
            }
            if (ips.size >= count) return ips
        }
        return ips
    }

    private fun generateFromRange(range: String, count: Int): List<String> {
        val parts = range.split('-')
        if (parts.size != 2) return emptyList()

        val fromIp = parts[0].trim().split('.').map { it.toInt() }
        val toIp = parts[1].trim().split('.').map { it.toInt() }
        if (fromIp.size != 4 || toIp.size != 4) return emptyList()

        val ips = mutableListOf<String>()
        repeat(count) {
            val a = (fromIp[0]..toIp[0]).random()
            val b = (fromIp[1]..toIp[1]).random()
            val c = (fromIp[2]..toIp[2]).random()
            val d = (1..254).random()
            ips.add("$a.$b.$c.$d")
        }
        return ips
    }

    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    // ==================== Asset Loading ====================

    private val DEFAULT_CF_RANGES = listOf(
        "173.245.48.0-173.245.63.255",
        "103.21.244.0-103.21.247.255",
        "103.22.200.0-103.22.203.255",
        "103.31.4.0-103.31.7.255",
        "141.101.64.0-141.101.127.255",
        "108.162.192.0-108.162.255.255",
        "190.93.240.0-190.93.255.255",
        "188.114.96.0-188.114.111.255",
        "197.234.240.0-197.234.243.255",
        "198.41.128.0-198.41.255.255",
        "162.158.0.0-162.159.255.255",
        "104.16.0.0-104.27.255.255",
        "172.64.0.0-172.71.255.255"
    )

    fun loadMainRanges(context: Context): List<String> {
        return try {
            context.assets.open("cf_ip_range_v4").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith('#') }
        } catch (e: Exception) {
            DEFAULT_CF_RANGES
        }
    }

    fun loadExtendedRanges(context: Context): List<String> {
        return try {
            context.assets.open("cf_cidrs_v4").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith('#') }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadRanges(context: Context, source: RangeSource): List<String> {
        return when (source) {
            RangeSource.MAIN -> loadMainRanges(context)
            RangeSource.EXTENDED -> loadExtendedRanges(context)
            RangeSource.BOTH -> loadMainRanges(context) + loadExtendedRanges(context)
        }
    }
}