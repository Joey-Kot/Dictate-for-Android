package com.joeykot.dictate.network.dns

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class FallbackDnsRecoveryTest {
    @Test fun preparedOrActiveProviderFailureRecoversTheSameLookupWithoutCooldown() {
        for (active in listOf(false, true)) Fixture().use { fixture ->
            fixture.prepare(active)
            fixture.primaryDown.set(true)
            assertEquals(fixture.answer, fixture.resolver.lookup("api.vendor.com"))
            assertEquals(2, fixture.probeStarts())
            assertEquals(listOf("primary", "secondary"), fixture.targets().map { it.first }.takeLast(2))
            val systemCalls = fixture.systemCalls.get()
            assertEquals(fixture.answer, fixture.resolver.lookup("storage.vendor.net"))
            assertEquals("secondary", fixture.targets().last().first)
            assertEquals(systemCalls, fixture.systemCalls.get())
            assertEquals(1, fixture.requests.count { it == "primary" to FallbackDns.PROBE_HOST })
        }
    }

    @Test fun concurrentLookupsShareOneReplacementProbe() {
        Fixture().use { fixture ->
            fixture.prepare(active = true)
            fixture.primaryDown.set(true)
            fixture.holdProbes.set(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<List<InetAddress>> { fixture.resolver.lookup("api.vendor.com") }
                assertTrue(fixture.held.await(2, TimeUnit.SECONDS))
                val second = executor.submit<List<InetAddress>> { fixture.resolver.lookup("storage.vendor.net") }
                await { fixture.systemCalls.get() == 3 }
                assertEquals(2, fixture.probeStarts())
                fixture.release.countDown()
                assertEquals(fixture.answer, first.get(2, TimeUnit.SECONDS))
                assertEquals(fixture.answer, second.get(2, TimeUnit.SECONDS))
                assertEquals(2, fixture.probeStarts())
            } finally { fixture.release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun maintenanceIsSharedAndCannotReinstallAProviderRejectedDuringTheRace() {
        Fixture(heldCount = 2).use { fixture ->
            fixture.prepare(active = true)
            fixture.holdProbes.set(true)
            repeat(20) { fixture.resolver.onTranscriptionCompleted() }
            assertTrue(fixture.held.await(2, TimeUnit.SECONDS))
            fixture.primaryDown.set(true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val recovered = executor.submit<List<InetAddress>> { fixture.resolver.lookup("api.vendor.com") }
                await { fixture.messages.contains("fallback_unhealthy provider=primary") }
                fixture.release.countDown()
                assertEquals(fixture.answer, recovered.get(2, TimeUnit.SECONDS))
                assertEquals("secondary", fixture.targets().last().first)
                assertEquals(2, fixture.probeStarts())
                assertEquals(fixture.answer, fixture.resolver.lookup("storage.vendor.net"))
                assertEquals("secondary", fixture.targets().last().first)
            } finally { fixture.release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun failedReplacementBatchCoolsDownAndRecoveredProvidersCanRejoinLater() {
        Fixture().use { fixture ->
            fixture.prepare(active = true)
            fixture.primaryDown.set(true)
            fixture.secondaryProbeDown.set(true)
            assertFails { fixture.resolver.lookup("api.vendor.com") }
            assertEquals(2, fixture.probeStarts())
            repeat(3) { assertFails { fixture.resolver.lookup("api.vendor.com") } }
            assertEquals(2, fixture.probeStarts())
            fixture.primaryDown.set(false)
            fixture.clock.set(TimeUnit.SECONDS.toNanos(31))
            assertEquals(fixture.answer, fixture.resolver.lookup("api.vendor.com"))
            assertEquals(3, fixture.probeStarts())
            assertEquals("primary", fixture.targets().last().first)
        }
    }

    @Test fun targetFailuresHaveOnlyOneReplacementAttemptPerLookup() {
        Fixture().use { fixture ->
            fixture.prepare(active = false)
            fixture.allTargetsDown.set(true)
            assertFails { fixture.resolver.lookup("api.vendor.com") }
            assertEquals(listOf("primary", "secondary"), fixture.targets().map { it.first })
            assertEquals(2, fixture.probeStarts())
        }
    }

    @Test fun cancellationOrPrivateDnsDuringReplacementStopsTheLookupAndProbe() {
        for (privateDns in listOf(false, true)) Fixture().use { fixture ->
            fixture.prepare(active = true)
            fixture.primaryDown.set(true)
            fixture.holdProbes.set(true)
            val call = OkHttpClient().newCall(Request.Builder().url("https://api.vendor.com/").build())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit {
                    DnsCallContext.enter(call)
                    assertFails { fixture.resolver.lookup("api.vendor.com") }
                }
                assertTrue(fixture.held.await(2, TimeUnit.SECONDS))
                if (privateDns) {
                    fixture.network.set(DnsNetworkState("wifi", true))
                    fixture.resolver.refreshNetwork()
                } else call.cancel()
                result.get(1, TimeUnit.SECONDS)
                assertTrue(fixture.stopped.await(1, TimeUnit.SECONDS))
                assertFalse(fixture.targets().any { it.first == "secondary" })
                assertEquals(2, fixture.probeStarts())
            } finally { fixture.release.countDown(); executor.shutdownNow() }
        }
    }

    private class Fixture(heldCount: Int = 1) : AutoCloseable {
        val primaryDown = AtomicBoolean()
        val secondaryProbeDown = AtomicBoolean()
        val allTargetsDown = AtomicBoolean()
        val holdProbes = AtomicBoolean()
        val held = CountDownLatch(heldCount)
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val systemCalls = AtomicInteger()
        val clock = AtomicLong()
        val network = AtomicReference(DnsNetworkState("wifi", false))
        val messages = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val answer = listOf(InetAddress.getByName("8.8.8.8"))
        val resolver = FallbackDns(
            networkState = network::get,
            system = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    systemCalls.incrementAndGet()
                    throw UnknownHostException()
                }
            },
            providers = listOf("primary", "secondary").map {
                DohProvider(it, "https://$it.resolver.com/dns-query", listOf("8.8.8.8"), "https://example.com/")
            },
            transport = DohTransport { provider, host, query ->
                requests.add(provider.id to host)
                if (host == FallbackDns.PROBE_HOST) {
                    if (holdProbes.get()) {
                        held.countDown()
                        try {
                            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                                // Deliberately allow a late primary probe response;
                                // the selection must still reject its stale success.
                                if (provider.id == "secondary") query.check()
                            }
                        } finally { stopped.countDown() }
                    }
                    if (provider.id == "secondary") {
                        if (secondaryProbeDown.get()) throw UnknownHostException()
                        while (!primaryDown.get() && !allTargetsDown.get() && !holdProbes.get()) {
                            Thread.sleep(5)
                            query.check()
                        }
                    }
                } else if (allTargetsDown.get() || provider.id == "primary" && primaryDown.get()) {
                    throw UnknownHostException()
                }
                answer
            },
            nanoTime = clock::get,
            log = messages::add,
            probeTimeoutMillis = 2_000,
        )

        fun prepare(active: Boolean) {
            assertEquals("primary", resolver.prepare().get(2, TimeUnit.SECONDS)?.id)
            if (active) assertEquals(answer, resolver.lookup("api.vendor.com"))
        }
        fun targets() = requests.filter { it.second != FallbackDns.PROBE_HOST }
        fun probeStarts() = messages.count { it.startsWith("probe_start ") }
        override fun close() { release.countDown(); resolver.close() }
    }

    private companion object {
        fun assertFails(action: () -> Unit) {
            try { action(); fail("Expected DNS failure") } catch (_: UnknownHostException) { }
        }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Timed out waiting for recovery", condition())
        }
    }
}
