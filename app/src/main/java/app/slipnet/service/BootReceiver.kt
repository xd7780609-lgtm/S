package app.slipnet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.domain.repository.ProfileRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesDataStore: PreferencesDataStore

    @Inject
    lateinit var profileRepository: ProfileRepository

    @Inject
    lateinit var connectionManager: VpnConnectionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        scope.launch {
            val autoConnect = preferencesDataStore.autoConnectOnBoot.first()
            if (!autoConnect) return@launch

            // Active اگر Scanner بود، ردش کن و برو سراغ lastConnected
            val active = profileRepository.getActiveProfile().first()
            val profile = active?.takeIf { !it.isScannerProfile }
                ?: getLastConnectedProfileNonScanner()
                ?: return@launch

            // Check VPN permission (must have been granted before)
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                // VPN permission not granted
                return@launch
            }

            val serviceIntent = Intent(context, SlipNetVpnService::class.java).apply {
                action = SlipNetVpnService.ACTION_CONNECT
                putExtra(SlipNetVpnService.EXTRA_PROFILE_ID, profile.id)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    private suspend fun getLastConnectedProfileNonScanner() =
        preferencesDataStore.lastConnectedProfileId.first()?.let { id ->
            profileRepository.getProfileById(id)?.takeIf { !it.isScannerProfile }
        }
}