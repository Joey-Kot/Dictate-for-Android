package com.joeykot.dictate.advanced_audio.http

import com.joeykot.dictate.advanced_audio.auth.HttpRequestSigner
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OkHttpAdvancedTransportTest {
    @Test
    fun logsHttpStatusUploadAndDownloadWithoutRequestOrResponseContents() {
        RawHttpServer().use { server ->
            server.respond(403, "private response")
            val events = CopyOnWriteArrayList<HttpDiagnosticEvent>()
            val response = OkHttpAdvancedTransport().executeAndRead(
                HttpRequestSpec(
                    AdvancedHttpMethod.POST, "${server.url}/private-path?key=private-key",
                    headers = listOf(HttpHeader("Authorization", "Bearer private-token")),
                    body = HttpBodySpec.Json("private audio"),
                    onDiagnostic = { events += it },
                ),
            )
            assertEquals(403, response.statusCode)
            val log = events.joinToString("\n") { it.message }
            assertTrue(log, log.contains("upload_end durationMs="))
            assertTrue(log, events.single { it.message.startsWith("upload_end") }.message.contains("bytes=13"))
            assertTrue(log, log.contains("download_end durationMs="))
            assertTrue(log, log.contains("status=403"))
            assertTrue(log, log.contains("connection_acquired reused=false"))
            assertEquals(1, events.count { it.message.startsWith("http_end outcome=complete") })
            assertFalse(log, log.contains("private"))
            assertFalse(log, log.contains(server.url))
        }
    }

    @Test
    fun fastDnsFailureLogsPhaseAndCauseWithoutTheExceptionMessage() {
        val events = mutableListOf<HttpDiagnosticEvent>()
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("private-token https://secret.test/private?key=credential")
        }).build()
        val error = captureFailure {
            OkHttpAdvancedTransport(client).executeAndRead(
                HttpRequestSpec(AdvancedHttpMethod.GET, "https://secret.test/private", onDiagnostic = { events += it }),
            )
        }
        assertTrue(error is HttpTransportException.NetworkFailure)
        val terminal = events.single { it.message.startsWith("http_end") }
        assertTrue(terminal.isError)
        assertTrue(terminal.message, terminal.message.contains("networkPhase=dns kind=dns causes=UnknownHostException"))
        assertTrue(terminal.message.contains("elapsedMs="))
        assertFalse(events.any { it.message.contains("private") || it.message.contains("credential") || it.message.contains("secret.test") })
        assertFalse(events.any { it.message.startsWith("connect_start") })
    }

    @Test
    fun refusedConnectionLogsTheConnectPhase() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val events = mutableListOf<HttpDiagnosticEvent>()
        val error = captureFailure {
            OkHttpAdvancedTransport().executeAndRead(
                HttpRequestSpec(AdvancedHttpMethod.GET, "http://127.0.0.1:$port", onDiagnostic = { events += it }),
            )
        }
        assertTrue(error is HttpTransportException.NetworkFailure)
        val terminal = events.single { it.message.startsWith("http_end") }
        assertTrue(terminal.message, terminal.message.contains("outcome=failure networkPhase=connect kind=connect"))
        assertTrue(terminal.message, terminal.message.contains("ConnectException"))
        assertFalse(events.any { it.message.contains("connection_acquired") })
    }

    @Test
    fun tlsHandshakeFailureIsVisibleBeforeAnyHttpResponse() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        check(socket.getInputStream().read(ByteArray(4096)) > 0)
                        // A TLS fatal handshake_failure alert, without needing
                        // certificates or a remote endpoint in this test.
                        socket.getOutputStream().write(byteArrayOf(21, 3, 3, 0, 2, 2, 40))
                        socket.getOutputStream().flush()
                    }
                }
                val events = mutableListOf<HttpDiagnosticEvent>()
                val error = captureFailure {
                    OkHttpAdvancedTransport().executeAndRead(
                        HttpRequestSpec(AdvancedHttpMethod.GET, "https://127.0.0.1:${server.localPort}/private", onDiagnostic = { events += it }),
                        callTimeoutMillis = 3_000,
                    )
                }
                peer.get(3, TimeUnit.SECONDS)
                assertTrue(error is HttpTransportException.NetworkFailure)
                assertTrue(events.any { it.message.startsWith("tls_start") })
                val terminal = events.single { it.message.startsWith("http_end") }
                assertTrue(terminal.message, terminal.message.contains("networkPhase=tls kind=tls_handshake"))
                assertFalse(events.any { it.statusCode != null || it.message.contains("private") })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun connectionReuseIsExplicitWithoutAnotherHandshake() {
        RawHttpServer().use { server ->
            server.respond(200, "ok", keepAliveResponses = 2)
            val events = mutableListOf<HttpDiagnosticEvent>()
            val transport = OkHttpAdvancedTransport()
            repeat(2) {
                transport.executeAndRead(HttpRequestSpec(AdvancedHttpMethod.GET, server.url, onDiagnostic = { events += it }))
            }
            val acquired = events.filter { it.message.startsWith("connection_acquired") }
            assertEquals(2, acquired.size)
            assertTrue(acquired[0].message.contains("reused=false"))
            assertTrue(acquired[1].message.contains("reused=true"))
            assertEquals(1, events.count { it.message.startsWith("connect_start") })
            assertEquals(2, events.count { it.message.startsWith("http_end outcome=complete") })
        }
    }

    @Test
    fun truncatedResponseLogsReadFailureAndHttpStatus() {
        RawHttpServer().use { server ->
            server.respond(200, "partial", advertisedLength = 100)
            val events = mutableListOf<HttpDiagnosticEvent>()
            val error = captureFailure {
                OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(AdvancedHttpMethod.GET, server.url, onDiagnostic = { events += it }),
                )
            }
            assertTrue(error is HttpTransportException.ResponseReadFailure)
            val terminal = events.single { it.message.startsWith("http_end") }
            assertTrue(terminal.message, terminal.message.contains("outcome=failure networkPhase=download"))
            assertTrue(terminal.message, terminal.message.contains("status=200"))
            assertTrue(terminal.message, terminal.message.contains("ProtocolException"))
        }
    }

    @Test
    fun timeoutAndCancellationHaveDifferentTerminalOutcomes() {
        for (cancel in listOf(false, true)) {
            RawHttpServer().use { server ->
                val gate = CountDownLatch(1)
                val received = server.respond(200, "ok", beforeResponse = gate)
                val events = CopyOnWriteArrayList<HttpDiagnosticEvent>()
                val cancellation = AdvancedCancellationSource()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val future = executor.submit<Throwable> {
                        captureFailure {
                            OkHttpAdvancedTransport().executeAndRead(
                                HttpRequestSpec(AdvancedHttpMethod.GET, server.url, onDiagnostic = { events += it }),
                                cancellation.token,
                                callTimeoutMillis = if (cancel) 5_000 else 500,
                            )
                        }
                    }
                    received.get(3, TimeUnit.SECONDS)
                    if (cancel) cancellation.cancel()
                    val error = future.get(3, TimeUnit.SECONDS)
                    assertTrue(if (cancel) error is HttpTransportException.Cancelled else error is HttpTransportException.NetworkFailure)
                    val terminal = events.single { it.message.startsWith("http_end") }
                    assertTrue(terminal.message, terminal.message.contains("outcome=${if (cancel) "cancelled" else "failure"}"))
                    if (!cancel) assertTrue(terminal.message, terminal.message.contains("kind=timeout"))
                } finally {
                    gate.countDown()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun invalidUrlStillHasAStartAndTerminalDiagnostic() {
        val events = mutableListOf<HttpDiagnosticEvent>()
        val error = captureFailure {
            OkHttpAdvancedTransport().execute(HttpRequestSpec(AdvancedHttpMethod.GET, "invalid private-url", onDiagnostic = { events += it }))
        }
        assertTrue(error is HttpTransportException.InvalidUrl)
        assertEquals(2, events.size)
        assertTrue(events.last().message.contains("networkPhase=request_build kind=request_configuration"))
        assertFalse(events.any { it.message.contains("private-url") })
    }

    @Test
    fun aBrokenDiagnosticSinkCannotFailARequest() {
        RawHttpServer().use { server ->
            server.respond(200, "ok")
            val response = OkHttpAdvancedTransport().executeAndRead(
                HttpRequestSpec(AdvancedHttpMethod.GET, server.url, onDiagnostic = { error("diagnostic sink failed") }),
            )
            assertEquals(200, response.statusCode)
        }
    }

    @Test
    fun sendsRenderedMethodUrlQueryHeadersAndMaterializedBody() {
        RawHttpServer().use { server ->
            val captured = server.respond(status = 202, body = "accepted")
            val result = OkHttpAdvancedTransport().executeAndRead(
                HttpRequestSpec(
                    method = AdvancedHttpMethod.POST,
                    url = "${server.url}/submit?original=true",
                    query = listOf(HttpQueryParameter("task", "two words")),
                    headers = listOf(HttpHeader("X-Workflow", "advanced")),
                    body = HttpBodySpec.RawBytes("payload".toByteArray(), "text/plain; charset=utf-8"),
                    acceptedStatuses = setOf(202),
                ),
            )

            assertEquals(202, result.statusCode)
            assertTrue(result.isAcceptedBy(setOf(202)))
            assertArrayEquals("accepted".toByteArray(), result.body)
            val received = captured.get(3, TimeUnit.SECONDS)
            assertTrue(received.headers.startsWith("POST /submit?original=true&task=two%20words HTTP/1.1"))
            assertTrue(received.headers.contains("X-Workflow: advanced", ignoreCase = true))
            assertTrue(received.headers.contains("User-Agent: dictate-client/advanced-audio-v1", ignoreCase = true))
            assertTrue(received.headers.contains("Content-Type: text/plain; charset=utf-8", ignoreCase = true))
            assertArrayEquals("payload".toByteArray(), received.body)
        }
    }

    @Test
    fun multipartKeepsAudioOnTheStreamingPath() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            RawHttpServer().use { server ->
                val captured = server.respond(status = 200, body = "ok")
                val result = OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "${server.url}/upload",
                        body = HttpBodySpec.Multipart(
                            fields = listOf(
                                MultipartPart("model", MultipartValue.Text("test")),
                                MultipartPart("file", MultipartValue.AudioFile),
                                MultipartPart("metadata", MultipartValue.Bytes("bytes".toByteArray())),
                            ),
                            audio = PreparedAudioUpload(audio, "audio.wav", "audio/wav"),
                        ),
                    ),
                )
                assertEquals(200, result.statusCode)
                val received = captured.get(3, TimeUnit.SECONDS)
                val wire = received.body.toString(Charsets.ISO_8859_1)
                assertTrue(received.headers.contains("multipart/form-data", ignoreCase = true))
                assertTrue(wire.contains("name=\"model\""))
                assertTrue(wire.contains("\r\n\r\ntest\r\n"))
                assertTrue(wire.contains("name=\"file\"; filename=\"audio.wav\""))
                assertTrue(wire.contains("Content-Type: audio/wav"))
                assertTrue(wire.contains("\u0001\u0002\u0003\u0004"))
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun boundedResponseRejectsOversizedBodies() {
        RawHttpServer().use { server ->
            server.respond(status = 200, body = "x".repeat(1_025))
            val failure = captureFailure {
                OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(AdvancedHttpMethod.GET, "${server.url}/large"),
                    maxBytes = 1_024,
                )
            }
            assertTrue(failure is HttpTransportException.ResponseTooLarge)
        }
    }

    @Test
    fun cancellationClosesTheActiveCall() {
        RawHttpServer().use { server ->
            val release = CountDownLatch(1)
            val accepted = server.respond(status = 200, body = "late", beforeResponse = release)
            val source = AdvancedCancellationSource()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit<Unit> {
                    OkHttpAdvancedTransport().executeAndRead(
                        HttpRequestSpec(AdvancedHttpMethod.GET, "${server.url}/wait"),
                        source.token,
                    )
                }
                accepted.get(3, TimeUnit.SECONDS)
                source.cancel()
                val failure = try {
                    task.get(3, TimeUnit.SECONDS)
                    error("request unexpectedly completed")
                } catch (error: ExecutionException) {
                    error.cause
                }
                assertTrue(failure is HttpTransportException.Cancelled)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun dynamicSignerRefusesStreamingAudioBodiesBeforeConnection() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeText("audio") }
        try {
            val signer = HttpRequestSigner { error("streaming body must never reach signer") }
            val failure = captureFailure {
                OkHttpAdvancedTransport().execute(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "https://example.test/upload",
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "audio.wav", "audio/wav")),
                        signer = signer,
                    ),
                )
            }
            assertTrue(failure is HttpTransportException.SignerRequiresMaterializedBody)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun remoteS3UploadCanUseOnlyTheExplicitUnsignedStreamingPayload() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        try {
            RawHttpServer().use { server ->
                val captured = server.respond(status = 200, body = "ok")
                val result = OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.PUT,
                        url = "${server.url}/bucket/object.wav",
                        headers = listOf(HttpHeader("Content-Length", audio.length().toString())),
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "object.wav", "audio/wav")),
                        signer = AwsSigV4Signer("us-east-1", "s3", "access", "secret"),
                        remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
                    ),
                )
                assertEquals(200, result.statusCode)
                val received = captured.get(3, TimeUnit.SECONDS)
                assertTrue(received.headers.contains("X-Amz-Content-Sha256: UNSIGNED-PAYLOAD", ignoreCase = true))
                assertTrue(received.headers.contains("Authorization: AWS4-HMAC-SHA256", ignoreCase = true))
                assertArrayEquals(byteArrayOf(9, 8, 7), received.body)
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun remoteUnsignedPayloadCannotBeReusedByAWorkflowPost() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            val failure = captureFailure {
                OkHttpAdvancedTransport().execute(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "https://example.test/not-storage",
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "audio.wav", "audio/wav")),
                        signer = AwsSigV4Signer("us-east-1", "s3", "access", "secret"),
                        remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
                    ),
                )
            }
            assertTrue(failure is HttpTransportException.InvalidRequest)
        } finally {
            audio.delete()
        }
    }

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }

    private data class ReceivedRequest(
        val headers: String,
        val body: ByteArray,
    )

    private class RawHttpServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:${server.localPort}"
        private var activeSocket: Socket? = null

        fun respond(
            status: Int,
            body: String,
            beforeResponse: CountDownLatch? = null,
            keepAliveResponses: Int = 1,
            advertisedLength: Int? = null,
        ): CompletableFuture<ReceivedRequest> {
            val captured = CompletableFuture<ReceivedRequest>()
            Thread({
                try {
                    server.accept().use { connection ->
                        activeSocket = connection
                        connection.soTimeout = 5_000
                        repeat(keepAliveResponses) { index ->
                            val headers = readHeaders(connection)
                            val contentLength = headers.lineSequence()
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.trim()
                                ?.toIntOrNull()
                                ?: 0
                            val chunked = headers.lineSequence().any {
                                it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                                    it.substringAfter(':').contains("chunked", ignoreCase = true)
                            }
                            val payload = if (chunked) readChunked(connection.getInputStream())
                            else readExactly(connection.getInputStream(), contentLength)
                            captured.complete(ReceivedRequest(headers, payload))
                            beforeResponse?.await(5, TimeUnit.SECONDS)
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val connectionHeader = if (index + 1 < keepAliveResponses) "keep-alive" else "close"
                            connection.getOutputStream().apply {
                                write("HTTP/1.1 $status Test\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${advertisedLength ?: bytes.size}\r\nConnection: $connectionHeader\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    captured.completeExceptionally(error)
                }
            }, "advanced-http-test-server").apply {
                isDaemon = true
                start()
            }
            return captured
        }

        override fun close() {
            activeSocket?.close()
            server.close()
        }

        private fun readHeaders(connection: Socket): String {
            val output = ByteArrayOutputStream()
            val input = connection.getInputStream()
            while (true) {
                val value = input.read()
                check(value >= 0)
                output.write(value)
                if (output.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
            }
            return output.toString(Charsets.ISO_8859_1.name())
        }

        private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                check(count > 0)
                offset += count
            }
            return bytes
        }

        private fun readChunked(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            while (true) {
                val length = readAsciiLine(input).substringBefore(';').trim().toInt(16)
                if (length == 0) {
                    // Consume the terminating empty trailer line. This small
                    // fake has no need to retain arbitrary trailer headers.
                    while (readAsciiLine(input).isNotEmpty()) Unit
                    return output.toByteArray()
                }
                output.write(readExactly(input, length))
                check(readAsciiLine(input).isEmpty())
            }
        }

        private fun readAsciiLine(input: java.io.InputStream): String {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0)
                if (next == '\n'.code) break
                if (next != '\r'.code) output.write(next)
            }
            return output.toString(Charsets.ISO_8859_1.name())
        }
    }
}
