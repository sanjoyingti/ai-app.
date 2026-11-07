package com.aiapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiapp.llm.OfflineCatalog
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "API keys are stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ProviderId.entries.filter { it.needsKey }.forEach { KeyField(it, vm) }
            OfflineSection(vm)
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
    ProviderId.OPEN -> "openrouter.ai/keys (one key for Kimi, DeepSeek, GLM, Qwen; some are free)"
    ProviderId.OFFLINE -> ""
}

@Composable
private fun KeyField(p: ProviderId, vm: ChatViewModel) {
    var text by remember(p) { mutableStateOf(vm.key(p)) }
    var note by remember(p) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                note = null
            },
            label = { Text("${p.label} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveKey(p, text)
                note = "Saved"
            }) { Text("Save") }
            TextButton(onClick = {
                text = ""
                vm.saveKey(p, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun OfflineSection(vm: ChatViewModel) {
    val recommended = vm.recommendedOffline()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Offline models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run on this phone with no internet or API key. Your phone has about ${vm.deviceRamGb()} GB " +
                "of RAM, so ${recommended.name} is recommended. Keep the app open while it downloads; " +
                "an interrupted download resumes where it stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OfflineCatalog.models.forEach { OfflineRow(it, vm) }
    }
}

@Composable
private fun OfflineRow(m: OfflineModel, vm: ChatViewModel) {
    val progress = vm.downloads[m.id]
    val downloaded = vm.offlineDownloaded(m)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                downloaded -> "Downloaded · ready offline"
                progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                else -> "About ${m.approxSizeGb} GB · needs ${m.minRamGb} GB+ RAM"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                downloaded -> {
                    Button(onClick = { vm.useOffline(m) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Delete") }
                }
                progress != null -> OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                else -> Button(onClick = { vm.downloadOffline(m) }) { Text("Download") }
            }
        }
        vm.downloadErrors[m.id]?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
