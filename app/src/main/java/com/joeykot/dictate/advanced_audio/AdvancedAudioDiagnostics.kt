package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.http.HttpDiagnosticEvent
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.diagnosticFailure
import com.joeykot.dictate.advanced_audio.http.emit

/** Owned by one transcribe invocation, never shared between jobs or segments. */
internal class AdvancedAudioDiagnostics(private val sink: ((HttpDiagnosticEvent) -> Unit)?) {
    private val startedAt = System.nanoTime()
    private var phase = "validation"
    private var attempt = 1
    @Volatile private var exchange = 0
    @Volatile private var lastStatus: Int? = null

    fun start(retries: Int) = record("workflow_start maxRetries=$retries")

    fun phase(label: String) {
        phase = label.substringAfterLast("] ")
        attempt = 1
        record("phase_start")
    }

    fun attempt(number: Int) {
        attempt = number
        record("attempt_start")
    }

    fun request(spec: HttpRequestSpec): HttpRequestSpec {
        exchange++
        lastStatus = null
        val context = context()
        val requestNumber = exchange
        record("request_start method=${spec.method.wireName}")
        return spec.copy(onDiagnostic = { event ->
            if (exchange == requestNumber && event.statusCode != null) lastStatus = event.statusCode
            // Capture context now: late transport callbacks must not acquire
            // the next poll/result request's stage or attempt identifiers.
            sink.emit(event.copy(message = "$context ${event.message}"))
            spec.onDiagnostic.emit(event)
        })
    }

    fun response(status: Int, bytes: Int? = null) {
        lastStatus = status
        record("response status=$status" + (bytes?.let { " bytes=$it" } ?: ""))
    }

    fun retry(nextAttempt: Int, delayMillis: Long, error: Throwable) =
        record("retry_scheduled nextAttempt=$nextAttempt delayMs=$delayMillis ${diagnosticFailure(error)}")

    fun noRetry(retryable: Boolean, retriesRemaining: Int) = record(
        "retry_stopped retryable=$retryable retriesRemaining=$retriesRemaining " +
            "reason=${if (!retryable) "non_retryable" else "retry_limit"}",
    )

    fun failure(error: Throwable) = record("workflow_failure ${diagnosticFailure(error)}", true)

    fun complete(result: AdvancedAudioClient.Result) {
        val summary = when (result) {
            is AdvancedAudioClient.Result.Success ->
                "outcome=success status=${result.statusCode} textChars=${result.text.length} noSpeech=${result.text.isBlank()}"
            is AdvancedAudioClient.Result.Failure ->
                "outcome=failure status=${result.statusCode ?: lastStatus ?: "none"} placeholderEligible=${result.placeholderEligible}"
            is AdvancedAudioClient.Result.Cancelled -> "outcome=cancelled status=${lastStatus ?: "none"}"
        }
        record("workflow_end $summary", result is AdvancedAudioClient.Result.Failure)
    }

    private fun context() = "phase=$phase attempt=$attempt request=$exchange"

    private fun record(message: String, isError: Boolean = false) {
        sink.emit(HttpDiagnosticEvent("${context()} $message workflowElapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}", isError))
    }
}
