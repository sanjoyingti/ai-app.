package com.aiapp.llm

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderTest {

    private fun obj(body: String): JsonObject = Http.json.parseToJsonElement(body).jsonObject

    private val chat = listOf(ChatMessage(Role.USER, "Hi"))

    // ---- Fallbacks and history ----

    @Test
    fun fallbackMovesOnAfter400WithNoText() = runTest {
        var tried = 0
        val out = withFallbacks(
            listOf(
                { tried++; flow<String> { throw ProviderException(400, "bad effort") } },
                { tried++; flow<String> { throw ProviderException(400, "still bad") } },
                { tried++; flowOf("ok") },
            ),
        ).toList()
        assertEquals(listOf("ok"), out)
        assertEquals(3, tried)
    }

    @Test
    fun fallbackPassesOnOtherErrors() = runTest {
        try {
            withFallbacks(
                listOf(
                    { flow<String> { throw ProviderException(401, "bad key") } },
                    { flowOf("never") },
                ),
            ).toList()
            fail("expected 401")
        } catch (e: ProviderException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun fallbackDoesNotRetryAfterTextArrived() = runTest {
        val seen = mutableListOf<String>()
        try {
            withFallbacks(
                listOf(
                    { flow { emit("partial"); throw ProviderException(400, "late") } },
                    { flowOf("again") },
                ),
            ).collect { seen += it }
            fail("expected 400")
        } catch (e: ProviderException) {
            assertEquals(listOf("partial"), seen)
        }
    }

    @Test
    fun normalizeMergesRepeatsAndDropsBlanksAndLeadingAssistant() {
        val out = normalize(
            listOf(
                ChatMessage(Role.ASSISTANT, "stray"),
                ChatMessage(Role.USER, "one"),
                ChatMessage(Role.USER, "two"),
                ChatMessage(Role.ASSISTANT, "  "),
                ChatMessage(Role.ASSISTANT, "reply"),
            ),
        )
        assertEquals(
            listOf(ChatMessage(Role.USER, "one\n\ntwo"), ChatMessage(Role.ASSISTANT, "reply")),
            out,
        )
    }

    // ---- Claude ----

    @Test
    fun claudeEffortUsesAdaptiveThinkingAndOutputConfig() {
        val body = obj(AnthropicProvider.buildBody("claude-opus-5-5", chat, "sys", "max", adaptive = true))
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(body["thinking"]!!.jsonObject.containsKey("budget_tokens"))
        assertEquals("32000", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun claudePlainRequestHasNoThinkingFields() {
        val body = obj(AnthropicProvider.buildBody("claude-haiku-5-5", chat, "sys", null, adaptive = false))
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun claudeEffortLevels() {
        assertNull(AnthropicProvider.effortValue(Effort.AUTO))
        assertEquals("low", AnthropicProvider.effortValue(Effort.LOW))
        assertEquals("medium", AnthropicProvider.effortValue(Effort.MEDIUM))
        assertEquals("high", AnthropicProvider.effortValue(Effort.HIGH))
        assertEquals("max", AnthropicProvider.effortValue(Effort.MAX))
    }

    @Test
    fun claudeParseKeepsTextAndSkipsThinking() {
        assertEquals(
            "Hello",
            AnthropicProvider.parse("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}"""),
        )
        assertNull(
            AnthropicProvider.parse("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}"""),
        )
        assertNull(AnthropicProvider.parse("""{"type":"message_stop"}"""))
    }

    @Test
    fun claudeOverloadedErrorIsReported() {
        try {
            AnthropicProvider.parse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
            fail("expected error")
        } catch (e: ProviderException) {
            assertEquals(529, e.code)
            assertEquals("Overloaded", e.message)
        }
    }

    // ---- ChatGPT ----

    @Test
    fun openAiMaxTriesXhighThenHigh() {
        assertEquals(listOf("xhigh", "high"), OpenAiProvider.effortValues("gpt-5.5", Effort.MAX))
        assertEquals(listOf("low"), OpenAiProvider.effortValues("o4-mini", Effort.LOW))
        assertTrue(OpenAiProvider.effortValues("gpt-4.1", Effort.HIGH).isEmpty())
        assertTrue(OpenAiProvider.effortValues("gpt-5.5", Effort.AUTO).isEmpty())
    }

    @Test
    fun openAiBodyUsesDeveloperRoleForReasoningModels() {
        val body = obj(OpenAiProvider.buildBody("gpt-5.5", chat, "sys", "high"))
        val first = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("developer", first["role"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)

        val plain = obj(OpenAiProvider.buildBody("gpt-4o", chat, "sys", null))
        assertEquals("system", plain["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse(plain.containsKey("reasoning_effort"))
    }

    @Test
    fun openAiModelFilter() {
        val kept = OpenAiProvider.filterChatModels(
            listOf("gpt-5.5", "gpt-4o-mini-tts", "o3", "text-embedding-3-small", "gpt-5.3-codex", "dall-e-3", "chatgpt-4o-latest"),
        )
        assertEquals(listOf("o3", "gpt-5.5", "chatgpt-4o-latest"), kept)
    }

    @Test
    fun openAiParse() {
        assertEquals("Hi", OpenAiProvider.parse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        assertNull(OpenAiProvider.parse("""{"choices":[{"index":0,"delta":{"role":"assistant"}}]}"""))
        assertNull(OpenAiProvider.parse("""{"choices":[]}"""))
    }

    // ---- Gemini ----

    @Test
    fun geminiPicksLevelOrBudgetByModel() {
        assertEquals(
            listOf(GeminiProvider.Thinking.Level("high"), GeminiProvider.Thinking.Budget(8_192)),
            GeminiProvider.thinkingConfigs("gemini-3.1-pro-preview", Effort.HIGH),
        )
        assertEquals(
            listOf(GeminiProvider.Thinking.Budget(0), GeminiProvider.Thinking.Level("low")),
            GeminiProvider.thinkingConfigs("gemini-2.5-flash", Effort.LOW),
        )
        assertTrue(GeminiProvider.thinkingConfigs("gemini-3.5-flash", Effort.AUTO).isEmpty())
    }

    @Test
    fun geminiBody() {
        val body = obj(GeminiProvider.buildBody(chat, "sys", GeminiProvider.Thinking.Level("low")))
        val thinking = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("low", thinking["thinkingLevel"]!!.jsonPrimitive.content)
        assertFalse(thinking.containsKey("thinkingBudget"))
        assertEquals("user", body["contents"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiParseSkipsThoughts() {
        val data = """{"candidates":[{"content":{"role":"model","parts":[{"text":"plan","thought":true},{"text":"Answer"}]}}]}"""
        assertEquals("Answer", GeminiProvider.parse(data))
        assertNull(GeminiProvider.parse("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""))
    }

    @Test
    fun geminiBlockedPromptIsReportedNotRetried() {
        try {
            GeminiProvider.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            fail("expected error")
        } catch (e: ProviderException) {
            assertTrue(e.code != 400)
        }
    }
}
