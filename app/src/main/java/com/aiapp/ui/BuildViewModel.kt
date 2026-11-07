package com.aiapp.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.BuildAgent
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildResult
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

class BuildViewModel(app: Application) : AndroidViewModel(app) {
    val steps = mutableStateListOf<String>()
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<BuildResult?>(null)
        private set

    private var job: Job? = null

    fun start(request: BuildRequest) {
        if (running) return
        steps.clear()
        result = null
        running = true
        job = viewModelScope.launch {
            try {
                result = BuildAgent(request) { line -> viewModelScope.launch { steps += line } }.run()
            } catch (e: CancellationException) {
                result = BuildResult(false, "Stopped.")
                throw e
            } catch (e: Throwable) {
                result = BuildResult(false, friendly(e))
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && t.code == 401 -> "A key or token was rejected. Check Settings. (${t.message})"
        t is ProviderException && t.code == 403 ->
            "Not allowed: ${t.message}. Your GitHub token needs the public_repo and workflow scopes."
        t is ProviderException && t.code == 429 -> "Rate limit reached. Wait a moment and try again."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }
}
