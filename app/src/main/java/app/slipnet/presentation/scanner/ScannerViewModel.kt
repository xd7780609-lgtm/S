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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val connectionManager: VpnConnectionManager
) : ViewModel() {

    // فقط پروفایل‌هایی که مخصوص اسکنر هستند رو نشون بده
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
                // اصلاح شده: استفاده از saveProfile به جای insertProfile
                profileRepository.saveProfile(scannerProfile)
            }
            return true
        }
        return false
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

    // منطق هوشمند: پینگ بگیر -> اگر بد بود اسکن کن -> وصل شو
    fun connectWithAutoScan(profile: ServerProfile) {
        viewModelScope.launch {
            _selectedProfile.value = profile

            val currentIp = getProfileAddress(profile)
            val port = getProfilePort(profile)
            val settings = _scannerSettings.value
            
            // ۱. تست پینگ آی‌پی فعلی
            // اگر پینگ فعلی زیر MaxLatency باشه، نیازی به اسکن نیست
            val isCurrentIpGood = CdnScanner.testCurrentIp(currentIp, port, settings.maxLatency)

            if (isCurrentIpGood) {
                // آی‌پی خوبه، مستقیم وصل شو
                connectionManager.connect(profile)
            } else {
                // ۲. آی‌پی بده، شروع اسکن برای پیدا کردن آی‌پی تمیز
                val ranges = CdnScanner.loadRanges(context, settings.rangeSource)

                // تابع autoFindBestIp خودش اسکن میکنه و بهترین رو برمیگردونه
                val newBestIp = CdnScanner.autoFindBestIp(
                    currentIp = currentIp,
                    port = port,
                    settings = settings.copy(customRanges = ranges),
                    scope = viewModelScope
                )

                if (newBestIp != null) {
                    // آی‌پی جدید پیدا شد، پروفایل رو آپدیت کن و وصل شو
                    val updated = profile.copy(
                        lastScannedIp = newBestIp,
                        lastScanTime = System.currentTimeMillis()
                    )
                    profileRepository.updateProfile(updated)
                    connectionManager.connect(updated)
                } else {
                    // هیچ آی‌پی پیدا نشد، با همون قبلی زورکی وصل شو
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