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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerImportScreen(
    viewModel: ScannerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    var configText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<ImportResultState>(ImportResultState.None) }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                            text = "Supported Formats",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "• vless:// / trojan:// / hy2:// / ss://\n• Subscription URL (sing-box JSON)\n• Multiple configs (one per line)",
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
                label = { Text("Paste config link or subscription URL") },
                placeholder = { Text("vless://... or https://...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10
            )

            // Import button
            Button(
                onClick = {
                    if (configText.isBlank()) {
                        importResult = ImportResultState.Error("Please paste a config link or URL")
                        return@Button
                    }

                    val text = configText.trim()

                    // Check if it's a subscription URL
                    if (text.startsWith("https://") || text.startsWith("http://")) {
                        isImporting = true
                        importResult = ImportResultState.None
                        scope.launch {
                            val (success, fail) = viewModel.importSubscription(text)
                            isImporting = false
                            importResult = when {
                                success > 0 && fail == 0 ->
                                    ImportResultState.Success("Imported $success config(s) from subscription!")
                                success > 0 ->
                                    ImportResultState.Warning("Imported $success, skipped $fail")
                                else ->
                                    ImportResultState.Error("Failed to import. Check URL or format.")
                            }
                            if (success > 0) configText = ""
                        }
                        return@Button
                    }

                    // Regular config links
                    val lines = text.lines().filter { it.isNotBlank() }
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
                        successCount > 0 && failCount == 0 ->
                            ImportResultState.Success("Imported $successCount config(s) successfully!")
                        successCount > 0 ->
                            ImportResultState.Warning("Imported $successCount, failed $failCount")
                        else ->
                            ImportResultState.Error("Failed to import. Check the format.")
                    }
                    if (successCount > 0) configText = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = configText.isNotBlank() && !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Importing...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import")
                }
            }

            // Result message
            when (val result = importResult) {
                is ImportResultState.Success -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Text(result.message, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                is ImportResultState.Warning -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Text(result.message, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                is ImportResultState.Error -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text(result.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                ImportResultState.None -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Back button
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
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
