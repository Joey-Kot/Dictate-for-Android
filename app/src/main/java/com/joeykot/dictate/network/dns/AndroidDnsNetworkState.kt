package com.joeykot.dictate.network.dns

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.joeykot.dictate.util.Diagnostics
import java.util.Locale

/** Uses public network APIs; missing/unknown state preserves the system's resolver policy. */
internal class AndroidDnsNetworkState(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val contentResolver = context.contentResolver

    fun snapshot(): DnsNetworkState = try {
        val network = manager?.activeNetwork
        val properties = network?.let { manager.getLinkProperties(it) }
        if (network == null || properties == null) {
            DnsNetworkState(null, null)
        } else {
            // A VPN's LinkProperties may omit the user's configured strict resolver.
            // These AOSP settings are readable by apps; no hidden API calls are used.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Settings.Global.getString(contentResolver, PRIVATE_DNS_MODE)
                    ?.takeIf(String::isNotBlank)
                    ?: Settings.Global.getString(contentResolver, PRIVATE_DNS_DEFAULT_MODE)
            } else null
            val privateDns = privateDnsEnabled(properties, mode)
            val identity = listOf(network.toString(), properties.dnsServers.toString(),
                properties.domains, properties.httpProxy?.toString(), privateDns.toString()).joinToString("|")
            val preferIpv6 = properties.linkAddresses.any { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress } &&
                properties.linkAddresses.none { it.address is java.net.Inet4Address }
            DnsNetworkState(identity, privateDns,
                properties.domains.orEmpty().split(Regex("[ ,]+"))
                    .filter(String::isNotBlank).map { it.trimEnd('.').lowercase(Locale.ROOT) }.toSet(), preferIpv6)
        }
    } catch (_: RuntimeException) {
        DnsNetworkState(null, null)
    }

    fun observe(onChange: () -> Unit) {
        try {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
            })
        } catch (_: RuntimeException) {
            // Synchronous snapshot checks remain active if callbacks cannot be registered.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = onChange()
                }
                listOf(PRIVATE_DNS_MODE, PRIVATE_DNS_DEFAULT_MODE).forEach {
                    contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
                }
            } catch (_: RuntimeException) {
                // snapshot() checks the setting again before every fallback request.
            }
        }
    }

    companion object {
        private const val PRIVATE_DNS_MODE = "private_dns_mode"
        private const val PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode"

        internal fun privateDnsEnabled(properties: LinkProperties, configuredMode: String? = null): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (properties.isPrivateDnsActive || properties.privateDnsServerName != null ||
                    configuredMode !in setOf(null, "", "off", "opportunistic"))

        fun install(context: Context, diagnostics: Diagnostics) {
            val environment = AndroidDnsNetworkState(context.applicationContext)
            val resolver = FallbackDns(environment::snapshot, log = { diagnostics.info("dns", it) })
            AppDns.install(resolver)
            environment.observe { resolver.prepare() }
            resolver.prepare()
        }
    }
}
