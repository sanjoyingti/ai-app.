package com.aiapp.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.asImageBitmap
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
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit, onOpenBuild: () -> Unit, onOpenHistory: () -> Unit, onOpenCreate: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val voice = remember { VoiceController(context) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var spokenCount by rememberSaveable { mutableStateOf(-1) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.destroy() } }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.attach(uris) }

    fun startListening() {
        voice.listen { said -> vm.send(said) }
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceMode = true
            spokenCount = vm.messages.size
            startListening()
        }
    }

    // Voice mode: when a reply finishes, read it aloud, then listen again.
    LaunchedEffect(vm.streaming, voiceMode) {
        if (!voiceMode || vm.streaming) return@LaunchedEffect
        val last = vm.messages.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && vm.messages.size > spokenCount) {
            spokenCount = vm.messages.size
            voice.speak(last.text) { if (voiceMode) startListening() }
        } else if (voice.state == VoiceState.THINKING && vm.error != null) {
            voice.state = VoiceState.IDLE
        }
    }

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
                    TextButton(onClick = onOpenCreate) { Text("Create") }
                    TextButton(onClick = onOpenHistory) { Text("Chats") }
                    TextButton(onClick = vm::newChat) { Text("New") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (voiceMode) {
                VoicePanel(
                    voice = voice,
                    streaming = vm.streaming,
                    onTalk = {
                        vm.stop()
                        startListening()
                    },
                    onExit = {
                        voiceMode = false
                        voice.stopListening()
                        voice.stopSpeaking()
                    },
                )
            } else {
                Composer(
                    vm = vm,
                    text = input,
                    onTextChange = { input = it },
                    onSend = {
                        vm.send(input)
                        if (vm.streaming) input = ""
                    },
                    onAttach = { pickFiles.launch(arrayOf("*/*")) },
                    onVoice = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                )
            }
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
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            message.attachments.forEach { a -> AttachmentChip(a, onRemove = null) }
            if (message.text.isNotBlank()) {
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
        }
    } else {
        SelectionContainer {
            MarkdownText(message.text, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AttachmentChip(a: com.aiapp.llm.Attachment, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 300.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            if (a.isImage) {
                val bmp = remember(a.path) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    }.getOrNull()
                }
                bmp?.let {
                    androidx.compose.foundation.Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.padding(vertical = 6.dp).size(40.dp),
                    )
                }
            }
            Text(
                (if (a.isImage) "" else if (a.isPdf) "PDF · " else "File · ") + a.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).weight(1f, fill = false),
            )
            if (onRemove != null) TextButton(onClick = onRemove) { Text("✕") }
        }
    }
}

@Composable
private fun Composer(
    vm: ChatViewModel,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (vm.pendingAttachments.isNotEmpty() || vm.attaching) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.pendingAttachments.toList().forEach { a -> AttachmentChip(a, onRemove = { vm.removeAttachment(a) }) }
                    if (vm.attaching) Text("Attaching…", modifier = Modifier.padding(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach, enabled = !vm.streaming) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach files or photos")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                when {
                    vm.streaming -> Button(onClick = vm::stop) { Text("Stop") }
                    text.isBlank() && vm.pendingAttachments.isEmpty() -> OutlinedButton(onClick = onVoice) { Text("🎙") }
                    else -> Button(onClick = onSend) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun VoicePanel(voice: VoiceController, streaming: Boolean, onTalk: () -> Unit, onExit: () -> Unit) {
    var showVoices by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val label = when {
                voice.state == VoiceState.LISTENING -> "Listening…"
                voice.state == VoiceState.SPEAKING -> "Speaking — tap Interrupt to talk"
                streaming || voice.state == VoiceState.THINKING -> "Thinking…"
                else -> "Tap Talk and speak"
            }
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (voice.partial.isNotBlank()) Text("“${voice.partial}”", color = MaterialTheme.colorScheme.onSurfaceVariant)
            voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (voice.state) {
                    VoiceState.SPEAKING -> Button(onClick = onTalk) { Text("Interrupt") }
                    VoiceState.LISTENING -> OutlinedButton(onClick = voice::stopListening) { Text("Stop listening") }
                    else -> Button(onClick = onTalk, enabled = !streaming) { Text("🎙 Talk") }
                }
                OutlinedButton(onClick = { showVoices = !showVoices }) { Text("Voice") }
                TextButton(onClick = onExit) { Text("Exit") }
            }
            if (showVoices) {
                Text("Pitch", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Deeper" to 0.8f, "Normal" to 1.0f, "Higher" to 1.2f).forEach { (n, p) ->
                        FilterChip(selected = voice.pitch == p, onClick = { voice.setPitch(p) }, label = { Text(n) })
                    }
                }
                Text("Speed", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Slow" to 0.8f, "Normal" to 1.0f, "Fast" to 1.25f).forEach { (n, r) ->
                        FilterChip(selected = voice.rate == r, onClick = { voice.setRate(r) }, label = { Text(n) })
                    }
                }
                Text(
                    "Voices on this phone (tap to hear; names often say male/female or a letter code):",
                    style = MaterialTheme.typography.labelMedium,
                )
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    voice.voices.forEach { v ->
                        FilterChip(
                            selected = voice.voiceName == v.name,
                            onClick = { voice.selectVoice(v) },
                            label = { Text("${v.locale.displayName} · ${v.name}" + if (v.isNetworkConnectionRequired) " (online)" else "") },
                        )
                    }
                }
            }
        }
    }
}
