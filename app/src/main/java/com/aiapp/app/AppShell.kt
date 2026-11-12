package com.aiapp.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.builder.ui.BuildScreen
import com.aiapp.builder.ui.BuildViewModel
import com.aiapp.builder.ui.CodespaceReturnBar
import com.aiapp.builder.ui.CodespaceScreen
import com.aiapp.builder.ui.CodespaceState
import com.aiapp.chat.ChatScreen
import com.aiapp.chat.ChatViewModel
import com.aiapp.chatlist.HistoryScreen
import com.aiapp.create.CreateScreen
import com.aiapp.create.CreateViewModel
import com.aiapp.settings.SettingsScreen

/**
 * The app's frame: owns which screen is showing and hands each feature the
 * callbacks it needs to move between screens. Features never navigate on their own.
 */
@Composable
fun AppShell() {
    val chatVm: ChatViewModel = viewModel()
    val buildVm: BuildViewModel = viewModel()
    val createVm: CreateViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    val home = { screen = Screen.Chat }
    val settings = { screen = Screen.Settings }
    BackHandler(enabled = screen != Screen.Chat, onBack = home)

    // VS Code (Codespace) on top when open; it keeps running while you use the app.
    if (CodespaceState.visible) {
        CodespaceScreen()
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (CodespaceState.alive) {
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { CodespaceReturnBar() }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Chat -> ChatScreen(
                    chatVm,
                    onOpenSettings = settings,
                    onOpenBuild = { screen = Screen.Build },
                    onOpenHistory = { screen = Screen.History },
                    onOpenCreate = { screen = Screen.Create },
                )
                Screen.History -> HistoryScreen(chatVm, onBack = home)
                Screen.Create -> CreateScreen(createVm, onBack = home, onOpenSettings = settings)
                Screen.Build -> BuildScreen(chatVm, buildVm, onBack = home, onOpenSettings = settings)
                Screen.Settings -> SettingsScreen(chatVm, onBack = home)
            }
        }
    }
}
