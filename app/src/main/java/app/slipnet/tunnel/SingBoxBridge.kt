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

/**
 * Bridge to sing-box for VLESS, Trojan, Hysteria2, Shadowsocks protocols.
 * Runs sing-box as a subprocess (like Tor), providing a local SOCKS5 proxy.
 */
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
            if (debugLogging) Log.d(TAG, "Config:\n$configJson")

            // Write config to file
            val configDir = File(context.filesDir, "singbox")
            configDir.mkdirs()
            val configFile = File(configDir, "config.json")
            configFile.writeText(configJson)

            // Find sing-box binary
            val binary = context.applicationInfo.nativeLibraryDir + "/libsingbox.so"
            if (!File(binary).exists()) {
                return@withContext Result.failure(RuntimeException(
                    "sing-box binary not found. VLESS/Trojan/Hysteria2/Shadowsocks " +
                    "requires sing-box to be bundled as libsingbox.so"
                ))
            }

            // Start sing-box process
            val pb = ProcessBuilder(binary, "run", "-c", configFile.absolutePath, "-D", configDir.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = configDir.absolutePath
            val process = pb.start()
            singBoxProcess = process

            // Monitor output
            Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "sing-box: $line")
                    }
                } catch (e: Exception) {
                    if (singBoxProcess != null) {
                        Log.w(TAG, "sing-box output reader error: ${e.message}")
                    }
                }
            }, "singbox-output").also { it.isDaemon = true; it.start() }

            // Wait for startup
            Thread.sleep(1000)

            if (!process.isAlive) {
                singBoxProcess = null
                return@withContext Result.failure(RuntimeException("sing-box exited immediately"))
            }

            // Verify SOCKS5 proxy is listening
            if (verifyTcpListening(listenHost, listenPort)) {
                running.set(true)
                currentPort = listenPort
                Log.i(TAG, "sing-box started successfully on port $listenPort")
                Result.success(Unit)
            } else {
                Log.w(TAG, "sing-box process alive but port not yet listening, giving more time...")
                Thread.sleep(1500)
                if (process.isAlive && verifyTcpListening(listenHost, listenPort)) {
                    running.set(true)
                    currentPort = listenPort
                    Log.i(TAG, "sing-box started successfully on port $listenPort (delayed)")
                    Result.success(Unit)
                } else {
                    process.destroyForcibly()
                    singBoxProcess = null
                    Result.failure(RuntimeException("sing-box started but SOCKS5 port not listening"))
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
                if (p.isAlive) {
                    p.destroyForcibly()
                }
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
        } catch (e: Exception) {
            false
        }
    }

    // ==================== build config ====================

    private fun buildConfig(profile: ServerProfile, listenPort: Int, listenHost: String): String {
        val config = JSONObject()

        config.put("log", JSONObject().apply {
            put("level", if (debugLogging) "debug" else "warn")
            put("timestamp", true)
        })

        config.put("dns", buildDnsConfig())

        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", listenHost)
                put("listen_port", listenPort)
                put("sniff", true)
                put("sniff_override_destination", true)
            })
        })

        val outbound = when (profile.tunnelType) {
            TunnelType.VLESS -> buildVlessOutbound(profile)
            TunnelType.TROJAN -> buildTrojanOutbound(profile)
            TunnelType.HYSTERIA2 -> buildHysteria2Outbound(profile)
            TunnelType.SHADOWSOCKS -> buildShadowsocksOutbound(profile)
            else -> throw IllegalArgumentException("Unsupported tunnel type: ${profile.tunnelType}")
        }

        config.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })
        })

        config.put("route", buildRouteConfig())

        return config.toString(2)
    }

    private fun buildDnsConfig(): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("address", "https://dns.google/dns-query")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "remote-dns2")
                    put("address", "https://cloudflare-dns.com/dns-query")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns")
                    put("address", "local")
                    put("detour", "direct")
                })
            })
            put("final", "remote-dns")
        }
    }

    private fun buildRouteConfig(): JSONObject {
        return JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
            })
            put("final", "proxy")
        }
    }

    // ==================== outbounds ====================

    private fun buildVlessOutbound(profile: ServerProfile): JSONObject {
        val address = profile.lastScannedIp.ifBlank { profile.vlessAddress }
        return JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", address)
            put("server_port", profile.vlessPort)
            put("uuid", profile.vlessUuid)

            if (profile.vlessFlow.isNotBlank()) put("flow", profile.vlessFlow)
            if (profile.vlessSecurity != "none") put("tls", buildVlessTls(profile))

            if (profile.vlessNetwork != "tcp") {
                put("transport", buildTransport(
                    profile.vlessNetwork,
                    profile.vlessWsPath,
                    profile.vlessWsHost,
                    profile.vlessGrpcServiceName
                ))
            }

            if (profile.proxyMux) put("multiplex", buildMux(profile))
        }
    }

    private fun buildVlessTls(profile: ServerProfile): JSONObject {
        return JSONObject().apply {
            put("enabled", true)

            val sni = profile.vlessSni.ifBlank { profile.vlessAddress }
            put("server_name", sni)

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
                val sni = profile.trojanSni.ifBlank { profile.trojanAddress }
                put("server_name", sni)
                put("insecure", profile.trojanAllowInsecure)

                if (profile.trojanFingerprint.isNotBlank()) {
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", profile.trojanFingerprint)
                    })
                }
            })

            if (profile.trojanNetwork != "tcp") {
                put("transport", buildTransport(
                    profile.trojanNetwork,
                    profile.trojanWsPath,
                    profile.trojanWsHost,
                    profile.trojanGrpcServiceName
                ))
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
                val sni = profile.hy2Sni.ifBlank { profile.hy2Address }
                put("server_name", sni)
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

    private fun buildTransport(
        network: String,
        wsPath: String,
        wsHost: String,
        grpcServiceName: String
    ): JSONObject {
        return JSONObject().apply {
            when (network) {
                "ws" -> {
                    put("type", "ws")
                    if (wsPath.isNotBlank()) put("path", wsPath)
                    if (wsHost.isNotBlank()) {
                        put("headers", JSONObject().apply { put("Host", wsHost) })
                    }
                    put("max_early_data", 2048)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
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
            val name = if (nameIndex >= 0) {
                java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8")
            } else "VLESS"

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
                name = name,
                tunnelType = TunnelType.VLESS,
                vlessUuid = uuid,
                vlessAddress = address,
                vlessPort = port,
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VLESS URI: ${e.message}")
            return null
        }
    }

    fun parseTrojanUri(uri: String): ServerProfile? {
        try {
            if (!uri.startsWith("trojan://")) return null

            val withoutScheme = uri.removePrefix("trojan://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) {
                java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8")
            } else "Trojan"

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
                name = name,
                tunnelType = TunnelType.TROJAN,
                trojanPassword = password,
                trojanAddress = address,
                trojanPort = port,
                trojanSni = params["sni"] ?: "",
                trojanFingerprint = params["fp"] ?: "chrome",
                trojanNetwork = params["type"] ?: "tcp",
                trojanWsPath = java.net.URLDecoder.decode(params["path"] ?: "", "UTF-8"),
                trojanWsHost = params["host"] ?: "",
                trojanGrpcServiceName = params["serviceName"] ?: "",
                trojanAllowInsecure = params["allowInsecure"] == "1",
                domain = address
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Trojan URI: ${e.message}")
            return null
        }
    }

    fun parseHysteria2Uri(uri: String): ServerProfile? {
        try {
            val normalized = uri.replace("hy2://", "hysteria2://")
            if (!normalized.startsWith("hysteria2://")) return null

            val withoutScheme = normalized.removePrefix("hysteria2://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) {
                java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8")
            } else "Hysteria2"

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
                name = name,
                tunnelType = TunnelType.HYSTERIA2,
                hy2Password = password,
                hy2Address = address,
                hy2Port = port,
                hy2Sni = params["sni"] ?: "",
                hy2AllowInsecure = params["insecure"] == "1",
                hy2Obfs = params["obfs"] ?: "",
                hy2ObfsPassword = params["obfs-password"] ?: "",
                domain = address
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Hysteria2 URI: ${e.message}")
            return null
        }
    }

    fun parseShadowsocksUri(uri: String): ServerProfile? {
        try {
            if (!uri.startsWith("ss://")) return null

            val withoutScheme = uri.removePrefix("ss://")
            val nameIndex = withoutScheme.lastIndexOf('#')
            val name = if (nameIndex >= 0) {
                java.net.URLDecoder.decode(withoutScheme.substring(nameIndex + 1), "UTF-8")
            } else "Shadowsocks"

            val mainPart = if (nameIndex >= 0) withoutScheme.substring(0, nameIndex) else withoutScheme

            val atIndex = mainPart.indexOf('@')
            val method: String
            val password: String
            val address: String
            val port: Int

            if (atIndex >= 0) {
                val userInfo = try {
                    String(android.util.Base64.decode(mainPart.substring(0, atIndex), android.util.Base64.NO_WRAP))
                } catch (e: Exception) {
                    mainPart.substring(0, atIndex)
                }
                val colonIndex = userInfo.indexOf(':')
                if (colonIndex < 0) return null
                method = userInfo.substring(0, colonIndex)
                password = userInfo.substring(colonIndex + 1)

                val hostPortStr = mainPart.substring(atIndex + 1)
                val parsed = parseHostPort(hostPortStr)
                address = parsed.first
                port = parsed.second
            } else {
                val decoded = try {
                    String(android.util.Base64.decode(mainPart, android.util.Base64.NO_WRAP))
                } catch (e: Exception) {
                    return null
                }
                val decodedAtIndex = decoded.indexOf('@')
                if (decodedAtIndex < 0) return null

                val userInfo = decoded.substring(0, decodedAtIndex)
                val colonIndex = userInfo.indexOf(':')
                if (colonIndex < 0) return null
                method = userInfo.substring(0, colonIndex)
                password = userInfo.substring(colonIndex + 1)

                val parsed = parseHostPort(decoded.substring(decodedAtIndex + 1))
                address = parsed.first
                port = parsed.second
            }

            return ServerProfile(
                name = name,
                tunnelType = TunnelType.SHADOWSOCKS,
                ssAddress = address,
                ssPort = port,
                ssMethod = method,
                ssPassword = password,
                domain = address
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Shadowsocks URI: ${e.message}")
            return null
        }
    }

    fun parseProxyUri(uri: String): ServerProfile? {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("vless://") -> parseVlessUri(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojanUri(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2Uri(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocksUri(trimmed)
            else -> null
        }
    }

    // ==================== Helpers ====================

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        return if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            val host = hostPort.substring(1, closeBracket)
            val port = hostPort.substring(closeBracket + 2).toIntOrNull() ?: 443
            Pair(host, port)
        } else {
            val lastColon = hostPort.lastIndexOf(':')
            if (lastColon >= 0) {
                val host = hostPort.substring(0, lastColon)
                val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 443
                Pair(host, port)
            } else {
                Pair(hostPort, 443)
            }
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { param ->
            val eqIndex = param.indexOf('=')
            if (eqIndex >= 0) {
                val key = param.substring(0, eqIndex)
                val value = param.substring(eqIndex + 1)
                key to value
            } else null
        }.toMap()
    }
}