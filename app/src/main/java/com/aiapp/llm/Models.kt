package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
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
 * Tries each request in order. If one is rejected with HTTP 400 before any text arrives (usually
 * because the model doesn't accept that reasoning setting), the next, plainer one is tried.
 * Any other error, or a 400 on the last attempt, is passed on.
 */
fun withFallbacks(attempts: List<() -> Flow<String>>): Flow<String> = flow {
    require(attempts.isNotEmpty())
    for ((index, attempt) in attempts.withIndex()) {
        var emitted = false
        try {
            attempt().collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ProviderException) {
            if (e.code != 400 || emitted || index == attempts.lastIndex) throw e
        }
    }
}

/**
 * Drops empty turns and merges back-to-back turns from the same side (left behind when a reply is
 * stopped before any text arrives), so every provider gets a clean user/assistant alternation that
 * starts with the user.
 */
fun normalize(messages: List<ChatMessage>): List<ChatMessage> {
    val out = ArrayList<ChatMessage>()
    for (m in messages) {
        if (m.text.isBlank()) continue
        val last = out.lastOrNull()
        if (last != null && last.role == m.role) {
            out[out.lastIndex] = ChatMessage(m.role, last.text + "\n\n" + m.text)
        } else if (out.isEmpty() && m.role == Role.ASSISTANT) {
            continue
        } else {
            out += m
        }
    }
    return out
}

object Providers {
    private val all: Map<ProviderId, LlmProvider> = listOf(
        AnthropicProvider(),
        GeminiProvider(),
        OpenAiProvider(),
    ).associateBy { it.id }

    fun get(id: ProviderId): LlmProvider = all.getValue(id)
}
