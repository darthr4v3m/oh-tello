package io.github.darthr4v3m.ohtello.tello

import java.net.DatagramSocket

/**
 * Pins a socket to a particular network interface.
 *
 * The Tello's access point has no internet, so Android keeps mobile data as
 * the process-wide default network and an unbound UDP socket happily sends the
 * drone's commands out over cellular, where they vanish. Binding each socket to
 * the Wi-Fi network is what makes this work on a phone with a SIM in it.
 *
 * Kept as an interface so the controller stays free of Android imports (and so
 * tests can run it over loopback). [WifiSocketBinder] is the real one.
 */
fun interface SocketBinder {

    /** Called once per socket, after binding a local port but before connecting. */
    fun bindToWifi(socket: DatagramSocket): SocketBindResult

    companion object {
        /** Leaves sockets on whatever network the OS picks. Used in tests. */
        val Unbound: SocketBinder = SocketBinder {
            SocketBindResult(SocketBindOutcome.NOT_ATTEMPTED, "socket left on the default network")
        }
    }
}

enum class SocketBindOutcome {
    /** No binder was configured. */
    NOT_ATTEMPTED,

    /** The socket is pinned to the Wi-Fi network. */
    BOUND,

    /** The phone is not joined to any Wi-Fi network right now. */
    NO_WIFI_NETWORK,

    /** A Wi-Fi network exists but the bind call failed. */
    FAILED,
}

data class SocketBindResult(
    val outcome: SocketBindOutcome,
    val detail: String,
)
