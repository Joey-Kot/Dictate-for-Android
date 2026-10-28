package com.joeykot.dictate.network.dns

import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.advanced_audio.realtime.OkHttpRealtimeWebSocketFactory
import com.joeykot.dictate.advanced_audio.realtime.RealtimeSessionException
import com.joeykot.dictate.advanced_audio.realtime.RealtimeWebSocketRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketDnsCancellationTest {
    @Test fun jobCancellationStopsDohSelectionEvenThoughOkHttpDisablesWebSocketEventListeners() {
        checkCancellation(deadline = false)
    }

    @Test fun handshakeDeadlineAlsoStopsDohSelection() {
        checkCancellation(deadline = true)
    }

    private fun checkCancellation(deadline: Boolean) {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val cancellation = AdvancedCancellationSource()
        val provider = DohProvider("local", "https://resolver.com/dns-query", listOf("8.8.8.8"), "https://resolver.com/")
        FallbackDns(
            networkState = { DnsNetworkState("wifi", false) },
            system = object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException()
            },
            providers = listOf(provider),
            transport = DohTransport { _, _, query ->
                entered.countDown()
                try {
                    while (query.allowed()) Thread.sleep(5)
                    throw UnknownHostException()
                } finally { stopped.countDown() }
            },
        ).use { resolver ->
            val client = OkHttpClient.Builder().dns(resolver)
                .eventListenerFactory { DnsCallContext.Listener() }.build()
            val factory = OkHttpRealtimeWebSocketFactory(client, if (deadline) 250 else 10_000)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> {
                    runCatching {
                        factory.connect(RealtimeWebSocketRequest("wss://api.vendor.com/", emptyList()), cancellation.token)
                    }.exceptionOrNull()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (!deadline) cancellation.cancel()
                assertSame(if (deadline) RealtimeSessionException.WebSocketConnectionFailed
                    else RealtimeSessionException.SessionCancelled, result.get(1, TimeUnit.SECONDS))
                assertTrue(stopped.await(1, TimeUnit.SECONDS))
            } finally { cancellation.cancel(); executor.shutdownNow() }
        }
    }
}
