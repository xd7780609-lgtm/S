path = "app/src/main/java/app/slipnet/presentation/scanner/ScannerScreen.kt"
with open(path, "r") as f:
    lines = f.readlines()

# Find ScannerProfileCard start
start = None
end = None
for i, line in enumerate(lines):
    if "fun ScannerProfileCard(" in line:
        start = i - 1 if (i > 0 and "@Composable" in lines[i-1]) else i
        break

# Find end: before tcpPing function
if start is not None:
    for i in range(start + 5, len(lines)):
        if "/** Simple TCP ping" in lines[i] or "suspend fun tcpPing" in lines[i]:
            end = i
            break

print(f"Replacing lines {start+1} to {end}")

new_card = '''@Composable
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
            text = { Text("Are you sure you want to delete \\"${profile.name}\\"?") },
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

'''

lines[start:end] = [new_card]

with open(path, "w") as f:
    f.writelines(lines)

print("ScannerProfileCard updated!")

# Fix import screen title
import_path = "app/src/main/java/app/slipnet/presentation/scanner/ScannerImportScreen.kt"
with open(import_path, "r") as f:
    imp = f.read()

imp = imp.replace("Import Scanner Config", "Import Proxy Config")
imp = imp.replace("Supported Formats", "Supported Protocols: VLESS / Trojan / Hy2 / SS")

with open(import_path, "w") as f:
    f.write(imp)

print("ScannerImportScreen title updated!")