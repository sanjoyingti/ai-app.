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

    /** Files picked for the next message. */
    val pendingAttachments = mutableStateListOf<com.aiapp.llm.Attachment>()
    var attaching by mutableStateOf(false)
        private set

    fun attach(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        attaching = true
        viewModelScope.launch {
            uris.forEach { uri ->
                try {
                    pendingAttachments += com.aiapp.data.AttachmentStore.import(getApplication(), uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e.message ?: "Couldn't attach that file."
                }
            }
            attaching = false
        }
    }

    fun removeAttachment(a: com.aiapp.llm.Attachment) {
        pendingAttachments.remove(a)
    }

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

    fun saveGithubToken(value: String) {
        keys.setExtra("GITHUB", value)
        githubUser = if (value.isBlank()) "" else githubUser
        if (value.isNotBlank()) refreshGithubUser()
    }

    // ---- Sign in with GitHub (device flow) ----

    var githubUser by mutableStateOf(keys.getExtra("GITHUB_USER"))
        private set
    var githubCode by mutableStateOf<com.aiapp.builder.GitHubLogin.DeviceCode?>(null)
        private set
    var githubLoginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: Job? = null

    fun githubClientId(): String = keys.getExtra("GITHUB_CLIENT_ID").ifBlank { com.aiapp.builder.GitHubLogin.DEFAULT_CLIENT_ID }

    fun saveGithubClientId(value: String) = keys.setExtra("GITHUB_CLIENT_ID", value)

    /** Starts sign-in; [openBrowser] opens github.com/login/device where you enter the code. */
    fun signInGithub(openBrowser: (String) -> Unit) {
        val clientId = githubClientId()
        if (clientId.isBlank()) {
            githubLoginError = "Add the GitHub Client ID first (see the steps below)."
            return
        }
        loginJob?.cancel()
        githubLoginError = null
        loginJob = viewModelScope.launch {
            try {
                val code = com.aiapp.builder.GitHubLogin.start(clientId)
                githubCode = code
                openBrowser(code.verificationUri)
                val token = com.aiapp.builder.GitHubLogin.waitForToken(clientId, code)
                keys.setExtra("GITHUB", token)
                refreshGithubUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                githubLoginError = e.message ?: "Sign-in failed."
            } finally {
                githubCode = null
            }
        }
    }

    fun signOutGithub() {
        loginJob?.cancel()
        keys.setExtra("GITHUB", "")
        keys.setExtra("GITHUB_USER", "")
        githubUser = ""
    }

    private fun refreshGithubUser() {
        val token = keys.getExtra("GITHUB")
        viewModelScope.launch {
            var result: Result<String> = Result.failure(Exception())
            for (attempt in 0 until 3) {
                result = runCatching { com.aiapp.builder.GitHubClient(token).login() }
                if (result.isSuccess) break
                kotlinx.coroutines.delay(3000)
            }
            result.onSuccess {
                githubUser = it
                keys.setExtra("GITHUB_USER", it)
            }
        }
    }

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

    /** Set when chat starts a build, so the screen can switch to Build. */
    var openBuild by mutableStateOf(false)

    private val buildWords = Regex("\\b(apk|build|compile|compil)", RegexOption.IGNORE_CASE)

    /** Compiles an uploaded Android Studio project (.zip) into an APK on GitHub. */
    fun buildZip(zip: com.aiapp.llm.Attachment) {
        val token = githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings first, then try again."
            return
        }
        if (com.aiapp.builder.BuildRunner.running) {
            error = "A build is already running. Open Build to see it."
            return
        }
        val app = getApplication<Application>()
        com.aiapp.builder.BuildRunner.newProject(app)
        com.aiapp.builder.BuildService.start(
            app,
            com.aiapp.builder.BuildRequest(
                description = "Compile ${zip.name} to an APK",
                appName = zip.name.removeSuffix(".zip"),
                provider = provider,
                model = model,
                apiKey = keys.get(provider),
                effort = effort,
                githubToken = token,
                allowOtherSites = false,
                zipPath = zip.path,
                zipName = zip.name,
            ),
        )
        messages += ChatMessage(
            Role.ASSISTANT,
            "Building **${zip.name}** into an APK on your GitHub. If the build hits errors, ${provider.label} will try to fix them. " +
                "Follow the progress on the Build screen; you'll also get a notification when it's ready.",
        )
        persist()
        openBuild = true
    }

    /** Use the provider's fastest smart model for voice (on by default). */
    var voiceFastModel by mutableStateOf(prefs.getBoolean("voice_fast", true))
        private set

    fun toggleVoiceFastModel() {
        voiceFastModel = !voiceFastModel
        prefs.edit().putBoolean("voice_fast", voiceFastModel).apply()
    }

    /** The model voice turns use: a quick one from the same provider when "fast" is on. */
    fun voiceModel(): String {
        if (!voiceFastModel) return model
        val ids = models.map { it.id }
        fun pick(vararg keys: String, exclude: List<String> = emptyList()) =
            keys.firstNotNullOfOrNull { k -> ids.firstOrNull { id -> id.contains(k) && exclude.none { id.contains(it) } } }
        val fast = when (provider) {
            ProviderId.CLAUDE -> pick("haiku", "sonnet")
            ProviderId.GEMINI -> pick("flash", exclude = listOf("lite", "image", "tts", "live", "audio"))
            ProviderId.OPENAI -> pick("mini", exclude = listOf("audio", "realtime", "tts", "transcribe", "search"))
            else -> null
        }
        return fast ?: model
    }

    /** Voice turns: short spoken answers, a fast model and light thinking, so replies start quickly. */
    fun sendVoice(text: String) = send(text, voice = true)

    /** Loads the offline model ahead of time so the first voice reply doesn't wait for it. */
    fun warmUp() {
        if (provider != ProviderId.OFFLINE || model.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) { runCatching { Providers.offline.preload(model) } }
    }

    /** Counts finished replies (including empty or failed ones), so voice mode knows when to listen again. */
    var replyCount by mutableStateOf(0)
        private set

    fun send(text: String, voice: Boolean = false) {
        val trimmed = text.trim()
        if (streaming) stop()
        if (trimmed.isEmpty() && pendingAttachments.isEmpty()) return
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
        val attached = pendingAttachments.toList()
        val zip = attached.firstOrNull { it.isZip }
        if (zip != null && buildWords.containsMatchIn(trimmed)) {
            messages += ChatMessage(Role.USER, trimmed, attached)
            pendingAttachments.clear()
            buildZip(zip)
            return
        }
        messages += ChatMessage(Role.USER, trimmed, attached)
        pendingAttachments.clear()
        val history = messages.toList()
        val p = provider
        val m = if (voice) voiceModel() else model
        // Voice: light thinking so the first words come almost immediately.
        val e = if (voice) Effort.LOW else effort

        streaming = true
        streamingText = ""
        status = if (p == ProviderId.OFFLINE) "Loading offline model…" else "Thinking…"
        val web = webEnabled
        val base = if (voice) SYSTEM_PROMPT + VOICE_PROMPT else SYSTEM_PROMPT
        val system = if (web) base + WebTools.instructions(today()) else base
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
                    if (!web || steps >= (if (voice) 1 else MAX_WEB_STEPS)) break
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
        replyCount++
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

        const val VOICE_PROMPT =
            " This is a live voice conversation and your reply is read aloud. Talk like a warm, sharp, " +
                "knowledgeable friend: natural spoken sentences, contractions, no lists, headings, tables, " +
                "code or emoji. Lead with the answer. Keep it short (one to three sentences) for simple " +
                "questions; give more detail when the user asks for it or the question needs it. Use what " +
                "was said earlier in the conversation. If something is ambiguous, ask one quick question. " +
                "Only search the web when the question needs current information."

        const val SYSTEM_PROMPT =
            "You are a helpful assistant in a mobile app. Answer clearly and concisely. " +
                "Use Markdown for code blocks and lists."

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
    }
}
