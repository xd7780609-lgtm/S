package app.slipnet.presentation.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.tunnel.CdnScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    IconButton(onClick = { viewModel.showSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToImport,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Config")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scan progress
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
                            Text("Scanning...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { viewModel.stopScan() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { scanState.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(scanState.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = when (scanState.phase) {
                                CdnScanner.ScanPhase.RANGE_DISCOVERY -> "Ranges: ${scanState.scannedRanges}/${scanState.totalRanges} | Responsive: ${scanState.responsiveRanges}"
                                CdnScanner.ScanPhase.DEEP_SCAN -> "IPs: ${scanState.scannedIps}/${scanState.totalIps} | Found: ${scanState.foundCount}"
                                CdnScanner.ScanPhase.TESTING_CURRENT -> "Testing current IP..."
                                else -> "Found: ${scanState.foundCount}"
                            } + " | ${scanState.elapsedMs / 1000}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Best IP result
            AnimatedVisibility(visible = bestIp != null && !scanState.isScanning) {
                bestIp?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("✅ Best IP Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(result.ip, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("Ping: ${result.latency}ms", style = MaterialTheme.typography.bodyMedium)
                                }
                                selectedProfile?.let { profile ->
                                    Button(onClick = { viewModel.connectWithBestIp(profile) }) {
                                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Connect")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Scan results
            AnimatedVisibility(visible = scanResults.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Found IPs (${scanResults.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        scanResults.take(5).forEach { result ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(result.ip, style = MaterialTheme.typography.bodyMedium)
                                Text("${result.latency}ms", style = MaterialTheme.typography.bodyMedium, color = getPingColor(result.latency))
                            }
                        }
                        if (scanResults.size > 5) {
                            Text("... and ${scanResults.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // Empty state
            if (profiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Scanner Configs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a VLESS/Trojan/Hysteria2 config\nwith CDN to start scanning", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                            onConnect = { viewModel.connectWithAutoScan(profile) },
                            onScan = { viewModel.startScan(profile) },
                            onDetails = { viewModel.showDetails(profile) },
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
        ProfileDetailsDialog(profile = profile, address = viewModel.getProfileAddress(profile), port = viewModel.getProfilePort(profile), onDismiss = { viewModel.hideDetails() })
    }
}

@Composable
fun ScannerProfileCard(
    profile: ServerProfile,
    address: String,
    port: Int,
    isScanning: Boolean,
    onConnect: () -> Unit,
    onScan: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(getProtocolColor(profile.tunnelType))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        profile.tunnelType.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$address:$port",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Ping Test") },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    isPinging = true
                                    pingResult = null
                                    pingResult = tcpPing(address, port)
                                    isPinging = false
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                            enabled = !isPinging
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Config") },
                            onClick = { showMenu = false; onDetails() },
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (profile.lastScannedIp.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("IP: ${profile.lastScannedIp}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text("No scan yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                if (isPinging) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pinging...", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (pingResult != null) {
                    val isSuccess = pingResult!!.contains("ms")
                    Text(
                        text = pingResult!!,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336),
                        modifier = Modifier
                            .background(
                                if (isSuccess) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color(0xFFF44336).copy(alpha = 0.1f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onScan,
                    enabled = !isScanning,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isScanning) "Scanning..." else "Scan CDN")
                }
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Connect")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Config?") },
            text = { Text("Are you sure you want to delete \"${profile.name}\"?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Simple TCP ping to check server reachability */
suspend fun tcpPing(host: String, port: Int): String = withContext(Dispatchers.IO) {
    try {
        val start = System.currentTimeMillis()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
        }
        val elapsed = System.currentTimeMillis() - start
        "${elapsed}ms"
    } catch (e: Exception) {
        "Failed"
    }
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
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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

@Composable
fun getPingColor(latency: Int): Color = when {
    latency < 100 -> Color(0xFF4CAF50)
    latency < 200 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}
}
