package com.joeykot.dictate.network

import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostProcessingRequestTest {
    private val prompt = PromptConfig(prompt = "Summarize this text")

    @Test
    fun chatProvidersUseSystemInstructionAndSeparateUserInput() {
        listOf(
            PostProcessingProvider.OPENAI_COMPATIBLE, PostProcessingProvider.OPENAI_COMPLETIONS,
            PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS, PostProcessingProvider.GLM,
            PostProcessingProvider.KIMI_CHAT_COMPLETIONS, PostProcessingProvider.VOLCENGINE_ARK_CHAT_COMPLETIONS,
            PostProcessingProvider.BAIDU_QIANFAN, PostProcessingProvider.TENCENT_TOKENHUB,
            PostProcessingProvider.MISTRAL, PostProcessingProvider.XAI_CHAT_COMPLETIONS,
            PostProcessingProvider.GROQ, PostProcessingProvider.COHERE,
        ).forEach { provider ->
            val request = request(provider)
            assertTrue(request.endpoint.endsWith("/chat/completions"))
            assertEquals("Bearer key", request.headers["Authorization"])
            val messages = request.body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals(prompt.prompt, messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            assertEquals("selected text", messages.getJSONObject(1).getString("content"))
        }
    }

    @Test
    fun responsesUsesInstructionsAndAzureChatUsesApiKey() {
        listOf(
            PostProcessingProvider.OPENAI_RESPONSES to "https://example.test/v1/responses",
            PostProcessingProvider.XAI_RESPONSES to "https://example.test/v1/responses",
            PostProcessingProvider.KIMI_RESPONSES to "https://example.test/v1/responses",
            PostProcessingProvider.DEEPSEEK_RESPONSES to "https://example.test/responses",
            PostProcessingProvider.AZURE_OPENAI_RESPONSES to "https://example.test/openai/v1/responses",
            PostProcessingProvider.VOLCENGINE_ARK_RESPONSES to "https://example.test/api/v3/responses",
        ).forEach { (provider, endpoint) ->
            val responses = request(provider)
            assertEquals(endpoint, responses.endpoint)
            assertEquals(prompt.prompt, responses.body.getString("instructions"))
            assertEquals("user", responses.body.getJSONArray("input").getJSONObject(0).getString("role"))
            assertEquals("selected text", responses.body.getJSONArray("input").getJSONObject(0).getString("content"))
            assertFalse(responses.body.has("messages"))
        }
        assertEquals("Bearer key", request(PostProcessingProvider.OPENAI_RESPONSES).headers["Authorization"])
        assertEquals("Bearer key", request(PostProcessingProvider.XAI_RESPONSES).headers["Authorization"])
        assertEquals("Bearer key", request(PostProcessingProvider.KIMI_RESPONSES).headers["Authorization"])
        assertEquals("Bearer key", request(PostProcessingProvider.DEEPSEEK_RESPONSES).headers["Authorization"])
        assertEquals("key", request(PostProcessingProvider.AZURE_OPENAI_RESPONSES).headers["api-key"])
        val azureChat = request(PostProcessingProvider.AZURE_OPENAI_CHAT_COMPLETIONS)
        assertEquals("https://example.test/openai/v1/chat/completions", azureChat.endpoint)
        assertEquals("key", azureChat.headers["api-key"])
        assertEquals(prompt.prompt, azureChat.body.getJSONArray("messages").getJSONObject(0).getString("content"))
        val anthropic = request(PostProcessingProvider.ANTHROPIC)
        assertEquals("https://example.test/v1/messages", anthropic.endpoint)
        assertEquals(prompt.prompt, anthropic.body.getString("system"))
        assertEquals(4096, anthropic.body.getInt("max_tokens"))
        assertEquals("key", anthropic.headers["x-api-key"])
        assertEquals("2023-06-01", anthropic.headers["anthropic-version"])
    }

    @Test
    fun qwenNativeProvidersUseTheirDistinctDashscopeRoutesAndPayloads() {
        val text = request(PostProcessingProvider.QWEN_TEXT)
        assertEquals("https://example.test/api/v1/services/aigc/text-generation/generation", text.endpoint)
        assertEquals("Bearer key", text.headers["Authorization"])
        assertEquals("enable", text.headers["X-DashScope-SSE"])
        assertEquals(prompt.prompt, text.body.getJSONObject("input").getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("message", text.body.getJSONObject("parameters").getString("result_format"))
        assertTrue(text.body.getJSONObject("parameters").getBoolean("incremental_output"))

        val multimodal = request(PostProcessingProvider.QWEN_MULTIMODAL)
        assertEquals("https://example.test/api/v1/services/aigc/multimodal-generation/generation", multimodal.endpoint)
        val messages = multimodal.body.getJSONObject("input").getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(prompt.prompt, messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("selected text", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun minimaxUsesNativeChatCompletionV2Route() {
        val request = request(PostProcessingProvider.MINIMAX_CHAT_COMPLETION_V2)
        assertEquals("https://example.test/v1/text/chatcompletion_v2", request.endpoint)
        assertEquals(prompt.prompt, request.body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun geminiProvidersUseOverriddenModelInUrlAndNestedSystemInstruction() {
        listOf(
            PostProcessingProvider.GOOGLE to "https://example.test",
            PostProcessingProvider.VERTEX_AI to "https://example.test/v1/projects/project/locations/global/publishers/google",
        ).forEach { (provider, baseUrl) ->
            val request = PostProcessingRequest.build(
                PostProcessingConfig(provider, baseUrl, "default"),
                "key", prompt.copy(additionalJson = """{"model":"models/alternate","generationConfig":{"temperature":0.3}}"""), "selected text",
            )
            val endpoint = if (provider == PostProcessingProvider.GOOGLE) {
                "https://example.test/v1beta/models/alternate:generateContent"
            } else {
                "https://example.test/v1/projects/project/locations/global/publishers/google/models/alternate:generateContent"
            }
            assertEquals(endpoint, request.endpoint)
            assertFalse(request.body.has("model"))
            assertEquals(prompt.prompt, request.body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
            if (provider == PostProcessingProvider.GOOGLE) assertEquals("key", request.headers["x-goog-api-key"])
            else assertEquals("Bearer key", request.headers["Authorization"])
        }
    }

    @Test
    fun finalMergeCanOverrideModelAndRemoveGeneratedFields() {
        val request = PostProcessingRequest.build(
            PostProcessingConfig(PostProcessingProvider.ANTHROPIC, "https://example.test", ""), "key",
            prompt.copy(additionalJson = """{"model":"other","max_tokens":null,"system":null,"metadata":{"a":null,"b":1}}"""), "text",
        )
        assertEquals("other", request.body.getString("model"))
        assertFalse(request.body.has("max_tokens"))
        assertFalse(request.body.has("system"))
        assertFalse(request.body.getJSONObject("metadata").has("a"))
        assertThrows(IllegalArgumentException::class.java) {
            PostProcessingRequest.build(
                PostProcessingConfig(baseUrl = "https://example.test", model = "default"), "key",
                prompt.copy(additionalJson = """{"model":null}"""), "text",
            )
        }
    }

    @Test
    fun additionalJsonIsMergedForEveryProvider() {
        PostProcessingProvider.entries.forEach { provider ->
            val baseUrl = if (provider == PostProcessingProvider.VERTEX_AI) {
                "https://example.test/v1/projects/project/locations/global/publishers/google"
            } else {
                "https://example.test"
            }
            val request = PostProcessingRequest.build(
                PostProcessingConfig(provider, baseUrl, "model"), "key",
                prompt.copy(additionalJson = """{"metadata":{"source":"test"}}"""), "selected text",
            )
            assertEquals("test", request.body.getJSONObject("metadata").getString("source"))
        }
    }

    @Test
    fun nativeAndCompatibleResponsesReturnOnlyVisibleText() {
        listOf(
            PostProcessingProvider.OPENAI_COMPATIBLE, PostProcessingProvider.XAI_CHAT_COMPLETIONS,
            PostProcessingProvider.KIMI_CHAT_COMPLETIONS, PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS,
        ).forEach { provider ->
            assertEquals("answer", PostProcessingRequest.parseText(provider,
                """{"choices":[{"message":{"content":"answer","reasoning_content":"hidden"},"finish_reason":"stop"}]}"""))
        }
        listOf(
            PostProcessingProvider.OPENAI_RESPONSES, PostProcessingProvider.XAI_RESPONSES,
            PostProcessingProvider.KIMI_RESPONSES, PostProcessingProvider.DEEPSEEK_RESPONSES,
        ).forEach { provider ->
            assertEquals("answer", PostProcessingRequest.parseText(provider,
                """{"status":"completed","output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"hidden"}],"summary":[{"type":"summary_text","text":"hidden"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}"""))
        }
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.AZURE_OPENAI_RESPONSES,
            """{"status":"completed","output_text":"answer"}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.ANTHROPIC,
            """{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"answer"}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.GOOGLE,
            """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"answer"}]},"finishReason":"STOP"}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.VERTEX_AI,
            """{"candidates":[{"content":{"parts":[{"text":"answer"}]},"finishReason":"STOP"}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.QWEN_TEXT,
            """{"output":{"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}]}}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.QWEN_MULTIMODAL,
            """{"output":{"choices":[{"message":{"content":[{"text":"answer"}]},"finish_reason":"stop"}]}}"""))
    }

    @Test
    fun kimiResponsesPreservesSearchOptionsAndReturnsOnlyTheFinalAnswer() {
        val request = PostProcessingRequest.build(
            PostProcessingConfig(PostProcessingProvider.KIMI_RESPONSES, "https://api.moonshot.ai", "kimi-k3"),
            "key",
            prompt.copy(additionalJson = """{"tools":[{"type":"web_search"}],"reasoning":{"effort":"low"}}"""),
            "selected text",
        )
        assertEquals("https://api.moonshot.ai/v1/responses", request.endpoint)
        assertEquals("kimi-k3", request.body.getString("model"))
        assertEquals(prompt.prompt, request.body.getString("instructions"))
        assertEquals("selected text", request.body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("web_search", request.body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("low", request.body.getJSONObject("reasoning").getString("effort"))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.KIMI_RESPONSES,
            """{"status":"completed","output":[{"type":"web_search_call","status":"completed","action":{"type":"search","query":"news"}},{"type":"reasoning","summary":[{"type":"summary_text","text":"hidden"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}"""))
    }

    @Test
    fun missingTextAndMalformedResponseAreFailures() {
        listOf("bad JSON", "{}", """{"choices":[{"message":{"content":null}}]}""").forEach {
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, it)
            }
        }
    }

    @Test
    fun incompleteStatusesAndFinishReasonsAreRejected() {
        listOf(
            PostProcessingProvider.AZURE_OPENAI_RESPONSES, PostProcessingProvider.XAI_RESPONSES,
            PostProcessingProvider.KIMI_RESPONSES, PostProcessingProvider.DEEPSEEK_RESPONSES,
        ).forEach { provider ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(provider, """{"status":"incomplete","output_text":"partial"}""")
            }
        }
        listOf("length", "tool_calls").forEach { reason ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(PostProcessingProvider.KIMI_CHAT_COMPLETIONS,
                    """{"choices":[{"message":{"content":"partial"},"finish_reason":"$reason"}]}""")
            }
        }
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingRequest.parseText(PostProcessingProvider.GOOGLE,
                """{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"CONTINUATION"}]}""")
        }
        listOf(
            """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":""}]}""",
        ).forEach { response ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(PostProcessingProvider.GOOGLE, response)
            }
        }
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingRequest.parseText(PostProcessingProvider.VERTEX_AI,
                """{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"FINISH_REASON_UNSPECIFIED"}]}""")
        }
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingRequest.parseText(PostProcessingProvider.MISTRAL,
                """{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}""")
        }
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingRequest.parseText(PostProcessingProvider.ANTHROPIC,
                """{"content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens"}""")
        }
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingRequest.parseText(PostProcessingProvider.QWEN_TEXT,
                """{"output":{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}}""")
        }
        listOf(PostProcessingProvider.QWEN_TEXT, PostProcessingProvider.QWEN_MULTIMODAL).forEach { provider ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(provider,
                    """{"output":{"choices":[{"message":{"content":"partial"},"finish_reason":"null"}]}}""")
            }
        }
    }

    @Test
    fun endpointsPreserveGatewayPathsAndAcceptCompleteEndpoints() {
        listOf(
            PostProcessingProvider.DEEPSEEK_RESPONSES to "/responses",
            PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS to "/chat/completions",
        ).forEach { (provider, suffix) ->
            listOf("", "/v1", "/proxy/v1").forEach { prefix ->
                val base = "https://api.deepseek.com$prefix"
                listOf(base, "$base/", "$base$suffix").forEach { baseUrl ->
                    assertEquals("$base$suffix", BaseUrl.postProcessingEndpoint(baseUrl, provider, "custom-model"))
                }
            }
        }
        listOf(
            Triple(PostProcessingProvider.XAI_RESPONSES, "https://api.x.ai", "/responses"),
            Triple(PostProcessingProvider.XAI_CHAT_COMPLETIONS, "https://api.x.ai", "/chat/completions"),
            Triple(PostProcessingProvider.KIMI_RESPONSES, "https://api.moonshot.ai", "/responses"),
            Triple(PostProcessingProvider.KIMI_CHAT_COMPLETIONS, "https://api.moonshot.ai", "/chat/completions"),
        ).forEach { (provider, origin, suffix) ->
            listOf(origin, "$origin/v1/", "$origin/v1$suffix").forEach { baseUrl ->
                assertEquals("$origin/v1$suffix", BaseUrl.postProcessingEndpoint(baseUrl, provider, "m"))
            }
            assertEquals("https://example.test/proxy/v1$suffix", BaseUrl.postProcessingEndpoint("https://example.test/proxy/v1/", provider, "m"))
        }
        assertEquals("https://example.test/proxy/v1/responses", BaseUrl.postProcessingEndpoint("https://example.test/proxy/v1/", PostProcessingProvider.OPENAI_RESPONSES, "m"))
        assertEquals("https://example.test/v1/messages", BaseUrl.postProcessingEndpoint("https://example.test/v1/messages", PostProcessingProvider.ANTHROPIC, "m"))
        assertEquals("https://example.test/api/paas/v4/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.GLM, "m"))
        assertEquals("https://example.test/api/v1/services/aigc/text-generation/generation", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.QWEN_TEXT, "m"))
        assertEquals("https://example.test/api/v1/services/aigc/multimodal-generation/generation", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.QWEN_MULTIMODAL, "m"))
        assertEquals("https://example.test/api/v3/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.VOLCENGINE_ARK_CHAT_COMPLETIONS, "m"))
        assertEquals("https://example.test/api/v3/responses", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.VOLCENGINE_ARK_RESPONSES, "m"))
        assertEquals("https://example.test/v2/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.BAIDU_QIANFAN, "m"))
        assertEquals("https://example.test/openai/v1/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.GROQ, "m"))
        assertEquals("https://example.test/compatibility/v1/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.COHERE, "m"))
        assertEquals("https://example.test/openai/v1/responses", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.AZURE_OPENAI_RESPONSES, "m"))
        assertEquals("https://example.test/openai/v1/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.AZURE_OPENAI_CHAT_COMPLETIONS, "m"))
        assertEquals("https://example.test/v1/text/chatcompletion_v2", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.MINIMAX_CHAT_COMPLETION_V2, "m"))
        assertEquals("https://example.test/v1beta/models/new:generateContent", BaseUrl.postProcessingEndpoint("https://example.test/v1beta/models/old:generateContent", PostProcessingProvider.GOOGLE, "new"))
        assertEquals("https://example.test/v1/projects/project/locations/global/publishers/google/models/new:generateContent", BaseUrl.postProcessingEndpoint("https://example.test/v1/projects/project/locations/global/publishers/google/models/old:generateContent", PostProcessingProvider.VERTEX_AI, "new"))
        assertThrows(IllegalArgumentException::class.java) {
            BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.VERTEX_AI, "m")
        }
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.postProcessingEndpoint("https://user:pass@example.test", PostProcessingProvider.OPENAI_COMPATIBLE, "m") }
    }

    private fun request(provider: PostProcessingProvider) = PostProcessingRequest.build(
        PostProcessingConfig(provider, "https://example.test", "model"), "key", prompt, "selected text",
    )
}
