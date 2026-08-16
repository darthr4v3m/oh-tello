package io.github.darthr4v3m.ohtello.tello

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.DatagramSocket

/**
 * Binds sockets to the phone's current Wi-Fi network, which is where the drone
 * lives. Requires only `ACCESS_NETWORK_STATE`: we inspect the network the user
 * already joined and never scan, so no location permission is involved.
 */
class WifiSocketBinder(context: Context) : SocketBinder {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun bindToWifi(socket: DatagramSocket): SocketBindResult {
        val manager = connectivityManager
            ?: return SocketBindResult(SocketBindOutcome.FAILED, "no ConnectivityManager available")

        // allNetworks is deprecated as of API 31 with no direct replacement for
        // "which network is the Wi-Fi one" — the alternatives all involve
        // registering a callback and waiting, which is worse for a button press.
        @Suppress("DEPRECATION")
        val wifiNetwork = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return SocketBindResult(
            SocketBindOutcome.NO_WIFI_NETWORK,
            "no Wi-Fi network found — join the drone's TELLO-XXXXXX network first",
        )

        return try {
            wifiNetwork.bindSocket(socket)
            SocketBindResult(SocketBindOutcome.BOUND, "socket bound to the Wi-Fi network")
        } catch (e: IOException) {
            SocketBindResult(
                SocketBindOutcome.FAILED,
                "could not bind socket to Wi-Fi: ${e.message ?: e.toString()}",
            )
        }
    }
}
