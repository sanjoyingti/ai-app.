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

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val history = normalize(messages)
        val level = effortValue(effort)
            ?: return request(apiKey, buildBody(model, history, system, null, adaptive = false))
        // Current models: adaptive thinking steered by effort. Older models may reject one or
        // both fields, so each retry drops one.
        return withFallbacks(
            listOf(
                { request(apiKey, buildBody(model, history, system, level, adaptive = true)) },
                { request(apiKey, buildBody(model, history, system, level, adaptive = false)) },
                { request(apiKey, buildBody(model, history, system, null, adaptive = false)) },
            ),
        )
    }

    private fun request(apiKey: String, body: String): Flow<String> =
        Http.sse("$base/messages", headers(apiKey), body).mapNotNull { data -> parse(data) }

    internal companion object {
        /** Claude's own effort levels (output_config.effort). AUTO sends none, so the model default applies. */
        fun effortValue(effort: Effort): String? = when (effort) {
            Effort.AUTO -> null
            Effort.LOW -> "low"
            Effort.MEDIUM -> "medium"
            Effort.HIGH -> "high"
            Effort.MAX -> "max"
        }

        fun buildBody(
            model: String,
            messages: List<ChatMessage>,
            system: String,
            effort: String?,
            adaptive: Boolean,
        ): String = buildJsonObject {
            put("model", model)
            // A hard cap on thinking plus reply; higher effort needs more room.
            put("max_tokens", if (effort == "high" || effort == "max") 32_000 else 16_000)
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
            if (adaptive) putJsonObject("thinking") { put("type", "adaptive") }
            if (effort != null) putJsonObject("output_config") { put("effort", effort) }
        }.toString()

        /** Returns the reply text in one stream event, or null for events that carry none (including thinking). */
        fun parse(data: String): String? {
            val o = Http.json.parseToJsonElement(data).jsonObject
            return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "content_block_delta" -> {
                    val delta = o["delta"] as? JsonObject ?: return null
                    if ((delta["type"] as? JsonPrimitive)?.contentOrNull == "text_delta") {
                        (delta["text"] as? JsonPrimitive)?.contentOrNull
                    } else {
                        null
                    }
                }
                "error" -> {
                    val err = o["error"] as? JsonObject
                    val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull
                    val code = when (type) {
                        "overloaded_error" -> 529
                        "rate_limit_error" -> 429
                        else -> 500
                    }
                    throw ProviderException(code, (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Claude error")
                }
                else -> null
            }
        }
    }
}
