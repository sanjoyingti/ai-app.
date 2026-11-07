package com.aiapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit, onOpenBuild: () -> Unit, onOpenHistory: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.streamingText.length, vm.streaming) {
        val count = vm.messages.size + if (vm.streaming) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI App") },
                actions = {
                    TextButton(onClick = onOpenBuild) { Text("Build") }
                    TextButton(onClick = onOpenHistory) { Text("Chats") }
                    TextButton(onClick = vm::newChat) { Text("New") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                text = input,
                onTextChange = { input = it },
                streaming = vm.streaming,
                onSend = {
                    vm.send(input)
                    if (vm.streaming) input = ""
                },
                onStop = vm::stop,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PickerRow(vm)
            vm.error?.let { ErrorBanner(it, onOpenSettings) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.messages) { MessageBubble(it) }
                if (vm.streaming) {
                    item {
                        Column {
                            vm.status?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (vm.streamingText.isNotEmpty()) {
                                MessageBubble(ChatMessage(Role.ASSISTANT, vm.streamingText))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(vm: ChatViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dropdown(
            label = vm.provider.label,
            options = ProviderId.entries.toList(),
            display = { it.label },
            onSelect = vm::selectProvider,
        )
        val modelLabel = when {
            vm.loadingModels -> "Loading models…"
            vm.model.isBlank() -> "Pick model"
            else -> vm.models.firstOrNull { it.id == vm.model }?.name ?: vm.model
        }
        Dropdown(
            label = modelLabel,
            options = vm.models,
            display = { it.name },
            onSelect = { vm.selectModel(it.id) },
            onOpen = { if (vm.models.isEmpty()) vm.refreshModels() },
        )
        AssistChip(
            onClick = vm::toggleWeb,
            label = { Text(if (vm.webEnabled) "Web: On" else "Web: Off") },
        )
        Dropdown(
            label = "Effort: ${vm.effort.label}",
            options = Effort.entries.toList(),
            display = { it.label },
            onSelect = vm::selectEffort,
        )
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    onOpen: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = {
                onOpen()
                open = true
            },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            },
        )
        DropdownMenu(expanded = open && options.isNotEmpty(), onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.role == Role.USER) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(message.text, modifier = Modifier.padding(12.dp))
                }
            }
        }
    } else {
        SelectionContainer {
            MarkdownText(message.text, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Message") },
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onSend, enabled = text.isNotBlank()) { Text("Send") }
            }
        }
    }
}
