# 1. Add connectionState and disconnect to ViewModel
vm_path = "app/src/main/java/app/slipnet/presentation/scanner/ScannerViewModel.kt"
with open(vm_path, "r") as f:
    vm = f.read()

# Add connectionState flow
if "val connectionState" not in vm:
    vm = vm.replace(
        "val scannerProfiles: StateFlow<List<ServerProfile>>",
        "val connectionState = connectionManager.connectionState\n\n    val scannerProfiles: StateFlow<List<ServerProfile>>"
    )

# Add disconnect function
if "fun disconnect()" not in vm:
    vm = vm.replace(
        "    fun getProfilePort(",
        """    fun disconnect() {
        viewModelScope.launch { connectionManager.disconnect() }
    }

    fun getProfilePort("""
    )

with open(vm_path, "w") as f:
    f.write(vm)

print("ViewModel updated!")

# 2. Update ScannerScreen to show Connect/Disconnect
screen_path = "app/src/main/java/app/slipnet/presentation/scanner/ScannerScreen.kt"
with open(screen_path, "r") as f:
    screen = f.read()

# Add ConnectionState import
if "import app.slipnet.domain.model.ConnectionState" not in screen:
    screen = screen.replace(
        "import app.slipnet.domain.model.ServerProfile",
        "import app.slipnet.domain.model.ConnectionState\nimport app.slipnet.domain.model.ServerProfile"
    )

# Add connectionState collection in ScannerScreen
screen = screen.replace(
    "    var showMoreMenu by remember { mutableStateOf(false) }",
    """    val connectionState by viewModel.connectionState.collectAsState()
    val connectedProfileId = (connectionState as? ConnectionState.Connected)?.profile?.id
    var showMoreMenu by remember { mutableStateOf(false) }"""
)

# Update ScannerProfileCard call to pass isConnected and onDisconnect
screen = screen.replace(
    """                        ScannerProfileCard(
                            profile = profile,
                            address = viewModel.getProfileAddress(profile),
                            port = viewModel.getProfilePort(profile),
                            isScanning = scanState.isScanning && selectedProfile?.id == profile.id,
                            pingResult = pingResults[profile.id],
                            isPinging = profile.id in pingingIds,
                            onPing = {""",
    """                        ScannerProfileCard(
                            profile = profile,
                            address = viewModel.getProfileAddress(profile),
                            port = viewModel.getProfilePort(profile),
                            isScanning = scanState.isScanning && selectedProfile?.id == profile.id,
                            isConnected = connectedProfileId == profile.id,
                            isConnecting = connectionState is ConnectionState.Connecting && selectedProfile?.id == profile.id,
                            pingResult = pingResults[profile.id],
                            isPinging = profile.id in pingingIds,
                            onPing = {"""
)

screen = screen.replace(
    """                            onConnect = { viewModel.connectWithAutoScan(profile) },
                            onEdit = { viewModel.showDetails(profile) },""",
    """                            onConnect = { viewModel.connectWithAutoScan(profile) },
                            onDisconnect = { viewModel.disconnect() },
                            onEdit = { viewModel.showDetails(profile) },"""
)

# Update ScannerProfileCard function signature
screen = screen.replace(
    """fun ScannerProfileCard(
    profile: ServerProfile,
    address: String,
    port: Int,
    isScanning: Boolean,
    pingResult: String?,
    isPinging: Boolean,
    onPing: () -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit""",
    """fun ScannerProfileCard(
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
    onDelete: () -> Unit"""
)

# Update card border for connected state
screen = screen.replace(
    """    val cardShape = RoundedCornerShape(12.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {""",
    """    val cardShape = RoundedCornerShape(12.dp)
    
    val borderMod = if (isConnected) Modifier.border(2.dp, ConnectedGreen, cardShape) else Modifier

    Card(
        modifier = Modifier.fillMaxWidth().then(borderMod),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) ConnectedGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {"""
)

# Add Connected badge next to protocol
screen = screen.replace(
    """                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = profile.tunnelType.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = getProtocolColor(profile.tunnelType),
                        modifier = Modifier
                            .background(getProtocolColor(profile.tunnelType).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )""",
    """                    Spacer(modifier = Modifier.width(8.dp))
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
                    }"""
)

# Update Connect button to show Disconnect when connected
screen = screen.replace(
    """            Button(
                onClick = onConnect,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect")
            }""",
    """            Button(
                onClick = if (isConnected) onDisconnect else onConnect,
                modifier = Modifier.weight(1f),
                colors = if (isConnected) ButtonDefaults.buttonColors(containerColor = DisconnectedRed) else ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isConnected) "Disconnect" else if (isConnecting) "Connecting..." else "Connect")
            }"""
)

with open(screen_path, "w") as f:
    f.write(screen)

print("ScannerScreen updated!")
