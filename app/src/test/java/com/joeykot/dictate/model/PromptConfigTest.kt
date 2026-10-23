package com.joeykot.dictate.model

import org.junit.Assert.*
import org.junit.Test

class PromptConfigTest {
    @Test fun providerCatalogUsesConfirmedIdsLabelsAndOrder() {
        assertEquals(
            listOf(
                "custom_openai_compatible" to "Custom / OpenAI-Compatible",
                "openai_responses" to "OpenAI / Responses",
                "openai_chat_completions" to "OpenAI / Chat Completions",
                "azure_openai_responses" to "Azure OpenAI / Responses",
                "azure_openai_chat_completions" to "Azure OpenAI / Chat Completions",
                "anthropic_claude" to "Anthropic / Claude",
                "google_gemini" to "Google / Gemini",
                "google_vertex_ai" to "Google / Vertex AI",
                "xai_grok_responses" to "xAI Grok / Responses",
                "xai_grok_chat_completions" to "xAI Grok / Chat Completions",
                "alibaba_qwen_text" to "Alibaba / Qwen Text",
                "alibaba_qwen_multimodal" to "Alibaba / Qwen Multimodal",
                "moonshot_kimi_responses" to "Moonshot Kimi / Responses",
                "moonshot_kimi_chat_completions" to "Moonshot Kimi / Chat Completions",
                "z_ai_glm" to "Z.AI / GLM",
                "deepseek_responses" to "DeepSeek / Responses",
                "deepseek_chat_completions" to "DeepSeek / Chat Completions",
                "volcengine_ark_chat_completions" to "Volcengine / Ark / Chat Completions",
                "volcengine_ark_responses" to "Volcengine / Ark / Responses",
                "baidu_qianfan" to "Baidu / Qianfan",
                "tencent_tokenhub" to "Tencent / TokenHub",
                "minimax_chat_completion_v2" to "MiniMax / ChatCompletion V2",
                "mistral_ai_mistral" to "Mistral AI / Mistral",
                "groq_cloud" to "Groq / Cloud",
                "cohere_compatibility_api" to "Cohere / Compatibility API",
            ),
            PostProcessingProvider.entries.map { it.id to it.label },
        )
        PostProcessingProvider.entries.forEach { provider ->
            assertEquals(provider, PostProcessingProvider.fromId(provider.id))
        }
        assertNull(PostProcessingProvider.fromId("OPENAI_COMPATIBLE"))
        assertNull(PostProcessingProvider.fromId("xai_grok"))
        assertNull(PostProcessingProvider.fromId("moonshot_ai_kimi"))
        assertNull(PostProcessingProvider.fromId("deepseek_deepseek"))
    }

    @Test fun defaultAndInheritedPromptsUseTheEntireCurrentMainApi() {
        assertNull(PromptConfig().provider)
        val prompt = PromptConfig(baseUrl = "ignored", model = "ignored")
        for (provider in PostProcessingProvider.entries) {
            val main = PostProcessingConfig(provider, "https://main.example", "main-model")
            val resolved = prompt.effectiveApi(main, "main-secret", "ignored-secret")
            assertEquals(main, resolved.config)
            assertEquals("main-secret", resolved.apiKey)
        }
    }

    @Test fun explicitProviderOwnsEveryFieldEvenWhenItMatchesMainOrHasBlanks() {
        for (provider in PostProcessingProvider.entries) {
            val main = PostProcessingConfig(provider, "https://main.example", "main-model")
            val prompt = PromptConfig(provider = provider, baseUrl = "https://prompt.example", model = "prompt-model")
            val resolved = prompt.effectiveApi(main, "main-secret", "prompt-secret")
            assertEquals(PostProcessingConfig(provider, "https://prompt.example", "prompt-model"), resolved.config)
            assertEquals("prompt-secret", resolved.apiKey)
            val empty = prompt.copy(baseUrl = "", model = "").effectiveApi(main, "main-secret", "")
            assertEquals("", empty.config.baseUrl)
            assertEquals("", empty.config.model)
            assertEquals("", empty.apiKey)
            assertFalse(resolved.toString().contains("prompt-secret"))
        }
    }
}
