package io.github.darthr4v3m.ohtello.tello

/** Where the link to the drone currently stands. */
sealed interface ConnectionState {

    /** Sockets closed. Nothing running. */
    data object Disconnected : ConnectionState

    /** Sockets opening, or the `command` handshake is in flight. */
    data object Connecting : ConnectionState

    /** The drone answered `ok` to `command` and is in SDK mode. */
    data object Connected : ConnectionState

    /** The last connection attempt failed, or the link dropped. */
    data class Failed(val message: String) : ConnectionState
}
