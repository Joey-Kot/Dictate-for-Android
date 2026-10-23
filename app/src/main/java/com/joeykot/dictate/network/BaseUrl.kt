package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PostProcessingWireFormat
import java.net.URI

object BaseUrl {
    fun postProcessingEndpoint(input: String, provider: PostProcessingProvider, model: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { AppStrings.get(R.string.val_base_url_empty, "Base URL cannot be empty") }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(AppStrings.get(R.string.val_base_url_invalid, "Invalid Base URL format"))
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { AppStrings.get(R.string.val_base_url_scheme, "Base URL must use http or https") }
        require(!uri.host.isNullOrBlank()) { AppStrings.get(R.string.val_base_url_host, "Base URL must contain a valid hostname") }
        require(uri.userInfo == null) { AppStrings.get(R.string.val_base_url_credentials, "Base URL cannot contain a username or password") }
        require(uri.query == null && uri.fragment == null) { AppStrings.get(R.string.val_base_url_query, "Base URL cannot contain query parameters or a fragment") }

        var path = (uri.path ?: "").trimEnd('/')
        if (provider.wireFormat == PostProcessingWireFormat.GEMINI) {
            val modelId = model.removePrefix("models/")
            require(modelId.isNotBlank() && modelId.none { it == '/' || it == '?' || it == '#' }) {
                AppStrings.get(R.string.val_google_model, "Google Model must be a model name or models/model-name")
            }
            path = path.substringBefore("/models/").trimEnd('/')
            if (provider == PostProcessingProvider.VERTEX_AI) {
                require(isVertexPublisherPath(path)) {
                    AppStrings.get(R.string.val_base_url_invalid, "Vertex AI Base URL must include /v1/projects/{project}/locations/{location}/publishers/google")
                }
            } else if (path.isEmpty()) {
                path = "/v1beta"
            }
            path += "/models/$modelId:generateContent"
        } else {
            val suffix = when (provider.wireFormat) {
                PostProcessingWireFormat.OPENAI_RESPONSES -> "/responses"
                PostProcessingWireFormat.ANTHROPIC -> "/messages"
                PostProcessingWireFormat.OPENAI_CHAT -> "/chat/completions"
                PostProcessingWireFormat.GEMINI -> error("Handled above")
                PostProcessingWireFormat.DASHSCOPE_TEXT -> "/services/aigc/text-generation/generation"
                PostProcessingWireFormat.DASHSCOPE_MULTIMODAL -> "/services/aigc/multimodal-generation/generation"
                PostProcessingWireFormat.MINIMAX_CHAT_COMPLETION_V2 -> "/text/chatcompletion_v2"
            }
            if (path.isEmpty()) {
                path = when (provider) {
                    PostProcessingProvider.DEEPSEEK_RESPONSES,
                    PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS -> ""
                    PostProcessingProvider.QWEN_TEXT,
                    PostProcessingProvider.QWEN_MULTIMODAL -> "/api/v1"
                    PostProcessingProvider.GLM -> "/api/paas/v4"
                    PostProcessingProvider.AZURE_OPENAI_RESPONSES,
                    PostProcessingProvider.AZURE_OPENAI_CHAT_COMPLETIONS -> "/openai/v1"
                    PostProcessingProvider.VOLCENGINE_ARK_CHAT_COMPLETIONS,
                    PostProcessingProvider.VOLCENGINE_ARK_RESPONSES -> "/api/v3"
                    PostProcessingProvider.BAIDU_QIANFAN -> "/v2"
                    PostProcessingProvider.GROQ -> "/openai/v1"
                    PostProcessingProvider.COHERE -> "/compatibility/v1"
                    else -> "/v1"
                }
            }
            if (!path.endsWith(suffix)) path += suffix
        }
        return URI(scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
    }

    private fun isVertexPublisherPath(path: String): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 7) return false
        val tail = segments.takeLast(7)
        return tail[0] == "v1" && tail[1] == "projects" && tail[2].isNotBlank() &&
            tail[3] == "locations" && tail[4].isNotBlank() &&
            tail[5] == "publishers" && tail[6] == "google"
    }

    fun transcriptionEndpoint(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { AppStrings.get(R.string.val_base_url_empty, "Base URL cannot be empty") }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(AppStrings.get(R.string.val_base_url_invalid, "Invalid Base URL format"))
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            AppStrings.get(R.string.val_base_url_scheme, "Base URL must use http or https")
        }
        require(!uri.host.isNullOrBlank()) { AppStrings.get(R.string.val_base_url_host, "Base URL must contain a valid hostname") }
        require(uri.userInfo == null) { AppStrings.get(R.string.val_base_url_credentials, "Base URL cannot contain a username or password") }
        require(uri.query == null && uri.fragment == null) { AppStrings.get(R.string.val_base_url_query, "Base URL cannot contain query parameters or a fragment") }

        val basePath = (uri.rawPath ?: "").trimEnd('/')
        val endpointPath = if (basePath.endsWith("/v1")) {
            "$basePath/audio/transcriptions"
        } else {
            "$basePath/v1/audio/transcriptions"
        }.replace(Regex("/{2,}"), "/")

        return URI(
            scheme,
            null,
            uri.host,
            uri.port,
            endpointPath,
            null,
            null,
        ).toASCIIString()
    }
}
