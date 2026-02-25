package app.slipnet.presentation.profiles

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.slipnet.domain.model.CongestionControl
import app.slipnet.domain.model.DnsResolver
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.SshAuthType
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.TunnelType
import app.slipnet.domain.usecase.GetProfileByIdUseCase
import app.slipnet.domain.usecase.SaveProfileUseCase
import app.slipnet.domain.usecase.SetActiveProfileUseCase
import app.slipnet.service.VpnConnectionManager
import app.slipnet.tunnel.DOH_SERVERS
import app.slipnet.tunnel.DohBridge
import app.slipnet.tunnel.DohServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class DohTestResult(
    val name: String,
    val url: String,
    val latencyMs: Long? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = latencyMs != null && error == null
}

/**
 * UI-only bridge type selector. Not persisted — the actual bridge lines are stored
 * in torBridgeLines and transport is auto-detected at runtime.
 */
enum class TorBridgeType(val displayName: String) {
    SNOWFLAKE("Snowflake"),
    DIRECT("Direct"),
    SNOWFLAKE_AMP("Snowflake (AMP)"),
    OBFS4("obfs4"),
    MEEK_AZURE("Meek"),
    SMART("Smart Connect"),
    CUSTOM("Custom")
}

data class EditProfileUiState(
    val profileId: Long? = null,
    val name: String = "",
    val domain: String = "",
    val resolvers: String = "",
    val authoritativeMode: Boolean = false,
    val keepAliveInterval: String = "200",
    val congestionControl: CongestionControl = CongestionControl.BBR,
    val gsoEnabled: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val tunnelType: TunnelType = TunnelType.DNSTT,
    val dnsttPublicKey: String = "",
    val dnsttPublicKeyError: String? = null,
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshPort: String = "22",
    val sshUsernameError: String? = null,
    val sshPasswordError: String? = null,
    val sshPortError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isAutoDetecting: Boolean = false,
    val saveSuccess: Boolean = false,
    val showRestartVpnMessage: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val domainError: String? = null,
    val resolversError: String? = null,
    val dohUrl: String = "",
    val dohUrlError: String? = null,
    val isTestingDoh: Boolean = false,
    val showDohTestDialog: Boolean = false,
    val dohTestResults: List<DohTestResult> = emptyList(),
    val dnsTransport: DnsTransport = DnsTransport.UDP,
    val customDohUrls: String = "",
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",
    val sshKeyPassphrase: String = "",
    val sshPrivateKeyError: String? = null,
    val torBridgeType: TorBridgeType = TorBridgeType.SNOWFLAKE,
    val torBridgeLines: String = "",
    val torBridgeLinesError: String? = null,
    val isRequestingBridges: Boolean = false,
    val isAskingTor: Boolean = false,
    val dnsttAuthoritative: Boolean = false,
    val naivePort: String = "443",
    val naiveUsername: String = "",
    val naivePassword: String = "",
    val naivePortError: String? = null,
    val naiveUsernameError: String? = null,
    val naivePasswordError: String? = null,
    val sortOrder: Int = 0,
    // ── sing-box specific display info ──
    val singBoxServerInfo: String = "",
) {
    val useSsh: Boolean
        get() = tunnelType == TunnelType.SSH || tunnelType == TunnelType.DNSTT_SSH || tunnelType == TunnelType.SLIPSTREAM_SSH || tunnelType == TunnelType.NAIVE_SSH

    val isDnsttBased: Boolean
        get() = tunnelType == TunnelType.DNSTT || tunnelType == TunnelType.DNSTT_SSH

    val isSlipstreamBased: Boolean
        get() = tunnelType == TunnelType.SLIPSTREAM || tunnelType == TunnelType.SLIPSTREAM_SSH

    val isSshOnly: Boolean
        get() = tunnelType == TunnelType.SSH

    val isDoh: Boolean
        get() = tunnelType == TunnelType.DOH

    val isSnowflake: Boolean
        get() = tunnelType == TunnelType.SNOWFLAKE

    val isNaiveSsh: Boolean
        get() = tunnelType == TunnelType.NAIVE_SSH

    // ✅ sing-box protocols (VLESS, Trojan, Hysteria2, Shadowsocks)
    val isSingBox: Boolean
        get() = tunnelType == TunnelType.VLESS || tunnelType == TunnelType.TROJAN ||
                tunnelType == TunnelType.HYSTERIA2 || tunnelType == TunnelType.SHADOWSOCKS

    val showConnectionMethod: Boolean
        get() = !isSshOnly && !isDoh && !isSnowflake && !isNaiveSsh && !isSingBox
}

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val getProfileByIdUseCase: GetProfileByIdUseCase,
    private val saveProfileUseCase: SaveProfileUseCase,
    private val setActiveProfileUseCase: SetActiveProfileUseCase,
    private val connectionManager: VpnConnectionManager
) : ViewModel() {

    private val profileId: Long? = savedStateHandle.get<Long>("profileId")
    private val initialTunnelType: TunnelType = savedStateHandle.get<String>("tunnelType")
        ?.let { TunnelType.fromValue(it) } ?: TunnelType.DNSTT

    private val _uiState = MutableStateFlow(
        EditProfileUiState(profileId = profileId, tunnelType = initialTunnelType)
    )
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    // Store original profile for sing-box (needed for save without losing data)
    private var originalProfile: ServerProfile? = null

    init {
        if (profileId != null && profileId != 0L) {
            loadProfile(profileId)
        } else {
            autoFillResolver()
        }
    }

    private fun autoFillResolver() {
        viewModelScope.launch {
            val dns = withContext(Dispatchers.IO) { getSystemDnsServer() }
            if (dns != null) {
                _uiState.value = _uiState.value.copy(resolvers = "$dns:53")
            }
        }
    }

    private fun loadProfile(id: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val profile = getProfileByIdUseCase(id)
            if (profile != null) {
                // Store original profile for sing-box types
                originalProfile = profile

                // Build sing-box server info string
                val singBoxInfo = when (profile.tunnelType) {
                    TunnelType.VLESS -> "VLESS @ ${profile.vlessAddress}:${profile.vlessPort}" +
                            if (profile.vlessNetwork != "tcp") " (${profile.vlessNetwork})" else ""
                    TunnelType.TROJAN -> "Trojan @ ${profile.trojanAddress}:${profile.trojanPort}" +
                            if (profile.trojanNetwork != "tcp") " (${profile.trojanNetwork})" else ""
                    TunnelType.HYSTERIA2 -> "Hysteria2 @ ${profile.hy2Address}:${profile.hy2Port}"
                    TunnelType.SHADOWSOCKS -> "Shadowsocks @ ${profile.ssAddress}:${profile.ssPort} (${profile.ssMethod})"
                    else -> ""
                }

                _uiState.value = _uiState.value.copy(
                    profileId = profile.id,
                    name = profile.name,
                    domain = profile.domain,
                    resolvers = profile.resolvers.joinToString(",") { "${it.host}:${it.port}" },
                    authoritativeMode = profile.authoritativeMode,
                    keepAliveInterval = profile.keepAliveInterval.toString(),
                    congestionControl = profile.congestionControl,
                    gsoEnabled = profile.gsoEnabled,
                    socksUsername = profile.socksUsername ?: "",
                    socksPassword = profile.socksPassword ?: "",
                    tunnelType = profile.tunnelType,
                    dnsttPublicKey = profile.dnsttPublicKey,
                    sshUsername = profile.sshUsername,
                    sshPassword = profile.sshPassword,
                    sshPort = profile.sshPort.toString(),
                    dohUrl = profile.dohUrl,
                    dnsTransport = profile.dnsTransport,
                    sshAuthType = profile.sshAuthType,
                    sshPrivateKey = profile.sshPrivateKey,
                    sshKeyPassphrase = profile.sshKeyPassphrase,
                    torBridgeType = detectBridgeType(profile.torBridgeLines),
                    torBridgeLines = profile.torBridgeLines,
                    dnsttAuthoritative = profile.dnsttAuthoritative,
                    naivePort = profile.naivePort.toString(),
                    naiveUsername = profile.naiveUsername,
                    naivePassword = profile.naivePassword,
                    sortOrder = profile.sortOrder,
                    singBoxServerInfo = singBoxInfo,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Profile not found"
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name, nameError = null)
    }

    fun updateDomain(domain: String) {
        val error = if (domain.isNotBlank()) {
            validateDomain(domain.trim(), _uiState.value.tunnelType)
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(domain = domain, domainError = error)
    }

    fun updateResolvers(resolvers: String) {
        val error = if (resolvers.isNotBlank()) {
            validateResolvers(resolvers)
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(resolvers = resolvers, resolversError = error)
    }

    fun updateAuthoritativeMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(authoritativeMode = enabled)
    }

    fun updateKeepAliveInterval(interval: String) {
        _uiState.value = _uiState.value.copy(keepAliveInterval = interval)
    }

    fun updateCongestionControl(cc: CongestionControl) {
        _uiState.value = _uiState.value.copy(congestionControl = cc)
    }

    fun updateGsoEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(gsoEnabled = enabled)
    }

    fun updateSocksUsername(username: String) {
        _uiState.value = _uiState.value.copy(socksUsername = username)
    }

    fun updateSocksPassword(password: String) {
        _uiState.value = _uiState.value.copy(socksPassword = password)
    }

    fun setUseSsh(useSsh: Boolean) {
        val currentType = _uiState.value.tunnelType
        val newType = when {
            useSsh && (currentType == TunnelType.DNSTT || currentType == TunnelType.DNSTT_SSH) -> TunnelType.DNSTT_SSH
            useSsh && (currentType == TunnelType.SLIPSTREAM || currentType == TunnelType.SLIPSTREAM_SSH) -> TunnelType.SLIPSTREAM_SSH
            !useSsh && (currentType == TunnelType.DNSTT || currentType == TunnelType.DNSTT_SSH) -> TunnelType.DNSTT
            !useSsh && (currentType == TunnelType.SLIPSTREAM || currentType == TunnelType.SLIPSTREAM_SSH) -> TunnelType.SLIPSTREAM
            else -> currentType
        }
        _uiState.value = _uiState.value.copy(
            tunnelType = newType,
            sshUsernameError = null,
            sshPasswordError = null,
            sshPortError = null
        )
    }

    fun updateDnsttPublicKey(publicKey: String) {
        val error = if (publicKey.isNotBlank()) {
            validateDnsttPublicKey(publicKey)
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(dnsttPublicKey = publicKey, dnsttPublicKeyError = error)
    }

    fun updateSshUsername(username: String) {
        _uiState.value = _uiState.value.copy(sshUsername = username, sshUsernameError = null)
    }

    fun updateSshPassword(password: String) {
        _uiState.value = _uiState.value.copy(sshPassword = password, sshPasswordError = null)
    }

    fun updateSshPort(port: String) {
        _uiState.value = _uiState.value.copy(sshPort = port, sshPortError = null)
    }

    fun updateDnsTransport(transport: DnsTransport) {
        _uiState.value = _uiState.value.copy(dnsTransport = transport)
    }

    fun updateDnsttAuthoritative(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(dnsttAuthoritative = enabled)
    }

    fun updateNaivePort(port: String) {
        _uiState.value = _uiState.value.copy(naivePort = port, naivePortError = null)
    }

    fun updateNaiveUsername(username: String) {
        _uiState.value = _uiState.value.copy(naiveUsername = username, naiveUsernameError = null)
    }

    fun updateNaivePassword(password: String) {
        _uiState.value = _uiState.value.copy(naivePassword = password, naivePasswordError = null)
    }

    fun updateDohUrl(url: String) {
        _uiState.value = _uiState.value.copy(dohUrl = url, dohUrlError = null)
    }

    fun updateCustomDohUrls(text: String) {
        _uiState.value = _uiState.value.copy(customDohUrls = text)
    }

    fun updateSshAuthType(type: SshAuthType) {
        _uiState.value = _uiState.value.copy(
            sshAuthType = type,
            sshPasswordError = null,
            sshPrivateKeyError = null
        )
    }

    fun updateSshPrivateKey(key: String) {
        _uiState.value = _uiState.value.copy(sshPrivateKey = key, sshPrivateKeyError = null)
    }

    fun updateSshKeyPassphrase(passphrase: String) {
        _uiState.value = _uiState.value.copy(sshKeyPassphrase = passphrase)
    }

    fun updateTorBridgeLines(lines: String) {
        _uiState.value = _uiState.value.copy(
            torBridgeLines = lines,
            torBridgeLinesError = null,
            torBridgeType = TorBridgeType.CUSTOM
        )
    }

    fun selectTorBridgeType(type: TorBridgeType) {
        val lines = when (type) {
            TorBridgeType.SNOWFLAKE -> ""
            TorBridgeType.DIRECT -> "DIRECT"
            TorBridgeType.SNOWFLAKE_AMP -> "SNOWFLAKE_AMP"
            TorBridgeType.OBFS4 -> DEFAULT_OBFS4_BRIDGES
            TorBridgeType.MEEK_AZURE -> DEFAULT_MEEK_BRIDGE
            TorBridgeType.SMART -> "SMART"
            TorBridgeType.CUSTOM -> _uiState.value.torBridgeLines
        }
        _uiState.value = _uiState.value.copy(
            torBridgeType = type,
            torBridgeLines = lines,
            torBridgeLinesError = null
        )
    }

    fun requestBridges() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRequestingBridges = true)
            try {
                val bridges = withContext(Dispatchers.IO) { fetchBridgesFromMoat() }
                if (bridges.isNotEmpty()) {
                    val lines = bridges.joinToString("\n")
                    _uiState.value = _uiState.value.copy(
                        isRequestingBridges = false,
                        torBridgeType = TorBridgeType.CUSTOM,
                        torBridgeLines = lines,
                        torBridgeLinesError = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isRequestingBridges = false,
                        error = "No bridges returned. Try Telegram or email instead."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRequestingBridges = false,
                    error = "Could not fetch bridges: ${e.message ?: "connection failed"}. Try Telegram or email instead."
                )
            }
        }
    }

    fun askTor() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAskingTor = true)
            try {
                val result = withContext(Dispatchers.IO) { fetchCircumventionSettings() }
                if (result != null) {
                    val (bridgeType, bridgeLines) = result
                    _uiState.value = _uiState.value.copy(
                        isAskingTor = false,
                        torBridgeType = bridgeType,
                        torBridgeLines = bridgeLines,
                        torBridgeLinesError = null,
                        error = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isAskingTor = false,
                        torBridgeType = TorBridgeType.SNOWFLAKE,
                        torBridgeLines = "",
                        torBridgeLinesError = null,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAskingTor = false,
                    torBridgeType = TorBridgeType.SNOWFLAKE,
                    torBridgeLines = "",
                    torBridgeLinesError = null,
                    error = null
                )
            }
        }
    }

    private fun fetchCircumventionSettings(): Pair<TorBridgeType, String>? {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/vnd.api+json".toMediaType()
        val requestJson = """{"country":"ir","transports":["webtunnel","snowflake","obfs4","meek_lite"]}"""

        try {
            val body = requestJson.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$MOAT_BASE_URL/$MOAT_SETTINGS_PATH")
                .post(body)
                .header("Content-Type", "application/vnd.api+json")
                .build()
            client.newCall(request).execute().use { response ->
                val result = parseSettingsResponse(response)
                if (result != null) return result
            }
        } catch (_: Exception) {}

        for (front in MOAT_FRONT_DOMAINS) {
            try {
                val body = requestJson.toRequestBody(jsonMediaType)
                val frontedRequest = Request.Builder()
                    .url("https://$front/moat/$MOAT_SETTINGS_PATH")
                    .post(body)
                    .header("Host", MOAT_HOST)
                    .header("Content-Type", "application/vnd.api+json")
                    .build()
                client.newCall(frontedRequest).execute().use { response ->
                    val result = parseSettingsResponse(response)
                    if (result != null) return result
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseSettingsResponse(response: okhttp3.Response): Pair<TorBridgeType, String>? {
        if (response.code == 404) {
            return Pair(TorBridgeType.DIRECT, "DIRECT")
        }

        if (!response.isSuccessful) return null

        val bodyStr = response.body?.string() ?: return null
        val json = JSONObject(bodyStr)
        val settings = json.optJSONArray("settings") ?: return null
        if (settings.length() == 0) {
            return Pair(TorBridgeType.DIRECT, "DIRECT")
        }

        val first = settings.getJSONObject(0)
        val bridges = first.optJSONObject("bridges") ?: return null
        val type = bridges.optString("type", "")
        val source = bridges.optString("source", "")
        val bridgeStrings = bridges.optJSONArray("bridge_strings")

        return when (type) {
            "snowflake" -> Pair(TorBridgeType.SNOWFLAKE, "")
            "obfs4", "webtunnel" -> {
                if (source == "bridgedb" && bridgeStrings != null && bridgeStrings.length() > 0) {
                    val lines = (0 until bridgeStrings.length()).joinToString("\n") {
                        bridgeStrings.getString(it)
                    }
                    Pair(TorBridgeType.CUSTOM, lines)
                } else if (type == "obfs4") {
                    Pair(TorBridgeType.OBFS4, DEFAULT_OBFS4_BRIDGES)
                } else {
                    null
                }
            }
            "meek_lite" -> Pair(TorBridgeType.MEEK_AZURE, DEFAULT_MEEK_BRIDGE)
            else -> null
        }
    }

    private fun fetchBridgesFromMoat(): List<String> {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val allBridges = mutableListOf<String>()

        val settingsBridges = fetchSettingsBridgeLines(client)
        if (settingsBridges != null) allBridges.addAll(settingsBridges)

        val builtinBridges = fetchBuiltinBridgeLines(client)
        if (builtinBridges != null) allBridges.addAll(builtinBridges)

        if (allBridges.isEmpty()) {
            return DEFAULT_OBFS4_BRIDGES.lines().filter { it.isNotBlank() }
        }
        return allBridges
    }

    private fun fetchBuiltinBridgeLines(client: OkHttpClient): List<String>? {
        try {
            val request = Request.Builder()
                .url("$MOAT_BASE_URL/$MOAT_BUILTIN_PATH")
                .get()
                .build()
            val bridges = executeBuiltinRequest(client, request)
            if (bridges.isNotEmpty()) return bridges
        } catch (_: Exception) {}

        for (front in MOAT_FRONT_DOMAINS) {
            try {
                val request = Request.Builder()
                    .url("https://$front/moat/$MOAT_BUILTIN_PATH")
                    .header("Host", MOAT_HOST)
                    .get()
                    .build()
                val bridges = executeBuiltinRequest(client, request)
                if (bridges.isNotEmpty()) return bridges
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fetchSettingsBridgeLines(client: OkHttpClient): List<String>? {
        val jsonMediaType = "application/vnd.api+json".toMediaType()
        val requestJson = """{"country":"ir","transports":["webtunnel"]}"""

        try {
            val body = requestJson.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$MOAT_BASE_URL/$MOAT_SETTINGS_PATH")
                .post(body)
                .header("Content-Type", "application/vnd.api+json")
                .build()
            val lines = parseSettingsBridgeLines(client, request)
            if (lines != null) return lines
        } catch (_: Exception) {}

        for (front in MOAT_FRONT_DOMAINS) {
            try {
                val body = requestJson.toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url("https://$front/moat/$MOAT_SETTINGS_PATH")
                    .post(body)
                    .header("Host", MOAT_HOST)
                    .header("Content-Type", "application/vnd.api+json")
                    .build()
                val lines = parseSettingsBridgeLines(client, request)
                if (lines != null) return lines
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseSettingsBridgeLines(client: OkHttpClient, request: Request): List<String>? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.code == 404) return null
            val json = JSONObject(response.body?.string() ?: return null)
            val settings = json.optJSONArray("settings") ?: return null
            if (settings.length() == 0) return null

            val first = settings.getJSONObject(0)
            val bridges = first.optJSONObject("bridges") ?: return null
            val source = bridges.optString("source", "")
            val bridgeStrings = bridges.optJSONArray("bridge_strings")

            if (source == "bridgedb" && bridgeStrings != null && bridgeStrings.length() > 0) {
                return (0 until minOf(bridgeStrings.length(), BRIDGES_PER_TYPE)).map {
                    bridgeStrings.getString(it)
                }
            }
            return null
        }
    }

    private fun executeBuiltinRequest(client: OkHttpClient, request: Request): List<String> {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string() ?: throw Exception("Empty response"))

            val bridges = mutableListOf<String>()
            for (transport in listOf("webtunnel", "obfs4", "meek-azure", "meek")) {
                val arr = json.optJSONArray(transport)
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until minOf(arr.length(), BRIDGES_PER_TYPE)) {
                        bridges.add(arr.getString(i))
                    }
                }
            }
            return bridges
        }
    }

    fun selectDohPreset(preset: DohServer) {
        _uiState.value = _uiState.value.copy(
            dohUrl = preset.url,
            dohUrlError = null
        )
    }

    private fun parseCustomDohUrls(): List<DohServer> {
        val state = _uiState.value
        val presetUrls = DOH_SERVERS.map { it.url }.toSet()
        return state.customDohUrls
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .filter { it !in presetUrls }
            .distinctBy { it }
            .map { url ->
                val host = try {
                    java.net.URL(url).host
                } catch (_: Exception) {
                    url
                }
                DohServer(name = host, url = url)
            }
    }

    fun testDohServers() {
        viewModelScope.launch {
            val customServers = parseCustomDohUrls()
            val allServers = DOH_SERVERS + customServers

            _uiState.value = _uiState.value.copy(
                isTestingDoh = true,
                showDohTestDialog = true,
                dohTestResults = allServers.map { DohTestResult(it.name, it.url) }
            )

            val client = DohBridge.createHttpClient()
            val completed = java.util.concurrent.ConcurrentHashMap<String, DohTestResult>()

            val jobs = allServers.map { preset ->
                launch(Dispatchers.IO) {
                    val result = testSingleDohServer(preset, client)
                    completed[result.url] = result

                    val snapshot = completed.values.toList()
                    val pending = allServers
                        .filter { p -> !completed.containsKey(p.url) }
                        .map { DohTestResult(it.name, it.url) }
                    _uiState.value = _uiState.value.copy(
                        dohTestResults = sortTestResults(snapshot + pending)
                    )
                }
            }

            jobs.joinAll()

            withContext(Dispatchers.IO) {
                client.connectionPool.evictAll()
            }

            _uiState.value = _uiState.value.copy(
                isTestingDoh = false,
                dohTestResults = sortTestResults(completed.values.toList())
            )
        }
    }

    private fun sortTestResults(results: List<DohTestResult>): List<DohTestResult> {
        return results.sortedWith(
            compareBy<DohTestResult> {
                when {
                    it.isSuccess -> 0
                    it.error != null -> 1
                    else -> 2
                }
            }.thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )
    }

    fun dismissDohTestDialog() {
        _uiState.value = _uiState.value.copy(showDohTestDialog = false)
    }

    fun selectDohTestResult(result: DohTestResult) {
        _uiState.value = _uiState.value.copy(
            dohUrl = result.url,
            dohUrlError = null,
            showDohTestDialog = false
        )
    }

    private fun testSingleDohServer(preset: DohServer, client: okhttp3.OkHttpClient): DohTestResult {
        return try {
            val dnsQuery = buildDnsQuery("example.com")
            val startTime = System.currentTimeMillis()

            val body = dnsQuery.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url(preset.url)
                .post(body)
                .header("Accept", "application/dns-message")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                    val latency = System.currentTimeMillis() - startTime
                    DohTestResult(preset.name, preset.url, latencyMs = latency)
                } else {
                    DohTestResult(preset.name, preset.url, error = "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            val error = when (e) {
                is SocketTimeoutException -> "Timeout"
                is ConnectException -> "Connection refused"
                is UnknownHostException -> "DNS lookup failed"
                is SSLException -> "TLS error"
                else -> {
                    val msg = e.message ?: "Connection failed"
                    if (msg.contains("/")) msg.substringAfterLast("/").let {
                        if (it.isBlank()) "Unreachable" else it
                    } else msg
                }
            }
            DohTestResult(preset.name, preset.url, error = error)
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00); out.write(0x01)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        for (label in domain.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    fun autoDetectResolver() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAutoDetecting = true)
            try {
                val resolverIp = withContext(Dispatchers.IO) {
                    getSystemDnsServer()
                }

                if (resolverIp != null) {
                    updateResolvers("$resolverIp:53")
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Could not detect DNS server"
                    )
                }
                _uiState.value = _uiState.value.copy(isAutoDetecting = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAutoDetecting = false,
                    error = "Auto-detect failed: ${e.message}"
                )
            }
        }
    }

    private fun getSystemDnsServer(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.dnsServers.firstOrNull()?.hostAddress
    }


    fun save() {
        val state = _uiState.value

        var hasError = false

        if (state.name.isBlank()) {
            _uiState.value = _uiState.value.copy(nameError = "Name is required")
            hasError = true
        }

        // ✅ Skip domain/resolver validation for sing-box protocols
        if (state.isSingBox) {
            // For sing-box, only name is required - server info comes from original profile
            if (hasError) return

            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isSaving = true)

                try {
                    // Use original profile and just update the name
                    val original = originalProfile
                    if (original == null) {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            error = "Original profile not found"
                        )
                        return@launch
                    }

                    val profile = original.copy(
                        name = state.name.trim(),
                        sortOrder = state.sortOrder
                    )

                    val savedId = saveProfileUseCase(profile)
                    setActiveProfileUseCase(savedId)

                    val connState = connectionManager.connectionState.value
                    val isVpnActive = connState is ConnectionState.Connected ||
                            connState is ConnectionState.Connecting

                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        saveSuccess = true,
                        showRestartVpnMessage = isVpnActive
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to save profile"
                    )
                }
            }
            return
        }

        // Non-sing-box validation continues as before...
        if (state.tunnelType != TunnelType.DOH && state.tunnelType != TunnelType.SNOWFLAKE && state.domain.isBlank()) {
            _uiState.value = _uiState.value.copy(domainError = "Domain is required")
            hasError = true
        } else if (state.tunnelType != TunnelType.DOH && state.tunnelType != TunnelType.SNOWFLAKE && state.domain.isNotBlank()) {
            val domainError = validateDomain(state.domain.trim(), state.tunnelType)
            if (domainError != null) {
                _uiState.value = _uiState.value.copy(domainError = domainError)
                hasError = true
            }
        }

        val needsDohUrl = state.tunnelType == TunnelType.DOH ||
                (state.isDnsttBased && state.dnsTransport == DnsTransport.DOH)
        if (needsDohUrl) {
            if (state.dohUrl.isBlank()) {
                _uiState.value = _uiState.value.copy(dohUrlError = "DoH server URL is required")
                hasError = true
            } else if (!state.dohUrl.startsWith("https://")) {
                _uiState.value = _uiState.value.copy(dohUrlError = "URL must start with https://")
                hasError = true
            }
        }

        val skipResolvers = state.tunnelType == TunnelType.SSH || state.tunnelType == TunnelType.DOH ||
                state.tunnelType == TunnelType.SNOWFLAKE || state.tunnelType == TunnelType.NAIVE_SSH ||
                (state.isDnsttBased && state.dnsTransport == DnsTransport.DOH)
        if (!skipResolvers) {
            if (state.resolvers.isBlank()) {
                _uiState.value = _uiState.value.copy(resolversError = "At least one resolver is required")
                hasError = true
            } else {
                val resolversError = validateResolvers(state.resolvers)
                if (resolversError != null) {
                    _uiState.value = _uiState.value.copy(resolversError = resolversError)
                    hasError = true
                }
            }
        }

        if (state.tunnelType == TunnelType.DNSTT || state.tunnelType == TunnelType.DNSTT_SSH) {
            val publicKeyError = validateDnsttPublicKey(state.dnsttPublicKey)
            if (publicKeyError != null) {
                _uiState.value = _uiState.value.copy(dnsttPublicKeyError = publicKeyError)
                hasError = true
            }
        }

        if (state.tunnelType == TunnelType.SNOWFLAKE && state.torBridgeType == TorBridgeType.CUSTOM) {
            if (state.torBridgeLines.isBlank()) {
                _uiState.value = _uiState.value.copy(torBridgeLinesError = "Bridge lines are required")
                hasError = true
            }
        }

        if (state.tunnelType == TunnelType.NAIVE_SSH) {
            val naivePort = state.naivePort.toIntOrNull()
            if (naivePort == null || naivePort !in 1..65535) {
                _uiState.value = _uiState.value.copy(naivePortError = "Port must be between 1 and 65535")
                hasError = true
            }
            if (state.naiveUsername.isBlank()) {
                _uiState.value = _uiState.value.copy(naiveUsernameError = "Proxy username is required")
                hasError = true
            }
            if (state.naivePassword.isBlank()) {
                _uiState.value = _uiState.value.copy(naivePasswordError = "Proxy password is required")
                hasError = true
            }
        }

        if (state.tunnelType == TunnelType.SSH || state.tunnelType == TunnelType.DNSTT_SSH || state.tunnelType == TunnelType.SLIPSTREAM_SSH || state.tunnelType == TunnelType.NAIVE_SSH) {
            if (state.sshUsername.isBlank()) {
                _uiState.value = _uiState.value.copy(sshUsernameError = "SSH username is required")
                hasError = true
            }
            if (state.sshAuthType == SshAuthType.PASSWORD) {
                if (state.sshPassword.isBlank()) {
                    _uiState.value = _uiState.value.copy(sshPasswordError = "SSH password is required")
                    hasError = true
                }
            } else {
                if (state.sshPrivateKey.isBlank()) {
                    _uiState.value = _uiState.value.copy(sshPrivateKeyError = "SSH private key is required")
                    hasError = true
                } else if (!state.sshPrivateKey.trimStart().startsWith("-----BEGIN")) {
                    _uiState.value = _uiState.value.copy(sshPrivateKeyError = "Invalid key format (must be PEM)")
                    hasError = true
                }
            }
        }

        if (state.tunnelType == TunnelType.SSH || state.tunnelType == TunnelType.DNSTT_SSH || state.tunnelType == TunnelType.SLIPSTREAM_SSH || state.tunnelType == TunnelType.NAIVE_SSH) {
            val sshPort = state.sshPort.toIntOrNull()
            if (sshPort == null || sshPort !in 1..65535) {
                _uiState.value = _uiState.value.copy(sshPortError = "Port must be between 1 and 65535")
                hasError = true
            }
        }

        if (hasError) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)

            try {
                val resolversList = parseResolvers(state.resolvers, state.authoritativeMode || state.dnsttAuthoritative)
                val keepAlive = state.keepAliveInterval.toIntOrNull() ?: 200

                val profile = ServerProfile(
                    id = state.profileId ?: 0,
                    name = state.name.trim(),
                    domain = state.domain.trim(),
                    resolvers = resolversList,
                    authoritativeMode = state.authoritativeMode,
                    keepAliveInterval = keepAlive,
                    congestionControl = state.congestionControl,
                    gsoEnabled = state.gsoEnabled,
                    socksUsername = state.socksUsername.takeIf { it.isNotBlank() },
                    socksPassword = state.socksPassword.takeIf { it.isNotBlank() },
                    tunnelType = state.tunnelType,
                    dnsttPublicKey = state.dnsttPublicKey.trim(),
                    sshUsername = if (state.useSsh) state.sshUsername.trim() else "",
                    sshPassword = if (state.useSsh && state.sshAuthType == SshAuthType.PASSWORD) state.sshPassword else "",
                    sshPort = state.sshPort.toIntOrNull() ?: 22,
                    sshHost = "127.0.0.1",
                    dohUrl = if (state.isDoh || (state.isDnsttBased && state.dnsTransport == DnsTransport.DOH)) state.dohUrl.trim() else "",
                    dnsTransport = if (state.isDnsttBased) state.dnsTransport else DnsTransport.UDP,
                    sshAuthType = if (state.useSsh) state.sshAuthType else SshAuthType.PASSWORD,
                    sshPrivateKey = if (state.useSsh && state.sshAuthType == SshAuthType.KEY) state.sshPrivateKey else "",
                    sshKeyPassphrase = if (state.useSsh && state.sshAuthType == SshAuthType.KEY) state.sshKeyPassphrase else "",
                    torBridgeLines = if (state.isSnowflake) state.torBridgeLines.trim() else "",
                    dnsttAuthoritative = if (state.isDnsttBased) state.dnsttAuthoritative else false,
                    naivePort = if (state.isNaiveSsh) (state.naivePort.toIntOrNull() ?: 443) else 443,
                    naiveUsername = if (state.isNaiveSsh) state.naiveUsername.trim() else "",
                    naivePassword = if (state.isNaiveSsh) state.naivePassword else "",
                    sortOrder = state.sortOrder,
                )

                val savedId = saveProfileUseCase(profile)
                setActiveProfileUseCase(savedId)

                val connState = connectionManager.connectionState.value
                val isVpnActive = connState is ConnectionState.Connected ||
                        connState is ConnectionState.Connecting

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    showRestartVpnMessage = isVpnActive
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message ?: "Failed to save profile"
                )
            }
        }
    }

    private fun parseResolvers(input: String, authoritativeMode: Boolean): List<DnsResolver> {
        return input.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { resolver ->
                val parts = resolver.split(":")
                DnsResolver(
                    host = parts[0].trim(),
                    port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 53,
                    authoritative = authoritativeMode
                )
            }
    }

    private fun validateDomain(domain: String, tunnelType: TunnelType): String? {
        val isDnsTunnel = tunnelType == TunnelType.DNSTT || tunnelType == TunnelType.DNSTT_SSH ||
                tunnelType == TunnelType.SLIPSTREAM || tunnelType == TunnelType.SLIPSTREAM_SSH

        if (!isDnsTunnel) return null

        if (domain.all { it.isDigit() || it == '.' } && isValidIPv4(domain)) {
            return "Domain must be a hostname, not an IP address"
        }

        if (!isValidDomainName(domain)) {
            return "Invalid domain format"
        }

        val labels = domain.split(".")
        if (labels.size < 2) {
            return "Domain must have at least two parts (e.g., t.example.com)"
        }

        return null
    }

    private fun validateDnsttPublicKey(publicKey: String): String? {
        val trimmed = publicKey.trim()

        if (trimmed.isBlank()) {
            return "Public key is required for DNSTT"
        }

        if (trimmed.length != 64) {
            return "Public key must be 64 hex characters (32 bytes), got ${trimmed.length}"
        }

        if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return "Public key must contain only hex characters (0-9, a-f)"
        }

        return null
    }

    private fun validateResolvers(input: String): String? {
        val resolvers = input.split(",").map { it.trim() }.filter { it.isNotBlank() }

        if (resolvers.isEmpty()) {
            return "At least one resolver is required"
        }

        if (resolvers.size > MAX_RESOLVERS) {
            return "Maximum $MAX_RESOLVERS resolvers allowed"
        }

        for (resolver in resolvers) {
            val error = validateSingleResolver(resolver)
            if (error != null) {
                return error
            }
        }

        return null
    }

    companion object {
        const val MAX_RESOLVERS = 8
        private const val BRIDGES_PER_TYPE = 2

        private const val MOAT_HOST = "bridges.torproject.org"
        private const val MOAT_BASE_URL = "https://$MOAT_HOST/moat"
        private const val MOAT_BUILTIN_PATH = "circumvention/builtin"
        private const val MOAT_SETTINGS_PATH = "circumvention/settings"
        private val MOAT_FRONT_DOMAINS = listOf(
            "ajax.aspnetcdn.com",
            "cdn.jsdelivr.net",
        )

        val DEFAULT_OBFS4_BRIDGES = """
            obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0
            obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0
            obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0
            obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0
            obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/mtAjp1BIC9vHKJ2FAEpGssTPw iat-mode=0
            obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1
            obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1
        """.trimIndent()

        const val DEFAULT_MEEK_BRIDGE = "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN"

        fun detectBridgeType(torBridgeLines: String): TorBridgeType {
            if (torBridgeLines.isBlank()) return TorBridgeType.SNOWFLAKE
            val trimmed = torBridgeLines.trim()
            return when (trimmed) {
                "DIRECT" -> TorBridgeType.DIRECT
                "SNOWFLAKE_AMP" -> TorBridgeType.SNOWFLAKE_AMP
                "SMART" -> TorBridgeType.SMART
                DEFAULT_OBFS4_BRIDGES -> TorBridgeType.OBFS4
                DEFAULT_MEEK_BRIDGE -> TorBridgeType.MEEK_AZURE
                else -> TorBridgeType.CUSTOM
            }
        }
    }

    private fun validateSingleResolver(resolver: String): String? {
        val trimmed = resolver.trim()

        if (trimmed.isBlank()) {
            return "Resolver cannot be empty"
        }

        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf("]")
            if (closeBracket == -1) {
                return "Invalid IPv6 format: missing closing bracket in '$trimmed'"
            }

            val ipv6 = trimmed.substring(1, closeBracket)
            if (!isValidIPv6(ipv6)) {
                return "Invalid IPv6 address: '$ipv6'"
            }

            if (closeBracket < trimmed.length - 1) {
                if (trimmed[closeBracket + 1] != ':') {
                    return "Invalid format: expected ':' after ']' in '$trimmed'"
                }
                val portStr = trimmed.substring(closeBracket + 2)
                val portError = validatePort(portStr, trimmed)
                if (portError != null) return portError
            }

            return null
        }

        val colonCount = trimmed.count { it == ':' }

        when {
            colonCount > 1 -> {
                if (!isValidIPv6(trimmed)) {
                    return "Invalid IPv6 address: '$trimmed'"
                }
            }
            colonCount == 1 -> {
                val parts = trimmed.split(":")
                val host = parts[0]
                val portStr = parts[1]

                val hostError = validateHost(host)
                if (hostError != null) return hostError

                val portError = validatePort(portStr, trimmed)
                if (portError != null) return portError
            }
            else -> {
                val hostError = validateHost(trimmed)
                if (hostError != null) return hostError
            }
        }

        return null
    }

    private fun validateHost(host: String): String? {
        if (host.isBlank()) {
            return "Host cannot be empty"
        }

        if (host.all { it.isDigit() || it == '.' }) {
            if (!isValidIPv4(host)) {
                return "Invalid IPv4 address: '$host'"
            }
            return null
        }

        if (host.first().isDigit() && host.count { it == '.' } == 3) {
            return "Invalid IPv4 address: '$host'"
        }

        if (!isValidDomainName(host)) {
            return "Invalid host: '$host'"
        }

        return null
    }

    private fun validatePort(portStr: String, context: String): String? {
        val port = portStr.toIntOrNull()
        if (port == null) {
            return "Invalid port number in '$context'"
        }
        if (port !in 1..65535) {
            return "Port must be between 1 and 65535 in '$context'"
        }
        return null
    }

    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255 && (part == "0" || !part.startsWith("0"))
        }
    }

    private fun isValidIPv6(ip: String): Boolean {
        val trimmed = ip.trim()

        if (trimmed.contains("::")) {
            val parts = trimmed.split("::")
            if (parts.size > 2) return false

            val left = if (parts[0].isEmpty()) emptyList() else parts[0].split(":")
            val right = if (parts.size < 2 || parts[1].isEmpty()) emptyList() else parts[1].split(":")

            if (left.size + right.size > 7) return false

            return (left + right).all { isValidIPv6Segment(it) }
        }

        val segments = trimmed.split(":")
        if (segments.size != 8) return false

        return segments.all { isValidIPv6Segment(it) }
    }

    private fun isValidIPv6Segment(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > 4) return false
        return segment.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun isValidDomainName(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false

        val labels = domain.split(".")
        if (labels.isEmpty()) return false

        return labels.all { label ->
            label.isNotEmpty() &&
                    label.length <= 63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
