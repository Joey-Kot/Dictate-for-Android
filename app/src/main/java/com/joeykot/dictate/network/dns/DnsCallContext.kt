package com.joeykot.dictate.network.dns

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import java.net.InetAddress

/** HTTP dnsStart and lookup share a thread; WebSockets supply their cancellation scope explicitly. */
internal object DnsCallContext {
    private val current = ThreadLocal<() -> Boolean>()

    fun enter(call: Call) { current.set(call::isCanceled) }
    fun take(): (() -> Boolean)? = current.get().also { current.remove() }
    fun clear() { current.remove() }

    fun <T> withCancellation(isCancelled: () -> Boolean, action: () -> T): T {
        val previous = current.get()
        current.set(isCancelled)
        return try { action() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    class Listener : EventListener() {
        override fun dnsStart(call: Call, domainName: String) = enter(call)
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = clear()
        override fun callFailed(call: Call, ioe: java.io.IOException) = clear()
    }
}

/** Stable indirection: clients created before Application.onCreate still share the process resolver. */
object AppDns : Dns {
    @Volatile private var resolver: Dns = Dns.SYSTEM

    internal fun install(value: Dns) { resolver = value }

    fun onTranscriptionCompleted() { (resolver as? FallbackDns)?.onTranscriptionCompleted() }

    override fun lookup(hostname: String): List<InetAddress> = try {
        resolver.lookup(hostname)
    } finally {
        DnsCallContext.clear()
    }
}
