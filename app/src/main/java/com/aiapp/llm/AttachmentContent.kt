package com.aiapp.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64

/**
 * Turns attachments into each provider's request format. Text files go in as text for every model;
 * photos and PDFs go in natively where the model supports them. Only the most recent attachments
 * are sent as files, so long chats don't resend every photo.
 */
object AttachmentContent {
    private const val MAX_INLINE_TEXT = 60_000
    private const val CHUNK = 1_500
    private const val RECENT_WITH_FILES = 3

    /** Indices of the user messages whose photos/PDFs should be sent as files. */
    fun binaryIndices(messages: List<ChatMessage>): Set<Int> =
        messages.withIndex()
            .filter { (_, m) -> m.role == Role.USER && m.attachments.any { it.isImage || it.isPdf } }
            .map { it.index }
            .takeLast(RECENT_WITH_FILES)
            .toSet()

    /** The message text plus any text files (or the most relevant parts of big ones). */
    fun text(m: ChatMessage, filesSentNatively: Boolean, canReadFiles: Boolean): String = buildString {
        append(m.text)
        m.attachments.forEach { a ->
            when {
                a.text != null -> {
                    append("\n\n[Attached file: ${a.name}]\n```\n")
                    append(relevant(a.text, m.text))
                    append("\n```")
                }
                filesSentNatively && canReadFiles -> Unit
                a.isImage -> append("\n\n[Attached photo: ${a.name} — this model can't see photos; ask the user to describe it or switch to Claude, Gemini or ChatGPT.]")
                a.isPdf -> append("\n\n[Attached PDF: ${a.name} — this model can't read PDFs; ask the user to switch to Claude, Gemini or ChatGPT.]")
                else -> append("\n\n[Attached file: ${a.name} (${a.mime}) — not readable as text.]")
            }
        }
    }

    /** For big files: the chunks that share the most words with the question, in file order. */
    private fun relevant(text: String, question: String): String {
        if (text.length <= MAX_INLINE_TEXT) return text
        val words = question.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val chunks = text.chunked(CHUNK)
        if (words.isEmpty()) return text.take(MAX_INLINE_TEXT) + "\n…(file truncated)"
        val scored = chunks.mapIndexed { i, c ->
            val lower = c.lowercase()
            i to words.sumOf { w -> Regex("\\b${Regex.escape(w)}").findAll(lower).count() }
        }
        val keep = scored.sortedByDescending { it.second }.take(MAX_INLINE_TEXT / CHUNK).map { it.first }.sorted()
        return "(Large file: showing the ${keep.size} most relevant of ${chunks.size} parts.)\n" +
            keep.joinToString("\n…\n") { chunks[it] }
    }

    private fun base64(a: Attachment): String? = runCatching {
        Base64.getEncoder().encodeToString(File(a.path).readBytes())
    }.getOrNull()

    private fun binaries(m: ChatMessage) = m.attachments.filter { it.isImage || it.isPdf }

    fun anthropic(m: ChatMessage, sendFiles: Boolean): JsonElement {
        val files = if (sendFiles) binaries(m) else emptyList()
        if (files.isEmpty()) return JsonPrimitive(text(m, false, true))
        return buildJsonArray {
            files.forEach { a ->
                val data = base64(a) ?: return@forEach
                addJsonObject {
                    put("type", if (a.isPdf) "document" else "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", a.mime)
                        put("data", data)
                    }
                }
            }
            addJsonObject {
                put("type", "text")
                put("text", text(m, true, true).ifBlank { "(see attachment)" })
            }
        }
    }

    fun geminiParts(m: ChatMessage, sendFiles: Boolean): JsonArray = buildJsonArray {
        val files = if (sendFiles) binaries(m) else emptyList()
        files.forEach { a ->
            val data = base64(a) ?: return@forEach
            addJsonObject {
                putJsonObject("inlineData") {
                    put("mimeType", a.mime)
                    put("data", data)
                }
            }
        }
        addJsonObject { put("text", text(m, files.isNotEmpty(), true).ifBlank { "(see attachment)" }) }
    }

    /** OpenAI-style content (also used by OpenRouter). */
    fun openAi(m: ChatMessage, sendFiles: Boolean): JsonElement {
        val files = if (sendFiles && m.role == Role.USER) binaries(m) else emptyList()
        if (files.isEmpty()) return JsonPrimitive(text(m, false, true))
        return buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text(m, true, true).ifBlank { "(see attachment)" })
            }
            files.forEach { a ->
                val data = base64(a) ?: return@forEach
                if (a.isImage) {
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:${a.mime};base64,$data") }
                    }
                } else {
                    addJsonObject {
                        put("type", "file")
                        putJsonObject("file") {
                            put("filename", a.name)
                            put("file_data", "data:application/pdf;base64,$data")
                        }
                    }
                }
            }
        }
    }
}
