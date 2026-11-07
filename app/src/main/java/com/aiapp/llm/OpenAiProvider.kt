package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class OpenAiProvider : LlmProvider {
    override val id = ProviderId.OPENAI

    private val base = "https://api.openai.com/v1"

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        val ids = data.mapNotNull { el -> ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
        return filterChatModels(ids).map { ModelInfo(it, it) }
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val history = normalize(messages)
        val attempts = effortValues(model, effort).map { value ->
            { request(apiKey, buildBody(model, history, system, value)) }
        } + listOf({ request(apiKey, buildBody(model, history, system, null)) })
        return withFallbacks(attempts)
    }

    private fun request(apiKey: String, body: String): Flow<String> =
        Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }

    internal companion object {
        private val excluded = listOf(
            "audio", "realtime", "tts", "transcribe", "image", "search", "embedding",
            "moderation", "whisper", "dall-e", "instruct", "codex",
        )

        private val reasoningPattern = Regex("^(o\\d|gpt-([5-9]|\\d{2}))")

        fun isReasoning(model: String): Boolean = reasoningPattern.containsMatchIn(model)

        fun filterChatModels(ids: List<String>): List<String> = ids
            .filter { id ->
                (id.startsWith("gpt") || id.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(id)) &&
                    excluded.none { id.contains(it) }
            }
            .sortedDescending()

        /**
         * reasoning_effort values to try, best first. Max asks for xhigh and steps down to high on
         * models without it. Non-reasoning models and AUTO send none.
         */
        fun effortValues(model: String, effort: Effort): List<String> {
            if (!isReasoning(model)) return emptyList()
            return when (effort) {
                Effort.AUTO -> emptyList()
                Effort.LOW -> listOf("low")
                Effort.MEDIUM -> listOf("medium")
                Effort.HIGH -> listOf("high")
                Effort.MAX -> listOf("xhigh", "high")
            }
        }

        fun buildBody(model: String, messages: List<ChatMessage>, system: String, effort: String?): String =
            buildJsonObject {
                put("model", model)
                put("stream", true)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", if (isReasoning(model)) "developer" else "system")
                        put("content", system)
                    }
                    messages.forEach { m ->
                        addJsonObject {
                            put("role", if (m.role == Role.USER) "user" else "assistant")
                            put("content", m.text)
                        }
                    }
                }
                if (effort != null) put("reasoning_effort", effort)
            }.toString()

        fun parse(data: String): String? {
            val o = Http.json.parseToJsonElement(data).jsonObject
            (o["error"] as? JsonObject)?.let { err ->
                throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "ChatGPT error")
            }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val delta = choice["delta"] as? JsonObject ?: return null
            return (delta["content"] as? JsonPrimitive)?.contentOrNull
        }
    }
}
