package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

enum class ProviderId(val label: String) {
    CLAUDE("Claude"),
    GEMINI("Gemini"),
    OPENAI("ChatGPT"),
}

/** Effort controls how hard the model thinks. AUTO leaves it to the provider's default. */
enum class Effort(val label: String) {
    AUTO("Auto"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    MAX("Max"),
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

data class ModelInfo(val id: String, val name: String)

class ProviderException(val code: Int, message: String) : Exception(message)

interface LlmProvider {
    val id: ProviderId
    suspend fun listModels(apiKey: String): List<ModelInfo>
    fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String>
}

/**
 * Runs [primary]; if it is rejected with HTTP 400 before any text arrives (usually because the
 * model doesn't accept a reasoning setting), runs [fallback] instead.
 */
fun fallbackOn400(primary: () -> Flow<String>, fallback: () -> Flow<String>): Flow<String> = flow {
    var emitted = false
    try {
        primary().collect {
            emitted = true
            emit(it)
        }
    } catch (e: ProviderException) {
        if (e.code == 400 && !emitted) emitAll(fallback()) else throw e
    }
}

object Providers {
    private val all: Map<ProviderId, LlmProvider> = listOf(
        AnthropicProvider(),
        GeminiProvider(),
        OpenAiProvider(),
    ).associateBy { it.id }

    fun get(id: ProviderId): LlmProvider = all.getValue(id)
}
