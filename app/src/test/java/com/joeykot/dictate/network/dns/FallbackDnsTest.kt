package com.joeykot.dictate.network.dns

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FallbackDnsTest {
    private val resolvers = mutableListOf<FallbackDns>()
    private val network = AtomicReference(DnsNetworkState("wifi", false))
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val answer = listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
    private val candidates = listOf(provider("slow"), provider("fast"))

    @After fun close() { resolvers.forEach(FallbackDns::close) }

    @Test fun firstFailureRecoversCurrentLookupAndReusesWinnerAcrossHosts() {
        val systemCalls = AtomicInteger()
        val resolver = resolver(system = dns { systemCalls.incrementAndGet(); throw UnknownHostException() })
        assertTrue(requests.isEmpty())
        assertEquals(answer, resolver.lookup("api.vendor.com"))
        val probeCount = requests.count { it.second == FallbackDns.PROBE_HOST }
        assertEquals(answer, resolver.lookup("storage.vendor.net"))
        assertEquals(1, systemCalls.get())
        assertEquals(probeCount, requests.count { it.second == FallbackDns.PROBE_HOST })
        assertTrue(requests.filter { it.second != FallbackDns.PROBE_HOST }.all { it.first == "fast" })
    }

    @Test fun networkChangeClearsActivationAndPreparesAgain() {
        val succeed = java.util.concurrent.atomic.AtomicBoolean(false)
        val systemCalls = AtomicInteger()
        val primaryAnswer = listOf(InetAddress.getByName("1.1.1.1"))
        val resolver = resolver(system = dns {
            systemCalls.incrementAndGet()
            if (succeed.get()) primaryAnswer else throw UnknownHostException()
        })
        assertNotNull(resolver.prepare().get(2, TimeUnit.SECONDS))
        assertEquals(answer, resolver.lookup("api.vendor.com"))
        val firstProbeCount = requests.count { it.second == FallbackDns.PROBE_HOST }
        network.set(DnsNetworkState("mobile", false))
        succeed.set(true)
        assertNotNull(resolver.prepare().get(2, TimeUnit.SECONDS))
        assertEquals(primaryAnswer, resolver.lookup("api.vendor.com"))
        assertEquals(2, systemCalls.get())
        assertTrue(requests.count { it.second == FallbackDns.PROBE_HOST } > firstProbeCount)
    }

    @Test fun strictActiveOrUnknownPrivateDnsNeverProbes() {
        val resolver = resolver()
        listOf(DnsNetworkState("wifi", true), DnsNetworkState("wifi", null), DnsNetworkState(null, null)).forEach {
            network.set(it)
            assertNull(resolver.prepare().get(1, TimeUnit.SECONDS))
            repeat(5) { assertFails { resolver.lookup("api.vendor.com") } }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun privateDnsEnabledAfterSelectionImmediatelyRestoresSystemDns() {
        val systemCalls = AtomicInteger()
        val resolver = resolver(system = dns { systemCalls.incrementAndGet(); throw UnknownHostException() })
        assertNotNull(resolver.prepare().get(2, TimeUnit.SECONDS))
        resolver.lookup("api.vendor.com")
        val before = requests.size
        network.set(DnsNetworkState("wifi", true))
        assertFails { resolver.lookup("api.vendor.com") }
        assertEquals(before, requests.size)
        assertEquals(2, systemCalls.get())
        network.set(DnsNetworkState("wifi", false))
        assertNotNull(resolver.prepare().get(2, TimeUnit.SECONDS))
        assertTrue(requests.size > before)
    }

    @Test fun localNamesAndSearchDomainsStayWithSystemDns() {
        val resolver = resolver()
        network.set(DnsNetworkState("wifi", false, setOf("corp.company.com")))
        listOf("localhost", "printer.local", "api.lan", "api.internal", "office.home.arpa",
            "host.corp.company.com", "api.test", "192.168.0.1", "2001:db8::1").forEach { host ->
            repeat(4) { assertFails { resolver.lookup(host) } }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun cancelledSystemLookupDoesNotActivateFallback() {
        val call = OkHttpClient().newCall(Request.Builder().url("https://api.vendor.com/").build())
        var cancelDuringLookup = true
        val resolver = resolver(system = dns {
            if (cancelDuringLookup) { call.cancel(); cancelDuringLookup = false }
            throw UnknownHostException()
        })
        DnsCallContext.enter(call)
        assertFails { resolver.lookup("api.vendor.com") }
        assertTrue(requests.isEmpty())
        assertEquals(answer, resolver.lookup("api.vendor.com"))
    }

    @Test fun failedProbesRespectCooldownAndProcessRestartRunsANewProbe() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val transport = DohTransport { provider, host, _ ->
            requests.add(provider.id to host)
            throw UnknownHostException()
        }
        val resolver = resolver(transport = transport, clock = clock::get)
        assertFails { resolver.lookup("api.vendor.com") }
        assertEquals(2, requests.size)
        repeat(4) { assertFails { resolver.lookup("api.vendor.com") } }
        assertEquals(2, requests.size)
        clock.set(TimeUnit.SECONDS.toNanos(31))
        assertFails { resolver.lookup("api.vendor.com") }
        assertEquals(4, requests.size)
        val fresh = resolver(transport = transport)
        assertNull(fresh.prepare().get(2, TimeUnit.SECONDS))
        assertEquals(6, requests.size)
    }

    @Test fun negativeAnswerDoesNotSendThePrivateTargetToAnotherProvider() {
        val resolver = resolver(transport = DohTransport { provider, host, _ ->
            requests.add(provider.id to host)
            if (host == FallbackDns.PROBE_HOST) answer else throw DohNegativeAnswer()
        })
        repeat(2) { assertFails { resolver.lookup("api.vendor.com") } }
        assertEquals(1, requests.filter { it.second != FallbackDns.PROBE_HOST }.map { it.first }.distinct().size)
        assertTrue(requests.count { it.second == FallbackDns.PROBE_HOST } <= 2)
    }

    @Test fun concurrentSegmentsShareOneProbeAndPrivateDnsChangeDiscardsItsResult() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(2)
        val resolver = resolver(transport = DohTransport { provider, host, query ->
            requests.add(provider.id to host)
            entered.countDown()
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) query.check()
                answer
            } finally { stopped.countDown() }
        })
        val preparation = resolver.prepare()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { assertFails { resolver.lookup("api.vendor.com") } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = executor.submit { assertFails { resolver.lookup("api.vendor.com") } }
            network.set(DnsNetworkState("wifi", true))
            resolver.refreshNetwork()
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertNull(preparation.get(1, TimeUnit.SECONDS))
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.second == FallbackDns.PROBE_HOST })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun preparationRunsAsynchronouslyAndSystemDnsRemainsPrimaryUntilItsFirstFailure() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val systemHealthy = java.util.concurrent.atomic.AtomicBoolean(true)
        val systemCalls = AtomicInteger()
        val primaryAnswer = listOf(InetAddress.getByName("1.1.1.1"))
        val resolver = resolver(system = dns {
            systemCalls.incrementAndGet()
            if (systemHealthy.get()) primaryAnswer else throw UnknownHostException()
        }, transport = DohTransport { provider, host, query ->
            requests.add(provider.id to host)
            if (host == FallbackDns.PROBE_HOST) {
                entered.countDown()
                while (!release.await(10, TimeUnit.MILLISECONDS)) query.check()
            }
            answer
        })
        val preparation = resolver.prepare()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(preparation.isDone)
            val primary = executor.submit<List<InetAddress>> { resolver.lookup("api.vendor.com") }
            assertEquals(primaryAnswer, primary.get(1, TimeUnit.SECONDS))
            assertTrue(requests.all { it.second == FallbackDns.PROBE_HOST })
            release.countDown()
            assertNotNull(preparation.get(2, TimeUnit.SECONDS))
            val probeCount = requests.count { it.second == FallbackDns.PROBE_HOST }
            assertNotNull(resolver.prepare().get(1, TimeUnit.SECONDS))
            assertEquals(probeCount, requests.count { it.second == FallbackDns.PROBE_HOST })
            systemHealthy.set(false)
            assertEquals(answer, resolver.lookup("api.vendor.com"))
            assertEquals(answer, resolver.lookup("storage.vendor.net"))
            assertEquals(2, systemCalls.get())
            assertEquals(probeCount, requests.count { it.second == FallbackDns.PROBE_HOST })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun firstFailureWaitsForExistingPreparationInsteadOfStartingAnotherBatch() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val systemFailed = CountDownLatch(1)
        val resolver = resolver(system = dns { systemFailed.countDown(); throw UnknownHostException() },
            transport = DohTransport { provider, host, query ->
                requests.add(provider.id to host)
                if (host == FallbackDns.PROBE_HOST) {
                    entered.countDown()
                    while (!release.await(10, TimeUnit.MILLISECONDS)) query.check()
                }
                answer
            })
        val preparation = resolver.prepare()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = executor.submit<List<InetAddress>> { resolver.lookup("api.vendor.com") }
            assertTrue(systemFailed.await(1, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            release.countDown()
            assertNotNull(preparation.get(2, TimeUnit.SECONDS))
            assertEquals(answer, result.get(2, TimeUnit.SECONDS))
            assertEquals(2, requests.count { it.second == FallbackDns.PROBE_HOST })
            assertEquals(1, requests.count { it.second == "api.vendor.com" })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun cancellingAWaitingRequestLeavesProcessPreparationAvailableForTheNextRequest() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val systemFailed = CountDownLatch(1)
        val resolver = resolver(system = dns { systemFailed.countDown(); throw UnknownHostException() },
            transport = DohTransport { provider, host, query ->
                requests.add(provider.id to host)
                if (host == FallbackDns.PROBE_HOST) {
                    entered.countDown()
                    while (!release.await(10, TimeUnit.MILLISECONDS)) query.check()
                }
                answer
            })
        val preparation = resolver.prepare()
        val call = OkHttpClient().newCall(Request.Builder().url("https://api.vendor.com/").build())
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = executor.submit {
                DnsCallContext.enter(call)
                assertFails { resolver.lookup("api.vendor.com") }
            }
            assertTrue(systemFailed.await(1, TimeUnit.SECONDS))
            call.cancel()
            result.get(1, TimeUnit.SECONDS)
            assertFalse(preparation.isDone)
            assertTrue(requests.all { it.second == FallbackDns.PROBE_HOST })
            release.countDown()
            assertNotNull(preparation.get(2, TimeUnit.SECONDS))
            assertEquals(answer, resolver.lookup("api.vendor.com"))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private fun resolver(
        system: Dns = dns { throw UnknownHostException() },
        transport: DohTransport = DohTransport { provider, host, query ->
            requests.add(provider.id to host)
            if (provider.id == "slow") {
                while (query.allowed()) Thread.sleep(5)
                query.check()
            }
            answer
        },
        clock: () -> Long = System::nanoTime,
    ): FallbackDns = FallbackDns(network::get, system, transport, candidates,
        nanoTime = clock, probeTimeoutMillis = 2_000).also { resolvers.add(it) }

    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected DNS failure") } catch (_: UnknownHostException) { }
    }

    private fun dns(action: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = action(hostname)
    }

    private fun provider(id: String) = DohProvider(id, "https://$id.resolver.com/dns-query",
        listOf("8.8.8.8"), "https://$id.resolver.com/")
}
