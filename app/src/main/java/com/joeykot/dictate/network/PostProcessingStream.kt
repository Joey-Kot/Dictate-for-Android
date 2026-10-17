package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PostProcessingWireFormat
import org.json.JSONException
import org.json.JSONObject

/** Collects SSE text without exposing partial text to the editor. */
internal object PostProcessingStream {
    fun parseText(provider: PostProcessingProvider, response: String): String {
        val text = StringBuilder()
        var completed = false
        var finalText: String? = null
        var eventName = ""
        val data = mutableListOf<String>()

        fun consumeEvent() {
            if (data.isEmpty()) return
            val payload = data.joinToString("\n")
            data.clear()
            if (payload == "[DONE]") {
                if (provider.wireFormat !in setOf(
                        PostProcessingWireFormat.GEMINI,
                        PostProcessingWireFormat.DASHSCOPE_TEXT,
                        PostProcessingWireFormat.DASHSCOPE_MULTIMODAL,
                    )
                ) completed = true
                return
            }
            val root = try {
                JSONObject(payload)
            } catch (_: JSONException) {
                throw PostProcessingRequest.InvalidResponseException(AppStrings.get(R.string.val_post_stream_json, "The post-processing stream contains invalid JSON"))
            }
            val type = root.optString("type", eventName)
            if (type == "response.incomplete") {
                throw PostProcessingRequest.InvalidResponseException(AppStrings.get(R.string.val_post_stream_incomplete, "The post-processing stream is incomplete"))
            }
            if (root.has("error") || type == "error" || type == "response.failed" ||
                (provider.wireFormat in setOf(
                    PostProcessingWireFormat.DASHSCOPE_TEXT,
                    PostProcessingWireFormat.DASHSCOPE_MULTIMODAL,
                ) && root.has("code") && root.has("message"))
            ) {
                throw serviceError(root)
            }
            when (provider.wireFormat) {
                PostProcessingWireFormat.OPENAI_RESPONSES -> when (type) {
                    "response.output_text.delta" -> text.append(root.opt("delta") as? String ?: "")
                    "response.completed" -> {
                        completed = true
                        val result = root.optJSONObject("response")
                        if (result != null) PostProcessingRequest.validateResponseStatus(provider, result)
                        if (result?.has("output") == true || result?.has("output_text") == true) {
                            finalText = PostProcessingRequest.parseText(provider, result.toString())
                        }
                    }
                }
                PostProcessingWireFormat.ANTHROPIC -> when (type) {
                    "content_block_start" -> {
                        val block = root.optJSONObject("content_block")
                        if (block?.optString("type") == "text") text.append(block.opt("text") as? String ?: "")
                    }
                    "content_block_delta" -> {
                        val delta = root.optJSONObject("delta")
                        if (delta?.optString("type") == "text_delta") text.append(delta.opt("text") as? String ?: "")
                    }
                    "message_delta" -> PostProcessingRequest.validateFinishReason(
                        provider, root.optJSONObject("delta")?.opt("stop_reason") as? String,
                    )
                    "message_stop" -> completed = true
                }
                PostProcessingWireFormat.GEMINI -> {
                    val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                    val finishReason = candidate?.opt("finishReason") as? String
                    PostProcessingRequest.validateFinishReason(provider, finishReason)
                    val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        if (!part.optBoolean("thought", false)) text.append(part.opt("text") as? String ?: "")
                    }
                    if (finishReason in setOf("STOP", "FINISH_REASON_STOP")) completed = true
                }
                PostProcessingWireFormat.OPENAI_CHAT,
                PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> {
                    val choices = root.optJSONArray("choices")
                    if (choices != null) for (index in 0 until choices.length()) {
                        val choice = choices.optJSONObject(index) ?: continue
                        if (choice.optInt("index", 0) != 0) continue
                        PostProcessingRequest.validateFinishReason(provider, choice.opt("finish_reason") as? String)
                        text.append(choice.optJSONObject("delta")?.opt("content") as? String ?: "")
                        if (choice.has("finish_reason") && !choice.isNull("finish_reason")) completed = true
                    }
                }
                PostProcessingWireFormat.DASHSCOPE_TEXT,
                PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> {
                    val choice = root.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)
                    if (choice != null) {
                        val finishReason = choice.opt("finish_reason") as? String
                        PostProcessingRequest.validateFinishReason(provider, finishReason)
                        text.append(
                            PostProcessingRequest.dashscopeText(
                                choice.optJSONObject("message")?.opt("content"),
                            ),
                        )
                        if (finishReason == "stop") completed = true
                    }
                }
            }
        }

        response.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> { consumeEvent(); eventName = "" }
                line.startsWith("event:") -> eventName = line.substringAfter(':').removePrefix(" ")
                line.startsWith("data:") -> data += line.substringAfter(':').removePrefix(" ")
            }
        }
        consumeEvent()
        val result = finalText ?: text.toString()
        if (!completed) throw PostProcessingRequest.InvalidResponseException(AppStrings.get(R.string.val_post_stream_interrupted, "The post-processing stream ended unexpectedly; no partial result was inserted"))
        if (result.isBlank()) throw PostProcessingRequest.InvalidResponseException(AppStrings.get(R.string.val_post_response_empty, "The post-processing response contains no text to insert"))
        return result
    }

    /** A service can report its failure in an SSE event after sending HTTP 200. */
    class ServiceException(val errorCode: String, val retryable: Boolean, message: String) : Exception(message)

    private fun serviceError(event: JSONObject): ServiceException {
        val error = event.optJSONObject("error")
            ?: event.optJSONObject("response")?.optJSONObject("error")
            ?: event
        val code = error.opt("code")?.takeIf { it is String || it is Number }?.toString()?.takeIf { it.isNotBlank() }
            ?: (error.opt("type") as? String)?.takeIf { it.isNotBlank() }
            ?: "unknown_error"
        val equivalentStatus = listOf("status_code", "status", "code")
            .mapNotNull { key -> error.opt(key)?.toString()?.toIntOrNull() }
            .firstOrNull { it in 400..599 }
            ?: when (code) {
                "overloaded_error" -> 529
                "rate_limit_error", "rate_limit_exceeded" -> 429
                "api_error", "server_error" -> 500
                "timeout_error" -> 504
                else -> null
            }
        val detail = (error.opt("message") as? String)?.takeIf { it.isNotBlank() }
        return ServiceException(
            errorCode = code,
            retryable = equivalentStatus?.let(::isRetryableHttpStatus) ?: false,
            message = if (detail == null) AppStrings.get(R.string.val_post_stream_code, "Post-processing service streaming error (%1\$s)", code)
                else AppStrings.get(R.string.val_post_stream_code_detail, "Post-processing service streaming error (%1\$s): %2\$s", code, detail),
        )
    }
}
