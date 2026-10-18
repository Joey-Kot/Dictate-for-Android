package com.joeykot.dictate.network

import com.joeykot.dictate.advanced_audio.JsonValue
import com.joeykot.dictate.advanced_audio.JsonValueCodec

/** A completed recognition with no words; other HTTP failures retain their normal handling. */
internal fun isNoWordsAsrResponse(status: Int, body: String): Boolean {
    if (status != 400) return false
    return hasNoWordsAsrCode(body)
}

/** Also used after an async workflow has positively matched its terminal failure state. */
internal fun hasNoWordsAsrCode(body: String): Boolean {
    val root = runCatching { JsonValueCodec.parse(body) }.getOrNull() as? JsonValue.Object ?: return false
    if (root.hasNoWordsCode()) return true
    return when (val error = root["error"]) {
        is JsonValue.Text -> isNoWordsAsrCode(error.value)
        is JsonValue.Object -> error.hasNoWordsCode()
        else -> false
    }
}

private fun JsonValue.Object.hasNoWordsCode(): Boolean =
    listOf("code", "message", "info", "detail")
        .any { (this[it] as? JsonValue.Text)?.value?.let(::isNoWordsAsrCode) == true }

internal fun isNoWordsAsrCode(value: String): Boolean = value == NO_WORDS_CODE

private const val NO_WORDS_CODE = "ASR_RESPONSE_HAVE_NO_WORDS"
