package app.slipnet.presentation.scanner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.service.VpnConnectionManager
import app.slipnet.tunnel.CdnScanner
import app.slipnet.tunnel.SingBoxBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val connectionManager: VpnConnectionManager
) : ViewModel() {

    val connectionState = connectionManager.connectionState

    val scannerProfiles: StateFlow<List<ServerProfile>> = profileRepository.getAllProfiles()
        .map { profiles -> profiles.filter { it.isScannerProfile } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val scanState = CdnScanner.scanState
    val scanResults = CdnScanner.scanResults
    val bestIp = CdnScanner.bestIp

    private val _selectedProfile = MutableStateFlow<ServerProfile?>(null)
    val selectedProfile: StateFlow<ServerProfile?> = _selectedProfile.asStateFlow()

    private val _scannerSettings = MutableStateFlow(CdnScanner.ScannerSettings())
    val scannerSettings: StateFlow<CdnScanner.ScannerSettings> = _scannerSettings.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showDetailsDialog = MutableStateFlow<ServerProfile?>(null)
    val showDetailsDialog: StateFlow<ServerProfile?> = _showDetailsDialog.asStateFlow()

    fun importConfig(uri: String): Boolean {
        val profile = SingBoxBridge.parseProxyUri(uri)
        if (profile != null) {
            val scannerProfile = profile.copy(
                isScannerProfile = true,
                name = if (profile.name.isBlank()) "Scanner Config" else profile.name
            )
            viewModelScope.launch {
                profileRepository.saveProfile(scannerProfile)
            }
            return true
        }
        return false
    }

    // ✅ FIX 2: اضافه کردن isScannerProfile = true
    suspend fun importSubscription(url: String): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) return@withContext Pair(0, 1)
                val body = response.body?.string() ?: return@withContext Pair(0, 1)

                val profiles = SingBoxBridge.parseSingBoxConfig(body)
                var success = 0
                var fail = 0

                for (profile in profiles) {
                    try {
                        // ✅ اضافه کردن isScannerProfile = true
                        val scannerProfile = profile.copy(isScannerProfile = true)
                        profileRepository.saveProfile(scannerProfile)
                        success++
                    } catch (e: Exception) { fail++ }
                }
                Pair(success, fail)
            } catch (e: Exception) {
                Pair(0, 1)
            }
        }
    }

    fun startScan(profile: ServerProfile) {
        _selectedProfile.value = profile
        val settings = _scannerSettings.value
        val ranges = CdnScanner.loadRanges(context, settings.rangeSource)
        CdnScanner.startScan(viewModelScope, settings.copy(customRanges = ranges))
    }

    fun stopScan() = CdnScanner.stopScan()

    fun connectWithBestIp(profile: ServerProfile) {
        val best = bestIp.value ?: return
        val updated = profile.copy(
            lastScannedIp = best.ip,
            lastScanTime = System.currentTimeMillis()
        )
        viewModelScope.launch {
            profileRepository.updateProfile(updated)
            connectionManager.connect(updated)
        }
    }

    fun connectWithAutoScan(profile: ServerProfile) {
        viewModelScope.launch {
            _selectedProfile.value = profile

            val currentIp = getProfileAddress(profile)
            val port = getProfilePort(profile)
            val settings = _scannerSettings.value
            
            val isCurrentIpGood = CdnScanner.testCurrentIp(currentIp, port, settings.maxLatency)

            if (isCurrentIpGood) {
                connectionManager.connect(profile)
            } else {
                val ranges = CdnScanner.loadRanges(context, settings.rangeSource)
                val newBestIp = CdnScanner.autoFindBestIp(
                    currentIp = currentIp,
                    port = port,
                    settings = settings.copy(customRanges = ranges),
                    scope = viewModelScope
                )

                if (newBestIp != null) {
                    val updated = profile.copy(
                        lastScannedIp = newBestIp,
                        lastScanTime = System.currentTimeMillis()
                    )
                    profileRepository.updateProfile(updated)
                    connectionManager.connect(updated)
                } else {
                    connectionManager.connect(profile)
                }
            }
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch { profileRepository.deleteProfile(profile.id) }
    }

    fun updateSettings(settings: CdnScanner.ScannerSettings) {
        _scannerSettings.value = settings
    }

    fun showSettings() { _showSettingsDialog.value = true }
    fun hideSettings() { _showSettingsDialog.value = false }
    fun showDetails(profile: ServerProfile) { _showDetailsDialog.value = profile }
    fun hideDetails() { _showDetailsDialog.value = null }

    // ✅ FIX 3: تابع جدید برای ویرایش پروفایل
    fun updateProfile(profile: ServerProfile, name: String, address: String, port: Int) {
        viewModelScope.launch {
            val updated = when (profile.tunnelType) {
                TunnelType.VLESS -> profile.copy(
                    name = name,
                    vlessAddress = address,
                    vlessPort = port,
                    lastScannedIp = if (address != profile.vlessAddress) "" else profile.lastScannedIp
                )
                TunnelType.TROJAN -> profile.copy(
                    name = name,
                    trojanAddress = address,
                    trojanPort = port,
                    lastScannedIp = if (address != profile.trojanAddress) "" else profile.lastScannedIp
                )
                TunnelType.HYSTERIA2 -> profile.copy(
                    name = name,
                    hy2Address = address,
                    hy2Port = port,
                    lastScannedIp = if (address != profile.hy2Address) "" else profile.lastScannedIp
                )
                TunnelType.SHADOWSOCKS -> profile.copy(
                    name = name,
                    ssAddress = address,
                    ssPort = port,
                    lastScannedIp = if (address != profile.ssAddress) "" else profile.lastScannedIp
                )
                else -> profile.copy(name = name, domain = address)
            }
            profileRepository.updateProfile(updated)
        }
    }

    fun getProfileAddress(profile: ServerProfile): String {
        return when (profile.tunnelType) {
            TunnelType.VLESS -> profile.lastScannedIp.ifBlank { profile.vlessAddress }
            TunnelType.TROJAN -> profile.lastScannedIp.ifBlank { profile.trojanAddress }
            TunnelType.HYSTERIA2 -> profile.lastScannedIp.ifBlank { profile.hy2Address }
            TunnelType.SHADOWSOCKS -> profile.lastScannedIp.ifBlank { profile.ssAddress }
            else -> profile.domain
        }
    }

    fun disconnect() {
        viewModelScope.launch { connectionManager.disconnect() }
    }

    fun getProfilePort(profile: ServerProfile): Int {
        return when (profile.tunnelType) {
            TunnelType.VLESS -> profile.vlessPort
            TunnelType.TROJAN -> profile.trojanPort
            TunnelType.HYSTERIA2 -> profile.hy2Port
            TunnelType.SHADOWSOCKS -> profile.ssPort
            else -> 443
        }
    }
}