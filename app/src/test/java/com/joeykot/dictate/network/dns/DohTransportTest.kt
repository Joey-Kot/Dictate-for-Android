package com.joeykot.dictate.network.dns

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DohTransportTest {
    @Test fun rejectsHttpSuccessWithDnsFailureOrMalformedEnvelope() {
        OkHttpDohTransport.validateDnsEnvelope(header(0x8180))
        listOf(header(0x8183), header(0x8182), header(0x8185), header(0x8380),
            header(0x0180), header(0x8980), ByteArray(11), ByteArray(65_536),
            header(0x8180).also { it[1] = 1 }, header(0x8180).also { it[5] = 0 }).forEach { body ->
            try { OkHttpDohTransport.validateDnsEnvelope(body); fail("Invalid DNS envelope accepted") }
            catch (_: IOException) { }
        }
    }

    @Test fun queryClosesRegisteredCallsAndLateRegistrations() {
        var allowed = true
        val query = DnsQuery { allowed }
        fun call(): Call = OkHttpClient().newCall(Request.Builder().url("https://resolver.com/").build())
        val first = call()
        query.register(first)
        assertFalse(first.isCanceled())
        allowed = false
        val late = call()
        query.register(late)
        assertTrue(late.isCanceled())
        query.close()
        assertTrue(first.isCanceled())
    }

    @Test fun catalogHasUniqueHttpsEndpointsAndOnlyLiteralBootstrapAddresses() {
        val providers = DohProviderCatalog.providers
        assertTrue(providers.size >= 16)
        assertEquals(providers.size, providers.map { it.id }.toSet().size)
        assertEquals(providers.size, providers.map { it.url }.toSet().size)
        providers.forEach { provider ->
            assertTrue(provider.url.startsWith("https://"))
            assertTrue(provider.source.startsWith("https://"))
            assertTrue(provider.bootstrap.isNotEmpty())
            provider.bootstrap.forEach { address ->
                assertTrue(address.matches(Regex("[0-9a-fA-F:.]+")))
                java.net.InetAddress.getByName(address)
            }
        }
    }

    private fun header(flags: Int) = ByteArray(12).apply {
        this[2] = (flags shr 8).toByte(); this[3] = flags.toByte(); this[5] = 1
    }
}
