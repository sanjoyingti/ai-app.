package com.aiapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiapp.builder.BuildEvent
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.TimelineItem
import com.aiapp.llm.ProviderId

/** Build Mode, laid out like an IDE agent chat: your requests, live steps, files and results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(chat: ChatViewModel, vm: BuildViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    val project = vm.project

    BackHandler(enabled = showFiles || openFile != null) {
        if (openFile != null) openFile = null else showFiles = false
    }

    openFile?.let { path ->
        CodeViewer(path, project?.files?.get(path).orEmpty(), onBack = { openFile = null })
        return
    }
    if (showFiles && project != null) {
        FileList(project.files.keys.toList(), onOpen = { openFile = it }, onBack = { showFiles = false })
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(vm.timeline.size) {
        if (vm.timeline.isNotEmpty()) listState.animateScrollToItem(vm.timeline.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.appName ?: "Build app")
                        Text(
                            "${chat.provider.label} · ${chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model} · ${chat.effort.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (project != null) {
                        TextButton(onClick = { showFiles = true }) { Text("Files (${project.files.size})") }
                        TextButton(onClick = vm::newProject, enabled = !vm.running) { Text("New") }
                    }
                },
            )
        },
        bottomBar = { BuildComposer(chat, vm, onOpenSettings) },
    ) { padding ->
        if (vm.timeline.isEmpty()) {
            EmptyState(chat, Modifier.padding(padding))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(vm.timeline) { index, item ->
                    val isLast = index == vm.timeline.lastIndex
                    when (item) {
                        is TimelineItem.User -> UserBubble(item.text)
                        is TimelineItem.Event -> EventRow(item.event, active = isLast && vm.running)
                        is TimelineItem.Result -> ResultCard(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(chat: ChatViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Describe an app and I'll build it", style = MaterialTheme.typography.titleLarge)
        Text(
            "I write the code, build the APK on your GitHub, fix any errors, and give you a download link. " +
                "Afterwards, ask for changes and I'll update the same app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Builds keep running if you close the app; you'll get a notification when it's ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chat.provider == ProviderId.OFFLINE) {
            Text(
                "You're using an offline model. It can manage simple one-screen apps; pick Claude Fable or Opus for bigger ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) { Text(text, modifier = Modifier.padding(12.dp)) }
    }
}

private fun iconFor(kind: BuildEvent.Kind): ImageVector = when (kind) {
    BuildEvent.Kind.THINKING -> Icons.Filled.Create
    BuildEvent.Kind.WEB -> Icons.Filled.Search
    BuildEvent.Kind.FILE -> Icons.Filled.Edit
    BuildEvent.Kind.LIBRARY -> Icons.Filled.Add
    BuildEvent.Kind.DOWNLOAD -> Icons.Filled.KeyboardArrowDown
    BuildEvent.Kind.BUILD -> Icons.Filled.Build
    BuildEvent.Kind.ERROR -> Icons.Filled.Warning
    BuildEvent.Kind.DONE -> Icons.Filled.Done
    BuildEvent.Kind.STEP -> Icons.Filled.CheckCircle
}

@Composable
private fun EventRow(e: BuildEvent, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    iconFor(e.kind),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (e.kind == BuildEvent.Kind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            e.text + if (active) "…" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (e.kind == BuildEvent.Kind.FILE) FontFamily.Monospace else null,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(r: TimelineItem.Result) {
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                r.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.apkUrl?.let { url -> Button(onClick = { uri.openUri(url) }) { Text("Download APK") } }
                r.repoUrl?.let { url -> OutlinedButton(onClick = { uri.openUri(url) }) { Text("GitHub") } }
            }
            if (r.success) {
                Text(
                    "Ask for changes below — the new version installs over this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuildComposer(chat: ChatViewModel, vm: BuildViewModel, onOpenSettings: () -> Unit) {
    val project = vm.project
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var allowOther by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            problem?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            if (vm.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Working…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                }
                return@Column
            }
            if (project == null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (project == null) "Describe the app…" else "Ask for a change…") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    problem = when {
                        text.isBlank() -> "Describe what you want."
                        project == null && name.isBlank() -> "Give the app a name."
                        chat.githubToken().isBlank() -> "Add your GitHub token in Settings first."
                        chat.provider.needsKey && chat.key(chat.provider).isBlank() -> "Add your ${chat.provider.label} key in Settings."
                        chat.model.isBlank() -> "Pick a model on the chat screen first."
                        else -> null
                    }
                    if (problem == null) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.start(
                            BuildRequest(
                                description = text.trim(),
                                appName = name.trim(),
                                provider = chat.provider,
                                model = chat.model,
                                apiKey = chat.key(chat.provider),
                                effort = chat.effort,
                                githubToken = chat.githubToken(),
                                allowOtherSites = allowOther,
                                project = project,
                            ),
                        )
                        text = ""
                    }
                }) { Text(if (project == null) "Build" else "Send") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allow downloads from any site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowOther, onCheckedChange = { allowOther = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileList(paths: List<String>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(paths.sorted()) { _, path ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        path.removePrefix("app/src/main/").substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeViewer(path: String, content: String, onBack: () -> Unit) {
    val lines = content.lines()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(padding).fillMaxSize()) {
            SelectionContainer {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEachIndexed { i, line ->
                        Row {
                            Text(
                                "${i + 1}".padStart(4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
