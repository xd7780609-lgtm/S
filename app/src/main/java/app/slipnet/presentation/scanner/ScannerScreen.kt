package app.slipnet.presentation.scanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.presentation.theme.ConnectedGreen
import app.slipnet.presentation.theme.ConnectingOrange
import app.slipnet.presentation.theme.DisconnectedRed
import app.slipnet.tunnel.CdnScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel = hiltViewModel(),
    onNavigateToImport: () -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    val profiles by viewModel.scannerProfiles.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val bestIp by viewModel.bestIp.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val showSettings by viewModel.showSettingsDialog.collectAsState()
    val showDetails by viewModel.showDetailsDialog.collectAsState()
    val scannerSettings by viewModel.scannerSettings.collectAsState()
    
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedProfileId = (connectionState as? ConnectionState.Connected)?.profile?.id
    var showMoreMenu by remember { mutableStateOf(false) }
    var pingResults by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    var pingingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val scope = rememberCoroutineScope()
    
    val context = LocalContext.current
    val activity = context.findActivity()
    val snackbarHostState = remember { SnackbarHostState() }

    // ✅ FIX 1: VPN Permission handling
    var pendingConnectProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingConnectMode by remember { mutableStateOf("autoScan") }
    
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConnectProfile?.let { profile ->
                when (pendingConnectMode) {
                    "bestIp" -> viewModel.connectWithBestIp(profile)
                    else -> viewModel.connectWithAutoScan(profile)
                }
            }
        }
        pendingConnectProfile = null
        pendingConnectMode = "autoScan"
    }

    // ✅ Helper: connect با AutoScan + چک VPN permission
    fun connectWithPermissionCheck(profile: ServerProfile) {
        if (activity != null) {
            val vpnIntent = VpnService.prepare(activity)
            if (vpnIntent != null) {
                pendingConnectProfile = profile
                pendingConnectMode = "autoScan"
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.connectWithAutoScan(profile)
            }
        }
    }

    // ✅ Helper: connect با Best IP + چک VPN permission
    fun connectBestIpWithPermissionCheck(profile: ServerProfile) {
        if (activity != null) {
            val vpnIntent = VpnService.prepare(activity)
            if (vpnIntent != null) {
                pendingConnectProfile = profile
                pendingConnectMode = "bestIp"
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.connectWithBestIp(profile)
            }
        }
    }

    // ✅ FIX 3: Edit dialog state
    var showEditDialog by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner Connection") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Ping All
                    IconButton(
                        onClick = {
                            profiles.forEach { profile ->
                                val address = viewModel.getProfileAddress(profile)
                                val port = viewModel.getProfilePort(profile)
                                scope.launch {
                                    pingingIds = pingingIds + profile.id
                                    val result = tcpPing(address, port)
                                    pingResults = pingResults + (profile.id to result)
                                    pingingIds = pingingIds - profile.id
                                }
                            }
                        },
                        enabled = profiles.isNotEmpty() && pingingIds.isEmpty()
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = "Ping All")
                    }
                    
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Scanner Settings") },
                                onClick = { showMoreMenu = false; viewModel.showSettings() },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToImport,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import Config")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scan progress card
            AnimatedVisibility(visible = scanState.isScanning) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Scanning CDN...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { viewModel.stopScan() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { scanState.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(scanState.statusMessage, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Found: ${scanState.foundCount} | ${scanState.elapsedMs / 1000}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Best IP result card
            AnimatedVisibility(visible = bestIp != null && !scanState.isScanning) {
                bestIp?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = ConnectedGreen.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("✅ Best IP Found", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(result.ip, style = MaterialTheme.typography.titleMedium, color = ConnectedGreen)
                                Text("${result.latency}ms", style = MaterialTheme.typography.bodySmall)
                            }
                            selectedProfile?.let { profile ->
                                Button(onClick = { connectBestIpWithPermissionCheck(profile) }) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
            }

            // Empty state
            if (profiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Scanner Configs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap + to import VLESS/Trojan/Hy2/SS config", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ScannerProfileCard(
                            profile = profile,
                            address = viewModel.getProfileAddress(profile),
                            port = viewModel.getProfilePort(profile),
                            isScanning = scanState.isScanning && selectedProfile?.id == profile.id,
                            isConnected = connectedProfileId == profile.id,
                            isConnecting = connectionState is ConnectionState.Connecting && selectedProfile?.id == profile.id,
                            pingResult = pingResults[profile.id],
                            isPinging = profile.id in pingingIds,
                            onPing = {
                                val address = viewModel.getProfileAddress(profile)
                                val port = viewModel.getProfilePort(profile)
                                scope.launch {
                                    pingingIds = pingingIds + profile.id
                                    pingResults = pingResults + (profile.id to null)
                                    val result = tcpPing(address, port)
                                    pingResults = pingResults + (profile.id to result)
                                    pingingIds = pingingIds - profile.id
                                }
                            },
                            onScan = { viewModel.startScan(profile) },
                            onConnect = { connectWithPermissionCheck(profile) },
                            onDisconnect = { viewModel.disconnect() },
                            onEdit = { showEditDialog = profile },
                            onDelete = { viewModel.deleteProfile(profile) }
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ScannerSettingsDialog(settings = scannerSettings, onDismiss = { viewModel.hideSettings() }, onSave = { viewModel.updateSettings(it) })
    }

    showDetails?.let { profile ->
        ProfileDetailsDialog(
            profile = profile, 
            address = viewModel.getProfileAddress(profile), 
            port = viewModel.getProfilePort(profile), 
            onDismiss = { viewModel.hideDetails() }
        )
    }

    showEditDialog?.let { profile ->
        ProfileEditDialog(
            profile = profile,
            currentAddress = viewModel.getProfileAddress(profile),
            currentPort = viewModel.getProfilePort(profile),
            onDismiss = { showEditDialog = null },
            onSave = { name, address, port ->
                viewModel.updateProfile(profile, name, address, port)
                showEditDialog = null
                scope.launch {
                    snackbarHostState.showSnackbar("Profile updated")
                }
            }
        )
    }
}

@Composable
fun ScannerProfileCard(
    profile: ServerProfile,
    address: String,
    port: Int,
    isScanning: Boolean,
    isConnected: Boolean = false,
    isConnecting: Boolean = false,
    pingResult: String?,
    isPinging: Boolean,
    onPing: () -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    val cardShape = RoundedCornerShape(12.dp)
    
    val borderMod = if (isConnected) Modifier.border(2.dp, ConnectedGreen, cardShape) else Modifier

    Card(
        modifier = Modifier.fillMaxWidth().then(borderMod),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) ConnectedGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = profile.tunnelType.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = getProtocolColor(profile.tunnelType),
                        modifier = Modifier
                            .background(getProtocolColor(profile.tunnelType).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConnectedGreen,
                            modifier = Modifier
                                .background(ConnectedGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$address:$port",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    when {
                        isPinging -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        }
                        pingResult != null -> {
                            val isSuccess = pingResult.contains("ms")
                            val color = if (isSuccess) ConnectedGreen else DisconnectedRed
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = pingResult,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = color,
                                modifier = Modifier
                                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                if (profile.lastScannedIp.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp), tint = ConnectedGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Last IP: ${profile.lastScannedIp}", style = MaterialTheme.typography.bodySmall, color = ConnectedGreen)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onScan, enabled = !isScanning, modifier = Modifier.size(36.dp)) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Scan", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Ping Test") },
                            onClick = { showMenu = false; onPing() },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                            enabled = !isPinging
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Config") },
                            onClick = { showMenu = false; onEdit() },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; showDeleteConfirm = true },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning...")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan CDN")
                }
            }
            Button(
                onClick = if (isConnected) onDisconnect else onConnect,
                modifier = Modifier.weight(1f),
                colors = if (isConnected) ButtonDefaults.buttonColors(containerColor = DisconnectedRed) else ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isConnected) "Disconnect" else if (isConnecting) "Connecting..." else "Connect")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Config?") },
            text = { Text("Are you sure you want to delete \"${profile.name}\"?") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

suspend fun tcpPing(host: String, port: Int): String = withContext(Dispatchers.IO) {
    try {
        val start = System.currentTimeMillis()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
        }
        "${System.currentTimeMillis() - start}ms"
    } catch (e: Exception) {
        "Failed"
    }
}

@Composable
fun getProtocolColor(tunnelType: TunnelType): Color = when (tunnelType) {
    TunnelType.VLESS -> Color(0xFF2196F3)
    TunnelType.TROJAN -> Color(0xFF9C27B0)
    TunnelType.HYSTERIA2 -> Color(0xFFFF9800)
    TunnelType.SHADOWSOCKS -> Color(0xFF4CAF50)
    else -> Color(0xFF607D8B)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSettingsDialog(
    settings: CdnScanner.ScannerSettings,
    onDismiss: () -> Unit,
    onSave: (CdnScanner.ScannerSettings) -> Unit
) {
    var maxLatency by remember { mutableStateOf(settings.maxLatency.toString()) }
    var maxResults by remember { mutableStateOf(settings.maxResults.toString()) }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }
    var saveHistory by remember { mutableStateOf(settings.saveHistory) }
    var rangeSource by remember { mutableStateOf(settings.rangeSource) }
    var scanSpeed by remember { mutableStateOf(settings.scanSpeed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanner Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = maxLatency, onValueChange = { maxLatency = it.filter { c -> c.isDigit() } }, label = { Text("Max Latency (ms)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxResults, onValueChange = { maxResults = it.filter { c -> c.isDigit() } }, label = { Text("Max IPs to Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                var sourceExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = sourceExpanded, onExpandedChange = { sourceExpanded = !sourceExpanded }) {
                    OutlinedTextField(
                        value = when (rangeSource) { CdnScanner.RangeSource.MAIN -> "Main"; CdnScanner.RangeSource.EXTENDED -> "Extended"; CdnScanner.RangeSource.BOTH -> "Both" },
                        onValueChange = {}, readOnly = true, label = { Text("Range Source") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                        DropdownMenuItem(text = { Text("Main (faster)") }, onClick = { rangeSource = CdnScanner.RangeSource.MAIN; sourceExpanded = false })
                        DropdownMenuItem(text = { Text("Extended (best for Iran)") }, onClick = { rangeSource = CdnScanner.RangeSource.EXTENDED; sourceExpanded = false })
                        DropdownMenuItem(text = { Text("Both (Recommended)") }, onClick = { rangeSource = CdnScanner.RangeSource.BOTH; sourceExpanded = false })
                    }
                }

                var speedExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = speedExpanded, onExpandedChange = { speedExpanded = !speedExpanded }) {
                    OutlinedTextField(
                        value = scanSpeed.displayName, onValueChange = {}, readOnly = true, label = { Text("Scan Speed") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speedExpanded) },
                        supportingText = { Text(scanSpeed.description) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                        CdnScanner.ScanSpeed.entries.forEach { sp ->
                            DropdownMenuItem(text = { Text("${sp.displayName} • ${sp.description}") }, onClick = { scanSpeed = sp; speedExpanded = false })
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto Connect"); Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Save History"); Switch(checked = saveHistory, onCheckedChange = { saveHistory = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(settings.copy(maxLatency = maxLatency.toIntOrNull() ?: 300, maxResults = maxResults.toIntOrNull() ?: 10, rangeSource = rangeSource, scanSpeed = scanSpeed, autoConnect = autoConnect, saveHistory = saveHistory))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ProfileDetailsDialog(profile: ServerProfile, address: String, port: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Protocol", profile.tunnelType.displayName)
                DetailRow("Address", address)
                DetailRow("Port", port.toString())
                when (profile.tunnelType) {
                    TunnelType.VLESS -> {
                        DetailRow("UUID", profile.vlessUuid.take(8) + "...")
                        DetailRow("Security", profile.vlessSecurity)
                        DetailRow("Network", profile.vlessNetwork)
                        if (profile.vlessFlow.isNotBlank()) DetailRow("Flow", profile.vlessFlow)
                        if (profile.vlessSni.isNotBlank()) DetailRow("SNI", profile.vlessSni)
                    }
                    TunnelType.TROJAN -> {
                        DetailRow("Network", profile.trojanNetwork)
                        if (profile.trojanSni.isNotBlank()) DetailRow("SNI", profile.trojanSni)
                    }
                    TunnelType.HYSTERIA2 -> { DetailRow("Up", "${profile.hy2UpMbps} Mbps"); DetailRow("Down", "${profile.hy2DownMbps} Mbps") }
                    TunnelType.SHADOWSOCKS -> DetailRow("Method", profile.ssMethod)
                    else -> {}
                }
                if (profile.lastScannedIp.isNotBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow("Last Scanned IP", profile.lastScannedIp)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun ProfileEditDialog(
    profile: ServerProfile,
    currentAddress: String,
    currentPort: Int,
    onDismiss: () -> Unit,
    onSave: (name: String, address: String, port: Int) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var address by remember { mutableStateOf(currentAddress) }
    var portText by remember { mutableStateOf(currentPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("IP / Domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    text = "Protocol: ${profile.tunnelType.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: currentPort
                    onSave(name, address, port)
                },
                enabled = name.isNotBlank() && address.isNotBlank() && portText.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}