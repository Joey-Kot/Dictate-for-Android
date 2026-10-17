package com.joeykot.dictate.model

import java.util.UUID

enum class PostProcessingWireFormat {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    GEMINI,
    ANTHROPIC,
    DASHSCOPE_TEXT,
    DASHSCOPE_MULTIMODAL,
    MINIMAX_CHAT_COMPLETION_V2,
}

enum class PostProcessingProvider(
    val id: String,
    val label: String,
    val wireFormat: PostProcessingWireFormat,
) {
    OPENAI_COMPATIBLE(
        "custom_openai_compatible",
        "Custom / OpenAI-Compatible",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    OPENAI_RESPONSES(
        "openai_responses",
        "OpenAI / Responses",
        PostProcessingWireFormat.OPENAI_RESPONSES,
    ),
    OPENAI_COMPLETIONS(
        "openai_chat_completions",
        "OpenAI / Chat Completions",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    AZURE_OPENAI_RESPONSES(
        "azure_openai_responses",
        "Azure OpenAI / Responses",
        PostProcessingWireFormat.OPENAI_RESPONSES,
    ),
    AZURE_OPENAI_CHAT_COMPLETIONS(
        "azure_openai_chat_completions",
        "Azure OpenAI / Chat Completions",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    ANTHROPIC(
        "anthropic_claude",
        "Anthropic / Claude",
        PostProcessingWireFormat.ANTHROPIC,
    ),
    GOOGLE(
        "google_gemini",
        "Google / Gemini",
        PostProcessingWireFormat.GEMINI,
    ),
    VERTEX_AI(
        "google_vertex_ai",
        "Google / Vertex AI",
        PostProcessingWireFormat.GEMINI,
    ),
    XAI("xai_grok", "xAI / Grok", PostProcessingWireFormat.OPENAI_CHAT),
    QWEN_TEXT(
        "alibaba_qwen_text",
        "Alibaba / Qwen Text",
        PostProcessingWireFormat.DASHSCOPE_TEXT,
    ),
    QWEN_MULTIMODAL(
        "alibaba_qwen_multimodal",
        "Alibaba / Qwen Multimodal",
        PostProcessingWireFormat.DASHSCOPE_MULTIMODAL,
    ),
    KIMI("moonshot_ai_kimi", "Moonshot AI / Kimi", PostProcessingWireFormat.OPENAI_CHAT),
    GLM("z_ai_glm", "Z.AI / GLM", PostProcessingWireFormat.OPENAI_CHAT),
    DEEPSEEK(
        "deepseek_deepseek",
        "DeepSeek / DeepSeek",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    VOLCENGINE_ARK_CHAT_COMPLETIONS(
        "volcengine_ark_chat_completions",
        "Volcengine / Ark / Chat Completions",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    VOLCENGINE_ARK_RESPONSES(
        "volcengine_ark_responses",
        "Volcengine / Ark / Responses",
        PostProcessingWireFormat.OPENAI_RESPONSES,
    ),
    BAIDU_QIANFAN(
        "baidu_qianfan",
        "Baidu / Qianfan",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    TENCENT_TOKENHUB(
        "tencent_tokenhub",
        "Tencent / TokenHub",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    MINIMAX_CHAT_COMPLETION_V2(
        "minimax_chat_completion_v2",
        "MiniMax / ChatCompletion V2",
        PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2,
    ),
    MISTRAL(
        "mistral_ai_mistral",
        "Mistral AI / Mistral",
        PostProcessingWireFormat.OPENAI_CHAT,
    ),
    GROQ("groq_cloud", "Groq / Cloud", PostProcessingWireFormat.OPENAI_CHAT),
    COHERE(
        "cohere_compatibility_api",
        "Cohere / Compatibility API",
        PostProcessingWireFormat.OPENAI_CHAT,
    );

    companion object {
        fun fromId(id: String?): PostProcessingProvider? = entries.find { it.id == id }
    }
}

data class PromptConfig(
    val id: String = UUID.randomUUID().toString(),
    val icon: String = "document",
    val customIcon: String? = null,
    val title: String = "",
    val prompt: String = "",
    val additionalJson: String = "",
    /** Null means Same as main provider, including all of its API fields. */
    val provider: PostProcessingProvider? = null,
    val baseUrl: String = "",
    val model: String = "",
) {
    fun effectiveApi(main: PostProcessingConfig, mainKey: String, promptKey: String): ResolvedPostProcessingApi =
        if (provider == null) {
            ResolvedPostProcessingApi(main.copy(prompts = emptyList()), mainKey)
        } else {
            ResolvedPostProcessingApi(PostProcessingConfig(provider, baseUrl, model), promptKey)
        }
}

/** Immutable per-job API snapshot. Keep credentials out of generated toString output. */
class ResolvedPostProcessingApi(val config: PostProcessingConfig, val apiKey: String)

data class PostProcessingConfig(
    val provider: PostProcessingProvider = PostProcessingProvider.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val prompts: List<PromptConfig> = emptyList(),
)
