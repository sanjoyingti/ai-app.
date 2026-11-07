package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GeminiProvider : LlmProvider {
    override val id = ProviderId.GEMINI

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private fun headers(key: String) = mapOf("x-goog-api-key" to key)

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val models = Http.getJson("$base/models?pageSize=200", headers(apiKey))["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if ("generateContent" !in methods || !name.contains("gemini")) return@mapNotNull null
            val id = name.removePrefix("models/")
            ModelInfo(id, (o["displayName"] as? JsonPrimitive)?.contentOrNull ?: id)
        }.sortedByDescending { it.id }
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val history = normalize(messages)
        val attempts = thinkingConfigs(model, effort).map { config ->
            { request(apiKey, model, buildBody(history, system, config)) }
        } + listOf({ request(apiKey, model, buildBody(history, system, null)) })
        return withFallbacks(attempts)
    }

    private fun request(apiKey: String, model: String, body: String): Flow<String> {
        val url = "$base/models/$model:streamGenerateContent?alt=sse"
        return Http.sse(url, headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    /** One thinkingConfig setting: a level (Gemini 3 and later) or a token budget (Gemini 2.5). */
    internal sealed interface Thinking {
        data class Level(val value: String) : Thinking
        data class Budget(val tokens: Int) : Thinking
    }

    internal companion object {
        private val levelModel = Regex("gemini-([3-9]|\\d{2})")

        /** Settings to try, best first; the caller always ends with a plain request. */
        fun thinkingConfigs(model: String, effort: Effort): List<Thinking> {
            val level = when (effort) {
                Effort.AUTO -> return emptyList()
                Effort.LOW -> Thinking.Level("low")
                Effort.MEDIUM -> Thinking.Level("medium")
                Effort.HIGH, Effort.MAX -> Thinking.Level("high")
            }
            val budget = when (effort) {
                Effort.LOW -> Thinking.Budget(0)
                Effort.MEDIUM -> Thinking.Budget(-1) // dynamic
                Effort.HIGH -> Thinking.Budget(8_192)
                else -> Thinking.Budget(24_576)
            }
            return if (levelModel.containsMatchIn(model)) listOf(level, budget) else listOf(budget, level)
        }

        fun buildBody(messages: List<ChatMessage>, system: String, thinking: Thinking?): String = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "model")
                        putJsonArray("parts") { addJsonObject { put("text", m.text) } }
                    }
                }
            }
            if (thinking != null) {
                putJsonObject("generationConfig") {
                    putJsonObject("thinkingConfig") {
                        when (thinking) {
                            is Thinking.Level -> put("thinkingLevel", thinking.value)
                            is Thinking.Budget -> put("thinkingBudget", thinking.tokens)
                        }
                    }
                }
            }
        }.toString()

        fun parse(data: String): String? {
            val o = Http.json.parseToJsonElement(data).jsonObject
            (o["error"] as? JsonObject)?.let { err ->
                throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Gemini error")
            }
            val blocked = ((o["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blocked != null) throw ProviderException(422, "Gemini declined this message ($blocked).")

            val candidate = (o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
            val text = parts.orEmpty().mapNotNull { p ->
                val part = p as? JsonObject ?: return@mapNotNull null
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                (part["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
            if (text.isEmpty()) {
                val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
                if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")) {
                    throw ProviderException(500, "Gemini stopped this reply ($finish).")
                }
                return null
            }
            return text
        }
    }
}
