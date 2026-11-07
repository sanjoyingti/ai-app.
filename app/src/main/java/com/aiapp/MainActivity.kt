package com.aiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                if (screen == "chat") {
                    ChatScreen(vm, onOpenSettings = { screen = "settings" })
                } else {
                    SettingsScreen(vm, onBack = { screen = "chat" })
                }
            }
        }
    }
}
