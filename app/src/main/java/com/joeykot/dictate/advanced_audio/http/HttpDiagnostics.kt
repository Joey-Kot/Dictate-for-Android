package com.joeykot.dictate.advanced_audio.http

import com.joeykot.dictate.advanced_audio.AdvancedAudioExecutionException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Only locally generated metadata; never request/response contents or exception messages. */
data class HttpDiagnosticEvent(
    val message: String,
    val isError: Boolean = false,
    val statusCode: Int? = null,
)

internal fun ((HttpDiagnosticEvent) -> Unit)?.emit(event: HttpDiagnosticEvent) {
    // Diagnostics must not change request, cancellation, or cleanup behavior.
    if (this != null) runCatching { invoke(event) }
}

/** Class names and a finite errno vocabulary retain causes without leaking URLs or credentials. */
internal fun diagnosticFailure(error: Throwable): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(8).toList()
    val kind = when {
        causes.any { it is HttpTransportException.Cancelled || it is InterruptedException } -> "cancelled"
        causes.any { it is UnknownHostException } -> "dns"
        causes.any { it is CertificateException || it is SSLPeerUnverifiedException } -> "tls_certificate"
        causes.any { it is SSLHandshakeException } -> "tls_handshake"
        causes.any { it is SSLException } -> "tls"
        causes.any { it is SocketTimeoutException || it is InterruptedIOException } -> "timeout"
        causes.any { it is NoRouteToHostException } -> "no_route"
        causes.any { it is ConnectException } -> "connect"
        causes.any { it is EOFException } -> "unexpected_eof"
        causes.any { it is SocketException } -> "socket"
        causes.any { it is ProtocolException } -> "protocol"
        causes.any { it is AdvancedAudioExecutionException.UnexpectedStatus } -> "http_status"
        causes.any { it is AdvancedAudioExecutionException.Extraction } -> "response_extraction"
        causes.any { it is AdvancedAudioExecutionException.StreamFailure } -> "stream_protocol"
        causes.any { it is AdvancedAudioExecutionException.PollTimeout } -> "poll_timeout"
        causes.any { it is AdvancedAudioExecutionException.PollFailure } -> "remote_task"
        causes.any { it is AdvancedAudioExecutionException.PollUnexpectedState } -> "poll_state"
        causes.any { it is HttpTransportException.InvalidUrl || it is HttpTransportException.InvalidRequest ||
            it is HttpTransportException.InvalidMediaType || it is HttpTransportException.SigningFailed ||
            it is HttpTransportException.SignerRequiresMaterializedBody } -> "request_configuration"
        causes.any { it is HttpTransportException.AudioFileUnavailable } -> "local_audio"
        causes.any { it is HttpTransportException.ResponseTooLarge } -> "response_limit"
        causes.any { it is HttpTransportException.ResponseReadFailure } -> "response_read"
        causes.any { it is HttpTransportException.NetworkFailure } -> "network"
        else -> "execution"
    }
    val errno = listOf("ECONNREFUSED", "ECONNRESET", "ENETUNREACH", "EHOSTUNREACH", "ETIMEDOUT", "EPIPE", "EACCES")
        .firstOrNull { code -> causes.any { cause -> cause.message?.contains(code) == true } }
    val detail = listOf(
        "connection refused" to "connection_refused",
        "connection reset" to "connection_reset",
        "broken pipe" to "broken_pipe",
        "unexpected end of stream" to "unexpected_end_of_stream",
        "socket closed" to "socket_closed",
        "network is unreachable" to "network_unreachable",
        "no route to host" to "host_unreachable",
        "CLEARTEXT communication" to "cleartext_policy",
        "Trust anchor for certification path not found" to "untrusted_certificate",
        "REFUSED_STREAM" to "http2_refused_stream",
    ).firstOrNull { (fragment, _) -> causes.any { it.message?.contains(fragment, ignoreCase = true) == true } }?.second
    return "kind=$kind causes=${causes.joinToString(">") { it.javaClass.simpleName.ifEmpty { "Throwable" } }}" +
        (errno?.let { " errno=$it" } ?: "") + (detail?.let { " detail=$it" } ?: "")
}

/** One listener per HTTP call, including pooled connections and response-body failures. */
internal class HttpCallDiagnostics(
    private val sink: ((HttpDiagnosticEvent) -> Unit)?,
    private val isCancelled: () -> Boolean,
) : EventListener() {
    private val startedAt = System.nanoTime()
    private val starts = mutableMapOf<String, Long>()
    private var phase = "request_build"
    private var connectAttempts = 0
    private var status: Int? = null
    private var finished = false

    @Synchronized
    fun start(method: String) = record("http_start method=$method")

    @Synchronized
    fun failure(error: Throwable) {
        if (finished) return
        finished = true
        record("http_end outcome=${if (isCancelled()) "cancelled" else "failure"} networkPhase=$phase " +
            diagnosticFailure(error), isError = !isCancelled())
    }

    @Synchronized
    private fun begin(name: String, detail: String = "") {
        phase = name
        starts[name] = System.nanoTime()
        record("${name}_start$detail")
    }

    @Synchronized
    private fun end(name: String, detail: String = "") {
        val duration = starts.remove(name)?.let { (System.nanoTime() - it) / 1_000_000L }
        record("${name}_end durationMs=${duration ?: 0}$detail")
    }

    private fun record(message: String, isError: Boolean = false) {
        sink.emit(HttpDiagnosticEvent("$message elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}" +
            (status?.let { " status=$it" } ?: ""), isError, status))
    }

    override fun dnsStart(call: Call, domainName: String) = begin("dns")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        end("dns", " addresses=${inetAddressList.size}")

    @Synchronized
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAttempts++
        begin("connect", " connectionAttempt=$connectAttempts proxy=${proxy.type()}")
    }

    override fun secureConnectStart(call: Call) = begin("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        end("tls", " version=${handshake?.tlsVersion?.javaName ?: "unknown"}")

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        end("connect", " protocol=${protocol ?: "unknown"}")

    @Synchronized
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
        protocol: Protocol?, ioe: IOException) {
        record("connect_failure connectionAttempt=$connectAttempts networkPhase=$phase ${diagnosticFailure(ioe)}", !isCancelled())
    }

    @Synchronized
    override fun connectionAcquired(call: Call, connection: Connection) {
        phase = "connected"
        record("connection_acquired reused=${connectAttempts == 0} protocol=${connection.protocol()} " +
            "proxy=${connection.route().proxy.type()}")
    }

    override fun requestHeadersStart(call: Call) = begin("request_headers")
    override fun requestHeadersEnd(call: Call, request: Request) = end("request_headers")
    override fun requestBodyStart(call: Call) = begin("upload")
    override fun requestBodyEnd(call: Call, byteCount: Long) = end("upload", " bytes=$byteCount")
    override fun responseHeadersStart(call: Call) = begin("response_headers")

    @Synchronized
    override fun responseHeadersEnd(call: Call, response: Response) {
        status = response.code
        end("response_headers", " protocol=${response.protocol}")
    }

    override fun responseBodyStart(call: Call) = begin("download")
    override fun responseBodyEnd(call: Call, byteCount: Long) = end("download", " bytes=$byteCount")

    @Synchronized
    override fun requestFailed(call: Call, ioe: IOException) {
        record("request_failure networkPhase=$phase ${diagnosticFailure(ioe)}", !isCancelled())
    }

    @Synchronized
    override fun responseFailed(call: Call, ioe: IOException) {
        record("response_failure networkPhase=$phase ${diagnosticFailure(ioe)}", !isCancelled())
    }

    @Synchronized
    override fun callEnd(call: Call) {
        if (finished) return
        finished = true
        record("http_end outcome=${if (isCancelled()) "cancelled" else "complete"}")
    }

    override fun callFailed(call: Call, ioe: IOException) = failure(ioe)
}
