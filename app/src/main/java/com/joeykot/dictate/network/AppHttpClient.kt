package com.joeykot.dictate.network

import com.joeykot.dictate.network.dns.AppDns
import com.joeykot.dictate.network.dns.DnsCallContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared application resolver, pool and dispatcher; callers retain their own retry policies. */
internal object AppHttpClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(AppDns)
            .eventListenerFactory { DnsCallContext.Listener() }
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // Standard ASR/Rewrite bodies are one-shot: routes may recover before
            // sending, but a submitted POST cannot be replayed by OkHttp.
            // Advanced HTTP/WebSocket clients retain their stricter retry policy.
            .retryOnConnectionFailure(true)
            .build()
    }
}
