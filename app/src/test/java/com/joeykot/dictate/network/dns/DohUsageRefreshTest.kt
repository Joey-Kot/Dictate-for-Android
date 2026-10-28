package com.joeykot.dictate.network.dns

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DohUsageRefreshTest {
    @Test fun twentiethTranscriptionRefreshesAsynchronouslyAndKeepsTheActiveBackupUntilReplacementIsReady() {
        Fixture().use { fixture ->
            fixture.prepareAndActivate()
            fixture.complete(19)
            assertEquals(1, fixture.probeStarts())

            fixture.desired.set("new")
            fixture.blockProbe = true
            fixture.complete(1)
            assertTrue(fixture.refreshEntered.await(2, TimeUnit.SECONDS))
            assertEquals(2, fixture.probeStarts())
            assertEquals(fixture.oldAddress, fixture.resolver.lookup("api.vendor.com"))

            // Another interval shares this in-flight batch instead of queuing a duplicate refresh.
            fixture.complete(20)
            assertEquals(2, fixture.probeStarts())
            fixture.release.countDown()
            await { fixture.resolver.lookup("api.vendor.com") == fixture.newAddress }
            fixture.complete(19)
            assertEquals(2, fixture.probeStarts())
            fixture.complete(1)
            await { fixture.probeStarts() == 3 }
        }
    }

    @Test fun failedMaintenanceKeepsThePreparedAndActiveBackup() {
        Fixture().use { fixture ->
            fixture.prepareAndActivate()
            fixture.desired.set("unavailable")
            fixture.complete(20)
            await { fixture.messages.any { it.startsWith("probe_failed retained=true") } }
            assertEquals(fixture.oldAddress, fixture.resolver.lookup("api.vendor.com"))
            // The retained backup remains available during the failure cooldown.
            fixture.complete(20)
            assertEquals(fixture.oldAddress, fixture.resolver.lookup("storage.vendor.net"))
        }
    }

    @Test fun successfulMaintenanceDoesNotActivateFallbackBeforeSystemDnsFails() {
        Fixture(systemHealthy = true).use { fixture ->
            assertNotNull(fixture.resolver.prepare().get(2, TimeUnit.SECONDS))
            fixture.desired.set("new")
            fixture.complete(20)
            await { fixture.messages.any { it.startsWith("fallback_ready provider=new") } }
            assertEquals(fixture.systemAddress, fixture.resolver.lookup("api.vendor.com"))
            assertFalse(fixture.messages.any { it.startsWith("fallback_activated") })
        }
    }

    @Test fun privateDnsOrUnknownNetworkSkipsUsageRefresh() {
        for (state in listOf(DnsNetworkState("wifi", true), DnsNetworkState(null, null))) {
            Fixture().use { fixture ->
                fixture.prepareAndActivate()
                fixture.network.set(state)
                fixture.resolver.refreshNetwork()
                fixture.complete(20)
                await { fixture.messages.contains("refresh_skipped reason=private_dns_or_unavailable") }
                assertEquals(1, fixture.probeStarts())
                try { fixture.resolver.lookup("api.vendor.com"); fail("Expected system DNS failure") }
                catch (_: UnknownHostException) { }
            }
        }
    }

    @Test fun networkChangeDiscardsAnOldMaintenanceResultAndPreparesForTheNewNetwork() {
        Fixture().use { fixture ->
            fixture.prepareAndActivate()
            fixture.desired.set("new")
            fixture.blockProbe = true
            fixture.complete(20)
            assertTrue(fixture.refreshEntered.await(2, TimeUnit.SECONDS))
            fixture.network.set(DnsNetworkState("mobile", false))
            fixture.desired.set("old")
            fixture.resolver.refreshNetwork()
            fixture.release.countDown()
            assertEquals("old", fixture.resolver.prepare().get(2, TimeUnit.SECONDS)?.id)
            assertEquals(fixture.oldAddress, fixture.resolver.lookup("api.vendor.com"))
        }
    }

    private class Fixture(private val systemHealthy: Boolean = false) : AutoCloseable {
        val oldAddress = listOf(InetAddress.getByName("8.8.8.8"))
        val newAddress = listOf(InetAddress.getByName("1.1.1.1"))
        val systemAddress = listOf(InetAddress.getByName("9.9.9.9"))
        val desired = AtomicReference("old")
        val network = AtomicReference(DnsNetworkState("wifi", false))
        val messages = CopyOnWriteArrayList<String>()
        val refreshEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockProbe = false
        val resolver = FallbackDns(
            networkState = network::get,
            system = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (systemHealthy) systemAddress else throw UnknownHostException()
            },
            providers = listOf("old", "new").map {
                DohProvider(it, "https://$it.resolver.com/dns-query", listOf("8.8.8.8"), "https://example.com/")
            },
            transport = DohTransport { provider, host, query ->
                if (host == FallbackDns.PROBE_HOST) {
                    // Capture before blocking so a late result really belongs to its old configuration.
                    val selected = desired.get()
                    if (blockProbe) {
                        refreshEntered.countDown()
                        while (!release.await(10, TimeUnit.MILLISECONDS)) query.check()
                    }
                    if (provider.id != selected) throw UnknownHostException()
                }
                if (provider.id == "old") oldAddress else newAddress
            },
            log = messages::add,
            probeTimeoutMillis = 2_000,
        )

        fun prepareAndActivate() {
            assertEquals("old", resolver.prepare().get(2, TimeUnit.SECONDS)?.id)
            assertEquals(oldAddress, resolver.lookup("api.vendor.com"))
        }

        fun complete(count: Int) { repeat(count) { resolver.onTranscriptionCompleted() } }
        fun probeStarts() = messages.count { it.startsWith("probe_start ") }
        override fun close() { release.countDown(); resolver.close() }
    }

    private companion object {
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Timed out waiting for DNS refresh", condition())
        }
    }
}
