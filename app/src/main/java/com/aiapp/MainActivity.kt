package com.aiapp

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aiapp.app.AppShell
import com.aiapp.builder.BuildRunner
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
        setContent {
            AppTheme { AppShell() }
        }
    }
}
