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
import kotlinx.serialization.json.putJsonObject

class AnthropicProvider : LlmProvider {
    override val id = ProviderId.CLAUDE

    private val base = "https://api.anthropic.com/v1"

    private fun headers(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val data = Http.getJson("$base/models?limit=100", headers(apiKey))["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ModelInfo(id, (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: id)
        }
    }

    private fun thinkingBudget(effort: Effort): Int? = when (effort) {
        Effort.HIGH -> 8_000
        Effort.MAX -> 24_000
        else -> null
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = fallbackOn400(
        primary = { request(apiKey, model, messages, system, thinkingBudget(effort)) },
        fallback = { request(apiKey, model, messages, system, null) },
    )

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        budget: Int?,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", if (budget != null) budget + 8_000 else 8_000)
            put("stream", true)
            put("system", system)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", m.text)
                    }
                }
            }
            if (budget != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
        }.toString()

        return Http.sse("$base/messages", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "content_block_delta" -> {
                val delta = o["delta"] as? JsonObject ?: return null
                if ((delta["type"] as? JsonPrimitive)?.contentOrNull == "text_delta") {
                    (delta["text"] as? JsonPrimitive)?.contentOrNull
                } else null
            }
            "error" -> {
                val err = o["error"] as? JsonObject
                throw ProviderException(500, (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Claude error")
            }
            else -> null
        }
    }
}
