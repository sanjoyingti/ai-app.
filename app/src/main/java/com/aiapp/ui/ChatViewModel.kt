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
import com.aiapp.data.ChatStore
import com.aiapp.data.Conversation
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.WebTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)

    init {
        Providers.init(app)
        WebTools.downloader = { url -> com.aiapp.data.Downloads.save(app, url) }
        Providers.offline.hfToken = keys.getExtra("HF")
    }
    private val prefs = app.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<ChatMessage>()

    private val store = ChatStore(app)
    private var conversationId: String = prefs.getString("current_chat", null) ?: UUID.randomUUID().toString()

    /** Saved chats for the Chats screen, newest first. */
    var history by mutableStateOf<List<Conversation>>(emptyList())
        private set

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

    /** Lets the model search the web and read pages (works for offline models too). */
    var webEnabled by mutableStateOf(prefs.getBoolean("web", true))
        private set

    fun toggleWeb() {
        webEnabled = !webEnabled
        prefs.edit().putBoolean("web", webEnabled).apply()
    }

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
            if (m.id.startsWith("hf-")) Providers.offline.removeCustom(m)
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
        store.load(conversationId)?.let { messages.addAll(it.messages) }
        refreshModels()
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.list()
            launch(Dispatchers.Main) { history = list }
        }
    }

    fun openChat(id: String) {
        stop()
        val c = store.load(id) ?: return
        conversationId = c.id
        prefs.edit().putString("current_chat", c.id).apply()
        messages.clear()
        messages.addAll(c.messages)
        error = null
    }

    fun deleteChat(id: String) {
        store.delete(id)
        if (id == conversationId) newChat()
        refreshHistory()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == Role.USER }?.text?.lines()?.firstOrNull()?.take(60) ?: "Chat"
        val c = Conversation(
            id = conversationId,
            title = title,
            updated = System.currentTimeMillis(),
            provider = provider.label,
            model = model,
            messages = messages.toList(),
        )
        prefs.edit().putString("current_chat", conversationId).apply()
        viewModelScope.launch(Dispatchers.IO) { store.save(c) }
    }

    fun key(p: ProviderId): String = keys.get(p)

    fun githubToken(): String = keys.getExtra("GITHUB")

    fun hfToken(): String = keys.getExtra("HF")

    fun saveHfToken(value: String) {
        keys.setExtra("HF", value)
        Providers.offline.hfToken = value.trim()
    }

    /** Built-in and user-added offline models. */
    fun offlineModels(): List<OfflineModel> {
        offlineVersion // recompose when the list changes
        return Providers.offline.allModels()
    }

    /** Adds an offline model from a Hugging Face link. Returns an error message, or null on success. */
    fun addOfflineModel(link: String): String? {
        val m = Providers.offline.addCustom(link) ?: return "Paste a Hugging Face link like huggingface.co/litert-community/Gemma3-1B-IT"
        offlineVersion++
        if (provider == ProviderId.OFFLINE) refreshModels()
        downloadOffline(m)
        return null
    }

    fun saveGithubToken(value: String) = keys.setExtra("GITHUB", value)

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
        val web = webEnabled
        val system = if (web) SYSTEM_PROMPT + WebTools.instructions(today()) else SYSTEM_PROMPT
        job = viewModelScope.launch {
            try {
                val working = history.toMutableList()
                // Small offline models have short memory, so give them shorter web results.
                val cap = if (p == ProviderId.OFFLINE) 3_000 else 8_000
                var steps = 0
                while (true) {
                    streamingText = ""
                    Providers.get(p).stream(k, m, working, e, system).collect { delta ->
                        if (status != "Writing…") status = "Writing…"
                        streamingText += delta
                    }
                    if (!web || steps >= MAX_WEB_STEPS) break
                    // (Offline models get the same number of lookups; they're cheap.)
                    val reply = streamingText
                    val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val download = WebTools.downloadTag.find(reply)?.groupValues?.get(1)?.trim()
                    val result = when {
                        download != null -> {
                            status = "Downloading ${download.substringAfterLast('/').take(40)}…"
                            WebTools.download(download)
                        }
                        search != null -> {
                            status = "Searching the web: $search"
                            "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                        }
                        fetch != null -> {
                            status = "Reading ${fetch.removePrefix("https://").removePrefix("http://").substringBefore('/')}…"
                            "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                        }
                        else -> break
                    }
                    steps++
                    working += ChatMessage(Role.ASSISTANT, reply.trim())
                    working += ChatMessage(
                        Role.USER,
                        "[Tool result — not from the user]\n$result\n\nNow answer the user's question, or search/fetch again if you need more.",
                    )
                    streamingText = ""
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
        conversationId = UUID.randomUUID().toString()
        prefs.edit().putString("current_chat", conversationId).apply()
    }

    private fun finishStream() {
        val stripped = streamingText.replace(WebTools.searchTag, "").replace(WebTools.fetchTag, "")
            .replace(WebTools.downloadTag, "").trim()
        if (stripped.isNotBlank()) messages += ChatMessage(Role.ASSISTANT, stripped)
        persist()
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

    private fun today(): String =
        java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.US).format(java.util.Date())

    private companion object {
        const val MAX_WEB_STEPS = 6

        const val SYSTEM_PROMPT =
            "You are a helpful assistant in a mobile app. Answer clearly and concisely. " +
                "Use Markdown for code blocks and lists."

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
    }
}
