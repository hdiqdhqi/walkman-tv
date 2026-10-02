package com.walkman.tv.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.walkman.tv.di.getLanIp
import java.net.Inet4Address

/**
 * "当前设备地址" for the sync settings: the LAN IPv4 that another LX Music client (or the Sync
 * Server) can reach this Android TV on.
 *
 * This is a local fact about the TV - it is never the configured sync *server* address, and
 * loopback (127.0.0.1) / link-local (169.254.x.x) addresses are never reported.
 *
 * Resolution order:
 * 1. the IPv4 of the network Android currently routes with, so a TV that has both Wi-Fi and
 *    Ethernet (or a virtual NIC) reports the address that is actually in use;
 * 2. [getLanIp] - the plain interface enumeration the QR flow already uses - when the active
 *    network has no IPv4 at all (or is a VPN, or ConnectivityManager reports nothing).
 *
 * Returns null when no usable address exists; the settings UI renders 未知 for that.
 */
object LocalDeviceAddress {
    fun currentLanIpv4(context: Context): String? =
        activeNetworkIpv4(context) ?: getLanIp()

    /**
     * Requires ACCESS_NETWORK_STATE (declared in the manifest). A VPN is skipped so a tunnel
     * address cannot shadow the real LAN address - [getLanIp] then reports the interface itself.
     */
    private fun activeNetworkIpv4(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            null
        } else {
            val network = cm.activeNetwork
            if (network == null ||
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            ) {
                null
            } else {
                ipv4Of(cm, network)
            }
        }
    }.getOrNull()

    private fun ipv4Of(cm: ConnectivityManager, network: Network): String? =
        cm.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
}
