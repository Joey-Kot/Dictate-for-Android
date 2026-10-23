package com.joeykot.dictate.network

import com.joeykot.dictate.model.PostProcessingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostProcessingStreamTest {
    @Test
    fun compatibleStreamCollectsFirstChoiceAndSkipsReasoning() {
        val stream = """
            : keep-alive

            data: {"choices":[{"index":0,"delta":{"reasoning_content":"hidden"}},{"index":1,"delta":{"content":"other answer"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"你"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        listOf(
            PostProcessingProvider.OPENAI_COMPATIBLE, PostProcessingProvider.XAI_CHAT_COMPLETIONS,
            PostProcessingProvider.KIMI_CHAT_COMPLETIONS, PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS,
        ).forEach { provider ->
            assertEquals("你好", PostProcessingStream.parseText(provider, stream))
        }
    }

    @Test
    fun responsesUsesFinalOutputWithoutDuplicatingDeltas() {
        val stream = """
            data: {"type":"response.reasoning_summary_text.delta","delta":"hidden"}

            event: response.reasoning_text.delta
            data: {"type":"response.reasoning_text.delta","delta":"hidden"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"answer"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"hidden"}],"summary":[{"type":"summary_text","text":"hidden"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}

        """.trimIndent()
        listOf(
            PostProcessingProvider.OPENAI_RESPONSES, PostProcessingProvider.XAI_RESPONSES,
            PostProcessingProvider.KIMI_RESPONSES, PostProcessingProvider.DEEPSEEK_RESPONSES,
        ).forEach { provider ->
            assertEquals("answer", PostProcessingStream.parseText(provider, stream))
            assertEquals("answer", PostProcessingStream.parseText(provider, "$stream\n\ndata: [DONE]\n\n"))
        }
    }

    @Test
    fun kimiSearchCompletionWaitsForTheFinalResponse() {
        val searchAndDraft = """
            event: response.web_search_call.completed
            data: {"type":"response.web_search_call.completed","sequence_number":1}

            event: response.reasoning_summary_text.delta
            data: {"type":"response.reasoning_summary_text.delta","sequence_number":2,"delta":"hidden"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","sequence_number":3,"delta":"draft"}

        """.trimIndent() + "\n\n"
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingStream.parseText(PostProcessingProvider.KIMI_RESPONSES, searchAndDraft)
        }
        val completed = """
            event: response.completed
            data: {"type":"response.completed","sequence_number":4,"response":{"status":"completed","output":[{"type":"web_search_call","status":"completed","action":{"type":"search","query":"news"}},{"type":"reasoning","summary":[{"type":"summary_text","text":"hidden"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}

        """.trimIndent()
        assertEquals("answer", PostProcessingStream.parseText(PostProcessingProvider.KIMI_RESPONSES, searchAndDraft + completed))
    }

    @Test
    fun qwenNativeStreamsAllowNullIntermediateReasonAndRequireStop() {
        val stream = """
            data: {"output":{"choices":[{"message":{"content":"answer"},"finish_reason":"null"}]}}

            data: {"output":{"choices":[{"finish_reason":"stop"}]}}

            data: [DONE]

        """.trimIndent()
        listOf(PostProcessingProvider.QWEN_TEXT, PostProcessingProvider.QWEN_MULTIMODAL).forEach { provider ->
            assertEquals("answer", PostProcessingStream.parseText(provider, stream))
        }
    }

    @Test
    fun anthropicCollectsTextBlocksAndIgnoresThinking() {
        val stream = """
            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hidden"}}

            event: content_block_start
            data: {"type":"content_block_start","content_block":{"type":"text","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"!"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        assertEquals("Hello!", PostProcessingStream.parseText(PostProcessingProvider.ANTHROPIC, stream))
    }

    @Test
    fun disconnectedOrFailedStreamsDoNotReturnPartialText() {
        val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingStream.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, partial)
        }
        assertThrows(PostProcessingStream.ServiceException::class.java) {
            PostProcessingStream.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, partial + "data: {\"error\":{\"message\":\"overloaded\"}}\n\n")
        }
        listOf(PostProcessingProvider.XAI_RESPONSES, PostProcessingProvider.KIMI_RESPONSES, PostProcessingProvider.DEEPSEEK_RESPONSES).forEach { provider ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingStream.parseText(provider,
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n")
            }
        }
    }

    @Test
    fun incompleteFinishReasonsAndStatusesDoNotReturnPartialText() {
        val cases = listOf(
            PostProcessingProvider.GOOGLE to
                """
                    data: {"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"CONTINUATION"}]}

                """.trimIndent(),
            PostProcessingProvider.VERTEX_AI to
                """
                    data: {"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.ANTHROPIC to
                """
                    data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}

                    data: {"type":"message_stop"}

                """.trimIndent(),
            PostProcessingProvider.MISTRAL to
                """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.AZURE_OPENAI_RESPONSES to
                """
                    data: {"type":"response.completed","response":{"status":"incomplete","output_text":"partial"}}

                """.trimIndent(),
            PostProcessingProvider.XAI_RESPONSES to
                """
                    data: {"type":"response.output_text.delta","delta":"partial"}

                    data: {"type":"response.incomplete","response":{"status":"incomplete"}}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.XAI_CHAT_COMPLETIONS to
                """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.KIMI_RESPONSES to
                """
                    data: {"type":"response.output_text.delta","delta":"partial"}

                    data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.KIMI_CHAT_COMPLETIONS to
                """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.DEEPSEEK_RESPONSES to
                """
                    data: {"type":"response.output_text.delta","delta":"partial"}

                    data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}

                """.trimIndent(),
            PostProcessingProvider.DEEPSEEK_CHAT_COMPLETIONS to
                """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}

                    data: [DONE]

                """.trimIndent(),
            PostProcessingProvider.QWEN_TEXT to
                """
                    data: {"output":{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}}

                    data: [DONE]

                """.trimIndent(),
        )
        cases.forEach { (provider, stream) ->
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingStream.parseText(provider, stream)
            }
        }
    }
}
