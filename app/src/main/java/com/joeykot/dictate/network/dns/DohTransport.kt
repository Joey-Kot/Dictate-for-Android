package com.joeykot.dictate.network.dns

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Each logical lookup owns both A/AAAA HTTP calls so cancellation also closes bootstrap sockets. */
internal class DnsQuery(val preferIpv6: Boolean = false, private val permitted: () -> Boolean) : AutoCloseable {
    private val cancelled = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    fun allowed(): Boolean = !cancelled.get() && permitted()
    fun check() { if (!allowed()) throw UnknownHostException("DNS lookup cancelled") }
    fun register(call: Call) {
        calls.add(call)
        if (!allowed()) call.cancel()
    }
    fun remove(call: Call) { calls.remove(call) }
    override fun close() {
        cancelled.set(true)
        calls.forEach(Call::cancel)
    }
}

internal fun interface DohTransport {
    fun lookup(provider: DohProvider, hostname: String, query: DnsQuery): List<InetAddress>
    fun reset() {}
}

/** An authoritative negative result must not trigger a race against other providers. */
internal class DohNegativeAnswer : UnknownHostException("DNS name has no address")
private class NegativeDnsResponse : IOException("DNS negative response")

internal class OkHttpDohTransport(baseClient: OkHttpClient = OkHttpClient()) : DohTransport {
    private data class BootstrapKey(val provider: DohProvider, val preferIpv6: Boolean, val generation: Long)
    private val generation = AtomicLong()
    private val bootstrapResolvers = ConcurrentHashMap<BootstrapKey, Dns>()
    // This client deliberately never uses AppDns. Bootstrap is independent of the failing resolver.
    // Keep the platform proxy selector: recovery must not bypass an explicitly configured proxy.
    private val client = baseClient.newBuilder()
        .dns(Dns.SYSTEM)
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(2_000, TimeUnit.MILLISECONDS)
        .writeTimeout(2_000, TimeUnit.MILLISECONDS)
        .callTimeout(2_500, TimeUnit.MILLISECONDS)
        // DNS queries are safe to retry across the resolver's bootstrap addresses.
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(4, 1, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            chain.proceed(chain.request()).use { response ->
                if (!response.isSuccessful) throw IOException("DoH HTTP failure")
                val body = response.body ?: throw IOException("Empty DoH response")
                val mediaType = body.contentType()
                if (mediaType?.type != "application" || mediaType.subtype != "dns-message") {
                    throw IOException("Invalid DoH content type")
                }
                // The library checks Content-Length, so also bound unknown/chunked bodies here.
                val source = body.source()
                source.request((MAX_DNS_BYTES + 1).toLong())
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, (MAX_DNS_BYTES + 1).toLong()))
                validateDnsEnvelope(bytes)
                response.newBuilder().body(bytes.toResponseBody(mediaType)).build()
            }
        }
        .build()

    override fun reset() {
        generation.incrementAndGet()
        bootstrapResolvers.clear()
        client.connectionPool.evictAll()
    }

    override fun lookup(provider: DohProvider, hostname: String, query: DnsQuery): List<InetAddress> {
        query.check()
        val scopedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) = query.register(call)
            override fun callEnd(call: Call) = query.remove(call)
            override fun callFailed(call: Call, ioe: IOException) = query.remove(call)
        }).build()
        val endpoint = provider.url.toHttpUrl()
        // OkHttp includes Dns identity in connection matching. Keep it stable for
        // this provider/network; call listeners own cancellation, never this cache.
        val bootstrapDns = bootstrapResolvers.computeIfAbsent(BootstrapKey(provider, query.preferIpv6, generation.get())) {
            val addresses = provider.bootstrap.map { InetAddress.getByName(it) }
                .sortedBy { if ((it.address.size == 16) == query.preferIpv6) 0 else 1 }
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    // A platform HTTP proxy may itself have a hostname. Preserve its system routing.
                    if (hostname == endpoint.host) addresses else Dns.SYSTEM.lookup(hostname)
            }
        }
        val resolver = DnsOverHttps.Builder()
            .client(scopedClient)
            .url(endpoint)
            .systemDns(bootstrapDns)
            .post(true)
            .includeIPv6(true)
            .resolvePrivateAddresses(false)
            .build()
        val watch = watchdog.scheduleWithFixedDelay({ if (!query.allowed()) query.close() },
            0, 50, TimeUnit.MILLISECONDS)
        return try {
            resolver.lookup(hostname).also { query.check() }
        } catch (error: UnknownHostException) {
            query.check()
            if (error.cause == null || hasNegativeResponse(error)) throw DohNegativeAnswer()
            throw error
        } finally {
            watch.cancel(false)
            query.close()
        }
    }

    private fun hasNegativeResponse(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is NegativeDnsResponse } ||
            error.suppressed.any { it is NegativeDnsResponse }

    companion object {
        private const val MAX_DNS_BYTES = 65_535
        private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dictate-doh-cancellation").apply { isDaemon = true }
        }

        internal fun validateDnsEnvelope(bytes: ByteArray) {
            if (bytes.size !in 12..MAX_DNS_BYTES) throw IOException("Invalid DoH response size")
            fun word(offset: Int) = (bytes[offset].toInt() and 255 shl 8) or (bytes[offset + 1].toInt() and 255)
            val flags = word(2)
            if (word(0) != 0 || flags and 0x8000 == 0 || flags and 0x7a00 != 0 || word(4) != 1) {
                throw IOException("Invalid DNS response header")
            }
            when (flags and 15) {
                0 -> Unit
                3 -> throw NegativeDnsResponse()
                else -> throw IOException("DNS server failure")
            }
        }
    }
}
