package com.joeykot.dictate.network.dns

import okhttp3.Dns
import java.net.IDN
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class DnsNetworkState(
    val network: String?,
    val privateDns: Boolean?,
    val searchDomains: Set<String> = emptySet(),
    val preferIpv6: Boolean = false,
) {
    val permitsFallback: Boolean get() = network != null && privateDns == false
}

/** Prepares a process-local backup while system DNS remains the primary resolver. */
internal class FallbackDns(
    private val networkState: () -> DnsNetworkState,
    private val system: Dns = Dns.SYSTEM,
    private val transport: DohTransport = OkHttpDohTransport(),
    private val providers: List<DohProvider> = DohProviderCatalog.providers,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val probeTimeoutMillis: Long = 6_000,
    private val cooldownMillis: Long = 30_000,
) : Dns, AutoCloseable {
    private val lock = Any()
    private var state: DnsNetworkState? = null
    private var generation = 0L
    private var prepared: DohProvider? = null
    private var fallbackActive = false
    private var preparationEnabled = false
    private var closed = false
    private var completedTranscriptions = 0
    private var usageRefreshQueued = false
    private var selection: Selection? = null
    private var retryAfterNanos = Long.MIN_VALUE
    private val unhealthyUntilNanos = mutableMapOf<DohProvider, Long>()
    private val probes = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "dictate-doh-probe").apply { isDaemon = true }
    }
    private val coordinator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-doh-selection").apply { isDaemon = true }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val callCancelled = DnsCallContext.take()
        fun cancelled() = callCancelled?.invoke() == true || Thread.currentThread().isInterrupted
        fun checkCancellation() { if (cancelled()) throw UnknownHostException("DNS lookup cancelled") }
        checkCancellation()
        val observed = refreshNetwork()
        val host = normalize(hostname)
        val eligible = observed.permitsFallback && isPublicHostname(host, observed.searchDomains)
        val epoch = synchronized(lock) { generation }
        fun permitted(): Boolean = !cancelled() && refreshNetwork() == observed &&
            synchronized(lock) { generation == epoch } && observed.permitsFallback

        var attempts = 0
        val preferred = synchronized(lock) { if (eligible && fallbackActive) prepared else null }
        if (preferred != null) {
            attempts++
            try {
                return resolve(preferred, host, observed.preferIpv6, ::permitted)
            } catch (error: DohNegativeAnswer) {
                throw error
            } catch (_: UnknownHostException) {
                checkCancellation()
                if (!permitted()) return system.lookup(hostname).also { checkCancellation() }
                markUnhealthy(preferred, epoch)
            }
        }

        val original: UnknownHostException
        try {
            return system.lookup(hostname).also { checkCancellation() }
        } catch (error: UnknownHostException) {
            checkCancellation()
            original = error
        }
        if (!eligible || !permitted()) throw original

        log("system_failure action=use_fallback")
        var failure = original
        // One initial provider and at most one recovery provider per lookup.
        // The shared probe races only the fixed probe name, never the API host.
        while (attempts < MAX_FALLBACK_ATTEMPTS && permitted()) {
            val provider = selectProvider(observed, epoch, ::permitted, failure,
                if (attempts == 0) "dns_failure" else "provider_failure")
            attempts++
            try {
                return resolve(provider, host, observed.preferIpv6, ::permitted).also {
                    synchronized(lock) {
                        if (generation == epoch && prepared == provider && !fallbackActive) {
                            fallbackActive = true
                            log("fallback_activated provider=${provider.id}")
                        }
                    }
                }
            } catch (error: DohNegativeAnswer) {
                throw error
            } catch (error: UnknownHostException) {
                checkCancellation()
                if (!permitted()) throw error
                markUnhealthy(provider, epoch)
                failure = error
            }
        }
        throw failure
    }

    private fun markUnhealthy(provider: DohProvider, epoch: Long) {
        synchronized(lock) {
            if (generation != epoch) return
            unhealthyUntilNanos[provider] = nanoTime() + TimeUnit.MILLISECONDS.toNanos(cooldownMillis)
            if (prepared == provider) {
                prepared = null
                fallbackActive = false
                // A failed maintenance race may have retained this provider.
                // Its own failure now permits one immediate replacement race.
                retryAfterNanos = Long.MIN_VALUE
            }
            log("fallback_unhealthy provider=${provider.id}")
        }
    }

    private fun selectProvider(
        observed: DnsNetworkState,
        epoch: Long,
        permitted: () -> Boolean,
        original: UnknownHostException,
        reason: String,
    ): DohProvider {
        val run = synchronized(lock) {
            if (generation != epoch) return@synchronized null
            // Preparation may have completed while this system lookup was running.
            prepared?.let { return it }
            startSelectionLocked(observed, background = false, reason = reason)?.also { it.waiters++ }
        } ?: throw original

        return try {
            awaitProvider(run.result, permitted, original)
        } finally {
            synchronized(lock) {
                run.waiters--
                if (run.waiters == 0 && !run.background && !run.result.isDone) {
                    if (selection === run) selection = null
                    run.cancel()
                }
            }
        }
    }

    /** Does not wait for network I/O; preparation alone never switches application lookups to DoH. */
    fun prepare(): CompletableFuture<DohProvider?> {
        synchronized(lock) { preparationEnabled = true }
        val current = refreshNetwork()
        return synchronized(lock) {
            if (state != current || !current.permitsFallback) return@synchronized CompletableFuture.completedFuture(null)
            prepared?.let { return@synchronized CompletableFuture.completedFuture(it) }
            startSelectionLocked(current, background = true)?.result ?: CompletableFuture.completedFuture(null)
        }
    }

    /** Whole voice jobs call this once; all network work runs on the coordinator. */
    fun onTranscriptionCompleted() {
        synchronized(lock) {
            if (closed) return
            completedTranscriptions++
            if (completedTranscriptions < TRANSCRIPTIONS_PER_REFRESH) return
            completedTranscriptions = 0
            log("refresh_requested reason=transcription_count interval=$TRANSCRIPTIONS_PER_REFRESH")
            selection?.let {
                it.background = true
                return
            }
            if (usageRefreshQueued) return
            usageRefreshQueued = true
            coordinator.execute {
                try {
                    val current = refreshNetwork()
                    synchronized(lock) {
                        if (!closed && !current.permitsFallback) {
                            log("refresh_skipped reason=private_dns_or_unavailable")
                        } else {
                            startSelectionLocked(current, background = true, reason = "transcription_count")
                        }
                    }
                } finally {
                    synchronized(lock) { usageRefreshQueued = false }
                }
            }
        }
    }

    /** Must be called under lock; unchanged callbacks and concurrent requests share one batch. */
    private fun startSelectionLocked(
        current: DnsNetworkState,
        background: Boolean,
        reason: String = if (background) "preparation" else "dns_failure",
    ): Selection? {
        if (closed || !current.permitsFallback || state != current) return null
        selection?.let {
            if (background) it.background = true
            return it
        }
        val now = nanoTime()
        if (now < retryAfterNanos) return null
        unhealthyUntilNanos.entries.removeAll { now >= it.value }
        val candidates = providers.filterNot { unhealthyUntilNanos.containsKey(it) }
        return Selection(generation, current, candidates, background).also {
            selection = it
            log("probe_start reason=$reason candidates=${candidates.size}")
            coordinator.execute { probe(it) }
        }
    }

    private fun awaitProvider(
        result: CompletableFuture<DohProvider?>,
        permitted: () -> Boolean,
        original: UnknownHostException,
    ): DohProvider {
        try {
            while (permitted()) {
                try {
                    return result.get(50, TimeUnit.MILLISECONDS) ?: throw original
                } catch (_: TimeoutException) {
                    // Observe cancellation and Private DNS changes while the batch is running.
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        throw original
    }

    /** Called by Android's default-network callback as well as synchronously before using DoH. */
    fun refreshNetwork(): DnsNetworkState {
        val current = networkState()
        synchronized(lock) {
            if (state != current) {
                val old = selection
                generation++
                state = current
                prepared = null
                fallbackActive = false
                retryAfterNanos = Long.MIN_VALUE
                unhealthyUntilNanos.clear()
                selection = null
                old?.cancel()
                transport.reset()
                log("reset reason=network_or_dns_changed fallbackAllowed=${current.permitsFallback}")
                if (preparationEnabled) startSelectionLocked(current, background = true)
            }
        }
        return current
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            preparationEnabled = false
            prepared = null
            fallbackActive = false
            selection?.cancel()
            selection = null
            generation++
        }
        coordinator.shutdownNow()
        probes.shutdownNow()
    }

    private fun resolve(provider: DohProvider, host: String, preferIpv6: Boolean, permitted: () -> Boolean): List<InetAddress> =
        DnsQuery(preferIpv6, permitted).use { query ->
            transport.lookup(provider, host, query).sortedBy {
                // Keep a usable address family first even if AAAA completes before A.
                if ((it.address.size == 16) == preferIpv6) 0 else 1
            }.also {
                if (!permitted()) throw UnknownHostException("DNS network changed")
                if (it.isEmpty()) throw DohNegativeAnswer()
                log("fallback_lookup provider=${provider.id} outcome=success addresses=${it.size}")
            }
        }

    private fun probe(run: Selection) {
        val startedAt = System.nanoTime()
        val completion = ExecutorCompletionService<DohProvider?>(probes)
        fun available(provider: DohProvider): Boolean = synchronized(lock) {
            generation == run.generation && nanoTime() >= (unhealthyUntilNanos[provider] ?: Long.MIN_VALUE)
        }
        val futures = run.candidates.shuffled().map { provider ->
            completion.submit(java.util.concurrent.Callable {
                val query = DnsQuery(run.network.preferIpv6) {
                    run.allowed() && refreshNetwork() == run.network && available(provider)
                }
                run.queries.add(query)
                try {
                    query.check()
                    val addresses = transport.lookup(provider, PROBE_HOST, query)
                    if (addresses.isNotEmpty() && addresses.all(::isPublicAddress)) provider else null
                } catch (_: Exception) {
                    null
                } finally {
                    query.close()
                    run.queries.remove(query)
                }
            })
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(probeTimeoutMillis)
            var remaining = futures.size
            while (remaining > 0 && run.allowed()) {
                val nanos = deadline - System.nanoTime()
                if (nanos <= 0) break
                val completed = completion.poll(minOf(nanos, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)
                    ?: continue
                remaining--
                val winner = completed.get() ?: continue
                synchronized(lock) {
                    // Select and complete atomically with health updates: a late
                    // probe cannot reinstall a provider another lookup just rejected.
                    if (selection === run && state == run.network && available(winner)) {
                        prepared = winner
                        retryAfterNanos = Long.MIN_VALUE
                        selection = null
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                        log("fallback_ready provider=${winner.id} elapsedMs=$elapsed")
                        run.result.complete(winner)
                    }
                }
            }
        } catch (_: Exception) {
            // Failure/cancellation is finalized below, preserving a healthy backup.
        } finally {
            run.queries.forEach(DnsQuery::close)
            futures.forEach { it.cancel(true) }
            synchronized(lock) {
                if (selection === run && generation == run.generation && state == run.network) {
                    // A maintenance probe must not discard a working backup on failure.
                    selection = null
                    retryAfterNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(cooldownMillis)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    log("probe_failed retained=${prepared != null} cooldownMs=$cooldownMillis elapsedMs=$elapsed")
                    run.result.complete(null)
                } else {
                    run.result.complete(null)
                }
            }
        }
    }

    private class Selection(
        val generation: Long,
        val network: DnsNetworkState,
        val candidates: List<DohProvider>,
        var background: Boolean = false,
    ) {
        var waiters = 0
        val result = CompletableFuture<DohProvider?>()
        val queries = java.util.concurrent.ConcurrentHashMap.newKeySet<DnsQuery>()
        fun allowed() = !result.isDone
        fun cancel() {
            result.complete(null)
            queries.forEach(DnsQuery::close)
        }
    }

    companion object {
        private const val TRANSCRIPTIONS_PER_REFRESH = 20
        private const val MAX_FALLBACK_ATTEMPTS = 2
        internal const val PROBE_HOST = "example.com"
        private fun normalize(host: String): String = try {
            IDN.toASCII(host.trimEnd('.')).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) { "" }

        internal fun isPublicHostname(host: String, searchDomains: Set<String> = emptySet()): Boolean {
            if (host.isEmpty() || host.length > 253 || '.' !in host || ':' in host ||
                host.all { it.isDigit() || it == '.' }) return false
            val privateSuffixes = setOf("local", "localhost", "localdomain", "lan", "internal", "intranet",
                "home", "home.arpa", "onion", "invalid", "test", "example", "arpa")
            return (privateSuffixes + searchDomains).none { host == it || host.endsWith(".$it") }
        }

        private fun isPublicAddress(address: InetAddress): Boolean =
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress &&
                // Inet6Address.isSiteLocalAddress does not cover RFC 4193 unique-local addresses.
                !(address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
    }
}
