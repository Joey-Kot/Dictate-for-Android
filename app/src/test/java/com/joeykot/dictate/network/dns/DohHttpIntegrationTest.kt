package com.joeykot.dictate.network.dns

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTP exercises the actual RFC 8484 client without external DNS or public endpoints. */
class DohHttpIntegrationTest {
    @Test fun bootstrapKeepsHostnameAndDecodesBothAddressFamilies() {
        Server { _, query -> dnsAnswer(query) }.use { server ->
            val result = transport().lookup(server.provider, "api.vendor.com", DnsQuery { true })
            assertEquals(setOf("8.8.8.8", "2001:4860:4860:0:0:0:0:8888"), result.map { it.hostAddress }.toSet())
            assertEquals(2, server.hosts.size)
            assertTrue(server.hosts.all { it == "bootstrap.resolver.com:${server.port}" })
            assertTrue(server.mediaTypes.all { it == "application/dns-message" })
        }
    }

    @Test fun successfulHttpWithNxDomainIsAnAuthoritativeNegativeAnswer() {
        Server { _, query -> query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x83.toByte() } }.use { server ->
            try {
                transport().lookup(server.provider, "missing.vendor.com", DnsQuery { true })
                fail("Expected NXDOMAIN")
            } catch (_: DohNegativeAnswer) { }
        }
    }

    @Test fun chunkedOversizedBodiesAreRejectedBeforeDnsDecoding() {
        Server(chunked = true) { _, _ -> ByteArray(70_000) }.use { server ->
            try {
                transport().lookup(server.provider, "api.vendor.com", DnsQuery { true })
                fail("Expected bounded response rejection")
            } catch (error: UnknownHostException) {
                assertFalse(error is DohNegativeAnswer)
            }
        }
    }

    @Test fun cancellationClosesBothInflightDohCalls() {
        val allowed = AtomicBoolean(true)
        Server { socket, _ ->
            socket.getInputStream().read() // Wait for the client's cancellation to close this connection.
            ByteArray(0)
        }.use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit {
                    try {
                        transport().lookup(server.provider, "api.vendor.com", DnsQuery { allowed.get() })
                        fail("Cancelled lookup returned addresses")
                    } catch (_: UnknownHostException) { }
                }
                assertTrue(server.received.await(2, TimeUnit.SECONDS))
                allowed.set(false)
                result.get(1, TimeUnit.SECONDS)
            } finally { allowed.set(false); executor.shutdownNow() }
        }
    }

    @Test fun consecutiveLookupsReuseConnectionsAndNetworkResetCreatesFreshConnections() {
        Server(keepAlive = true) { _, query -> dnsAnswer(query) }.use { server ->
            val transport = transport()
            try {
                assertEquals(2, transport.lookup(server.provider, "api.vendor.com", DnsQuery { true }).size)
                val connected = server.connections.get()
                assertEquals(2, transport.lookup(server.provider, "storage.vendor.net", DnsQuery { true }).size)
                assertEquals(connected, server.connections.get())
                assertEquals(4, server.hosts.size)
                transport.reset()
                assertEquals(2, transport.lookup(server.provider, "api.vendor.com", DnsQuery { true }).size)
                assertTrue(server.connections.get() > connected)
            } finally { transport.reset() }
        }
    }

    @Test fun completedQueryCancellationDoesNotPoisonTheCachedBootstrapResolver() {
        // Closing each server connection forces the next lookup through bootstrap
        // again, after the preceding DnsQuery has already been closed.
        Server { _, query -> dnsAnswer(query) }.use { server ->
            val transport = transport()
            val first = DnsQuery { true }
            assertEquals(2, transport.lookup(server.provider, "api.vendor.com", first).size)
            first.close()
            assertEquals(2, transport.lookup(server.provider, "storage.vendor.net", DnsQuery { true }).size)
            assertEquals(4, server.hosts.size)
        }
    }

    private fun transport() = OkHttpDohTransport(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())

    private fun dnsAnswer(query: ByteArray): ByteArray {
        val type = ((query[query.size - 4].toInt() and 255) shl 8) or (query[query.size - 3].toInt() and 255)
        val address = InetAddress.getByName(if (type == 1) "8.8.8.8" else "2001:4860:4860::8888").address
        val output = ByteArrayOutputStream()
        output.write(query.copyOf().apply {
            this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1
        })
        DataOutputStream(output).apply {
            writeShort(0xc00c); writeShort(type); writeShort(1); writeInt(60)
            writeShort(address.size); write(address)
        }
        return output.toByteArray()
    }

    private class Server(
        private val chunked: Boolean = false,
        private val keepAlive: Boolean = false,
        private val response: (Socket, ByteArray) -> ByteArray,
    ) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port = socket.localPort
        val provider = DohProvider("local", "http://bootstrap.resolver.com:$port/dns-query",
            listOf("127.0.0.1"), "https://example.com/")
        val hosts = CopyOnWriteArrayList<String>()
        val mediaTypes = CopyOnWriteArrayList<String>()
        val received = CountDownLatch(2)
        val connections = AtomicInteger()
        private val clients = CopyOnWriteArrayList<Socket>()
        private val executor = Executors.newCachedThreadPool()

        init {
            executor.submit {
                try {
                    while (!socket.isClosed) {
                        val connection = socket.accept().also { clients.add(it); connections.incrementAndGet(); it.soTimeout = 3_000 }
                        executor.submit {
                            try { connection.use { handle(it) } } catch (_: IOException) { }
                        }
                    }
                } catch (_: IOException) { }
            }
        }

        private fun handle(connection: Socket) {
            val input = connection.getInputStream().buffered()
            fun line(): String = buildString {
                while (true) {
                    val byte = input.read()
                    if (byte == -1 || byte == 10) break
                    if (byte != 13) append(byte.toChar())
                }
            }
            do {
                val requestLine = line()
                if (requestLine.isEmpty()) return
                assertTrue(requestLine.startsWith("POST /dns-query "))
                var length = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    val name = header.substringBefore(':').lowercase()
                    val value = header.substringAfter(':').trim()
                    when (name) {
                        "host" -> hosts.add(value)
                        "content-type" -> mediaTypes.add(value)
                        "content-length" -> length = value.toInt()
                    }
                }
                val query = ByteArray(length)
                java.io.DataInputStream(input).readFully(query)
                received.countDown()
                val bytes = response(connection, query)
                val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${bytes.size}"
                connection.getOutputStream().apply {
                    val persistence = if (keepAlive) "keep-alive" else "close"
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n$framing\r\nConnection: $persistence\r\n\r\n".toByteArray())
                    if (chunked) write("${bytes.size.toString(16)}\r\n".toByteArray())
                    write(bytes)
                    if (chunked) write("\r\n0\r\n\r\n".toByteArray())
                    flush()
                }
            } while (keepAlive)
        }

        override fun close() {
            socket.close()
            clients.forEach { it.close() }
            executor.shutdownNow()
        }
    }
}
