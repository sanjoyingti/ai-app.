package com.aiapp.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.data.ApiKeys
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)

    init {
        Providers.init(app)
    }
    private val prefs = app.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<ChatMessage>()

    var streamingText by mutableStateOf("")
        private set
    var streaming by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var provider by mutableStateOf(
        enumValueOrDefault(prefs.getString("provider", null), ProviderId.CLAUDE),
    )
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var loadingModels by mutableStateOf(false)
        private set
    var model by mutableStateOf(prefs.getString("model_${provider.name}", "").orEmpty())
        private set
    var effort by mutableStateOf(
        enumValueOrDefault(prefs.getString("effort", null), Effort.AUTO),
    )
        private set

    private var job: Job? = null

    /** Download progress (0..1) for offline models being downloaded, by model id. */
    val downloads = mutableStateMapOf<String, Float>()
    val downloadErrors = mutableStateMapOf<String, String>()
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Bumped when an offline model is downloaded or deleted, so the UI re-reads file state. */
    var offlineVersion by mutableStateOf(0)
        private set

    fun deviceRamGb(): Int = Providers.offline.deviceRamGb()

    fun recommendedOffline(): OfflineModel = Providers.offline.recommended()

    fun offlineDownloaded(m: OfflineModel): Boolean {
        offlineVersion // read so Compose recomposes after changes
        return Providers.offline.isDownloaded(m)
    }

    fun downloadOffline(m: OfflineModel) {
        if (downloadJobs[m.id]?.isActive == true) return
        downloadErrors.remove(m.id)
        downloads[m.id] = 0f
        downloadJobs[m.id] = viewModelScope.launch {
            try {
                Providers.offline.download(m) { p -> viewModelScope.launch { if (m.id in downloads) downloads[m.id] = p } }
                offlineVersion++
                if (provider == ProviderId.OFFLINE) refreshModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                downloadErrors[m.id] = friendly(e)
            } finally {
                downloads.remove(m.id)
                downloadJobs.remove(m.id)
            }
        }
    }

    fun cancelDownload(m: OfflineModel) {
        downloadJobs[m.id]?.cancel()
    }

    fun deleteOffline(m: OfflineModel) {
        viewModelScope.launch {
            Providers.offline.delete(m)
            offlineVersion++
            if (provider == ProviderId.OFFLINE) refreshModels()
        }
    }

    /** Switches the chat to this offline model. */
    fun useOffline(m: OfflineModel) {
        selectProvider(ProviderId.OFFLINE)
        selectModel(m.id)
    }

    init {
        refreshModels()
    }

    fun key(p: ProviderId): String = keys.get(p)

    fun saveKey(p: ProviderId, value: String) {
        keys.set(p, value)
        if (p == provider) refreshModels()
    }

    fun selectProvider(p: ProviderId) {
        if (p == provider) return
        provider = p
        prefs.edit().putString("provider", p.name).apply()
        model = prefs.getString("model_${p.name}", "").orEmpty()
        models = emptyList()
        refreshModels()
    }

    fun selectModel(id: String) {
        model = id
        prefs.edit().putString("model_${provider.name}", id).apply()
    }

    fun selectEffort(e: Effort) {
        effort = e
        prefs.edit().putString("effort", e.name).apply()
    }

    fun refreshModels() {
        val p = provider
        val k = keys.get(p)
        if (p.needsKey && k.isBlank()) {
            error = "Add your ${p.label} API key in Settings."
            return
        }
        loadingModels = true
        viewModelScope.launch {
            try {
                val list = Providers.rankedModels(p, k)
                if (p == provider) {
                    models = list
                    error = null
                    if (list.none { it.id == model }) list.firstOrNull()?.let { selectModel(it.id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (p == provider) error = friendly(e)
            } finally {
                loadingModels = false
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val k = keys.get(provider)
        if (provider.needsKey && k.isBlank()) {
            error = "Add your ${provider.label} API key in Settings."
            return
        }
        if (model.isBlank()) {
            error = "Pick a model first."
            return
        }
        error = null
        messages += ChatMessage(Role.USER, trimmed)
        val history = messages.toList()
        val p = provider
        val m = model
        val e = effort

        streaming = true
        streamingText = ""
        status = if (p == ProviderId.OFFLINE) "Loading offline model…" else "Thinking…"
        job = viewModelScope.launch {
            try {
                Providers.get(p).stream(k, m, history, e, SYSTEM_PROMPT).collect { delta ->
                    if (status != "Writing…") status = "Writing…"
                    streamingText += delta
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                finishStream()
            }
        }
    }

    /** Stops the reply; whatever was already written stays in the chat. */
    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        stop()
        messages.clear()
        error = null
    }

    private fun finishStream() {
        if (streamingText.isNotBlank()) messages += ChatMessage(Role.ASSISTANT, streamingText)
        streamingText = ""
        status = null
        streaming = false
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) ->
            "Your ${provider.label} key was rejected. Check it in Settings."
        t is ProviderException && t.code == 429 ->
            "Rate limit reached. Wait a moment and try again."
        t is ProviderException -> t.message ?: "Request failed (${t.code})."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are a helpful assistant in a mobile app. Answer clearly and concisely. " +
                "Use Markdown for code blocks and lists."

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
    }
}
