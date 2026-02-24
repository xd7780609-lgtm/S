package app.slipnet.service

import android.content.Context
import android.content.Intent
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.data.repository.VpnRepositoryImpl
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TrafficStats
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.widget.VpnWidgetCompactProvider
import app.slipnet.widget.VpnWidgetProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpnRepository: VpnRepositoryImpl,
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val trafficStats: StateFlow<TrafficStats> = vpnRepository.trafficStats

    private var pendingProfile: ServerProfile? = null

    init {
        // Observe VPN repository state
        scope.launch {
            vpnRepository.connectionState.collect { state ->
                _connectionState.value = state
            }
        }

        // Push state changes to home screen widget
        scope.launch {
            _connectionState.collect { state ->
                VpnWidgetProvider.notifyStateChanged(context, state)
                VpnWidgetCompactProvider.notifyStateChanged(context, state)
            }
        }
    }

    fun connect(profile: ServerProfile) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            return
        }

        pendingProfile = profile
        _connectionState.value = ConnectionState.Connecting

        // فقط اگر Scanner نبود، Active را تغییر بده
        scope.launch {
            if (!profile.isScannerProfile) {
                profileRepository.setActiveProfile(profile.id)
            }
        }

        // Start VPN service
        val intent = Intent(context, SlipNetVpnService::class.java).apply {
            action = SlipNetVpnService.ACTION_CONNECT
            putExtra(SlipNetVpnService.EXTRA_PROFILE_ID, profile.id)
        }
        context.startForegroundService(intent)
    }

    fun reconnect(profile: ServerProfile) {
        pendingProfile = profile
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            if (!profile.isScannerProfile) {
                profileRepository.setActiveProfile(profile.id)
            }
        }

        val intent = Intent(context, SlipNetVpnService::class.java).apply {
            action = SlipNetVpnService.ACTION_CONNECT
            putExtra(SlipNetVpnService.EXTRA_PROFILE_ID, profile.id)
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        if (_connectionState.value is ConnectionState.Disconnected) {
            return
        }

        _connectionState.value = ConnectionState.Disconnecting

        val intent = Intent(context, SlipNetVpnService::class.java).apply {
            action = SlipNetVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun onVpnEstablished() {
        val profile = pendingProfile ?: return

        // اگر Scanner بود lastConnected را دست نزن
        scope.launch {
            if (!profile.isScannerProfile) {
                preferencesDataStore.setLastConnectedProfileId(profile.id)
                profileRepository.updateLastConnectedAt(profile.id)
            }
        }
    }

    fun onVpnDisconnected() {
        // Reset repository state بدون disconnect flow کامل
        vpnRepository.updateConnectionState(ConnectionState.Disconnected)
        _connectionState.value = ConnectionState.Disconnected
        pendingProfile = null
    }

    fun onVpnError(error: String) {
        scope.launch {
            _connectionState.value = ConnectionState.Error(error)
        }
    }

    fun refreshTrafficStats() {
        vpnRepository.refreshTrafficStats()
    }

    suspend fun getProfileById(id: Long): ServerProfile? {
        return profileRepository.getProfileById(id)
    }

    suspend fun getActiveProfile(): ServerProfile? {
        return profileRepository.getActiveProfile().first()?.takeIf { !it.isScannerProfile }
    }

    suspend fun shouldAutoConnect(): Boolean {
        return preferencesDataStore.autoConnectOnBoot.first()
    }

    suspend fun getLastConnectedProfile(): ServerProfile? {
        val lastProfileId = preferencesDataStore.lastConnectedProfileId.first() ?: return null
        return profileRepository.getProfileById(lastProfileId)?.takeIf { !it.isScannerProfile }
    }
}