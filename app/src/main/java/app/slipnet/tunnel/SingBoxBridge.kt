package app.slipnet.tunnel

import android.content.Context
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object SingBoxBridge {

    private const val TAG = "SingBoxBridge"

    private val running = AtomicBoolean(false)
    private var singBoxProcess: Process? = null

    @Volatile
    var debugLogging = false

    @Volatile
    var currentPort = 0
        private set

    suspend fun start(
        context: Context,
        profile: ServerProfile,
        listenPort: Int,
        listenHost: String = "127.0.0.1"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (running.get()) {
            Log.w(TAG, "sing-box already running, stopping first")
            stop()
        }

        try {
            val configJson = buildConfig(profile, listenPort, listenHost)
            Log.i(TAG, "Starting sing-box on $listenHost:$listenPort (${profile.tunnelType.displayName})")
            Log.d(TAG, "Config:\n$configJson")

            // Write config to file
            val configDir = File(context.filesDir, "singbox")
            configDir.mkdirs()
            val configFile = File(configDir, "config.json")
            configFile.writeText(configJson)

            // Find sing-box binary
            val binary = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
            if (!binary.exists()) {
                return@withContext Result.failure(RuntimeException(
                    "sing-box binary not found at ${binary.absolutePath}. " +
                    "VLESS/Trojan/Hysteria2/Shadowsocks requires sing-box."
                ))
            }

            // Ensure executable permission
            binary.setExecutable(true, false)
            Log.d(TAG, "Binary: ${binary.absolutePath} (${binary.length()} bytes, exec=${binary.canExecute()})")

            // Start sing-box process
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath, "-D", configDir.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = configDir.absolutePath
            val process = pb.start()
            singBoxProcess = process

            // Read startup output synchronously to catch errors
            val startupOutput = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val startTime = System.currentTimeMillis()

            // Read output for up to 3 seconds to detect startup errors
            while (System.currentTimeMillis() - startTime < 3000) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    startupOutput.appendLine(line)
                    Log.d(TAG, "sing-box: $line")

                    // Check for fatal error indicators
                    if (line.contains("fatal", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true) && !line.contains("level=")) {
                        // Give it a moment to fully exit
                        Thread.sleep(200)
                        if (!process.isAlive) {
                            singBoxProcess = null
                            return@withContext Result.failure(RuntimeException(
                                "sing-box failed: $line"
                            ))
                        }
                    }
                } else if (!process.isAlive) {
                    // Process died, read remaining output
                    while (reader.ready()) {
                        val line = reader.readLine() ?: break
                        startupOutput.appendLine(line)
                    }
                    break
                } else {
                    Thread.sleep(100)
                }
            }

            // Check if process survived startup
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                singBoxProcess = null
                val output = startupOutput.toString().trim()
                Log.e(TAG, "sing-box exited with code $exitCode. Output:\n$output")
                return@withContext Result.failure(RuntimeException(
                    "sing-box exited (code $exitCode): ${output.lines().lastOrNull { it.isNotBlank() } ?: "no output"}"
                ))
            }

            // Start background output reader for ongoing logs
            Thread({
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "sing-box: $line")
                    }
                } catch (e: Exception) {
                    if (singBoxProcess != null) {
                        Log.w(TAG, "sing-box reader error: ${e.message}")
                    }
                }
            }, "singbox-output").also { it.isDaemon = true; it.start() }

            // Verify SOCKS5 proxy
            if (verifyTcpListening(listenHost, listenPort)) {
                running.set(true)
                currentPort = listenPort
                Log.i(TAG, "sing-box started on port $listenPort")
                Result.success(Unit)
            } else {
                // Give more time
                Thread.sleep(2000)
                if (process.isAlive && verifyTcpListening(listenHost, listenPort)) {
                    running.set(true)
                    currentPort = listenPort
                    Log.i(TAG, "sing-box started on port $listenPort (delayed)")
                    Result.success(Unit)
                } else if (!process.isAlive) {
                    singBoxProcess = null
                    Result.failure(RuntimeException("sing-box exited during startup"))
                } else {
                    // Process alive but port not listening - might still be connecting
                    running.set(true)
                    currentPort = listenPort
                    Log.w(TAG, "sing-box running but port not verified yet")
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box: ${e.message}", e)
            running.set(false)
            currentPort = 0
            singBoxProcess = null
            Result.failure(e)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping sing-box")
        currentPort = 0

        val p = singBoxProcess
        singBoxProcess = null
        if (p != null) {
            try {
                p.destroy()
                Thread.sleep(500)
                if (p.isAlive) p.destroyForcibly()
                Log.i(TAG, "sing-box stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping sing-box: ${e.message}", e)
            }
        }
    }

    fun isRunning(): Boolean = running.get() && singBoxProcess?.isAlive == true

    fun isClientHealthy(): Boolean = isRunning()

    private fun verifyTcpListening(host: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 2000)
                true
            }
        } catch (e: Exception) { false }
    }

    // ==================== Config ====================

    private fun buildConfig(profile: ServerProfile, listenPort: Int, listenHost: String): String {
        val config = JSONObject()

        // لاگ
        config.put("log", JSONObject().apply {
            put("level", if (debugLogging) "debug" else "info")
            put("timestamp", true)
        })

        // ── DNS ──
        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote")
                    put("address", "https://dns.google/dns-query")
                    put("address_resolver", "local")   // ✅ رفع FATAL: missing address_resolver
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "local")
                    put("address", "local")
                    put("detour", "direct")
                })
            })
            put("final", "remote")
            put("independent_cache", true)
            put("strategy", "prefer_ipv4")
        })

        // فقط یک inbound به صورت SOCKS5 روی لوکال
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", listenHost)
                put("listen_port", listenPort)
                put("sniff", true)
            })
        })

        // outbound اصلی بر اساس نوع تونل
        val outbound = when (profile.tunnelType) {
            TunnelType.VLESS -> buildVlessOutbound(profile)
            TunnelType.TROJAN -> buildTrojanOutbound(profile)
            TunnelType.HYSTERIA2 -> buildHysteria2Outbound(profile)
            TunnelType.SHADOWSOCKS -> buildShadowsocksOutbound(profile)
            else -> throw IllegalArgumentException("Unsupported: ${profile.tunnelType}")
        }

        // ── Outbounds ──
        config.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
            put(JSONObject().apply { put("type", "dns");    put("tag", "dns-out") })
            put(JSONObject().apply { put("type", "block");  put("tag", "block") })   // ✅ جدید: برای بلاک QUIC
        })

        // ── Route ──
        config.put("route", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("protocol", "quic")        // ✅ جدید: بلاک QUIC برای جلوگیری از HTTP 400
                    put("outbound", "block")
                })
            })
            put("final", "proxy")
        })

        return config.toString(2)
    }

    private fun buildVlessOutbound(profile: ServerProfile): JSONObject {
        val address = profile.lastScannedIp.ifBlank { profile.vlessAddress }
        return JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", address)
            put("server_port", profile.vlessPort)
            put("uuid", profile.vlessUuid)
            if (profile.vlessFlow.isNotBlank()) put("flow", profile.vlessFlow)

            if (profile.vlessSecurity != "none") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", profile.vlessSni.ifBlank { profile.vlessAddress })
                    if (profile.vlessFingerprint.isNotBlank()) {
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", profile.vlessFingerprint)
                        })
                    }
                    if (profile.vlessSecurity == "reality") {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", profile.vlessRealityPublicKey)
                            if (profile.vlessRealityShortId.isNotBlank()) put("short_id", profile.vlessRealityShortId)
                        })
                    }
                })
            }

            if (profile.vlessNetwork != "tcp") {
                put("transport", buildTransport(profile.vlessNetwork, profile.vlessWsPath.ifBlank { "/" }, profile.vlessWsHost.ifBlank { profile.vlessSni.ifBlank { profile.vlessAddress } }, profile.vlessGrpcServiceName))
            }
            if (profile.proxyMux) put("multiplex", buildMux(profile))
        }
    }

    private fun buildTrojanOutbound(profile: ServerProfile): JSONObject {
        val address = profile.lastScannedIp.ifBlank { profile.trojanAddress }
        return JSONObject().apply {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", address)
            put("server_port", profile.trojanPort)
            put("password", profile.trojanPassword)
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", profile.trojanSni.ifBlank { profile.trojanAddress })
                put("insecure", profile.trojanAllowInsecure)
                if (profile.trojanFingerprint.isNotBlank()) {
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", profile.trojanFingerprint)
                    })
                }
            })
            if (profile.trojanNetwork != "tcp") {
                put("transport", buildTransport(profile.trojanNetwork, profile.trojanWsPath.ifBlank { "/" }, profile.trojanWsHost.ifBlank { profile.trojanSni.ifBlank { profile.trojanAddress } }, profile.trojanGrpcServiceName))
            }
            if (profile.proxyMux) put("multiplex", buildMux(profile))
        }
    }

    private fun buildHysteria2Outbound(profile: ServerProfile): JSONObject {
        val address = profile.lastScannedIp.ifBlank { profile.hy2Address }
        return JSONObject().apply {
            put("type", "hysteria2")
            put("tag", "proxy")
            put("server", address)
            put("server_port", profile.hy2Port)
            put("password", profile.hy2Password)
            put("up_mbps", profile.hy2UpMbps)
            put("down_mbps", profile.hy2DownMbps)
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", profile.hy2Sni.ifBlank { profile.hy2Address })
                put("insecure", profile.hy2AllowInsecure)
            })
            if (profile.hy2Obfs.isNotBlank()) {
                put("obfs", JSONObject().apply {
                    put("type", profile.hy2Obfs)
                    put("password", profile.hy2ObfsPassword)
                })
            }
        }
    }

    private fun buildShadowsocksOutbound(profile: ServerProfile): JSONObject {
        val address = profile.lastScannedIp.ifBlank { profile.ssAddress }
        return JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", address)
            put("server_port", profile.ssPort)
            put("method", profile.ssMethod)
            put("password", profile.ssPassword)
            if (profile.proxyMux) put("multiplex", buildMux(profile))
        }
    }

    private fun buildTransport(network: String, wsPath: String, wsHost: String, grpcServiceName: String): JSONObject {
        return JSONObject().apply {
            when (network) {
                "ws" -> {
                    put("type", "ws")
                    if (wsPath.isNotBlank()) put("path", wsPath)
                    if (wsHost.isNotBlank()) put("headers", JSONObject().apply { put("Host", wsHost) })
                }
                "grpc" -> {
                    put("type", "grpc")
                    if (grpcServiceName.isNotBlank()) put("service_name", grpcServiceName)
                }
                "h2", "http" -> {
                    put("type", "http")
                    if (wsHost.isNotBlank()) put("host", JSONArray().put(wsHost))
                    if (wsPath.isNotBlank()) put("path", wsPath)
                }
            }
        }
    }

    private fun buildMux(profile: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("protocol", profile.muxProtocol)
            put("max_connections", profile.muxMaxConnections)
            put("padding", true)
        }
    }

    // ==================== URI Parsers ====================

    fun parseVlessUri(uri: String): ServerProfile? {
        try {
            if (!uri.startsWith("vless://")) return null
            val withoutScheme = uri.removePrefix("vless://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8") else "VLESS"
            val mainPart = if (nameIndex >= 0) withoutScheme.substring(0, nameIndex) else withoutScheme
            val atIndex = mainPart.indexOf('@')
            if (atIndex < 0) return null
            val uuid = mainPart.substring(0, atIndex)
            val rest = mainPart.substring(atIndex + 1)
            val queryIndex = rest.indexOf('?')
            val hostPort = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            val queryString = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""
            val (address, port) = parseHostPort(hostPort)
            val params = parseQueryString(queryString)
            return ServerProfile(
                name = name, tunnelType = TunnelType.VLESS, vlessUuid = uuid,
                vlessAddress = address, vlessPort = port,
                vlessSecurity = params["security"] ?: "none",
                vlessFlow = params["flow"] ?: "",
                vlessSni = params["sni"] ?: "",
                vlessFingerprint = params["fp"] ?: "chrome",
                vlessNetwork = params["type"] ?: "tcp",
                vlessWsPath = java.net.URLDecoder.decode(params["path"] ?: "", "UTF-8"),
                vlessWsHost = params["host"] ?: "",
                vlessGrpcServiceName = params["serviceName"] ?: "",
                vlessRealityPublicKey = params["pbk"] ?: "",
                vlessRealityShortId = params["sid"] ?: "",
                domain = address
            )
        } catch (e: Exception) { Log.e(TAG, "Parse VLESS failed: ${e.message}"); return null }
    }

    fun parseTrojanUri(uri: String): ServerProfile? {
        try {
            if (!uri.startsWith("trojan://")) return null
            val withoutScheme = uri.removePrefix("trojan://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8") else "Trojan"
            val mainPart = if (nameIndex >= 0) withoutScheme.substring(0, nameIndex) else withoutScheme
            val atIndex = mainPart.indexOf('@')
            if (atIndex < 0) return null
            val password = java.net.URLDecoder.decode(mainPart.substring(0, atIndex), "UTF-8")
            val rest = mainPart.substring(atIndex + 1)
            val queryIndex = rest.indexOf('?')
            val hostPort = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            val queryString = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""
            val (address, port) = parseHostPort(hostPort)
            val params = parseQueryString(queryString)
            return ServerProfile(
                name = name, tunnelType = TunnelType.TROJAN, trojanPassword = password,
                trojanAddress = address, trojanPort = port,
                trojanSni = params["sni"] ?: "", trojanFingerprint = params["fp"] ?: "chrome",
                trojanNetwork = params["type"] ?: "tcp",
                trojanWsPath = java.net.URLDecoder.decode(params["path"] ?: "", "UTF-8"),
                trojanWsHost = params["host"] ?: "",
                trojanGrpcServiceName = params["serviceName"] ?: "",
                trojanAllowInsecure = params["allowInsecure"] == "1",
                domain = address
            )
        } catch (e: Exception) { Log.e(TAG, "Parse Trojan failed: ${e.message}"); return null }
    }

    fun parseHysteria2Uri(uri: String): ServerProfile? {
        try {
            val normalized = uri.replace("hy2://", "hysteria2://")
            if (!normalized.startsWith("hysteria2://")) return null
            val withoutScheme = normalized.removePrefix("hysteria2://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8") else "Hysteria2"
            val mainPart = if (nameIndex >= 0) withoutScheme.substring(0, nameIndex) else withoutScheme
            val atIndex = mainPart.indexOf('@')
            if (atIndex < 0) return null
            val password = java.net.URLDecoder.decode(mainPart.substring(0, atIndex), "UTF-8")
            val rest = mainPart.substring(atIndex + 1)
            val queryIndex = rest.indexOf('?')
            val hostPort = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            val queryString = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""
            val (address, port) = parseHostPort(hostPort)
            val params = parseQueryString(queryString)
            return ServerProfile(
                name = name, tunnelType = TunnelType.HYSTERIA2, hy2Password = password,
                hy2Address = address, hy2Port = port,
                hy2Sni = params["sni"] ?: "", hy2AllowInsecure = params["insecure"] == "1",
                hy2Obfs = params["obfs"] ?: "", hy2ObfsPassword = params["obfs-password"] ?: "",
                domain = address
            )
        } catch (e: Exception) { Log.e(TAG, "Parse Hysteria2 failed: ${e.message}"); return null }
    }

    fun parseShadowsocksUri(uri: String): ServerProfile? {
        try {
            if (!uri.startsWith("ss://")) return null
            val withoutScheme = uri.removePrefix("ss://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8") else "SS"
            val mainPart = if (nameIndex >= 0) withoutScheme.substring(0, nameIndex) else withoutScheme
            val atIndex = mainPart.indexOf('@')
            if (atIndex >= 0) {
                val userInfo = try { String(android.util.Base64.decode(mainPart.substring(0, atIndex), android.util.Base64.NO_WRAP)) } catch (_: Exception) { mainPart.substring(0, atIndex) }
                val ci = userInfo.indexOf(':'); if (ci < 0) return null
                val (address, port) = parseHostPort(mainPart.substring(atIndex + 1))
                return ServerProfile(name = name, tunnelType = TunnelType.SHADOWSOCKS, ssAddress = address, ssPort = port, ssMethod = userInfo.substring(0, ci), ssPassword = userInfo.substring(ci + 1), domain = address)
            } else {
                val decoded = try { String(android.util.Base64.decode(mainPart, android.util.Base64.NO_WRAP)) } catch (_: Exception) { return null }
                val di = decoded.indexOf('@'); if (di < 0) return null
                val ci = decoded.indexOf(':'); if (ci < 0) return null
                val (address, port) = parseHostPort(decoded.substring(di + 1))
                return ServerProfile(name = name, tunnelType = TunnelType.SHADOWSOCKS, ssAddress = address, ssPort = port, ssMethod = decoded.substring(0, ci), ssPassword = decoded.substring(ci + 1, di), domain = address)
            }
        } catch (e: Exception) { Log.e(TAG, "Parse SS failed: ${e.message}"); return null }
    }

    fun parseProxyUri(uri: String): ServerProfile? {
        val t = uri.trim()
        return when {
            t.startsWith("vless://") -> parseVlessUri(t)
            t.startsWith("trojan://") -> parseTrojanUri(t)
            t.startsWith("hysteria2://") || t.startsWith("hy2://") -> parseHysteria2Uri(t)
            t.startsWith("ss://") -> parseShadowsocksUri(t)
            else -> null
        }
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        return if (hostPort.startsWith("[")) {
            val cb = hostPort.indexOf(']')
            Pair(hostPort.substring(1, cb), hostPort.substring(cb + 2).toIntOrNull() ?: 443)
        } else {
            val lc = hostPort.lastIndexOf(':')
            if (lc >= 0) Pair(hostPort.substring(0, lc), hostPort.substring(lc + 1).toIntOrNull() ?: 443)
            else Pair(hostPort, 443)
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq >= 0) p.substring(0, eq) to p.substring(eq + 1) else null
        }.toMap()
    }
}