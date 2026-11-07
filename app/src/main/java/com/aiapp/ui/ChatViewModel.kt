package com.aiapp.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.data.ApiKeys
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
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
    private val prefs = app.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<ChatMessage>()

    /** The text in the message box. Kept here so a failed send can put it back. */
    var input by mutableStateOf("")

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
    private var turnId = 0

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
        if (k.isBlank()) {
            error = "Add your ${p.label} API key in Settings."
            return
        }
        loadingModels = true
        viewModelScope.launch {
            try {
                val list = Providers.get(p).listModels(k)
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

    fun send(text: String = input) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val k = keys.get(provider)
        if (k.isBlank()) {
            error = "Add your ${provider.label} API key in Settings."
            return
        }
        if (model.isBlank()) {
            error = "Pick a model first."
            return
        }
        error = null
        input = ""
        messages += ChatMessage(Role.USER, trimmed)
        val history = messages.toList()
        val p = provider
        val m = model
        val e = effort

        val turn = ++turnId
        streaming = true
        streamingText = ""
        status = "Thinking…"
        job = viewModelScope.launch {
            try {
                Providers.get(p).stream(k, m, history, e, SYSTEM_PROMPT).collect { delta ->
                    if (turn != turnId) return@collect
                    if (status != "Writing…") status = "Writing…"
                    streamingText += delta
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (turn == turnId) {
                    error = friendly(t, p)
                    // Nothing came back: take the message out of the chat and put it back in the box.
                    if (streamingText.isBlank() && messages.lastOrNull()?.role == Role.USER) {
                        messages.removeAt(messages.lastIndex)
                        if (input.isBlank()) input = trimmed
                    }
                }
            } finally {
                if (turn == turnId) finishStream()
            }
        }
    }

    /** Stops the reply; whatever was already written stays in the chat. */
    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        job?.cancel()
        job = null
        turnId++ // the cancelled reply, if any, must not land in the new chat
        streamingText = ""
        status = null
        streaming = false
        messages.clear()
        error = null
    }

    private fun finishStream() {
        if (streamingText.isNotBlank()) messages += ChatMessage(Role.ASSISTANT, streamingText)
        streamingText = ""
        status = null
        streaming = false
    }

    private fun friendly(t: Throwable, p: ProviderId = provider): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) ->
            "Your ${p.label} key was rejected. Check it in Settings."
        t is ProviderException && t.code == 429 ->
            "Rate limit reached. Wait a moment and try again."
        t is ProviderException && (t.code == 529 || t.code == 503) ->
            "${p.label} is busy right now. Try again in a moment."
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
