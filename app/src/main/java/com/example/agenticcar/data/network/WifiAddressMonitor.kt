package com.example.agenticcar.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits the phone's IPv4 address on Wi-Fi, or null when there is none.
 *
 * Two sources are combined:
 *  - the address of a Wi-Fi network the phone has joined (ConnectivityManager callback),
 *  - the address of the phone's own hotspot interface, polled because hotspot
 *    changes raise no network callbacks. In a car, the laptop often joins the
 *    phone's hotspot rather than a shared Wi-Fi network.
 */
@Singleton
class WifiAddressMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val ipv4: Flow<String?> =
        combine(joinedWifiAddress().onStart { emit(null) }, hotspotAddress()) { joined, hotspot ->
            joined ?: hotspot
        }.distinctUntilChanged()

    private fun joinedWifiAddress(): Flow<String?> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val addresses = ConcurrentHashMap<Network, String>()
        fun publish() = trySend(addresses.values.firstOrNull())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                if (ip != null) addresses[network] = ip else addresses.remove(network)
                publish()
            }

            override fun onLost(network: Network) {
                addresses.remove(network)
                publish()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // A car or travel router usually has no internet; we only need the LAN.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    private fun hotspotAddress(): Flow<String?> = flow {
        while (true) {
            emit(scanHotspotInterface())
            delay(HOTSPOT_POLL_MILLIS)
        }
    }

    private fun scanHotspotInterface(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && HOTSPOT_PREFIXES.any { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val HOTSPOT_POLL_MILLIS = 5_000L
        // Interface names used by AOSP and OEM tethering stacks.
        val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1")
    }
}
