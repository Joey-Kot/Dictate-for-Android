package com.joeykot.dictate.network.dns

import android.net.LinkProperties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDnsNetworkStateTest {
    @Test fun strictPrivateDnsRemainsProtectedWhenValidationHasFailed() {
        val properties = LinkProperties()
        ReflectionHelpers.callInstanceMethod<Void>(properties, "setPrivateDnsServerName",
            ReflectionHelpers.ClassParameter.from(String::class.java, "private.resolver.com"))
        assertTrue(AndroidDnsNetworkState.privateDnsEnabled(properties))
    }

    @Test fun activeOpportunisticPrivateDnsIsAlsoProtected() {
        val properties = LinkProperties()
        ReflectionHelpers.callInstanceMethod<Void>(properties, "setUsePrivateDns",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertTrue(AndroidDnsNetworkState.privateDnsEnabled(properties))
    }

    @Test fun inactiveAutomaticModeAllowsFallback() {
        assertFalse(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties()))
    }

    @Test fun strictConfigurationIsProtectedEvenWhenAVpnHidesTheServerName() {
        assertTrue(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties(), "hostname"))
        assertTrue(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties(), "unknown-vendor-mode"))
        assertFalse(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties(), "off"))
        assertFalse(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties(), "opportunistic"))
    }

    @Test @Config(sdk = [26]) fun android8DoesNotCallNewerPrivateDnsApis() {
        assertFalse(AndroidDnsNetworkState.privateDnsEnabled(LinkProperties()))
    }
}
