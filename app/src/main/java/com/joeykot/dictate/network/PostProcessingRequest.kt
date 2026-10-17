package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PostProcessingWireFormat
import com.joeykot.dictate.model.PromptConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Provider-specific wire formats share one final, non-mutating parameter merge. */
object PostProcessingRequest {
    data class Request(val endpoint: String, val headers: Map<String, String>, val body: JSONObject)

    fun build(config: PostProcessingConfig, apiKey: String, prompt: PromptConfig, input: String): Request {
        require(apiKey.isNotBlank()) { AppStrings.get(R.string.val_post_api_key_empty, "The post-processing API key cannot be empty") }
        require(prompt.prompt.isNotBlank()) { AppStrings.get(R.string.val_prompt_empty, "Prompt content cannot be empty") }
        require(input.isNotEmpty()) { AppStrings.get(R.string.val_input_empty, "Text to process cannot be empty") }
        val base = JSONObject().put("model", config.model.trim())
        when (config.provider.wireFormat) {
            PostProcessingWireFormat.OPENAI_RESPONSES -> base
                .put("instructions", prompt.prompt)
                .put("input", JSONArray().put(message("user", input)))
            PostProcessingWireFormat.GEMINI -> base
                .put("systemInstruction", JSONObject().put("parts", parts(prompt.prompt)))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts(input))))
            PostProcessingWireFormat.ANTHROPIC -> base
                .put("system", prompt.prompt)
                .put("max_tokens", 4096)
                .put("messages", JSONArray().put(message("user", input)))
            PostProcessingWireFormat.OPENAI_CHAT,
            PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> base
                .put("messages", JSONArray().put(message("system", prompt.prompt)).put(message("user", input)))
            PostProcessingWireFormat.DASHSCOPE_TEXT -> base
                .put("input", JSONObject().put("messages", JSONArray().put(message("system", prompt.prompt)).put(message("user", input))))
                .put("parameters", JSONObject().put("result_format", "message").put("incremental_output", true))
            PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> base
                .put("input", JSONObject().put("messages", JSONArray().put(message("system", prompt.prompt)).put(dashscopeMessage("user", input))))
                .put("parameters", JSONObject().put("result_format", "message").put("incremental_output", true))
        }
        val body = AdditionalParameters.merge(base, AdditionalParameters.parseObject(prompt.additionalJson))
        val model = body.opt("model")
        require(model is String && model.isNotBlank()) { AppStrings.get(R.string.val_merged_model_type, "The merged model must be a nonempty string") }
        val endpoint = BaseUrl.postProcessingEndpoint(config.baseUrl, config.provider, model)
        val headers = when (config.provider) {
            PostProcessingProvider.ANTHROPIC -> mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
            PostProcessingProvider.GOOGLE -> mapOf("x-goog-api-key" to apiKey)
            PostProcessingProvider.AZURE_OPENAI_RESPONSES,
            PostProcessingProvider.AZURE_OPENAI_CHAT_COMPLETIONS -> mapOf("api-key" to apiKey)
            PostProcessingProvider.QWEN_TEXT,
            PostProcessingProvider.QWEN_MULTIMODAL -> mapOf(
                "Authorization" to "Bearer $apiKey",
                "X-DashScope-SSE" to "enable",
            )
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }
        // generateContent specifies the model in the URL, not the JSON body.
        if (config.provider.wireFormat == PostProcessingWireFormat.GEMINI) body.remove("model")
        return Request(endpoint, headers, body)
    }

    fun parseText(provider: PostProcessingProvider, response: String): String {
        val root = try {
            JSONObject(response)
        } catch (_: JSONException) {
            throw InvalidResponseException(AppStrings.get(R.string.val_post_response_object, "The post-processing response is not a valid JSON object"))
        }
        validateResponseStatus(provider, root)
        validateFinishReason(
            provider,
            finishReason(provider, root),
            requireTerminalReason = provider.wireFormat in setOf(
                PostProcessingWireFormat.GEMINI,
                PostProcessingWireFormat.DASHSCOPE_TEXT,
                PostProcessingWireFormat.DASHSCOPE_MULTIMODAL,
            ),
        )
        val text = when (provider.wireFormat) {
            PostProcessingWireFormat.OPENAI_RESPONSES -> {
                val direct = root.opt("output_text") as? String
                direct ?: buildString {
                    val output = root.optJSONArray("output") ?: return@buildString
                    for (index in 0 until output.length()) {
                        val item = output.optJSONObject(index) ?: continue
                        if (item.optString("type") == "message") {
                            append(textBlocks(item.optJSONArray("content"), setOf("output_text")))
                        }
                    }
                }
            }
            PostProcessingWireFormat.ANTHROPIC -> textBlocks(root.optJSONArray("content"), setOf("text"))
            PostProcessingWireFormat.GEMINI -> {
                val content = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                buildString {
                    if (parts != null) for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        if (!part.optBoolean("thought", false)) append(part.opt("text") as? String ?: "")
                    }
                }
            }
            PostProcessingWireFormat.OPENAI_CHAT,
            PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> {
                val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> textBlocks(content, setOf("text"))
                    else -> ""
                }
            }
            PostProcessingWireFormat.DASHSCOPE_TEXT,
            PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> dashscopeText(
                root.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content"),
            )
        }
        if (text.isBlank()) throw InvalidResponseException(AppStrings.get(R.string.val_post_response_empty, "The post-processing response contains no text to insert"))
        return text
    }

    internal fun validateResponseStatus(provider: PostProcessingProvider, root: JSONObject) {
        if (provider.wireFormat == PostProcessingWireFormat.OPENAI_RESPONSES &&
            root.optString("status") in setOf("failed", "incomplete", "cancelled", "in_progress", "queued")
        ) {
            throw incomplete(provider, root.optString("status"))
        }
    }

    internal fun validateFinishReason(
        provider: PostProcessingProvider,
        reason: String?,
        requireTerminalReason: Boolean = false,
    ) {
        if (reason.isNullOrBlank()) {
            if (requireTerminalReason) {
                throw incomplete(provider, "missing finish reason")
            }
            return
        }
        if (provider.wireFormat in setOf(
                PostProcessingWireFormat.DASHSCOPE_TEXT,
                PostProcessingWireFormat.DASHSCOPE_MULTIMODAL,
            ) && reason == "null"
        ) {
            // DashScope uses the string "null" for non-final SSE packets.
            // A non-stream response still needs an explicit terminal `stop`.
            if (requireTerminalReason) throw incomplete(provider, reason)
            return
        }
        val incomplete = when (provider.wireFormat) {
            PostProcessingWireFormat.ANTHROPIC -> reason in setOf(
                "max_tokens", "tool_use", "pause_turn", "refusal", "model_context_window_exceeded",
            )
            PostProcessingWireFormat.GEMINI -> reason !in setOf("STOP", "FINISH_REASON_STOP")
            PostProcessingWireFormat.OPENAI_RESPONSES -> false
            PostProcessingWireFormat.OPENAI_CHAT,
            PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> reason in setOf(
                "length", "content_filter", "tool_calls", "function_call", "insufficient_system_resource", "aborted",
            )
            PostProcessingWireFormat.DASHSCOPE_TEXT,
            PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> reason != "stop"
        }
        if (incomplete) throw incomplete(provider, reason)
    }

    internal fun finishReason(provider: PostProcessingProvider, root: JSONObject): String? = when (provider.wireFormat) {
        PostProcessingWireFormat.ANTHROPIC -> root.opt("stop_reason") as? String
        PostProcessingWireFormat.GEMINI -> root.optJSONArray("candidates")?.optJSONObject(0)?.opt("finishReason") as? String
        PostProcessingWireFormat.OPENAI_RESPONSES -> null
        PostProcessingWireFormat.OPENAI_CHAT,
        PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> root.optJSONArray("choices")?.optJSONObject(0)?.opt("finish_reason") as? String
        PostProcessingWireFormat.DASHSCOPE_TEXT,
        PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> root.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)?.opt("finish_reason") as? String
    }

    private fun incomplete(provider: PostProcessingProvider, reason: String): InvalidResponseException =
        InvalidResponseException("${AppStrings.get(R.string.val_post_response_invalid, "Invalid post-processing response")} (${provider.label}: $reason)")

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun parts(text: String): JSONArray = JSONArray().put(JSONObject().put("text", text))

    private fun dashscopeMessage(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", parts(content))

    private fun textBlocks(blocks: JSONArray?, types: Set<String>): String = buildString {
        if (blocks != null) for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") in types) append(block.opt("text") as? String ?: "")
        }
    }

    internal fun dashscopeText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                append(content.optJSONObject(index)?.opt("text") as? String ?: "")
            }
        }
        else -> ""
    }

    class InvalidResponseException(message: String) : Exception(message)
}
