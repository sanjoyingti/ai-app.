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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.aiapp.builder.BuildRequest
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(chat: ChatViewModel, vm: BuildViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var allowOther by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build app") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val modelName = chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model.ifBlank { "no model picked" }
            Text(
                "Using ${chat.provider.label} · $modelName · Brain ${chat.effort.label}. Change these on the chat screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (chat.provider == ProviderId.OFFLINE) {
                Text(
                    "Offline models can write simple one-screen apps. For bigger apps pick Claude Fable or Opus. " +
                        "The APK is still built on GitHub, so you need internet for that step.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("App name") },
                singleLine = true,
                enabled = !vm.running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Describe the app") },
                placeholder = { Text("A tip calculator with a dark mode toggle and split between people") },
                minLines = 4,
                enabled = !vm.running,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow other sites", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "GitHub, Google Fonts, Maven and Wikimedia are always allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowOther, onCheckedChange = { allowOther = it }, enabled = !vm.running)
            }

            if (vm.running) {
                OutlinedButton(onClick = vm::stop) { Text("Stop") }
            } else {
                Button(onClick = {
                    problem = when {
                        name.isBlank() || description.isBlank() -> "Add an app name and a description."
                        chat.githubToken().isBlank() -> "Add your GitHub token in Settings first."
                        chat.provider.needsKey && chat.key(chat.provider).isBlank() -> "Add your ${chat.provider.label} key in Settings."
                        chat.model.isBlank() -> "Pick a model on the chat screen first."
                        else -> null
                    }
                    if (problem == null) {
                        vm.start(
                            BuildRequest(
                                description = description.trim(),
                                appName = name.trim(),
                                provider = chat.provider,
                                model = chat.model,
                                apiKey = chat.key(chat.provider),
                                effort = chat.effort,
                                githubToken = chat.githubToken(),
                                allowOtherSites = allowOther,
                            ),
                        )
                    }
                }) { Text("Build APK") }
            }
            problem?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }

            if (vm.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (vm.steps.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vm.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            vm.result?.let { r ->
                Text(
                    r.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.apkUrl?.let { url -> Button(onClick = { uri.openUri(url) }) { Text("Download APK") } }
                    r.repoUrl?.let { url -> OutlinedButton(onClick = { uri.openUri(url) }) { Text("Open on GitHub") } }
                }
            }
        }
    }
}
