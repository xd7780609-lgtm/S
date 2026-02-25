package app.slipnet.presentation.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerImportScreen(
    viewModel: ScannerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    var configText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<ImportResultState>(ImportResultState.None) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Proxy Config") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Supported Protocols: VLESS / Trojan / Hy2 / SS",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "• vless://...\n• trojan://...\n• hysteria2:// or hy2://...\n• ss://...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Input field
            OutlinedTextField(
                value = configText,
                onValueChange = { 
                    configText = it
                    importResult = ImportResultState.None
                },
                label = { Text("Paste config link here") },
                placeholder = { Text("vless://... or trojan://...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10
            )

            // Import button
            Button(
                onClick = {
                    if (configText.isBlank()) {
                        importResult = ImportResultState.Error("Please paste a config link")
                        return@Button
                    }

                    val lines = configText.trim().lines().filter { it.isNotBlank() }
                    var successCount = 0
                    var failCount = 0

                    for (line in lines) {
                        if (viewModel.importConfig(line.trim())) {
                            successCount++
                        } else {
                            failCount++
                        }
                    }

                    importResult = when {
                        successCount > 0 && failCount == 0 -> {
                            ImportResultState.Success("Imported $successCount config(s) successfully!")
                        }
                        successCount > 0 && failCount > 0 -> {
                            ImportResultState.Warning("Imported $successCount, failed $failCount")
                        }
                        else -> {
                            ImportResultState.Error("Failed to import. Check the format.")
                        }
                    }

                    if (successCount > 0) {
                        configText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = configText.isNotBlank()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Config")
            }

            // Result message
            when (val result = importResult) {
                is ImportResultState.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = result.message,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                is ImportResultState.Warning -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = result.message,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                is ImportResultState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = result.message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                ImportResultState.None -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Example configs
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Example Formats",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "VLESS + Reality:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "vless://uuid@server:443?security=reality&pbk=xxx&type=tcp#Name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "VLESS + WebSocket + CDN:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "vless://uuid@cdn.example.com:443?type=ws&path=/path#Name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Trojan:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "trojan://password@server:443?sni=example.com#Name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Hysteria2:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "hy2://password@server:443?sni=example.com#Name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Back button
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back to Scanner")
            }
        }
    }
}

sealed class ImportResultState {
    object None : ImportResultState()
    data class Success(val message: String) : ImportResultState()
    data class Warning(val message: String) : ImportResultState()
    data class Error(val message: String) : ImportResultState()
}