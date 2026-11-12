package com.aiapp

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.aiapp.app.AppShell
import com.aiapp.app.IncomingShare
import com.aiapp.builder.BuildRunner
import com.aiapp.chat.ChatViewModel
import com.aiapp.design.AppTheme
import com.aiapp.design.ImageLoader
import com.aiapp.llm.Providers

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // App hidden or memory low: drop the offline model and image cache (they reload when needed).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ImageLoader.clear()
            if (!BuildRunner.running) Providers.offlineIfLoaded?.release()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh start from another app's Share button (not a screen rotation replaying it).
        if (savedInstanceState == null) receiveShare(intent)
        setContent {
            AppTheme { AppShell() }
        }
    }

    /** The app was already open when something was shared into it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        IncomingShare.handle(intent, ViewModelProvider(this)[ChatViewModel::class.java])
    }
}
