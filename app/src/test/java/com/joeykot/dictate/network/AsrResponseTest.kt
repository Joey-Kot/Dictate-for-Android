package com.joeykot.dictate.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrResponseTest {
    @Test
    fun recognizesOnlyTheExplicitNoWordsCodeOnHttp400() {
        val code = "ASR_RESPONSE_HAVE_NO_WORDS"
        val bodies = listOf("code", "message", "info", "detail").flatMap { field ->
            listOf("{\"$field\":\"$code\"}", "{\"error\":{\"$field\":\"$code\"}}")
        } + "{\"error\":\"$code\"}"
        bodies.forEach { body ->
            assertTrue(body, isNoWordsAsrResponse(400, body))
            listOf(200, 401, 403, 408, 429, 500, 503).forEach { status ->
                assertFalse("$status $body", isNoWordsAsrResponse(status, body))
            }
        }
        listOf(
            "invalid JSON",
            "{}",
            "{\"text\":\"$code\"}",
            "{\"message\":\"prefix $code\"}",
            "{\"message\":\"${code}_SUFFIX\"}",
            "{\"error\":{\"code\":\"INVALID_AUDIO\"}}",
            "{\"detail\":[\"$code\"]}",
        ).forEach { assertFalse(it, isNoWordsAsrResponse(400, it)) }
    }
}
