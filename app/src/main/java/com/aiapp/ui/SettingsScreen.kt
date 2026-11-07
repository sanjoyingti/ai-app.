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
            ProviderId.entries.forEach { KeyField(it, vm) }
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
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
