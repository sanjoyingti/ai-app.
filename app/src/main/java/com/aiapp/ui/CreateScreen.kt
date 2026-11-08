package com.aiapp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Create tab: free, unlimited, no-key images. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: CreateViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.attach(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create")
                        Text("Free · no key · unlimited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                OutlinedButton(onClick = { modelMenu = true }) {
                    Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: vm.model.ifBlank { "Loading models…" })
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    vm.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = {
                            vm.model = m.id
                            modelMenu = false
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("square" to "Square", "portrait" to "Portrait", "landscape" to "Landscape").forEach { (id, label) ->
                    FilterChip(selected = vm.shape == id, onClick = { vm.shape = id }, label = { Text(label) })
                }
            }

            // Attach a photo to base the new image on.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (vm.reference == null) " Attach photo" else " Change photo")
                }
                vm.reference?.let { ref ->
                    val bmp = remember(ref.path) { BitmapFactory.decodeFile(ref.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) }
                    TextButton(onClick = { vm.reference = null }) { Text("Remove") }
                }
            }
            vm.attachProgress?.let { UploadBar("Attaching photo…", it) }
            if (vm.reference != null) {
                Text("✓ Photo ready — describe what to change or make from it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe the image") },
                placeholder = { Text("A cinematic photo of a tea garden in Assam at sunrise, mist, golden light") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.busy) {
                UploadBar(vm.status ?: "Creating image…", -1f)
                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
            } else {
                Button(onClick = { vm.generate(prompt) }, enabled = prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create image") }
                vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.resultBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bmp?.let {
                    Image(it.asImageBitmap(), contentDescription = "Created image", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
            vm.resultUri?.let { uri ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }) { Text("Open") }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(share, "Share")) }
                    }) { Text("Share") }
                }
            }
            Text(
                "Free images by Pollinations.AI using open models. No key needed. Video isn't available for free without limits yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
